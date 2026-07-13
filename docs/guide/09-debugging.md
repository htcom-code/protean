**English** | [한국어](09-debugging.ko.md)

# 09. Debugging

Protean provides a Level 3 surface (the `debug.*` MCP tools) for interactively debugging a deployed module while it is running. Breakpoints, stepping, stack/variable inspection, expression evaluation, and fix-and-continue (redefine) are performed over JDI (Java Debug Interface). This document covers the steps for a library consumer to enable the debug surface and operate a session.

## Enabling the debug surface (execution gate)

Debugging is more dangerous than deploying (`redefine`·`evaluate` = effectively arbitrary code execution), so it is a **separate opt-in** from the MCP adapter ([08. MCP Integration](08-mcp-integration.md)). With `protean.mcp.enabled=true` the debug tools are **always exposed in `tools/list`**, but actual **execution** is gated by `protean.mcp.debug.enabled` (default `false`):

```properties
protean.mcp.enabled=true         # MCP dispatcher (required)
protean.mcp.debug.enabled=true   # debug.* execution gate (default false = prod posture)
```

- **Gate OFF (default, prod)**: the debug tools are in the list, but calling one is **rejected immediately** with `isError` ("debug surface disabled") — no side effects at all, such as spawning a JDWP worker.
- **Gate ON (dev)**: calls are allowed.
- **Runtime flip**: this gate can be opened and closed **without a restart** via the `DebugSurfaceState` bean (the consumer calls `setEnabled(true/false)` from its own authorized admin path). The tools are already in the list, so there is nothing for the client to re-fetch — **no reconnect needed**.
- Turning off `protean.mcp.enabled` removes the dispatcher itself, so no tool is exposed. The debug surface is active only under the `!worker` profile.

**Two-layer defense**: the execution gate (`debug.enabled`) + the `ModuleActionAuthorizer` (the authorization SPI, [08](08-mcp-integration.md)) that decides `ModuleAction.DEBUG` on every `debug.*` call. In prod either one blocks it.

## zero-dep — JDI

The debug core (`DebugCore`) uses only the standard JDK module `jdk.jdi` (no separate dependency). It socket-attaches to a target JVM with JDWP enabled via the `com.sun.jdi.SocketAttach` connector. Therefore the server must run on a **JDK** that includes `jdk.jdi`, not a JRE (without the connector, attach errors out).

The core is protocol-agnostic, so MCP is just a thin session adapter on top of it.

## Debug tool catalog

| Tool name | Input | Purpose |
|---|---|---|
| `debug.launch` | `files[]` or `manifest`, `id`·`version`·`controller`·`isolationMode` | (Re)deploy the module to a dedicated JDWP-enabled debug worker and auto-attach, opening a session |
| `debug.attach` | `host` (required)·`port` (required) | Attach to a target JVM already up with JDWP and open a session |
| `debug.set_breakpoint` | `sessionId`·`className`·`line` | Set a breakpoint at `className:line` |
| `debug.await_stop` | `sessionId`·`timeoutMs` (default 10000) | Wait for the next stop (breakpoint/step) and return the stop location |
| `debug.frames` | `sessionId` | Return the stack frames of the suspended thread |
| `debug.get_variables` | `sessionId`·`frame` (default 0) | The frame's local variables (name→value). Requires `-g` compilation |
| `debug.evaluate` | `sessionId`·`expr` (required)·`frame` (default 0) | Evaluate an expression in the suspended frame |
| `debug.step` | `sessionId`·`depth` (`over`\|`into`\|`out`, default `over`) | Run one step and stop at the next line |
| `debug.continue` | `sessionId` | Resume the suspended thread |
| `debug.redefine` | `sessionId`·`files[]` (required) | fix-and-continue: recompile the fixed source and replace the loaded class in place |
| `debug.terminate` | `sessionId` | End the session (detach the target VM) |
| `debug.list_sessions` | (none) | List of active debug sessions (`sessionId`·`vmName`·`owner`·`idleMs`·`paused`·`lastStop`). For reattach/rediscovery |

`debug.launch`/`debug.attach` return a `sessionId` in the response. All subsequent tools pass this `sessionId`. `debug.launch` additionally returns `moduleId`·`workerPort`·`jdwpPort`·`paths`.

## Workflows

### debug.launch — one step (recommended)

`debug.launch` (re)deploys the target module to a **dedicated debug worker** with JDWP enabled and auto-attaches. The input is the same `files[]`/`manifest` format as `protean.deploy_module` (reuses `ModuleInputNormalizer`). On session end (`debug.terminate`), the worker JVM is killed and the routes revert to the normal deploy.

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call",
 "params":{"name":"debug.launch","arguments":{
   "id":"orders","version":"1.0.0",
   "controller":"com.acme.orders.OrderController",
   "files":[{"kind":"source","filename":"OrderController.java",
             "content":"package com.acme.orders; ... @RestController ..."}]
 }}}
```

Use the response's `sessionId` (e.g. `dbg-1`) in subsequent calls.

### debug.attach — external JVM

Use this to attach to a JVM already up with JDWP (`-agentlib:jdwp=...,server=y,address=<port>`).

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call",
 "params":{"name":"debug.attach","arguments":{"host":"127.0.0.1","port":5005}}}
```

### Hands-on scenario — breakpoint → inspect → resume

After opening session `dbg-1` with `debug.launch`:

```json
// 1) breakpoint
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
  "name":"debug.set_breakpoint",
  "arguments":{"sessionId":"dbg-1","className":"com.acme.orders.OrderController","line":42}}}

// 2) call that endpoint once over HTTP, then wait for the stop
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
  "name":"debug.await_stop","arguments":{"sessionId":"dbg-1","timeoutMs":15000}}}
// → {"stopped":true,"className":"...OrderController","method":"create","line":42}

// 3) inspect the stack and locals
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
  "name":"debug.frames","arguments":{"sessionId":"dbg-1"}}}
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
  "name":"debug.get_variables","arguments":{"sessionId":"dbg-1","frame":0}}}

// 4) evaluate an expression
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{
  "name":"debug.evaluate","arguments":{"sessionId":"dbg-1","expr":"order.getItems().size()"}}}

// 5) step / resume
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{
  "name":"debug.step","arguments":{"sessionId":"dbg-1","depth":"over"}}}
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{
  "name":"debug.continue","arguments":{"sessionId":"dbg-1"}}}

// 6) end (also kills the worker launched by launch + reverts the routes)
{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{
  "name":"debug.terminate","arguments":{"sessionId":"dbg-1"}}}
```

Since `frames`/`get_variables`/`evaluate` are meaningful only while suspended, when you advance the flow with step/resume, wait for the next stop with `debug.await_stop`.

### debug.evaluate — supported grammar scope

`debug.evaluate` is a zero-dep, hand-rolled expression evaluator. Since it is a library and the consumer cannot extend it at use time, it supports Java expression grammar as completely as possible.

**Supported**: identifiers (locals / `this` fields)·literals·`this`·field/getter/indexing·type-aware overload/constructor resolution (widening·autoboxing·supertype·most-specific)·arithmetic/comparison/logical (short-circuit)·bit/shift·unary (`- ! ~`)·ternary (`?:`)·string `+` concatenation·primitive and reference-type casts (FQCN)·`instanceof`·`new`·FQCN static references·assignment (`= += …`, local/field/array/static lvalue)·**lambdas·method references** (a synthesized class is injected into the target VM and passed to real `stream()` etc.).

**Constraints**:
- Lambdas·method references materialize **only in a functional-interface argument position** (e.g. `stream().filter(...)`). They cannot be evaluated standalone.
- Lambda parameters must have an **explicit type** — `(java.lang.String s) -> ...` (no type inference).
- Type names in static/cast/`instanceof` must be loaded **FQCNs**.

```json
// examples
{"expr":"user.getName()"}
{"expr":"order.items[0].price * 2"}
{"expr":"list.size() > 0 ? \"has\" : \"empty\""}
{"expr":"new java.math.BigDecimal(\"1.5\").add(total)"}
{"expr":"obj instanceof com.acme.orders.Order"}
// lambda — explicit parameter type
{"expr":"items.stream().filter((java.lang.String s) -> s.length() > 0).count()"}
// method reference — unbound/bound/static/constructor
{"expr":"items.stream().map(java.lang.String::length).count()"}
```

Evaluation errors are returned as an `isError` tool result.

### debug.redefine — fix-and-continue

`debug.redefine` recompiles the fixed source and replaces the running class in place (**method bodies only**; schema changes are impossible due to JVM constraints). The session and suspended state are preserved. The input `files[]` is a `{filename, content}` array, and the FQCN is auto-derived from the file name/content.

```json
{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{
  "name":"debug.redefine","arguments":{"sessionId":"dbg-1","files":[
    {"filename":"OrderController.java",
     "content":"package com.acme.orders; ... // fixed method body ..."}]}}}
```

An unloaded/unsupported class (e.g. a method-signature or field change) throws and is mapped to `isError`.

## `RuntimeCompiler`'s `-g` — why it is needed

Protean's runtime compiler always compiles module source with the `-g` option (full debug info: line + vars + source). This information is what lets interactive debugging:

- have the breakpoint's **line mapping** work exactly, and
- have `debug.get_variables` look up **local variable names**.

The class file is merely slightly larger, with no runtime performance impact. That is, you can debug a deployed module as-is without a separate debug build.

## Breakpoint stops and the request timeout

When you stop at a breakpoint, the thread that triggered the request is suspended (JDI suspend) and waits indefinitely. Because of this:

- **The triggering HTTP client may time out.** While suspended, the triggering request gets no response, so a short client timeout (e.g. `curl --max-time`) cuts off. **This is harmless** — breakpoint inspection is done over a separate MCP channel (`POST /platform/mcp`), not the triggering connection. If you need the trigger's final response body, set a generous (or unlimited) client timeout.
- **The module execution watchdog does not apply to the debug path.** Even with `protean.module.request-timeout-ms` on, a path being served by a `debug.launch` session is exempted from the execution timeout (interactive debugging and the request timeout are mutually exclusive). Normal (non-debug) paths still get the timeout safety net.
- **The session is decoupled from the triggering request.** Even if the client disconnects, the session stays alive. The session's lifetime ends only by `debug.terminate` or idle auto-reclaim.

## Session reattach · rediscovery

A session is identified only by `sessionId` and is not tied to a particular client connection. So even if a client disconnects and **reconnects, it reattaches to the same `sessionId` as-is** (no separate handshake — just call `debug.frames`/`await_stop`/`continue` with that id). If you lost the `sessionId`, rediscover it by listing active sessions with `debug.list_sessions`:

```json
{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{
  "name":"debug.list_sessions","arguments":{}}}
```

Each entry in the response's `structuredContent.sessions[]` carries `sessionId`·`vmName`·`owner`·`idleMs`·`paused`·`lastStop` (the last stop location). (Currently it returns all sessions. Per-user scoping is a follow-up.)

## Session idle auto-reclaim

If an agent forgets `debug.terminate`, the worker JVM launched by `debug.launch` can leak. To prevent this, `DebugCore` runs an idle sweeper: it auto-reclaims a session whose idle time since last activity exceeds the threshold (terminate → dispose hook → worker kill). The activity time is refreshed on every tool call that accesses the session.

```properties
# Default 30 minutes. 0 or negative disables auto-reclaim.
protean.mcp.debug.session-idle-timeout=30m
```

The sweep interval is auto-determined at 1/4 of the timeout (min 1 second, max 60 seconds). A warning log is left on reclaim.

## Operational notes

- Since the debug surface allows arbitrary code redefinition and expression evaluation, when enabling it in production you must strongly control the `DEBUG` action with `ModuleActionAuthorizer` and put `POST /platform/mcp` behind authentication ([12. Security](12-security.md)).
- Confirm you are running on a JDK (`jdk.jdi` is required). For operations overall, see [11. Operations](11-operations.md).

## Related docs

- [08. MCP Integration](08-mcp-integration.md)
- [05. Isolation Modes](05-isolation-modes.md)
- [11. Operations](11-operations.md)
- [12. Security](12-security.md)
- [13. Troubleshooting](13-troubleshooting.md)
- [README](../../README.md)
