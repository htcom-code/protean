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

- Maven Central (Sonatype Central Portal) publishing is now wired via the
  `com.vanniktech.maven.publish.base` plugin: the published POM carries the
  Central-required metadata (name, description, url, MPL-2.0 license, developer,
  scm), and artifact signing is property-gated — with no in-memory GPG key,
  signing is skipped so `publishToMavenLocal` / GitHub Packages still publish
  unsigned, while the release pipeline supplies the key and Central Portal token.
  The published set stays plain + sources + javadoc + worker (no boot jar).
  Namespace verification, GPG/token setup, and the release cut are external steps,
  not yet performed.
- Worker DB admin credentials (`protean.worker.db.admin-url` / `username` /
  `password`) are now runtime-rotatable without a restart: `DbScopeProvisioner`
  reads an `AdminCreds` snapshot per provision/detach/destroy and rebuilds the admin
  `JdbcTemplate` only when the creds change (`REQUIRES_RESTART` → `APPLIED_FUTURE`).
  A rotation is validated first — one connection with the candidate creds must
  pass `Connection.isValid` before the swap; a bad rotation fails clearly and
  retains the previous connection. (Dialect stays restart-only.)
- The trace SSE stream (`GET /platform/traces/stream`) now pushes a fourth
  `summary` event for the observability console header: a windowed `TraceSummary`
  aggregate (`protean.trace.summary-window-ms`, default 60s) with the current
  window's request count / error rate / p50–p99 latency, a trend versus the
  previous equal window (null when there is no baseline — no fabricated delta),
  and a point-in-time count of active modules by isolation mode. Computed
  out-of-band from the trace ring buffer (recording hot path untouched) and
  independent of `protean.trace.metrics.enabled`.

- **DB scope model.** `worker.db.auto-provision` is reframed from "isolate every
  module" to "**select a scope**". A scope (tenant / business-domain grouping) is
  the unit of both DB provisioning and worker/container packing: same-scope modules
  share one provisioned database and pack into that scope's worker/container(s) up
  to `worker.modules-per-worker`, and different scopes are isolated. A deploy must
  name a known, ACTIVE `scope` (module.yaml / deploy API / `ModuleDescriptor.scope`);
  a startup seed allowlist `worker.db.scopes` (empty → a single `default`) plus the
  new `ScopeStore`/`ScopeManager` registry track known scopes and survive restart.
- **Scope admin surface.** REST `/platform/scopes` (list · get · create · close ·
  open · detach · destroy — explicit action sub-resources, no `DELETE` verb; active
  under `admin.enabled` + `auto-provision`) and MCP `protean.scope_*` tools (always
  listed like `debug.*`, gated at call time — an `isError` when `auto-provision` is
  off). Lifecycle:
  create/open → ACTIVE, close → CLOSED, detach (drop login, keep data — reversible),
  destroy (`DROP DATABASE/SCHEMA` — irreversible). `destroy` is guarded by the new
  `worker.db.allow-destroy` (default `false`) + a name-confirmation, and audit-logged.
- `DbDialect` gains `detachScope` (login-only, reversible) and `destroyScope`
  (CASCADE, irreversible) as backward-compatible default methods; built-in MySQL /
  PostgreSQL override both.

### Changed

- Worker packing defaults raised for production density. `worker.modules-per-worker`
  `4` → `128` (a worker JVM's ~200–300 MB base overhead dominates cost at small
  values; verified code has low crash risk in production). Container-track companions
  scaled to hold them: `worker.container.memory` `256m` → `512m`, `worker.container.pids-limit`
  `512` → `1024`, and container workers now launch with `-XX:MaxRAMPercentage=75.0`.
  New `worker.jvm-args` sizes heap for the process/embed/sidecar tracks (no cgroup
  bound there, so a percentage is unsafe). Raise these together when overriding
  `modules-per-worker`.
- Under `worker.db.auto-provision`, **both worker and container modes now pack
  same-scope modules** into a shared worker/container up to `worker.modules-per-worker`
  (the isolation boundary is the scope, not the module) — container mode is no longer
  one-container-per-module. Set `worker.modules-per-worker=1` for the strict
  one-worker/container-per-module boundary. A scoped module routed to in-process is
  rejected (in-process cannot bind a per-scope datasource); a scope declared with
  auto-provision off is ignored with a warning.

### Removed

- `worker.db.deprovision-on-undeploy` — removed (it never had an effect under the scope
  model: undeploy does not tear down a scope). Scope teardown is operator-driven via the
  scope admin API (detach / destroy). Setting the key in config is now simply ignored.

### Fixed

- Hot-swap drain race in the worker and container pools: after a swap the emptied old
  worker/container was left in the pool for the grace window and could be reused by a
  concurrent deploy, then killed by the deferred cleanup (surfacing as a worker
  `/__admin/deploy` 500). It is now marked retiring and removed from the pool
  immediately; only the process kill / `docker rm` is deferred.

- The library no longer registers its internal RPC-bridge demo beans
  (`Echo`/`Greeting`/`Math`/`Ledger`/`Stream` `*Port`) in consumer apps. They
  were `@Component`s under `src/main`, swept up by the auto-configuration
  component scan, so every consumer got them — and `LedgerPortImpl` created a
  `ledger` table in the consumer's database at startup. They are now test-only
  scaffolding, removed from the published jar.
- Worker JVMs no longer instantiate the module-store beans. `JdbcModuleStore` /
  `FileSystemModuleStore` had no profile gate, so a worker (process or
  container) that inherited `module-store.backend=jdbc` created the platform's
  `module` / `module_version` tables — and ran the startup self-check — inside
  each module's auto-provisioned scope database, dead artifacts the worker never
  uses. Both are now `@Profile("!worker")`, matching their host-only consumers.
- The JDBC module-store backend now works on MySQL and PostgreSQL, not only
  H2. Its schema was hardcoded to H2-only types (`descriptor_json CLOB`,
  `seq BIGINT AUTO_INCREMENT`), so `module-store.backend=jdbc` failed at
  startup on other engines (CLOB exists on neither; Postgres has no
  AUTO_INCREMENT). DDL is now vendor-adaptive via a `ModuleStoreDialect` SPI —
  H2/MySQL/PostgreSQL built in, other vendors pluggable via a bean — selected
  by auto-detection or `protean.module-store.dialect`, and an unknown vendor
  fails fast instead of silently using H2 DDL. A startup self-check verifies
  the descriptor column holds large text without truncation and that `seq`
  auto-increments. `protean.module-store.dialect` is exposed read-only on the
  config surface.
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
  Unclean exits (`kill -9` / crash), where `@PreDestroy` never runs, are now
  reaped on the next startup: each worker carries a per-spawn uuid on its command
  line (`-Dprotean.worker.id`) plus a marker file under `<module-store>/workers`,
  and startup force-kills any leftover-marked JVM found in the process table.
  Matching by uuid (not PID) avoids killing an unrelated or another instance's
  process.
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
