**English** | [한국어](CHANGELOG.ko.md)

# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

While the version is `0.x`, the public API may change between minor releases.

## [Unreleased]

Pre-release baseline of **Protean** — a library that turns Spring Boot into a
runtime platform: it compiles Java source at runtime, loads it under a dedicated
ClassLoader, registers REST endpoints, and hot-swaps / rolls back / unloads them
with no restart. Coordinate `org.htcom:protean`; Spring Boot 3.5.x / Java 21.
Currently published to `mavenLocal` only; remote publishing (GitHub Packages)
follows the migration.

### Added

- Worker DB admin credentials (`protean.worker.db.admin-url` / `username` /
  `password`) are now runtime-rotatable without a restart: `DbScopeProvisioner`
  reads an `AdminCreds` snapshot per provision/deprovision and rebuilds the admin
  `JdbcTemplate` only when the creds change (`REQUIRES_RESTART` → `APPLIED_FUTURE`).
  A rotation is validated first — one connection with the candidate creds must
  pass `Connection.isValid` before the swap; a bad rotation fails clearly and
  retains the previous connection. (Dialect stays restart-only.)

### Fixed

- Worker/container-isolated modules now forward all HTTP methods and request
  bodies. The reverse proxy previously hardcoded bodyless GET, so a
  `@PostMapping` that worked in-process returned 405 once isolated; route
  listings also reported empty methods for proxied routes. The proxy now forwards
  the request verbatim and records per-path methods, so REST and MCP route
  listings report the real methods across isolation modes.
- Container reconcile no longer fails on a restart name collision. A detached
  container outliving the JVM plus a per-run seq counter that resets on restart
  made reconcile re-derive an existing container name, so `docker run --name` hit
  a 125 conflict and the module's route 404'd. Stale same-name containers are
  removed before respawn, and a `@PreDestroy` retires this instance's containers
  on graceful shutdown.
- Process-track worker JVMs are now terminated on graceful shutdown.
  `WorkerProcessIsolation` had no `@PreDestroy`, so `ProcessBuilder`-spawned
  worker JVMs (which the OS does not kill when the parent JVM exits) survived as
  orphans holding their random ports and heap. A `@PreDestroy` now tears them
  down in parallel — SIGTERM, then force-kill — the process-track counterpart to
  the container fix above. The grace period is configurable via
  `protean.worker.shutdown-grace-ms` (default `5000`; `0` = force immediately).
  Unclean exits (`kill -9` / crash) are not yet reaped — a follow-up.
- MCP resource surface restored to REST parity. `protean://modules/{id}/routes`
  returned an empty list for worker/container modules (misreading a healthy
  module as route-less) and `protean://modules` left the shared-lib generation
  fields null. Both now mirror the REST admin surface.

### Dynamic loading engine

- Runtime JSR-199 in-memory compile (`RuntimeCompiler`), per-module
  `ModuleClassLoader`, dynamic RequestMapping register/deregister
  (`DynamicEndpointRegistrar`).
- Hot-swap update, explicit rollback, version history, and clean unload — the
  unload path purges the `RequestMappingHandlerAdapter` per-Class cache so a
  module ClassLoader is fully collectible (no Metaspace leak).
- `update` diff: a resource-only change skips recompilation (javac skipped when
  source is unchanged) while keeping the zero-downtime swap.

### Trust model & promotion gates

- `install` routes every module through a promotion pipeline before it serves:
  ①tests (compile + run the module's JUnit tests; no tests = rejected) →
  ②review (ASM bytecode static scan, `ForbiddenApiRule`; `CodeRule` SPI) →
  ③verify (live HTTP probe / concurrency / timeout / memory, auto-rollback on
  failure).
- Opt-in **signature** gate (Ed25519 trust store) and **approval** gate
  (`PENDING_APPROVAL` until a human approves; no bypass across restart).
- Trust model: all source is trusted-developer by design; a sandbox for
  untrusted source is an explicit non-goal (`SandboxAbsenceTest` proves absence).

### Isolation modes

- `in-process` (dedicated ClassLoader + child ApplicationContext), `worker`
  (separate JVM + reverse-proxy forwarding), `container` (Docker with cgroup
  memory/PID, read-only FS, cap-drop, seccomp).
- All modes support zero-downtime hot-swap, pooling, supervision (crash
  restart), and a dedicated DB. Workers call shared host beans over an **RPC
  bridge** (`bridgedInterfaces`). `WorkerRuntimeProvider` SPI swaps the
  embed/sidecar deployment model.

### Data access

- Mechanism, not policy: a module configures its own persistence layer
  (JdbcTemplate / MyBatis / JPA / multi-DataSource) inside its child context.
  Drivers/ORM are host-bundled; Protean's bundled MySQL/Postgres drivers are
  `optional` in the published POM (not forced transitively).
- Per-module DB-scope auto-provisioning with GRANT isolation
  (`protean.worker.db.auto-provision`); `DbDialect` SPI (built-in MySQL/Postgres).
- Resource channel (`ModuleDescriptor.resources`) ships non-Java files (mapper
  XML, migration SQL) served by the module ClassLoader. Managed
  `ProteanTaskExecutor` (per-module, lazy, bounded) auto-shuts-down on unload;
  `ModuleUnloadCallback` SPI for out-of-context cleanup.

### MCP adapter & Level 3 debugging

- Zero-dependency MCP (Model Context Protocol) adapter over Streamable HTTP
  (`POST /platform/mcp`) + stdio; module deploy / update / rollback / approve /
  reject / uninstall / get / list / versions tools. Fail-safe off
  (`protean.mcp.enabled=false`); auth delegated to consumer Spring Security +
  `ModuleActionAuthorizer` SPI. MCP `2025-11-25` spec completeness (sessions,
  standing stream + resumption, `listChanged`, cancellation, `_meta`
  passthrough, opt-in OAuth protected-resource metadata).
- JDI-based (`jdk.jdi`, zero-dep) Level 3 debugging: `launch` / `attach` /
  `frames` / `step` / `continue` / `evaluate` / `redefine` (fix-and-continue) /
  `terminate`. `evaluate` supports the full expression grammar (operators,
  casts, `new`, lambdas, method references).

### Control surface & config

- Admin REST (`/platform/modules`, `protean.admin.enabled`) and trace REST.
- Typed config surface `ProteanProperties` (`protean.*`) with
  configuration-processor metadata for consumer IDE completion.

### Build & docs

- Plain jar (consumable, no classifier) + fat `-boot.jar` (embed worker
  runtime) + flat shaded `-worker.jar` (Shadow; the sidecar worker's process
  track, published under the `worker` classifier) + a sidecar worker container
  image (Jib) at `ghcr.io/<owner>/protean-worker`. `publishToMavenLocal` for
  POM/consumability checks. **`test` and `bootJar` run separately** (combined
  runs can OOM `LeakDiagnosisTest`).
- README (en/ko) and user guides under `docs/guide/`.
