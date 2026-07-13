**English** | [한국어](08-mcp-integration.ko.md)

# 08. MCP Integration

Protean embeds an MCP (Model Context Protocol) adapter so that AI agents can deploy, update, roll back, and approve modules through JSON-RPC 2.0 tool calls. This document covers the practical steps for a library consumer to safely enable and connect to the MCP surface.

## Enabling the MCP adapter

The MCP server is **off by default**. Opening runtime code deployment to an agent is an RCE (remote code execution) surface, the library implements no authentication, and the default authorization is permissive (allow everything) — so it only starts when the consumer explicitly enables it (fail-safe).

```properties
# Minimal activation — the Streamable HTTP transport comes up at POST /platform/mcp
protean.mcp.enabled=true
```

Unless `protean.mcp.enabled=true`, the `McpHttpController`, `McpDispatcher`, and the built-in tool beans are never registered at all (they simply do not exist). The adapter as a whole is also active only under the `!worker` profile, so it does not come up in worker processes.

Related config keys:

| Key | Default | Purpose |
|---|---|---|
| `protean.mcp.enabled` | `false` | Whether the MCP server (HTTP controller, dispatcher, tools) is registered |
| `protean.mcp.stdio` | `false` | Enable the stdio transport (newline-delimited JSON-RPC) |
| `protean.mcp.debug.enabled` | `false` | The **execution gate** for the Level 3 `debug.*` tools. The tools are always exposed and only the call is gated (default false = prod, runtime-flippable) → [09. Debugging](09-debugging.md) |
| `protean.mcp.session.enabled` | `true` | Use Streamable HTTP sessions (`Mcp-Session-Id`) and the standing GET stream. When false, purely stateless |
| `protean.mcp.session.timeout` | `30m` | Idle-session auto-reclaim threshold |
| `protean.mcp.capture-test-output` | `false` | Capture stdout/stderr during gate ① test execution for failure diagnostics (an opt-in, since it intercepts the global `System.out`) |

> For detailed keys such as sessions and the resend buffer, see [03. Configuration](03-configuration.md).

## Remote server security posture

> ⚠️ **The MCP endpoint is a remote code execution surface.** `deploy_module` compiles and runs arbitrary Java, and the Level 3 `debug.*` tools attach a live JDI debugger (`evaluate`, `redefine`). With the default permissive authorizer and no consumer authentication, exposing it on a reachable address is **unauthenticated RCE**.

Because the library ships **no authentication of its own**, the safe posture depends entirely on where the endpoint can be reached from. Choose one:

| Environment | Authentication | Authorization | Also |
|---|---|---|---|
| Local dev / demo | none (acceptable) | permissive (default) | **Bind to localhost** and don't publish the port. This is the `examples/quickstart` `mcp`/`debug` posture — demo-only. |
| Shared / staging | **Bearer** (Spring Security resource server) | a `ModuleActionAuthorizer` that splits `READ` from writes | rotate tokens; keep `protean.mcp.debug.enabled=false` |
| Production / multi-tenant | **OAuth 2.0** (scopes → actions) | per-action `ModuleActionAuthorizer` | approval gate (`protean.gate.approval`) + Ed25519 signing |

Protean delegates authentication to the consumer's Spring Security and authorization to the `ModuleActionAuthorizer` SPI. See [Authentication & authorization](#authentication--authorization) for the mechanism, and the [Bearer](#bearer-token--protecting-the-endpoint) / [OAuth 2.0](#oauth-20--protecting-the-endpoint) recipes below for concrete setups. `examples/oauth-mcp` is a complete runnable reference; the `examples/quickstart` `mcp`/`debug` profiles are permissive **by design for local demos only** — never copy that posture to a reachable deployment.

## Transports — two of them

The dispatcher (`McpDispatcher`) is transport-agnostic, so both transports share the same core. It is zero-dep and processes JSON-RPC directly with Jackson alone (no separate MCP SDK dependency).

### 1) Streamable HTTP — `POST /platform/mcp`

With `protean.mcp.enabled=true`, the `POST /platform/mcp` endpoint comes up. You send JSON-RPC messages as an `application/json` body.

```bash
# initialize (version negotiation)
curl -s http://localhost:8080/platform/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
       "params":{"protocolVersion":"2025-11-25"}}'

# tools/list (the exposed tool catalog)
curl -s http://localhost:8080/platform/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

The protocol version is pinned to `2025-11-25`. `initialize` echoes the requested version if it is in the supported set, otherwise it responds with the latest.

**Progress-notification streaming**: if you want to receive a long-running deploy (the gate stages) in real time, put a `params._meta.progressToken` on the `tools/call`. The response then becomes an SSE stream instead of a single JSON, emitting a `notifications/progress` frame at each gate stage and a result frame at the end. Without `progressToken`, it is a single JSON response.

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call",
 "params":{"name":"protean.deploy_module",
           "arguments":{ "...": "..." },
           "_meta":{"progressToken":"deploy-1"}}}
```

**Sessions & the standing server→client stream** (`protean.mcp.session.enabled=true`, on by default): the `initialize` response carries an `Mcp-Session-Id` header. Include this header on subsequent requests to keep the session alive, and on the same endpoint you can open a **standing SSE stream** with **`GET /platform/mcp`** (`Accept: text/event-stream`, `Mcp-Session-Id` required) — the server pushes notifications that occur outside of a call here. If the connection drops, reconnect with the last received event id in the `Last-Event-ID` header to **resume (resumability)** from just after it.

- **`tools.listChanged`**: the server advertises `capabilities.tools.listChanged=true` in `initialize`. When the tool set changes at runtime (a consumer adds/removes its own tools via `McpDispatcher.registerTool`/`unregisterTool`), it pushes `notifications/tools/list_changed` over the standing stream so the client re-fetches `tools/list` **without reconnecting**.
- **Backward compatibility**: send without `Mcp-Session-Id` and it behaves **statelessly** (request/response) as before. An unknown/expired session id returns `404` (prompting a re-`initialize`). If the `MCP-Protocol-Version` header is out of the supported range, `400`.
- The `MCP-Protocol-Version` header is attached to requests after `initialize` (if absent, tolerantly treated as the latest).

### 2) stdio — local spawn

For scenarios where a local agent (e.g. Claude Desktop/Code) spawns the server process directly, use the stdio transport. `McpStdioServer` reads newline-delimited JSON-RPC from stdin and responds one line at a time on stdout. EOF is treated as client shutdown and the process exits.

Two ways to enable it:

```properties
# (a) Add only the stdio transport to an existing app
protean.mcp.enabled=true
protean.mcp.stdio=true
```

```bash
# (b) Spawn via a dedicated entry point — ProteanMcpStdioLauncher
#     It auto-sets mcp.enabled=true, mcp.stdio=true, logging.pattern.console=.
java -cp app.jar org.htcom.protean.boot.ProteanMcpStdioLauncher
```

`ProteanMcpStdioLauncher` turns off the banner and console logging (`logging.pattern.console=` empty) so that stdout is dedicated to JSON-RPC. The web server still comes up, so deployed modules are still served over HTTP and stdio is used only as the control channel.

Example MCP-client (agent) configuration:

```json
{
  "mcpServers": {
    "protean": {
      "command": "java",
      "args": ["-cp", "/opt/app/app.jar",
               "org.htcom.protean.boot.ProteanMcpStdioLauncher"]
    }
  }
}
```

## Tool catalog

All built-in tools use the `protean.` prefix. `tools/list` returns each tool's name, description, input schema, and (when configured) title, output schema, and behavior hints ([Tool-object metadata](#tool-object-metadata-title--outputschema--annotations)). When a consumer registers its own `McpTool` beans, they are exposed alongside (open-core).

### Query tools

| Tool name | Input | Purpose |
|---|---|---|
| `protean.list_modules` | all optional: `query`·`mode`·`trustTier`·`limit`·`cursor` | Status list of deployed (ACTIVE) modules (id·version·state·isolation mode). Called with no arguments, returns all. See [below](#list_modules-search--paging) for search & paging |
| `protean.get_module` | `id` (required) | Look up a single module's status by id |
| `protean.module_versions` | `id` (required) | Version history (newest-first, for identifying a rollback target) |

#### `list_modules` search & paging

When there are many modules, browsing the list by id is hard, so `list_modules` accepts optional filters and cursor paging (all omittable — omitting them returns all ACTIVE, the same behavior as before).

| Argument | Meaning |
|---|---|
| `query` | Substring match (case-insensitive) on `id` or `controllerFqcn` |
| `mode` | Exact isolation-mode match (`in-process`·`worker`·`container`) |
| `trustTier` | Exact trust-tier match (`TRUSTED`·`UNTRUSTED`) |
| `limit` | Max number returned (default 50, max 200, **`0` = all/unlimited**) |
| `cursor` | The `nextCursor` from the previous response (continue) |

`limit=0` returns **all** modules matching the filter with no paging cap (no `nextCursor` is emitted in that case). Results are sorted ascending by `id`, and if more items remain it includes `structuredContent.nextCursor` (an opaque token). Pass this value as the next request's `cursor` to continue. (This paging is for the `modules` array inside the tool result, and is separate from the [cursor pagination](#pagination-cursor) of JSON-RPC lists such as `tools/list`.)

```jsonc
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
  "name":"protean.list_modules",
  "arguments":{"query":"order","mode":"worker","limit":2}
}}
// → structuredContent: {"modules":[ …up to 2… ], "nextCursor":"Mg"}
// next page: add "cursor":"Mg" to arguments
```

### Deploy & modify tools

| Tool name | Input | Purpose |
|---|---|---|
| `protean.deploy_module` | `files[]` or `manifest`, `id`·`version`·`controller`·`isolationMode` | Deploy a new module (ACTIVE on passing the gates) |
| `protean.update_module` | same as deploy (`files[]`/`manifest`) | Canary hot-swap update (auto-rollback on verification failure) |
| `protean.patch_module` | `id`·`version`·`files[]`·`removeFiles[]` | Delta update — overlay only the changed files + remove, then canary update |
| `protean.reload_module_resources` | `id`·`files[]`·`removeFiles[]` | Swap resources only, in place (live-reload with no compile/rebuild) |
| `protean.rollback_module` | `id` (required)·`version` (required) | Roll back to a specific version in history |
| `protean.uninstall_module` | `id` (required) | Uninstall a module (remove endpoints and context) |

### Approval gate tools

When the approval gate (`protean.gate.approval.required=true`) is on, a module that has only passed the automated gates is stored as `PENDING_APPROVAL` and is not served. A human promotes/rejects it with the tools below. `approver` is a string for the audit log; identity verification is the consumer's Security / `ModuleActionAuthorizer` responsibility.

| Tool name | Input | Purpose |
|---|---|---|
| `protean.approve_module` | `id` (required)·`approver` (required) | Promote a pending module to ACTIVE via ③ verify + deploy |
| `protean.reject_module` | `id` (required)·`approver` (required) | Reject a pending module and remove it |

For the promotion gates overall, see [06. Promotion Gates](06-promotion-gates.md).

### Deploy input formats — `files[]` and `manifest`

`deploy_module`/`update_module` accept one of two input styles (mutually exclusive).

**(A) `files[]` style** (recommended) — supply `id`·`version`·`controller` at top level, and put the sources/tests/resources in a file array. Each file entry:

- `kind`: `source` | `test` | `resource` (default `source`)
- `filename`: for `source`/`test`, the file name (the FQCN is auto-derived from `package` + file name); for `resource`, a classpath path (e.g. `mapper/OrderMapper.xml`)
- `content`: the file content
- `base64`: `true` if the resource is binary (then `content` is Base64). Default `false` (plain text)

```json
{"jsonrpc":"2.0","id":10,"method":"tools/call",
 "params":{"name":"protean.deploy_module","arguments":{
   "id":"orders",
   "version":"1.0.0",
   "controller":"com.acme.orders.OrderController",
   "isolationMode":"in-process",
   "files":[
     {"kind":"source","filename":"OrderController.java",
      "content":"package com.acme.orders; ... @RestController ..."},
     {"kind":"test","filename":"OrderControllerTest.java",
      "content":"package com.acme.orders; ... @Test ..."},
     {"kind":"resource","filename":"mapper/OrderMapper.xml",
      "content":"<mapper> ... </mapper>"}
   ]}}}
```

**(B) `manifest` style** — put a single `module.yaml` text in the `manifest` field (for the module authoring format, see [02. Module Authoring](02-module-authoring.md)).

`isolationMode` is `in-process` | `worker` | `container` — see [05. Isolation Modes](05-isolation-modes.md).

Domain failures such as a gate rejection or compile failure are not mapped to a JSON-RPC error but to a tool result with `isError: true` (+ diagnostic text). Only protocol-level errors (unknown tool, missing required argument, etc.) go out as JSON-RPC errors.

## Authentication & authorization

**The library implements no authentication.** It splits into two axes.

- **Authentication (who)**: the consumer's Spring Security responsibility. The `Principal` of a request arriving at `POST /platform/mcp` is passed straight through as the authorization context (`McpCallContext.caller`). With no security it is `null`. stdio is a **local trust boundary**, so the spawning subject is the authorization subject and `caller=null` always.
- **Authorization (what can be done)**: the `ModuleActionAuthorizer` SPI. Every tool call (core tools + consumer custom tools) passes through this single choke point. Each tool is classified with a `ModuleAction` (`READ`·`DEPLOY`·`UPDATE`·`DELETE`·`APPROVE`·`DEBUG`·`CUSTOM`), so policy can branch per action.

The default implementation is `PermissiveModuleActionAuthorizer` (allow everything). When a consumer registers a `ModuleActionAuthorizer` bean, `@ConditionalOnMissingBean` replaces the default.

```java
@Bean
ModuleActionAuthorizer moduleActionAuthorizer() {
    return (caller, action, moduleId) -> {
        // anonymous (unauthenticated HTTP) may read only
        if (caller == null) {
            return action == ModuleActionAuthorizer.ModuleAction.READ
                    ? ModuleActionAuthorizer.Decision.allow()
                    : ModuleActionAuthorizer.Decision.deny("authentication required");
        }
        // deploy/update/delete/debug are admin-only
        boolean admin = caller instanceof org.springframework.security.core.Authentication a
                && a.getAuthorities().stream()
                     .anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN"));
        return admin
                ? ModuleActionAuthorizer.Decision.allow()
                : ModuleActionAuthorizer.Decision.deny("ROLE_ADMIN required: " + action);
    };
}
```

On denial the tool call is not executed and an `isError` result returns "permission denied: <reason>". For the concrete configuration of protecting `POST /platform/mcp` with Spring Security, see [12. Security](12-security.md).

### Bearer token — protecting the endpoint

The simplest posture for a reachable deployment: put a Spring Security resource server in front of `POST /platform/mcp` and require a bearer token. Protean stays out of it — the verified `Principal` flows through as `McpCallContext.caller`, and your `ModuleActionAuthorizer` decides per action.

```java
@Bean
SecurityFilterChain mcpChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/platform/mcp/**")
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
    return http.build();
}
```

No token → `401 WWW-Authenticate: Bearer`, emitted by the resource server **before** Protean's controller (the library never sees the request). Obtain a token however your authorization server issues them, e.g. `client_credentials`:

```bash
curl -s -u mcp-service:service-secret \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode "scope=mcp.read mcp.write" \
  http://localhost:8080/oauth2/token
# → feed the access_token to the client as `Authorization: Bearer <token>`
```

> Bearer answers **who**, not **what**. Pair it with a `ModuleActionAuthorizer` (above) so a read-only caller cannot `deploy_module`. See `examples/oauth-mcp` for the full static-token setup.

### OAuth 2.0 — protecting the endpoint

For production, front the endpoint with OAuth 2.0 and map **scopes → `ModuleAction`s**. The `examples/oauth-mcp` module is a complete, runnable reference (authorization server + resource server + `McpScopeAuthorizer`) and supports two client styles:

| | Static token | Native discovery (`native-oauth`) |
|---|---|---|
| Token | manually injected bearer | client obtains it automatically |
| Flow | issue out-of-band, paste into client | 401 → `resource_metadata` → discovery → authorization_code + PKCE |
| Signing key | regenerated per boot (tokens die on restart) | file-persisted → survives restart |

When you set the `authorization` block, Protean **opt-in** publishes `/.well-known/oauth-protected-resource` (RFC 9728) so a discovery-capable client can locate the authorization server from the endpoint URL alone. **Token validation stays with the consumer's Security** — Protean only advertises the metadata:

```yaml
protean:
  mcp:
    enabled: true
    authorization:                 # present → /.well-known/oauth-protected-resource is served
      resource: http://localhost:8080/platform/mcp
      authorization-servers:
        - http://localhost:8080
      scopes-supported: [mcp.read, mcp.write]
```

The scope→action mapping lives in your `ModuleActionAuthorizer` (e.g. `mcp.write` required for `DEPLOY`/`UPDATE`/`DELETE`, `mcp.read` for `READ`). See [`examples/oauth-mcp/README.md`](../../examples/oauth-mcp/README.md) for the flow walkthrough and the design decisions behind it.

## Connecting and driving from an MCP client (Claude Code · Curator · …)

Any MCP client (Claude Code, Curator, or another agent) drives the running server the same way: register the remote endpoint, then call tools. For a local spawn use [stdio](#2-stdio--local-spawn) above; for a reachable server use the Streamable HTTP endpoint.

Register the HTTP endpoint (Claude Code shown; other clients use the same `mcpServers` shape):

```bash
# CLI
claude mcp add --transport http protean http://localhost:8080/platform/mcp
```

```jsonc
// or .mcp.json — add the Authorization header when the endpoint is secured (Bearer/OAuth)
{
  "mcpServers": {
    "protean": {
      "type": "http",
      "url": "http://localhost:8080/platform/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

Once connected, the built-in tools appear to the client under the server name (e.g. `mcp__protean__protean.deploy_module`). A typical agent-driven loop — expressed as instructions you give the agent — is:

1. **Deploy** — "deploy module `orders` from these sources; the bundled test is the gate." → `protean.deploy_module`. A gate failure returns as an `isError` result with diagnostics, so the agent fixes and retries.
2. **Verify live** — call the module's real endpoint (`GET /orders/{id}`), or `protean.query_traces` to confirm it served.
3. **Debug** — "set a breakpoint at `OrderController:42`, launch under debug, and evaluate `order.total`." → `debug.launch` / `debug.set_breakpoint` / `debug.await_stop` / `debug.evaluate` (requires `protean.mcp.debug.enabled=true`).
4. **Iterate** — `protean.patch_module` (changed files only) or `debug.redefine` (fix-and-continue) — no full redeploy.
5. **Roll back** — "roll `orders` back to 1.0.0." → `protean.rollback_module`.

Because errors surface as structured RFC 9457 results correlated by `traceId` (not prose), the agent self-corrects deterministically rather than guessing. For the full tool list see [Tool catalog](#tool-catalog); for the debug tools see [09. Debugging](09-debugging.md).

## Tool-object metadata (title · outputSchema · annotations)

Beyond name, description, and input schema, an `McpTool` can **optionally** expose the tool-object fields of MCP spec `2025-11-25`. All are default methods, so if not implemented they are `null` and are **omitted** from `tools/list` serialization (unset ≠ false — only implemented ones are advertised, honestly).

| Method | Field | Meaning |
|---|---|---|
| `title()` | `title` | A human display name distinct from `name`. Client UIs prefer this. |
| `outputSchema()` | `outputSchema` | The JSON Schema of a successful result's `structuredContent`. |
| `annotations()` | `annotations` | Behavior hints — `readOnlyHint` / `destructiveHint` / `idempotentHint` / `openWorldHint`. |

Behavior hints are built with the `McpToolAnnotations` builder. Only the hints you set are serialized:

```java
@Override public String title() { return "Deploy Module"; }

@Override public McpToolAnnotations annotations() {
    return McpToolAnnotations.builder()
            .readOnly(false).destructive(false).idempotent(false).openWorld(false).build();
}

@Override public ObjectNode outputSchema() { /* JSON Schema (type:object) */ }
```

> **Hints are not something to trust.** `annotations` are only hints for client UX/gating decisions, not an authorization or safety boundary. Actual permission is always enforced by `ModuleActionAuthorizer`, and whether debug execution is allowed by the `DebugSurfaceState` gate.

### outputSchema validation scope (server vs client)

When a tool that declared `outputSchema` returns **success**, the dispatcher checks **minimal conformance only** against the result `structuredContent`:

- that `structuredContent` exists and is an **object**
- that all of the schema's **top-level `required`** fields are present

If either is violated, it does not silently pass but blocks with `isError` ("output schema violation: …") — to stop, at the server, the class of bugs where malformed structured output leaks to the client. This check is not applied to `isError` results (domain failures), where the absence of structured output is normal.

**By default it does not perform full JSON Schema validation of nested fields, types, formats, etc.** The MCP core is deliberately zero-dep and does not pull in a schema validator, and the spec too leaves full validation to the **client** ("Clients SHOULD validate structured results against this schema"). That is, the server's job is to **advertise `outputSchema` accurately** so the client can validate fully, and the default server-side guard is a safety net that catches only blatant contract violations (missing/non-object).

> **strict mode (opt-in) — full validation.** With `protean.mcp.strict-schema=true`, instead of the minimal conformance above it performs **full JSON Schema validation**: incoming `arguments` ↔ `inputSchema` and successful `structuredContent` ↔ `outputSchema`, down to nesting and types (input violation → `INVALID_ARGUMENT`, output violation → `OUTPUT_SCHEMA_VIOLATION`). To keep the core zero-dep, the validator (networknt) is **not bundled** (`compileOnly`) — turn strict on with the validator on the classpath and it is active, without it, it auto-falls back to the top-level guard above. Since the library's own tools are guaranteed conformant by tests, this mode is mostly for when you want to enforce **consumer custom tools'** contracts at runtime.

## Adding custom tools

When a consumer registers a bean implementing the `McpTool` interface, `McpDispatcher` collects it into `List<McpTool>` and exposes it automatically. A custom tool's default `action()` is `CUSTOM`, so the authorizer decides it separately. The [Tool-object metadata](#tool-object-metadata-title--outputschema--annotations) above (title·outputSchema·annotations) can be used as-is in custom tools. For SPI extension overall, see [10. SPI Extension](10-spi-extension.md).

Since the library tests cannot cover custom tools, the in/out contract conformance of a custom tool is guaranteed two ways: **(1) runtime** — `protean.mcp.strict-schema=true` (above [outputSchema validation scope](#outputschema-validation-scope-server-vs-client)) validates fully at the boundary; **(2) tests** — the public `SchemaValidator` (`SchemaValidator.create().validate(schema, instance)` → a list of violation messages, adding networknt as a test dependency) validates in your own tests. Both add networknt on the consumer side only, with no effect on the core runtime.

## Server capability surface (logging · completions · templates · subscribe · pagination)

The `initialize` response advertises the following in `capabilities` (only the implemented ones, honestly).

### Logging (`logging`)

Adjusting the server log threshold with `logging/setLevel` makes the server emit `notifications/message` (syslog's 8 levels: debug…emergency) over the standing stream. Levels below the threshold are suppressed.

```json
{"jsonrpc":"2.0","id":10,"method":"logging/setLevel","params":{"level":"debug"}}
```

> The threshold is **server-global** (shared across all connected clients) — a simple, honest choice given the control-plane nature. Emission goes out over the standing stream only when the session surface is on. A library consumer can emit its own logs with `McpDispatcher.emitLog(level, logger, data)`.

### Completions (`completions`)

`completion/complete` returns candidate values for prompt / resource-template arguments. Protean completes the `id` of the resource template `protean://modules/{id}/…` from deployed module-id prefixes (prompt free-text arguments have no candidates).

```json
{"jsonrpc":"2.0","id":11,"method":"completion/complete",
 "params":{"ref":{"type":"ref/resource","uri":"protean://modules/{id}/source"},
           "argument":{"name":"id","value":"ord"}}}
```

### Resource templates (`resources/templates/list`)

Separate from the static resource list (`resources/list`), it exposes parameterized URI templates: `protean://modules/{id}/source`, `protean://modules/{id}/versions`, `protean://modules/{id}/routes`. Fill in `{id}` and read via `resources/read`.

**`protean://modules/{id}/routes` — live router readout.** It returns the HTTP routes (method + path pattern) the module **actually registered** at runtime. Since it reads from the live mappings rather than the store's `desiredState` (declaration), it surfaces mismatches like "state is ACTIVE but the call 404s" (e.g. 0 registrations from a recompile/recovery failure) as-is — **an empty array signals that there are actually no routes being served**. A non-existent module id → `-32602` (invalid params).

```jsonc
{"jsonrpc":"2.0","id":1,"method":"resources/read",
 "params":{"uri":"protean://modules/order-svc/routes"}}
// → contents[0].text (JSON):
// [{"methods":["GET"],"patterns":["/orders/{id}"]},
//  {"methods":["POST"],"patterns":["/orders"]}]
```

### Resource subscription (`resources.subscribe`)

Subscribe to changes of a specific resource uri with `resources/subscribe` (+ `unsubscribe`), and when a module changes (deploy·approve·reject·update·resource-reload·remove·rollback) the server pushes `notifications/resources/updated{uri}` so the client re-`read`s.

```json
{"jsonrpc":"2.0","id":12,"method":"resources/subscribe","params":{"uri":"protean://modules"}}
```

> Subscriptions are tracked **server-globally** and, on change, broadcast to all connected sessions. A core `ModulePlatform` change flows through `McpResourceUpdateBridge` into the notification (a change on either the REST or MCP path is reflected identically).

### Pagination (cursor)

`tools/list`·`resources/list`·`resources/templates/list`·`prompts/list` support cursor pagination. If the response has a `nextCursor`, pass it as the next request's `params.cursor` to continue (an opaque token). The default page size is 100 — even with many consumer tools they can all be retrieved.

```json
{"jsonrpc":"2.0","id":13,"method":"tools/list","params":{"cursor":"MTAw"}}
```

## Client capability requests (sampling · roots · elicitation)

These are bidirectional capabilities the server requests **of the client** (server→client JSON-RPC requests). The session surface must be on (requests are pushed over the standing stream, and when the client POSTs a response to the same endpoint it is matched by request id), and the client must have declared the corresponding capability in `initialize`. A library consumer calls them via `McpDispatcher` primitives:

| Primitive | MCP method | Required client capability |
|---|---|---|
| `createMessage(sessionId, params, timeoutMs)` | `sampling/createMessage` | `sampling` |
| `listRoots(sessionId, timeoutMs)` | `roots/list` | `roots` |
| `elicit(sessionId, params, timeoutMs)` | `elicitation/create` | `elicitation` |

The call **blocks** until the client responds (timeout), and throws if there is no channel (stateless/stdio) or the client has not declared the capability. The session (`ctx.sessionId()`) is the destination — it flows in via `McpCallContext`. `notifications/roots/list_changed` is received, but since roots is fetched-on-demand there is no cache, so it is a no-op.

> Response correlation: the server assigns a `srv:<n>` id to the request and sends it over the session stream, and when the client POSTs a response with that id, `McpSessionRegistry` completes the waiting future. The stdio transport has no correlation channel, so it does not support this capability (response frames are silently ignored).

## Cancellation · _meta · authorization errors

- **Request cancellation** — when the client sends `notifications/cancelled{requestId, reason}`, it sets the cancellation token of the matching in-flight request and interrupts the worker thread. The cooperative cancellation point is a **progress notification** — the moment a long-running tool calls `ctx.progress().report(...)`, if it has been cancelled it aborts and becomes an `isError` result (cut off at the next stage boundary). Cancellation correlation is session-scoped, and an already-finished or unknown requestId is ignored (idempotent).
- **`_meta`** — a request's `params._meta` is passed to the tool as `McpCallContext.meta()`. A tool can carry `_meta` back on the result with `McpToolResult.withMeta(json)` (bidirectional passthrough).
- **Authorization error text** — on an authorization denial the result text is of the form `permission denied: <reason> [action=…, tool=…]`, so the agent clearly recognizes "no permission" (steering it toward approval/role adjustment instead of a retry).

## Related docs

- [02. Module Authoring](02-module-authoring.md)
- [05. Isolation Modes](05-isolation-modes.md)
- [06. Promotion Gates](06-promotion-gates.md)
- [09. Debugging](09-debugging.md)
- [10. SPI Extension](10-spi-extension.md)
- [12. Security](12-security.md)
- [README](../../README.md)
