**English** | [한국어](03-configuration.ko.md)

# 03. Configuration Reference

All of Protean's configuration lives under the `protean.*` prefix (`ProteanProperties`). It is type-safe, and
because spring-boot-configuration-processor metadata is generated, the consumer's IDE provides
completion/validation. Keys follow the relaxed-binding (kebab-case) convention (e.g.
`protean.module.request-timeout-ms`).

Below is the full per-group key list you can use directly in `application.yml`/`application.properties`. The
defaults match the source bindings — `ProteanProperties` for most keys, and `@Value` in the corresponding
configuration for a few (e.g. `protean.mcp.session.*` in `McpConfiguration`, `protean.mcp.debug.*` in
`DebugMcpConfiguration`).

The **Tier** column says when a change takes effect: `live` (immediately, at operation time), `future` (only for
instances created afterward — new deploys/workers/containers; already-running ones are unaffected), `restart`
(requires an application restart). `live`/`future` keys can also be changed at runtime via `PATCH /platform/config`
([04. REST API](04-rest-api.md)); `restart` keys are read-only there.

## admin — admin REST surface

Whether to expose the `/platform/*` control plane.

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.admin.enabled` | `boolean` | `true` | `restart` | If `false`, the `/platform/*` admin controllers (`ModuleAdminController`, `TraceAdminController`, `ConfigAdminController`, `SharedLibAdminController`) are not registered. |

## mcp — MCP adapter surface

The entry point for an MCP agent to deploy modules directly. Being an RCE surface, it is **off by default** —
it comes up only when explicitly enabled.

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.mcp.enabled` | `boolean` | `false` | `restart` | Must be `true` to register the MCP server (`McpHttpController`, etc.). |
| `protean.mcp.stdio` | `boolean` | `false` | `restart` | Enable stdio transport (newline-delimited JSON-RPC). For the local agent-spawn entry point. |
| `protean.mcp.debug.enabled` | `boolean` | `false` | `restart` | **Execution gate** for the `debug.*` tools (initial value). The tools are always exposed when `mcp.enabled`; if this is false, calls are rejected with `isError` ("debug surface disabled") (default false = prod). Runtime-mutable (`DebugSurfaceState`). |
| `protean.mcp.debug.session-idle-timeout` | `Duration` | `30m` | `restart` | Threshold for auto-reclaiming idle debug sessions. `0`/negative disables. |
| `protean.mcp.capture-test-output` | `boolean` | `false` | `live` | Capture stdout/stderr during gate ① test execution and include it in failure diagnostics (opt-in, since it intercepts the global `System.out`). |
| `protean.mcp.strict-schema` | `boolean` | `false` | `live` | When on, the dispatcher validates tool `arguments` ↔ `inputSchema` and success `structuredContent` ↔ `outputSchema` against the **full JSON Schema** (nesting · types) (input violation → `INVALID_ARGUMENT`, output → `OUTPUT_SCHEMA_VIOLATION`). The validator (networknt) is not bundled in the core (`compileOnly`) — active only when on the classpath, otherwise falls back to a top-level `required` guard. Mainly for enforcing consumer custom-tool contracts at runtime. |
| `protean.mcp.session.enabled` | `boolean` | `true` | `restart` | Use Streamable HTTP sessions (`Mcp-Session-Id`) and the always-on GET SSE stream. If false, purely stateless (request/response). |
| `protean.mcp.session.timeout` | `Duration` | `30m` | `restart` | Threshold for auto-reclaiming idle MCP sessions. |
| `protean.mcp.session.replay-buffer` | `int` | `256` | `restart` | Always-on stream replay buffer size (last N events per session, for `Last-Event-ID` reconnect). |
| `protean.mcp.session.stream-timeout` | `Duration` | `1h` | `restart` | Emitter timeout for the GET always-on stream (kept alive with heartbeats; on exceed the client reconnects). |

### mcp.authorization — OAuth protected-resource metadata (RFC 9728, opt-in)

Advertises this MCP endpoint as an OAuth 2.0 protected resource so clients can discover the authorization server. Off unless `resource` is set. The library only serves the metadata; the consumer wires the actual token validation (see [12. Security](12-security.md) and the `examples/oauth-mcp` example).

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.mcp.authorization.resource` | `String` | (none) | `restart` | Protected-resource identifier (usually the MCP endpoint URL). When set, the `/.well-known/oauth-protected-resource` metadata endpoint is active. |
| `protean.mcp.authorization.authorization-servers` | `List<String>` | (empty) | `live` | Authorization Server issuer URLs that issue tokens for this resource. |
| `protean.mcp.authorization.scopes-supported` | `List<String>` | (empty) | `live` | Advertised supported scopes (optional). |
| `protean.mcp.authorization.bearer-methods-supported` | `List<String>` | `["header"]` | `live` | Bearer token delivery methods (RFC 9728 `bearer_methods_supported`). |

## bridge — worker→main RPC bridge

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.bridge.url` | `String` | (none) | `restart` | The main bridge URL to which a worker forwards shared-bean calls (injected into the worker process). |
| `protean.bridge.auth-enabled` | `boolean` | `false` | `restart` | Enforce shared-secret auth on `/__bridge/*` (opt-in). When on, the main generates/uses a secret and injects it into spawned workers, and unauthenticated calls get 401. Transport encryption (TLS) is separate. |
| `protean.bridge.secret` | `String` | (none) | `restart` | Shared secret for bridge auth. If `auth-enabled=true` but empty, the main auto-generates one per JVM lifetime and injects it into workers. Specify to use an externally-managed fixed secret. |
| `protean.bridge.auth-mode` | `String` | `token` | `restart` | Auth method: `token` (static bearer token) or `hmac` (per-request HMAC-SHA256 signature, defending even against replay/body tampering). Both use the same symmetric secret. |
| `protean.bridge.hmac-window-ms` | `long` | `30000` | `live` | In `hmac` mode, the maximum allowed skew (ms) between the worker request timestamp and the main clock. Beyond it, 401. |

## gate — promotion gates

The default is the safe side (all on). A consumer can relax individual gates to match its trust level.

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.gate.tests-enabled` | `boolean` | `true` | `live` | Gate ①: enforce bundled unit tests pass. If `false`, passes even without tests. |
| `protean.gate.review-enabled` | `boolean` | `true` | `live` | Gate ②: bytecode review (`ForbiddenApiRule`, etc.). If `false`, the code check is skipped. |
| `protean.gate.signature.required` | `boolean` | `false` | `live` | Enforce the signature gate (opt-in). When on, every install must be signed with a trusted key to pass. |
| `protean.gate.signature.keys` | `Map<String,String>` | (empty map) | `live` | Trusted public keys: `keyId → Base64(X.509 Ed25519 public key)`. |
| `protean.gate.signature.shared-lib-required` | `boolean` | `false` | `live` | Require live shared-lib uploads (the put-jar surface) to be signed with a trusted key (opt-in; reuses `signature.keys` as the trust store). |
| `protean.gate.approval.required` | `boolean` | `false` | `live` | Enforce the approval gate (opt-in, human authorization). When on, an install is stored as `PENDING_APPROVAL` and must be approved via `POST /{id}/approve` to become `ACTIVE`. |

## isolation — isolation strategy

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.isolation.mode` | `String` | `in-process` | `future` | Global default isolation mode: `in-process` \| `worker` \| `container`. |

For isolation-mode details see [05. Isolation Modes](05-isolation-modes.md).

## module — module request-execution control

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.module.request-timeout-ms` | `long` | `0` | `live` | Module request timeout (ms). `0` = unlimited. |
| `protean.module.shared-lib-dir` | `String` | `""` | `restart` | Shared lib directory. When set, builds an app-lifetime `URLClassLoader` from that directory's `*.jar`, inserts it as the module ClassLoader's parent, and adds it to the compile classpath too. Empty = off. Targets in-process. |
| `protean.module.shared-lib-store-dir` | `String` | `${java.io.tmpdir}/protean-shared-libs` | `restart` | Directory for the server-managed live shared-lib store (the put-jar surface). Persists runtime-uploaded jars (separate from the read-only `shared-lib-dir` seed). The path is a restart artifact; the jar set it holds is live. |
| `protean.module.eager-shared-lib-invalidation` | `boolean` | `true` | `live` | On a new shared-lib generation (a put-jar deploy/remove changed an in-use jar), eagerly rebind the ACTIVE modules using it onto the new generation (zero-downtime). `false` leaves them on their bound generation until redeploy. Read live. |
| `protean.module.eager-shared-module-invalidation` | `boolean` | `true` | `restart` | On a library module republishing its generation (typed shared-module sharing), eagerly propagate to the ACTIVE dependents that `use` it. `false` leaves dependents on their bound generation until redeploy. |
| `protean.module.executor.pool-size` | `int` | `2` | `future` | Thread pool size of the per-module managed executor (`ProteanTaskExecutor`). |

## module-store — descriptor durable store

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.module-store.backend` | `String` | `filesystem` | `restart` | Storage backend: `filesystem` \| `jdbc`. |
| `protean.module-store.dir` | `String` | `${java.io.tmpdir}/protean-modules` | `restart` | Storage directory for the filesystem backend. |

## reconcile — startup reconcile (ACTIVE-module recovery)

On boot the platform recompiles and redeploys every `ACTIVE` module from source. These tune that phase (boot-time only, not live-reloadable).

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.reconcile.compile-parallelism` | `int` | `0` | `restart` | Thread-pool size for the parallel pre-compile phase. `0` = auto (`availableProcessors()`), `1` = fully serial (kill switch), `N` = cap at N threads. |
| `protean.reconcile.reuse-file-manager` | `boolean` | `true` | `restart` | Reuse one javac file manager per worker thread across the parallel pre-compile (scans the read-only compile classpath once per thread instead of per compile). `false` restores per-call file managers (kill switch). Only relevant when the parallel phase runs. |

## trace — runtime trace recording

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.trace.enabled` | `boolean` | `true` | `live` | Enable trace recording. |
| `protean.trace.capacity` | `int` | `200` | `live` | Ring-buffer capacity (last N requests). |

### trace.metrics — per-module aggregate metrics (opt-in)

Aggregates request count · error rate · latency percentiles per module. When off (default), the recording hot
path costs nothing beyond a single boolean check. Query via `GET /platform/traces/metrics`
(→ [04. REST API](04-rest-api.md)) and the MCP `protean.module_metrics` tool.

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.trace.metrics.enabled` | `boolean` | `false` | `live` | Enable per-module counter/latency-histogram aggregation. |
| `protean.trace.metrics.latency-buckets` | `int` | `20` | `future` | Number of latency-histogram buckets per module (log-linear). More buckets = more precise percentiles but more memory. |
| `protean.trace.metrics.max-modules` | `int` | `512` | `future` | Maximum number of tracked modules. On exceed, evicts the least-recently-seen module first. |

## worker — external worker (process/container)

Worker-isolation execution settings.

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.worker.modules-per-worker` | `int` | `4` | `future` | Max modules per worker (`1` = a dedicated JVM per module). |
| `protean.worker.min-warm` | `int` | `0` | `future` | Number of empty workers to keep warm (for reuse). |
| `protean.worker.auto-restart` | `boolean` | `false` | `live` | Auto-restart the modules of a crashed worker (process track). |
| `protean.worker.shutdown-grace-ms` | `long` | `5000` | `live` | Grace period (ms) each worker JVM gets to shut down gracefully (SIGTERM) on main shutdown before it is force-killed. `0` = force-kill immediately; negative → treated as `0`. |
| `protean.worker.rpc-bridge` | `boolean` | `false` | `restart` | Allow a worker to call the main's shared beans over the RPC bridge. |
| `protean.worker.runtime` | `String` | `embed` | `restart` | Worker runtime deployment model: `embed` \| `sidecar`. |

### worker.admin-auth — worker `/__admin/*` authentication (opt-in)

Defense-in-depth auth on mutating worker admin calls (chiefly for the container track, whose port is more exposed). The read-only `/__admin/health` probe stays open. Independent of `protean.bridge.*`.

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.worker.admin-auth.enabled` | `boolean` | `false` | `restart` | Require auth on mutating `/__admin/*` calls. When on, the main generates/injects a secret into spawned workers; unauthenticated mutating calls get 401. |
| `protean.worker.admin-auth.secret` | `String` | (none) | `restart` | Shared secret. Blank + enabled → the main auto-generates one per JVM lifetime and injects it. Set explicitly to pin an externally-managed secret. |
| `protean.worker.admin-auth.mode` | `String` | `hmac` | `restart` | `hmac` (per-request HMAC-SHA256 over timestamp + nonce + body, with replay/tamper defense — default) or `token` (static bearer token). |
| `protean.worker.admin-auth.hmac-window-ms` | `long` | `30000` | `restart` | `hmac` mode: max accepted clock skew (ms) between sender timestamp and worker clock. |

### worker.datasource — global manual DB scope

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.worker.datasource.url` | `String` | `""` | `future` | Global manual worker DB-scope URL when auto-provision is not used. |

### worker.container — container track (Docker)

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.worker.container.image` | `String` | `eclipse-temurin:21-jdk` | `future` | Worker container image. |
| `protean.worker.container.jar` | `String` | `""` | `future` | Explicit worker jar path. If empty, auto-discovers the `-boot.jar` in `build/libs`. |
| `protean.worker.container.memory` | `String` | `256m` | `future` | Container memory limit. |
| `protean.worker.container.pids-limit` | `long` | `512` | `future` | PID limit for fork-bomb defense. |
| `protean.worker.container.network` | `String` | `""` | `future` | Network for egress isolation (e.g. `internal`). Empty = default. |
| `protean.worker.container.seccomp` | `String` | `""` | `future` | seccomp profile path. Empty = docker default. |
| `protean.worker.container.auto-restart` | `boolean` | `false` | `live` | Auto-restart container workers. |
| `protean.worker.container.db-host` | `String` | `host.docker.internal` | `future` | Hostname-rewrite target for reaching the host DB from a container. |

### worker.db — per-module isolated DB-scope auto-provisioning

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.worker.db.auto-provision` | `boolean` | `false` | `restart` | Enable per-module isolated DB-scope auto-provisioning. |
| `protean.worker.db.dialect` | `String` | (none) | `restart` | `mysql` \| `postgresql`. `restart` (not live): existing scopes were created under the current dialect's DDL/URL shape, so a live swap would leave them unmanageable. |
| `protean.worker.db.admin-url` | `String` | (none) | `future` | Admin connection URL for provisioning. Rotatable at runtime — a change applies to the next provision without a restart; the new connection is validated before adoption and a bad value is rejected (the previous connection is kept). |
| `protean.worker.db.admin-username` | `String` | (none) | `future` | Admin username (rotatable at runtime; see `admin-url`). |
| `protean.worker.db.admin-password` | `String` | (none) | `future` | Admin password (rotatable at runtime without a restart; see `admin-url`). |
| `protean.worker.db.deprovision-on-undeploy` | `boolean` | `false` | `live` | Whether to remove the provisioned scope on undeploy. |

### worker.sidecar — sidecar worker runtime (opt-in)

| Key | Type | Default | Tier | Description |
|----|------|--------|------|------|
| `protean.worker.sidecar.jar` | `String` | `""` | `future` | sidecar worker jar path. |
| `protean.worker.sidecar.image` | `String` | `""` | `future` | sidecar worker image. |
| `protean.worker.sidecar.shared-api` | `String` | `""` | `future` | Shared-type jar for worker compilation. |

## Example

```yaml
protean:
  isolation:
    mode: in-process
  admin:
    enabled: true
  gate:
    tests-enabled: true
    review-enabled: true
  module:
    request-timeout-ms: 5000
    executor:
      pool-size: 4
  module-store:
    backend: filesystem
    dir: /var/lib/protean/modules
  trace:
    enabled: true
    capacity: 500
```

## Related Docs

- [01. Getting Started](01-getting-started.md)
- [02. Module Authoring](02-module-authoring.md)
- [05. Isolation Modes](05-isolation-modes.md)
- [06. Promotion Gates](06-promotion-gates.md)
- [08. MCP Integration](08-mcp-integration.md)
- [README](../../README.md)
