**English** | [한국어](CHANGELOG.ko.md)

# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

While the version is `0.x`, the public API may change between minor releases.

## [Unreleased]

No public API was added, removed, or changed in this release: a consumer compiled
against `0.0.1` links unchanged. What changed is what the built-in MCP tools
*answer*. If you wrap, subclass, or delegate to them, read the migration note at
the end of this section.

### Fixed

- `protean.list_modules`'s `trustTier` filter compared the argument (a `String`)
  against `ModuleStatus.trustTier()` (a `TrustTier` enum), so `String.equals(Object)`
  was false for every module and **the filter matched nothing for any value** — with
  no error, and a well-formed empty list as the answer. An agent auditing for
  untrusted modules was told there were none. The argument is now resolved to the
  enum (case-insensitive, so `trusted` == `TRUSTED`), and an unrecognized value is
  rejected as `INVALID_ARGUMENT` instead of being answered with an empty result.
  This shipped in `0.0.1` and has **no workaround there** — list unfiltered and
  filter client-side.
- The module and shared-lib tools checked only `hasNonNull` for their required
  arguments, so `""` passed and the empty id reached the store lookup: the caller
  got `MODULE_NOT_FOUND` ("module not found: ") and went hunting for a module it
  had never named. Absent, `null` and blank are now one case across those twelve
  tools — `get_module`, `get_module_source`, `module_versions`, `uninstall_module`,
  `rollback_module`, `approve_module`, `reject_module`, `patch_module`,
  `reload_module_resources`, `get_shared_lib`, `remove_shared_lib`,
  `deploy_shared_lib` — failing as `INVALID_ARGUMENT` that names the argument.
  Their required string arguments also declare `minLength: 1`, so
  `protean.mcp.strict-schema=true` catches the same case at the schema layer.
- `protean.query_traces` returned a bare `traces[]`, leaving "capture is off" and
  "nothing matched" indistinguishable — an agent read an empty list as "no such
  requests" and stopped looking. The result now always carries `enabled`
  (`protean.trace.enabled`), matching what `protean.module_metrics` already
  reported, and its `outputSchema` requires both keys.
- `protean.reload_module_resources` declared no `required` array and no `items`
  type for `removeFiles`, alone among its family. The tool body already rejected a
  missing `id`, so this was a contract-declaration gap rather than a runtime hole.

### Changed

- `debug.evaluate` and `debug.redefine` now advertise **`destructiveHint: true`**
  (was `false`). The spec's default for that hint is `true`, so `false` was not
  silence — it claimed these were safer than a tool that says nothing. They are
  not: `evaluate` resolves arbitrary method and constructor calls and assigns to
  local/field/array/static lvalues, and a redefined method body runs on the next
  call. `debug.evaluate`'s description was rewritten to say the same thing.
  Nothing about execution changes — hints are not an authorization boundary
  (`ModuleActionAuthorizer` and `protean.mcp.debug.enabled` are, and both are
  untouched) — but **a client that auto-approved on `destructiveHint: false` will
  now prompt for these two**, which is the point.
- Required-argument error messages now name the tool.
  `patch_module` and `reload_module_resources` said `missing required field: id`
  and now say `patch_module: id is required`; `approve_module`, `reject_module`,
  `rollback_module` and `deploy_shared_lib` reported their required arguments in
  one combined message (`approve_module: id and approver required`) and now report
  the first missing one individually. `missing required field` was doing double
  duty — the dispatcher emits the same phrase when a tool's own result violates its
  `outputSchema`, which is a server bug rather than a caller mistake.
  `ModuleInputNormalizer` still uses the old phrasing for `deploy_module`,
  `update_module` and `debug.launch`.
- The `debug.*` tools still accept a blank string where the tools above no longer
  do (`debug.frames` with `sessionId: ""` answers `no debug session: `). This is
  documented rather than changed, so the boundary is explicit; `debug.launch` is
  the exception, since it shares the module tools' argument handling.

### Migration — if you wrap or subclass the built-in MCP tools

The tool classes are public and non-final and `McpDispatcher.registerTool` replaces
by name, so consumers can subclass them, delegate to them, or register a same-named
replacement. Three of the changes above are visible through that seam:

| If your wrapper… | On `0.0.1` | Now |
|---|---|---|
| delegates `outputSchema()` to `QueryTracesTool` but builds its own `structuredContent` with `traces` only | returned your result | the dispatcher rejects it with `OUTPUT_SCHEMA_VIOLATION` (`missing required field: enabled`) |
| catches `RuntimeException` around the inner tool and is called with a blank required argument | the inner tool *returned* `isError MODULE_NOT_FOUND`, so your catch never ran | the inner tool *throws* `McpException` (a `RuntimeException`), so your catch runs and can turn an error into a success |
| gives `""` its own meaning and inherits `inputSchema()`, with `protean.mcp.strict-schema=true` | your `call()` ran | `minLength: 1` rejects the argument before `call()` is entered |

Fixes: emit `enabled` alongside `traces` (or declare your own `outputSchema`);
let `McpException` propagate, or re-throw it from your catch; and override
`inputSchema()` if a blank argument is meaningful to you.

None of the three rows applies to wrappers of `list_modules`, `module_metrics` or
`list_runtimes`: their optional-argument handling is unchanged, and their
`outputSchema` did not grow a required key. A `list_modules` wrapper does still
see the `trustTier` change above — a value that is not `TRUSTED` or `UNTRUSTED`
now comes back as an error rather than an empty list. A tool that fully replaces
a built-in by name is unaffected throughout, since it supplies its own schema and
body.

## [0.0.1] - 2026-08-09

First public release of **Protean** — a library that turns Spring Boot into a
runtime platform: it compiles Java source at runtime, loads it under a dedicated
ClassLoader, registers REST endpoints, and hot-swaps / rolls back / unloads them
with no restart. Coordinate `org.htcom:protean:0.0.1`; Spring Boot 3.5.x / Java 21.
Published to Maven Central; the sidecar worker image to `ghcr.io/htcom-code/protean-worker:0.0.1`.

### Added

- Maven Central (Sonatype Central Portal) publishing is now wired via the
  `com.vanniktech.maven.publish.base` plugin: the published POM carries the
  Central-required metadata (name, description, url, MPL-2.0 license, developer,
  scm), and artifact signing is property-gated — with no in-memory GPG key,
  signing is skipped so `publishToMavenLocal` / GitHub Packages still publish
  unsigned, while the release pipeline supplies the key and Central Portal token.
  The published set stays plain + sources + javadoc + worker (no boot jar).
  The release is cut by the manual `Release` workflow, which uploads and stops at
  VALIDATED — going live stays a deliberate Publish on the Portal.
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
- The published jars declare `Automatic-Module-Name: org.htcom.protean` (pinned
  before the first release, since a name derived from the file name would become
  breaking to change once consumers `requires` it) and carry the MPL-2.0 text at
  `META-INF/LICENSE`, so a recipient holding only the jar has the license.
- The pre-Central gate checks the full published set (including the `worker`
  classifier and its signature) and refuses to upload a jar whose shipped
  configuration metadata lost its descriptions.
- README (en/ko) and user guides under `docs/guide/`.
