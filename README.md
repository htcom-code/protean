**English** | [한국어](README.ko.md)

# Protean

[![License: MPL 2.0](https://img.shields.io/badge/License-MPL_2.0-brightgreen.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.x-green.svg)

**A dynamic API-server library that uses Spring Boot as a runtime platform.**
While the server is running it takes Java source → compiles it → loads it under a
dedicated ClassLoader → registers it as REST endpoints → and hot-swaps, rolls
back, and unloads it with zero downtime. You can attach and detach APIs without a
restart or redeploy.

Two control surfaces — **REST** and **MCP** (Model Context Protocol) — deploy and
debug modules. Over **MCP an AI agent connects directly (HTTP/stdio)** and runs the
whole development loop against a running server, no restart: author source → deploy
behind the test/bytecode/verify gates → debug live (JDI breakpoints, `evaluate`,
hot-swap) → observe traces & metrics → roll back. This surface is powerful (RCE-class),
so authenticate it outside local use — see [08. MCP Integration](docs/guide/08-mcp-integration.md).
Coordinate: `org.htcom:protean` (Spring Boot 3.5.x / Java 21).

## Features

- **Dynamic loading engine** — compile Java source at runtime → load under a dedicated
  ClassLoader → register REST mappings, with zero-downtime hot-swap/rollback/unload. No
  restart or redeploy. On unload it even purges Spring/ClassLoader cache leaks.
- **Promotion pipeline (trust model)** — `install` must pass ①tests ②bytecode review
  ③live verify before serving. Ed25519 signing and human approval are opt-in. Premise:
  "all source = trusted developer".
- **Versioning & rollback** — every deploy accrues version history; roll back to a specific
  version (`/rollback?version=`). A canary update that fails ③verify auto-rolls back.
- **Restart recovery** — descriptors are persisted to a write-ahead store (filesystem or
  JDBC); on restart `reconcile` restores the ACTIVE modules. No reliance on in-memory state.
- **Three isolation modes** — `in-process` / `worker` (separate JVM) / `container`
  (Docker · cgroup · seccomp · read-only FS). Chosen per module; all support zero-downtime
  hot-swap, worker pooling, crash supervision, and a dedicated DB.
- **RPC bridge** — a worker calls the main process's shared beans (dynamic proxy over
  arbitrary interfaces). Opt-in shared-secret auth (token/HMAC) and `InputStream` return
  streaming.
- **Runtime execution guard** — a request-timeout watchdog (`protean.module.request-timeout-ms`)
  cuts off runaway/blocking handlers (503 on exceed). Cooperative interrupt — only blocking
  calls are released.
- **MCP adapter** — an MCP agent deploys, queries, and debugs modules over HTTP/stdio.
  Zero-dep JSON-RPC, open-core (consumers can contribute custom tool beans).
- **Level 3 debugging** — JDI-based breakpoints, stepping, variable inspection, expression
  evaluation, fix-and-continue (redefine). Zero-dep.
- **Module data-access contract** — provides only engine-agnostic mechanism (resource
  channel, multi-DataSource, per-module DB-scope auto-provisioning); ORM, pooling, and
  policy are left to the consumer.
- **Structured errors (RFC 9457)** — MCP/HTTP/Admin errors surface as RFC 9457
  problem + extension members correlated by `traceId`, so an agent self-corrects and retries
  deterministically instead of parsing prose.
- **Runtime observability** — request traces (module attribution + `traceId` log correlation)
  plus opt-in per-module aggregate metrics (request count, error rate, latency p50/p95/p99).
  Queryable via REST (`/platform/traces`) and MCP.

See [Core concepts](#core-concepts) for how each works and [Control surface](#control-surface)
for how to drive them. Axes that need to vary are opened via bean registration
(**open-core**) — see [Extension points (SPI)](#extension-points-spi).

---

## Core concepts

### Module
The unit of deployment. Declared as a `ModuleDescriptor` (record) — id/version,
controller/component FQCNs, `sources` (FQCN → Java source), `tests` (promotion
gate input), `resources` (path → non-Java resource, e.g. mapper XML), isolation
mode, verification plan, signature, etc. It can also be declared with a
`module.yaml` manifest.

### Lifecycle & promotion gates
A module must pass the **promotion pipeline** at `install` before it serves. Each
stage toggles via `protean.gate.*`:

```
(signature opt-in) → ①tests → ②review(bytecode) → (approval opt-in) → ③verify(live) → ACTIVE
```

- **① tests** — the module's bundled JUnit tests are compiled and run at runtime
  and must pass (no tests = rejected).
- **② review** — an ASM bytecode static scan (`ForbiddenApiRule` blocks
  `System.exit` / `Runtime.exec` / `Runtime.addShutdownHook`, …). Register a
  `CodeRule` bean to add rules (extension point).
- **③ verify** — HTTP probe / concurrency / timeout / memory checks on a real
  port. Auto-rollback on failure.
- **signature gate** (opt-in) — Ed25519 signature verification (trust store).
- **approval gate** (opt-in) — auto-gate-passing modules are held in
  `PENDING_APPROVAL`; a human must `approve` them to reach ③verify + deploy →
  `ACTIVE`. An unapproved module is not served even after a restart (no bypass).

`DesiredState = { ACTIVE, INACTIVE, PENDING_APPROVAL }`. Only `ACTIVE` is
`reconcile`d at startup.

### Isolation strategy
Each module can pick an isolation mode (`ModuleDescriptor.isolationMode`,
null = the global default `protean.isolation.mode`):

| Mode | Implementation | Isolation level |
|------|------|-----------|
| `in-process` | `InProcessIsolation` | Same JVM · dedicated ClassLoader + child ApplicationContext (default) |
| `worker` | `WorkerProcessIsolation` | Separate JVM process · reverse-proxy forwarding |
| `container` | `ContainerWorkerIsolation` | Docker container · cgroup memory/PID · read-only FS · cap-drop · seccomp |

Every mode supports zero-downtime hot-swap, pooling, supervision (crash restart),
and a dedicated DB. When needed a worker calls the main process's shared beans
over an **RPC bridge** (declared via `bridgedInterfaces`).

### Trust model
All source = trusted developer, by design (the in-process production case). A
security sandbox for untrusted source is an **explicit non-goal**
(`SandboxAbsenceTest` proves the absence).

### Data access (SQL · DataSource)
Protean does not pick a data-access engine — it **provides mechanism and leaves
policy to the consumer**. A module configures its own persistence layer
(JdbcTemplate / MyBatis / JPA / multi-DataSource) with arbitrary `@Configuration`
inside its child context.

- **Drivers/ORM = host-bundled.** A module is source-only; drivers/ORM go on the host
  and the module uses them parent-first. Drop a jar into `protean.module.shared-lib-dir`
  (app-lifetime CL, no `DriverManager` leak). The bundled mysql/postgres drivers are
  **optional** in the published POM (not forced transitively).
- **Resource channel** — `ModuleDescriptor.resources` (path → content, binaries base64)
  ships non-Java files such as mapper XML, `persistence.xml`, migration SQL; the module
  ClassLoader serves them owned-child-first.
- **Per-module DB-scope provisioning** — `protean.worker.db.auto-provision` creates a
  dedicated DB/schema + user per module (MySQL/Postgres built in; vendor extension via a
  `DbDialect` bean).
- **Transactions** — follow from the DataSource choice: in-process + shared DataSource +
  parent tx manager participates in the host tx; a private DataSource is isolated;
  worker/container are always isolated (crossing is via the RPC bridge).
- **Managed execution & unload** — `ProteanTaskExecutor` (per-module, lazy, bounded;
  auto-shuts-down on unload) + the `ModuleUnloadCallback` SPI.
- **update diff** — a resource-only update skips recompilation while keeping the
  zero-downtime swap.

Pool sizing, sharding, routing, multi-tenancy, XA, and ORM choice are **consumer policy**
(out of scope).

---

## Quick start (consuming the library)

Protean is published as a plain jar (currently mavenLocal; the remote registry is
GitHub Packages after the migration).

```gradle
dependencies {
    implementation 'org.htcom:protean:0.0.1-SNAPSHOT'
    // The consumer app is a Spring Boot app so it already has this
    // (Protean transits spring only at runtime scope):
    implementation 'org.springframework.boot:spring-boot-starter-web'
}
```

Auto-configuration (`ProteanAutoConfiguration`) loads automatically. The admin
REST (`/platform/modules`) is on by default; MCP and debugging are fail-safe off.
Deploying a module (REST):

```bash
curl -X POST localhost:8080/platform/modules \
  -H 'Content-Type: application/json' \
  -d '{ "id": "hello", "version": "1", "controllerFqcn": "gen.HelloController",
        "sources": { "gen.HelloController": "...java source..." },
        "tests":   { "gen.HelloTest": "...junit source..." } }'
```

---

## Control surface

### Admin REST — `/platform/modules` (`ModuleAdminController`, `protean.admin.enabled`)
| Method | Path | Action |
|--------|------|------|
| `GET` | `/platform/modules` | List ACTIVE modules |
| `GET` | `/platform/modules/{id}` | Single status |
| `POST` | `/platform/modules` | Deploy (201 on gate/verify pass) |
| `POST` | `/platform/modules/from-manifest` | Deploy from `module.yaml` |
| `PUT` | `/platform/modules/{id}` | Canary update (hot-swap) |
| `PATCH` | `/platform/modules/{id}` | Delta/patch update (file overlay) |
| `POST` | `/platform/modules/{id}/reload-resources` | Swap resources, no downtime (no recompile) |
| `GET` | `/platform/modules/{id}/versions` | Version history |
| `GET` | `/platform/modules/{id}/routes` | Live-registered routes (ground truth for ACTIVE) |
| `POST` | `/platform/modules/{id}/rollback?version=` | Explicit rollback |
| `POST` | `/platform/modules/{id}/approve?approver=` | Approve → ACTIVE |
| `POST` | `/platform/modules/{id}/reject?approver=` | Reject |
| `DELETE` | `/platform/modules/{id}` | Unload |

### Observability REST — `/platform/traces` (`TraceAdminController`, `protean.admin.enabled`)
| Method | Path | Action |
|--------|------|------|
| `GET` | `/platform/traces` | Request traces (newest-first). Filters `moduleId`·`errorsOnly`·`status`·`minLatencyMs`·`since`·`beforeSeq`·`limit` |
| `GET` | `/platform/traces/metrics` | Per-module aggregate metrics (count, error rate, latency p50/p95/p99). Requires `protean.trace.metrics.enabled` |
| `GET` | `/platform/traces/stream` | Live SSE push (`trace`/`metrics`/`modules` events) |

### Config REST — `/platform/config` (`ConfigAdminController`, `protean.admin.enabled`)
| Method | Path | Action |
|--------|------|------|
| `GET` | `/platform/config` | All keys with current value + tier |
| `GET` | `/platform/config/{key}` | Single key |
| `PATCH` | `/platform/config` | Apply a `{key: value}` patch (atomic; aborts on any unknown/invalid key) |

### Shared-lib REST — `/platform/shared-libs` (`SharedLibAdminController`, `protean.admin.enabled`)
| Method | Path | Action |
|--------|------|------|
| `GET` | `/platform/shared-libs` | Current generation + stored libs |
| `GET` | `/platform/shared-libs/{name}` | Single stored-lib metadata |
| `POST` | `/platform/shared-libs` | Upload a jar bundle as a new generation (multipart) |
| `DELETE` | `/platform/shared-libs/{name}` | Remove from store (future generations only) |

### MCP adapter (`protean.mcp.enabled`, default off)
Streamable HTTP (`POST /platform/mcp`) + stdio (`protean.mcp.stdio`). Zero-dep JSON-RPC
(no SDK). Consumers that register a custom `McpTool` bean have it exposed alongside
(open-core). The library does not implement auth — it delegates to the consumer's Spring
Security + the `ModuleActionAuthorizer` SPI. Built-in tools (all under `protean.*`):

| Group | Tool | Action |
|-------|------|------|
| Lifecycle | `deploy_module` | Deploy (ACTIVE on passing gates ①②③) |
| | `update_module` | Canary update (hot-swap, auto-rollback on failure) |
| | `patch_module` | Delta update (file overlay, changed files only) |
| | `rollback_module` | Roll back to a specific version |
| | `approve_module` / `reject_module` | Approval gate: approve→ACTIVE / reject |
| | `uninstall_module` | Unload |
| | `reload_module_resources` | Swap resources with no downtime (no recompile) |
| Query | `get_module` / `list_modules` | Single status / list |
| | `get_module_source` | Fetch module source |
| | `module_versions` | Version history |
| Observability | `query_traces` | Request traces (filters, paging) |
| | `module_metrics` | Per-module aggregate metrics |

### Level 3 debugging (`protean.mcp.debug.enabled`, default off)
JDI-based (`jdk.jdi`, zero-dep) interactive step debugging. `evaluate` supports the full
expression grammar (operators, casts, `new`, lambdas, method references). Tools (all under
`debug.*`):

| Group | Tool | Action |
|-------|------|------|
| Session | `launch` | Deploy to a JDWP-enabled worker + auto-attach (reverts to normal deploy on end) |
| | `attach` | Attach to an already-running JDWP JVM |
| | `list_sessions` | List open debug sessions (reattach) |
| | `terminate` | End the session (tears down a launch worker) |
| Execution | `set_breakpoint` | Breakpoint at `className:line` |
| | `continue` / `step` | Resume / over·into·out step |
| | `await_stop` | Wait for the next stop (breakpoint / step completion) |
| Inspection | `frames` | Stack frames of the suspended thread |
| | `get_variables` | Frame locals (needs `-g` compilation) |
| | `evaluate` | Evaluate an expression in the suspended frame |
| Hot-swap | `redefine` | Replace a method body in place (fix-and-continue) |

---

## Package layout

```
org.htcom.protean
├── ProteanApplication            app entry point (auto-config stands in when consumed as a library)
├── autoconfigure/                ProteanAutoConfiguration · ProteanProperties (protean.* config)
├── compiler/                     JSR-199 in-memory compile (RuntimeCompiler) · ModuleClassLoader · ModuleSharedLibs
├── dynamic/                      dynamic RequestMapping register/deregister (DynamicEndpointRegistrar)
├── proxy/                        ReverseProxy (worker routing)
├── module/                       ModulePlatform (facade) · ModuleDescriptor · ModuleResource · Store (FS/JDBC) · Reconciler
│                                  · ProteanTaskExecutor · ModuleUnloadCallback (unload SPI)
├── gate/                         promotion gates ①②③ · signature · rules/ (CodeRule SPI)
├── isolation/                    IsolationStrategy SPI + InProcess/Worker/Container + WorkerRuntimeProvider
├── worker/                       worker management (admin · port handshake)
├── bridge/                       worker→main RPC bridge (dynamic proxy over arbitrary interfaces)
├── db/                           DB-scope auto-provisioning · DbDialect SPI (built-in MySQL/Postgres)
├── runtime/                      execution-timeout watchdog · correlation id · request trace + per-module metrics (TraceStore · TraceMetrics)
├── error/                        structured errors (RFC 9457): ErrorCode · ProblemDetail · ProteanException
├── web/                          control-plane REST (ModuleAdminController · TraceAdminController)
├── mcp/                          MCP adapter (dispatcher · tools/ · transport/ · debug/)
└── boot/                         worker/stdio launchers (ProteanWorkerLauncher · ProteanMcpStdioLauncher)
```

The core facade is `module/ModulePlatform` —
install/update/rollback/approve/reject/uninstall/reconcile all delegate here, and
gate + verify + isolation routing happen inside it.

---

## Extension points (SPI)

A library consumer can't extend the code at their point of use, so Protean opens
the axes that need to vary via bean registration:

| SPI | How to register | Purpose |
|-----|-----------|------|
| `gate.rules.CodeRule` | register a `CodeRule` bean | Add a custom bytecode rule to promotion gate ② |
| `db.DbDialect` | register a `DbDialect` bean | Add/override a vendor for DB-scope provisioning (built-in mysql/postgresql fallback) |
| `mcp.ModuleActionAuthorizer` | register a `ModuleActionAuthorizer` bean | Authorize MCP module actions (default permissive) |
| `isolation.WorkerRuntimeProvider` | register a bean | Swap the worker deployment model (embed/sidecar) |
| `module.ModuleUnloadCallback` | register a module/consumer bean | Cleanup hook for out-of-context resources (ThreadLocal, MBean, …) on unload |
| `ModuleDescriptor.bridgedInterfaces` | descriptor declaration | Main shared interfaces a worker calls over RPC |

Injected into module code: `module.ProteanTaskExecutor` (per-module, lazy, bounded
managed executor; auto-shuts-down on unload).

Example — adding a custom DB dialect:

```java
@Bean
DbDialect oracleDialect() {
    return new DbDialect() {
        public String id() { return "oracle"; }
        // ... createScope / dropScope / scopedUrl / maxNameLength
    };
}
// config: protean.worker.db.dialect=oracle
```

---

## Key configuration (`protean.*`)

`ProteanProperties` is the typed config surface (with IDE-completion metadata).
Excerpt:

| Key | Default | Description |
|----|--------|------|
| `protean.isolation.mode` | `in-process` | Global isolation mode: in-process\|worker\|container |
| `protean.admin.enabled` | `true` | Expose the admin REST |
| `protean.mcp.enabled` | `false` | MCP server (a deployment entry, an RCE surface → fail-safe off) |
| `protean.mcp.stdio` / `.debug.enabled` | `false` | stdio transport / debug tools |
| `protean.gate.tests-enabled` / `.review-enabled` | `true` | Promotion gates ①/② |
| `protean.gate.signature.required` / `.approval.required` | `false` | Signature / approval gate |
| `protean.module.request-timeout-ms` | `0` | Module request timeout (0 = unlimited) |
| `protean.module.shared-lib-dir` | (empty) | Shared lib dir — its jars go on the module CL parent + compile classpath (drop-in) |
| `protean.module.executor.pool-size` | `2` | Managed executor (`ProteanTaskExecutor`) pool size |
| `protean.module-store.backend` / `.dir` | `filesystem` | Descriptor store: filesystem\|jdbc |
| `protean.trace.enabled` / `.capacity` | `true` / `200` | Request-trace ring buffer |
| `protean.trace.metrics.enabled` | `false` | Per-module aggregate metrics (count, error rate, latency percentiles; opt-in) |
| `protean.trace.metrics.latency-buckets` / `.max-modules` | `20` / `512` | Latency histogram buckets / tracked-module cap |
| `protean.worker.modules-per-worker` / `.min-warm` | `4` / `0` | Worker packing / warming |
| `protean.worker.auto-restart` / `.rpc-bridge` | `false` | Crash restart / RPC bridge |
| `protean.worker.runtime` | `embed` | Worker runtime: embed\|sidecar |
| `protean.worker.container.*` | — | image · memory · pids-limit · network · seccomp · db-host |
| `protean.worker.db.auto-provision` | `false` | Auto-create an isolated DB scope per module (needs dialect + admin credentials) |

See `autoconfigure/ProteanProperties.java` for the full set.

---

## Build & test

There is **no `gradlew` wrapper** — use a system `gradle` (Java 21 toolchain).

```bash
gradle build                              # compile + plain jar + bootJar ('-boot')
gradle test                               # full test suite (maxHeap 512m — for the leak canary)
gradle :test --tests 'org.htcom.protean.XxxTest'  # a single class (root project only)
gradle publishToMavenLocal                # publish to ~/.m2 (POM / consumability check)
```

Notes:
- **Run bootJar and test separately.** Combining them (`clean bootJar test`) can
  trigger a collateral OOM in `LeakDiagnosisTest`.
- Container / Testcontainers / OS-isolation / seccomp tests need **Docker**, and
  an exploded layout from `gradle bootJar` must exist first. Without Docker those
  tests skip.
- Artifacts: a plain jar (consumable, no classifier) + `-boot.jar` (the embed-worker
  runnable fat jar).

See [CONTRIBUTING.md](CONTRIBUTING.md) to contribute and [SECURITY.md](SECURITY.md)
to report a vulnerability.

---

## License

[Mozilla Public License 2.0](LICENSE).
