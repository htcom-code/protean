**English** | [한국어](04-rest-api.ko.md)

# 04. REST API Reference

Full reference for the Protean control plane HTTP API. It covers every endpoint exposed by the four admin controllers under `/platform` — `ModuleAdminController`, `TraceAdminController`, `ConfigAdminController`, and `SharedLibAdminController`. It drives the module lifecycle (deploy, update, uninstall, query, approve, rollback), runtime trace queries, live configuration, and the shared-lib store over HTTP.

## Exposure toggle

All four controllers register only under the following conditions.

- `protean.admin.enabled=true` (default, on when unset). Setting it to `false` means the `/platform` admin controllers (`/modules`, `/traces`, `/config`, `/shared-libs`) are not registered at all.
- Not on the worker profile (`@Profile("!worker")`).

This library does not implement authentication/authorization. The admin surface assumes trusted developers; when exposed, the consumer must control access with Spring Security or the like.

## Common notes

- All request/response bodies are JSON (the only exception is `from-manifest` — YAML text).
- Error responses take the form `{"error": "<message>"}` (the 400/409/422 cases the exception handler maps). Some 404/400 responses follow Spring's default `ResponseStatusException` format.

### Status-code mapping

| Exception | Status code | Situation |
|---|---|---|
| `GateFailedException` | `422 Unprocessable Entity` | Promotion gate ① (tests) / ② (review) rejection |
| `IllegalStateException` | `409 Conflict` | Isolation mode unsupported / unknown mode / state conflict |
| `IllegalArgumentException` | `400 Bad Request` | Invalid manifest/input (missing required field, etc.) |
| `ResponseStatusException(NOT_FOUND)` | `404 Not Found` | Target module not found |
| `ResponseStatusException(BAD_REQUEST)` | `400 Bad Request` | Path id ↔ body id mismatch, non-resource file in reload |

---

## Module management — base path `/platform/modules`

### GET `/platform/modules`

List of ACTIVE module statuses.

- Response `200`: `ModuleStatus[]`

```json
[
  {
    "id": "cp-mod",
    "version": "1.0.0",
    "trustTier": "TRUSTED",
    "desiredState": "ACTIVE",
    "controllerFqcn": "runtime.cp.CpController",
    "mode": "in-process",
    "needsSharedBeans": false,
    "bridgedInterfaces": null
  }
]
```

`ModuleStatus` fields: `id`, `version`, `trustTier` (`TRUSTED`|`UNTRUSTED`), `desiredState` (`ACTIVE`|`INACTIVE`|`PENDING_APPROVAL`), `controllerFqcn`, `mode` (the actually applied isolation mode), `needsSharedBeans`, `bridgedInterfaces`. Heavy fields such as source/test/verification plan are excluded from the response.

### GET `/platform/modules/{id}`

Single module status.

- Response `200`: `ModuleStatus`
- `404`: module not found

### POST `/platform/modules`

Deploy a module. The body is a `ModuleDescriptor` JSON. If it passes the gates/verification, `201`.

- Request body: `ModuleDescriptor`
- Response `201`: `Location: /platform/modules/{id}` header + `ModuleStatus` body
- `422`: gate rejection (e.g. no test bundled)
- `409`: isolation mode does not support the module (e.g. shared-bean dependency)
- `400`: invalid input

Example `ModuleDescriptor` request body:

```json
{
  "id": "cp-mod",
  "version": "1.0.0",
  "trustTier": "TRUSTED",
  "desiredState": "ACTIVE",
  "controllerFqcn": "runtime.cp.CpController",
  "componentFqcns": ["runtime.cp.CpController"],
  "sources": {
    "runtime.cp.CpController": "package runtime.cp; import org.springframework.web.bind.annotation.*; @RestController public class CpController { @GetMapping(\"/cp/ping\") public String ping() { return \"v1\"; } }"
  },
  "tests": {
    "runtime.cp.CpControllerTest": "package runtime.cp; import org.junit.jupiter.api.*; import static org.junit.jupiter.api.Assertions.*; public class CpControllerTest { @Test void ping() { assertEquals(\"v1\", new CpController().ping()); } }"
  },
  "needsSharedBeans": false,
  "verification": null,
  "isolationMode": null,
  "bridgedInterfaces": null
}
```

Key `ModuleDescriptor` fields:

| Field | Description |
|---|---|
| `id` | Module identifier |
| `version` | Version (recompile pin on recovery, rollback-history key) |
| `trustTier` | `TRUSTED` \| `UNTRUSTED` |
| `desiredState` | `ACTIVE` \| `INACTIVE` \| `PENDING_APPROVAL` |
| `controllerFqcn` | FQCN of the controller whose REST mappings are registered |
| `componentFqcns` | FQCNs of components to register in the child context (controller included) |
| `sources` | `FQCN → Java source` (runtime-compile input) |
| `tests` | `FQCN → JUnit test source` (gate ① input, mandatory) |
| `needsSharedBeans` | Whether it depends on shared in-process beans (isolation-mode compatibility check) |
| `verification` | Gate ③ verification plan (`null` = skip verification) |
| `isolationMode` | `"in-process"` \| `"worker"` \| `"container"` (`null` = global default) |
| `bridgedInterfaces` | FQCNs of interfaces the worker mode calls over RPC (`null`/empty = none) |
| `signerKeyId`, `signature` | For the signature gate (`null` = unsigned) |
| `resources` | `classpath path → ModuleResource` (mapper XML, etc.; `null` = none) |

Supplying `verification` (`VerificationPlan`) makes gate ③ verify the live endpoint. Each item is skipped if `null`.

```json
{
  "integration": [
    { "method": "GET", "path": "/cp/ping", "expectedStatus": 200, "bodyContains": "v1" }
  ],
  "loadPath": "/cp/ping",
  "concurrency": 4,
  "requestsPerThread": 50,
  "maxAvgLatencyMs": 100,
  "maxHeapGrowthBytes": 10485760
}
```

### POST `/platform/modules/from-manifest`

Deploy via a `module.yaml` declarative manifest (inline sources). The body is YAML text.

- `Content-Type`: `text/plain` | `application/yaml` | `application/x-yaml`
- Request body: YAML text
- Response `201`: `Location` header + `ModuleStatus`

```yaml
id: cp-mod
version: 1.0.0
controller: runtime.cp.CpController
trustTier: TRUSTED
needsSharedBeans: false
sources:
  runtime.cp.CpController: |
    package runtime.cp;
    import org.springframework.web.bind.annotation.*;
    @RestController public class CpController {
      @GetMapping("/cp/ping") public String ping() { return "v1"; }
    }
tests:
  runtime.cp.CpControllerTest: |
    package runtime.cp;
    import org.junit.jupiter.api.*;
    import static org.junit.jupiter.api.Assertions.*;
    public class CpControllerTest {
      @Test void ping() { assertEquals("v1", new CpController().ping()); }
    }
```

Manifest keys: `id`, `version`, `controller` are required. `trustTier` (default `TRUSTED`), `isolationMode`, `needsSharedBeans` (default false), `components`, `bridgedInterfaces`, `sources`/`tests`/`resources` (inline maps). The `sourceDir`/`testDir`/`resourceDir` (file-scan) keys cannot be used for HTTP inline deploy. `desiredState` is fixed to `ACTIVE`.

### PUT `/platform/modules/{id}`

Canary update of a module (zero-downtime hot-swap). The path `id` and the body `id` must match.

- Request body: `ModuleDescriptor` (full-replace, canonical)
- Response `200`: `ModuleStatus`
- `400`: path id ↔ body id mismatch
- `404`: no update target
- `422`: gate rejection. On verification (③) failure it auto-rolls back to the previous version.

### PATCH `/platform/modules/{id}`

Delta/patch update — send only the changed files, overlay them onto the current descriptor, then run a canary update. This is a convenience for assembling input; internally it goes through the full-replace `update` pipeline.

- Request body: `ModulePatchRequest`
- Response `200`: `ModuleStatus`
- `404`: no patch target

```json
{
  "version": "2.0.0",
  "files": [
    { "kind": "source", "filename": "runtime.cp.CpController", "content": "...java...", "base64": false }
  ],
  "removeFiles": ["runtime.cp.OldClass"]
}
```

`ModulePatchRequest` fields: `version` (new version, keeps the current one if empty), `files` (`FileSpec[]`, add/replace), `removeFiles` (keys to remove — source/test FQCN or resource path).

`FileSpec`: `kind` (`source`|`test`|`resource`), `filename` (source/test derive the FQCN, resource is the classpath path), `content`, `base64` (true if content is Base64).

### POST `/platform/modules/{id}/reload-resources`

Resource live-reload — swap only resources in place with no compile or context rebuild. For resources read on every request (no effect on ORM init-parse resources). Only resource files are allowed. If the isolation mode does not support live-reload (worker/container), it falls back to a full `update`.

- Request body: `ModulePatchRequest` (but `files[].kind` must be empty or `resource`)
- Response `200`: `ModuleStatus`
- `400`: includes a non-resource file (`kind` is not resource)
- `404`: no target

### GET `/platform/modules/{id}/versions`

Module version history (newest-first).

- Response `200`: `ModuleVersion[]`
- `404`: module not found

```json
[
  { "seq": 2, "version": "2.0.0", "savedAtMillis": 1720000000000, "desiredState": "ACTIVE" },
  { "seq": 1, "version": "1.0.0", "savedAtMillis": 1719990000000, "desiredState": "ACTIVE" }
]
```

### GET `/platform/modules/{id}/routes`

The routes actually registered at runtime for a module — the ground truth behind an `ACTIVE` status (see [13. Troubleshooting](13-troubleshooting.md)). An empty array proves that no routes are live even when the store shows `ACTIVE`.

- Response `200`: `RouteInfo[]` — each entry is `{ "methods": [...], "patterns": [...] }`. In-process routes carry both HTTP methods and path patterns; worker/container routes are served through the reverse proxy, which does not track the forwarded method (GET-only PoC), so their `methods` set is empty.
- `404`: module not found

```json
[
  { "methods": ["GET", "POST"], "patterns": ["/cp/ping"] }
]
```

### POST `/platform/modules/{id}/rollback`

Explicit rollback to a specific version in history. It redeploys that version's descriptor, so it takes the safe path of canary hot-swap + gates ①②③ + auto-rollback on failure. The rollback result is recorded as a new history entry too.

- Query parameter: `version` (required) — the version string to revert to
- Response `200`: `ModuleStatus`
- `409`: module not installed / target version not found

```
POST /platform/modules/cp-mod/rollback?version=1.0.0
```

### POST `/platform/modules/{id}/approve`

Promote a module awaiting approval (`PENDING_APPROVAL`) by human authorization (③ verify + deploy → ACTIVE). Used when the approval gate (`protean.gate.approval.required=true`) is on.

- Query parameter: `approver` (required) — approver identity (audit log)
- Response `200`: `ModuleStatus`
- `409`: no approval target / not in the awaiting-approval state. On verification/deploy failure it reverts to PENDING.

```
POST /platform/modules/cp-mod/approve?approver=alice
```

### POST `/platform/modules/{id}/reject`

Reject a module awaiting approval and remove it.

- Query parameter: `approver` (required)
- Response `204`
- `409`: no reject target / not in the awaiting-approval state

### DELETE `/platform/modules/{id}`

Uninstall a module.

- Response `204`
- `404`: no removal target (uninstalling an already-absent module is 404 — idempotent observation)

---

## Runtime traces — base path `/platform/traces`

### GET `/platform/traces`

Query recent request-execution traces, newest-first. If `protean.trace.enabled=false`, nothing is recorded and the list is empty. The trace-query endpoint itself (`/platform/traces`) is not recorded, to avoid self-noise.

- Query parameters (all optional, combined with AND, results stay newest-first):
  - `limit` (default `50`) — max number returned. Clamped to a minimum of 1.
  - `moduleId` — when set, only traces attributed to that module.
  - `errorsOnly` (default `false`) — when `true`, only exception/error responses.
  - `status` — when set, only that status code.
  - `minLatencyMs` — when set, only traces whose latency is at least this value (slow-request diagnosis).
  - `since` — epoch-millis lower bound (only traces completed after this time).
  - `beforeSeq` — a cursor for paging into the past (only below this `seq`).
- Response `200`: `RequestTrace[]`

```json
[
  {
    "seq": 12,
    "epochMillis": 1720000000123,
    "method": "GET",
    "uri": "/cp/ping",
    "pattern": "/cp/ping",
    "moduleId": "cp-mod",
    "status": 200,
    "latencyMs": 3,
    "error": null,
    "traceId": "b1c2d3e4f5a60718"
  }
]
```

`RequestTrace` fields: `seq` (monotonically increasing), `epochMillis` (completion time), `method`, `uri`, `pattern` (matched handler pattern, `null` if unmatched), `moduleId` (attributed module, `null` for static/platform paths), `status`, `latencyMs`, `error` (handler exception FQCN, `null` if none), `traceId` (the correlation id `X-Request-Id`/MDC `traceId` shared with the same request's log lines and RFC 9457 error response, `null` if none).

### GET `/platform/traces/metrics`

Query per-module aggregate metrics (request count, error rate, latency percentiles). **opt-in** — aggregation happens only when `protean.trace.metrics.enabled=true`; when off, it returns an empty list.

- Query parameters:
  - `moduleId` (optional) — when set, only that module (empty list if the module is not tracked); when omitted, all tracked modules.
- Response `200`: `ModuleMetricsSnapshot[]`

```json
[
  {
    "moduleId": "cp-mod",
    "count": 1200,
    "errorCount": 3,
    "errorRate": 0.0025,
    "p50LatencyMs": 2,
    "p95LatencyMs": 11,
    "p99LatencyMs": 25,
    "maxLatencyMs": 140,
    "lastSeenEpochMillis": 1720000000123
  }
]
```

`ModuleMetricsSnapshot` fields: `moduleId` (attributed module, `"(platform)"` for platform/static paths), `count` (total requests), `errorCount` (exception or status ≥ 500), `errorRate` (`errorCount/count`, 0.0 if count is 0), `p50/p95/p99LatencyMs` (approximate percentile latency), `maxLatencyMs` (worst latency), `lastSeenEpochMillis` (most recent request time). Percentile precision and the number of tracked modules are tuned via `protean.trace.metrics.latency-buckets` / `max-modules` ([03. Configuration Reference](03-configuration.md#trace--runtime-trace-recording)).

### GET `/platform/traces/stream`

Live server-sent-events (SSE) push stream — the console uses it in place of 5-second polling. One connection multiplexes three named event types; a freshly opened connection first receives an initial snapshot of all three, then incremental pushes.

- Produces: `text/event-stream`
- Named events: `trace` (a new `RequestTrace`), `metrics` (a `ModuleMetricsSnapshot` update), `modules` (a module-list change)
- Like `/platform/traces`, the stream connection itself is excluded from trace recording (no self-observation).

```
event: trace
data: {"seq":13,"method":"GET","uri":"/cp/ping","status":200,"latencyMs":2,"moduleId":"cp-mod"}
```

---

## Runtime configuration — base path `/platform/config`

Read and live-update `protean.*` configuration through the running `ProteanConfigService`. Each key has a **tier** that decides whether a change takes effect live or needs a restart: `LIVE` (applied immediately), `FUTURE` (stored, applies to the next affected operation), `RESTART_CONDITIONAL` / `RESTART_ARTIFACT` (needs a restart). See [03. Configuration Reference](03-configuration.md).

### GET `/platform/config`

Every known key with its current value and tier.

- Response `200`: `ConfigEntry[]` — each is `{ "key", "value", "tier", "liveApplicable" }`.

```json
[
  { "key": "protean.trace.enabled", "value": true, "tier": "LIVE", "liveApplicable": true },
  { "key": "protean.isolation.mode", "value": "in-process", "tier": "RESTART_CONDITIONAL", "liveApplicable": false }
]
```

### GET `/platform/config/{key}`

A single key.

- Response `200`: `ConfigEntry`
- `400`: unknown config key

### PATCH `/platform/config`

Apply a `{ "key": value }` patch atomically. If any key is unknown or invalid, the whole batch is aborted and **nothing** is applied.

- Request body: JSON object mapping key → new value
- Response `200`: `ApplyResult` when committed
- Response `400`: `ApplyResult` when aborted (unknown/invalid key); nothing applied
- `ApplyResult`: `{ "applied": <bool>, "outcomes": [ { "key", "tier", "outcome", "reason" } ] }`. `outcome` is one of `APPLIED_LIVE`, `APPLIED_FUTURE`, `REJECTED_UNKNOWN`, `REJECTED_INVALID`.

```json
{
  "applied": true,
  "outcomes": [
    { "key": "protean.trace.enabled", "tier": "LIVE", "outcome": "APPLIED_LIVE", "reason": null }
  ]
}
```

---

## Shared libraries — base path `/platform/shared-libs`

Live management of the shared-lib store — the drop-in jar surface that adds libraries (JDBC drivers, etc.) to the module parent classpath without rebuilding the host (see [07. Data Access](07-data-access.md), [11. Operations](11-operations.md)). A deploy is a bundle = one **generation**; multipart is the primary transport (native jars are 1–5 MB).

### GET `/platform/shared-libs`

The current generation id and the live stored libs.

- Response `200`: `SharedLibsView` — `{ "generation": <long>, "libs": [ StoredLib... ] }`. `StoredLib` is `{ "name", "version", "sha256", "size", "signerKeyId", "signature" }`.

```json
{
  "generation": 3,
  "libs": [
    { "name": "mysql-connector-j", "version": "8.4.0", "sha256": "…", "size": 2512345, "signerKeyId": null, "signature": null }
  ]
}
```

### GET `/platform/shared-libs/{name}`

Single stored-lib metadata.

- Response `200`: `StoredLib`
- `404`: not stored

### POST `/platform/shared-libs`

Upload a bundle of jars as one new generation. Content type `multipart/form-data`. The `name` / `version` form fields (and the optional `signerKeyId` / `signature`) are **parallel arrays** to the `file` parts — the counts must match. An upload whose every jar already matches the store is idempotent (no new generation).

- Form fields: `name` (repeated), `version` (repeated), `signerKeyId` (optional, repeated), `signature` (optional, repeated)
- File parts: `file` (repeated) — one per jar
- Response `201`: `SharedLibsView` (the resulting store view)
- `400`: name/version/file counts do not match, or an optional field is not parallel to `file`

```
curl -X POST http://localhost:8080/platform/shared-libs \
  -F name=mysql-connector-j -F version=8.4.0 -F file=@mysql-connector-j-8.4.0.jar
```

### DELETE `/platform/shared-libs/{name}`

Remove a lib from the store. It affects **future generations only** — generations already in use keep it.

- Response `204`

## Related docs

- [02. Module Authoring](02-module-authoring.md)
- [03. Configuration Reference](03-configuration.md)
- [06. Promotion Gates](06-promotion-gates.md)
- [08. MCP Integration](08-mcp-integration.md)
- [11. Operations](11-operations.md)
- [13. Troubleshooting](13-troubleshooting.md)
- [README](../../README.md)
