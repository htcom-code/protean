**English** | [한국어](11-operations.ko.md)

# 11. Operations

Covers the persistent store, restart recovery, monitoring, request timeouts, resource-leak avoidance, and the build/publish procedure you need to know when running Protean in production.

## Descriptor persistent store

Module descriptors (the declarative metadata) are stored durably in `ModuleStore`. The backend is chosen via `protean.module-store.backend`.

### filesystem backend (default)

`protean.module-store.backend=filesystem` (default when unset).

```yaml
protean:
  module-store:
    backend: filesystem
    dir: /var/lib/protean/modules   # default: ${java.io.tmpdir}/protean-modules
```

- Current state: `dir/<id>.json`. Writes go to a temp file → atomic move to prevent partial writes (crash) — write-ahead durability.
- Version history: `dir/<id>.history/<seq>.json` (append-only snapshots, for audit/rollback).
- **Note**: the default `dir` is under `java.io.tmpdir`. In production you must change it to a persistent path that is not wiped on reboot.

### jdbc backend

`protean.module-store.backend=jdbc`. Compared to the filesystem, it offers better durability and queryability, plus the multi-instance benefit of several main instances sharing the same store.

```yaml
protean:
  module-store:
    backend: jdbc
```

- At startup (`@PostConstruct`) it auto-creates the schema (`CREATE TABLE IF NOT EXISTS`): `module` (current state), `module_version` (append-only history). The column is `descriptor_json CLOB`.
- It uses the application's `JdbcTemplate` (i.e. the `DataSource` the consumer configured) as-is. It does not inject a separate DataSource.
- The upsert uses `UPDATE → (0 rows) → INSERT` instead of MERGE, for DB portability.

### Switching backends

Both backends store the same `ModuleDescriptor` JSON but in different locations. On switching, existing descriptors are not migrated automatically. If you need zero downtime, move the existing store's descriptors to the new backend, or redeploy the ACTIVE modules onto the new backend.

## reconcile on server restart

`ModuleReconciler` (`ApplicationRunner`, `@Profile("!worker")`) calls `ModulePlatform.reconcile()` at startup to redeploy the modules from the store.

- **Only ACTIVE is restored.** Via `store.listActive()`, only those with `desiredState=ACTIVE` are redeployed in each module's isolation mode.
- `PENDING_APPROVAL` modules are not restored — this blocks the bypass of serving an unapproved module via a restart.
- An individual module's restore failure is only logged and skipped (it does not block restoring other modules). Check the result via the `reconcile: {restored}/{total} modules restored` log.
- reconcile does not re-run the gates; it redeploys the stored descriptor (already passed at install time). If the store is empty, it is a no-op.

## Request traces / monitoring

An in-memory trace ring buffer (`TraceStore`) is provided for runtime observation.

```yaml
protean:
  trace:
    enabled: true      # default true
    capacity: 200      # default 200 — the most recent N requests (oldest discarded on overflow)
```

- `RequestTraceFilter` is the outermost (highest-priority) filter; it records every request's entry-to-response duration, status, and exceptions, and attributes it to the dynamic module by the matched handler pattern.
- Query via `GET /platform/traces?limit=&moduleId=` (→ [04. REST API Reference](04-rest-api.md)).
- The ring buffer is bounded in-memory — it is lost on restart, and persistence/external exposure is a separate task. If you need long-term retention or aggregation, collect it separately via logs/APM.
- capacity is a trade-off against memory use. Under heavy traffic the recent window gets shorter, so increase it as needed when diagnosis is the goal.

### Observability surface at a glance

The trace feature is split across config, REST, and MCP. Below is a reference hub gathering the entire surface; see the linked docs for each detail. Trace recording is on by default (lightweight), and **per-module aggregate metrics are opt-in** (when off, the recording hot path is just a single boolean check).

**Config (`protean.trace.*`)** — detail [03. Configuration Reference](03-configuration.md#trace--runtime-trace-recording)

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Request trace recording on/off |
| `capacity` | `200` | Ring-buffer capacity (most recent N; oldest discarded on overflow) |
| `metrics.enabled` | `false` | Per-module aggregate metrics (opt-in) |
| `metrics.latency-buckets` | `20` | Number of latency histogram buckets (precision ↔ memory) |
| `metrics.max-modules` | `512` | Max modules tracked (LRU eviction on overflow) |

**Query surface** — REST detail [04. REST API Reference](04-rest-api.md#runtime-traces--base-path-platformtraces), MCP detail [08. MCP Integration](08-mcp-integration.md)

| Kind | Endpoint / tool | Returns | Filters |
|---|---|---|---|
| REST | `GET /platform/traces` | `RequestTrace[]` | `limit` (default 50, min 1) · `moduleId` · `errorsOnly` · `status` · `minLatencyMs` · `since` · `beforeSeq` (AND, newest-first) |
| REST | `GET /platform/traces/metrics` | `ModuleMetricsSnapshot[]` | `moduleId` (all modules if omitted); empty list if metrics off |
| MCP tool | `protean.query_traces` | same as traces above | same filters as above |
| MCP tool | `protean.module_metrics` | `{enabled, metrics[]}` | `moduleId` |
| MCP resource | `protean://traces` | recent traces (fixed 50) | — |

**Data model**

- `RequestTrace` (one request): `seq` · `epochMillis` · `method` · `uri` · `pattern` (`null` if unmatched) · `moduleId` (`null` for platform/static) · `status` · `latencyMs` · `error` (exception FQCN, `null` if none) · `traceId` (the correlation id shared with logs and RFC 9457 errors).
- `ModuleMetricsSnapshot` (module aggregate): `moduleId` (`"(platform)"` for platform) · `count` · `errorCount` (exception or status ≥ 500) · `errorRate` · `p50/p95/p99LatencyMs` · `maxLatencyMs` · `lastSeenEpochMillis`.

**Recording pipeline (internal)**

| Component | Role |
|---|---|
| `RequestTraceFilter` | Highest-priority filter; records entry-to-response duration, status, exceptions; attributes to a module by handler pattern |
| `CorrelationIdFilter` | Assigns the `X-Request-Id`/MDC `traceId` correlation id → links trace, logs, and error responses |
| `TraceStore` | Bounded ring buffer; its own query path (`/platform/traces`) is not recorded |
| `TraceMetrics` / `LatencyHistogram` | When metrics are opt-in, aggregates per-module counters and a log-linear latency histogram |

**Attribution scope per isolation mode (worth knowing)**

**The meaning of the latency** recorded by the main platform's traces/metrics **differs by isolation mode.**

- **in-process** — the handler runs directly in the main JVM, so the trace's `latencyMs` = the module's execution time as-is.
- **worker / container** — the request is forwarded to the worker/container by the reverse proxy. The main's trace/metrics attribute it to that module accurately (`moduleId` is filled in), but the recorded latency is the **proxy-hop (= client-perceived) latency** (including the network round-trip). The module's **internal execution time** and the worker's own detailed trace live in the `TraceStore` inside that worker/container JVM, and because the worker does not expose `/platform/traces` (due to `@Profile("!worker")`), it is **not queried directly from the main**. Collect it via the worker's logs/APM if needed.

> Whether to expose the internal-execution observability of worker/container modules through a single window (aggregate pull-back / console multi-source) is subject to a support-feasibility determination — see [ROADMAP](../../ROADMAP.md#observability).

## Module request timeout

`ModuleExecutionWatchdog` puts a deadline on module request execution.

```yaml
protean:
  module:
    request-timeout-ms: 0   # default 0 = unlimited
```

- **Cooperative limit**: on deadline exceed, it `interrupt`s the execution thread. It breaks blocking such as `Thread.sleep` and interruptible I/O, but a CPU spin (`while(true){}`) ignores the interrupt and cannot be stopped.
- A hard cap (runaway CPU/memory) is the job of OS/process isolation (worker/container mode). In in-process mode the timeout is only a line of defense, not hard isolation.

## ClassLoader leak-avoidance principles

A dynamic module is loaded under its own `ModuleClassLoader`. If that ClassLoader is not GC'd after unload, Metaspace is not reclaimed and you eventually hit a Metaspace OOM. The platform and the module must both uphold this.

- **What the platform does**: on `unregister`/`swap`, `DynamicEndpointRegistrar` not only releases the mapping but also evicts, keyed by the controller Class, the per-Class caches of the MVC infrastructure that were populated by calls (`RequestMappingHandlerAdapter`'s `sessionAttributesHandlerCache` · `initBinderCache` · `modelAttributeCache`, the argument-resolver caches, `ExceptionHandlerExceptionResolver` caches, and the registered `@ControllerAdvice` caches). Releasing only the mapping would let a cache pin the ClassLoader and cause a hard leak.
- **What the module must do**:
  - Inject the managed executor `ProteanTaskExecutor` (per-module · daemon · bounded) to run async/scheduled work. Using this instead of a raw `new Thread` means that on unload, closing the child context triggers `close()` (→ `shutdownNow`) so threads and jobs are cleaned up. It prevents leaks of shared threads that pin a dead ClassLoader.
  - Clean up out-of-context resources that the child context cannot reach (ThreadLocals on shared threads, static cache registrations, JMX MBeans, custom clients) yourself via a `ModuleUnloadCallback` bean. The platform invokes it just before closing the child context.

## Build / publish operations

This project is built with Gradle. It calls gradle tasks directly rather than running via IDE integration.

### Artifacts

Three jars come out in `build/libs`, plus a worker container image built by Jib.

- **plain jar** (no classifier, `protean-<ver>.jar`): the ordinary library layout that other projects depend on (no `BOOT-INF`). The artifact consumers depend on.
- **bootJar** (`-boot` classifier, `protean-<ver>-boot.jar`): the runnable fat jar (`BOOT-INF/classes,lib`) that the embed worker explodes and uses. Because there are two `main()`s, `springBoot.mainClass` is fixed to `ProteanApplication`.
- **worker jar** (`-worker` classifier, `protean-<ver>-worker.jar`): a flat shaded uber-jar (Shadow) for the **sidecar** worker runtime's process track. That track launches with a bare `java -cp`, which a `-boot.jar`'s nested `BOOT-INF` layout cannot satisfy — hence a flat jar. Spring auto-configuration imports and JDBC driver service files are concatenated during shading so the worker context still comes up.
- **worker image** (`ghcr.io/<owner>/protean-worker:<ver>`, built by Jib — no `build/libs` file): the sidecar worker runtime's container track. Bundles the worker jar at `/app/` on a JDK base image (the worker recompiles module sources at runtime, so it needs `javac`). See [05. Isolation Modes](05-isolation-modes.md).

### bootJar and test must run separately (required)

The `test` task limits the heap to `maxHeapSize=512m` to forcibly clear soft references in the leak canary. Bundling `bootJar` (fat-jar assembly, memory pressure) and `test` in one gradle invocation can make `LeakDiagnosisTest` and others throw a collateral OOM. **Always invoke them separately.**

```bash
# Bad — combining risks OOM
./gradlew clean bootJar test

# Good — separated
./gradlew clean test
./gradlew bootJar
```

### Publish

```bash
./gradlew publishToMavenLocal   # publish to ~/.m2 (for POM / consumability verification)
```

- The publication comprises `components.java` (plain jar + the **worker** shaded jar the Shadow plugin contributes, distinguished by the `shadowed` bundling attribute so default consumers still resolve the plain jar) + sources jar + javadoc jar + the generated POM.
- The **worker container image** publishes separately via Jib: `./gradlew jib` pushes `ghcr.io/<owner>/protean-worker:<ver>` when `GITHUB_OWNER`/`GITHUB_ACTOR`/`GITHUB_TOKEN` are set (otherwise `jibDockerBuild`/`jibBuildTar` build it locally). CI runs this on pushes to `main` only, gated exactly like the library publish.
- The JDBC drivers (`mysql-connector-j`, `postgresql`) are needed for protean's own bootJar/tests but are marked `optional=true` in the published POM so as not to force them transitively on consumers. A consumer using worker DB provisioning must add its own driver explicitly.
- The remote registry is **GitHub Packages** (migration target); the URL/credentials are not hardcoded in `build.gradle` but externalized via `gradle.properties`/environment variables. The remote repository is registered **only when all of** `githubOwner`/`githubRepo`/`githubActor`/`githubToken` (or the `GITHUB_OWNER`/`GITHUB_REPO`/`GITHUB_ACTOR`/`GITHUB_TOKEN` env vars) are provided (otherwise mavenLocal only), and it is then published via the `publishLibraryPublicationToGitHubPackagesRepository` task. For the credential template see `gradle.properties.example`.

## Related documents

- [03. Configuration Reference](03-configuration.md)
- [04. REST API Reference](04-rest-api.md)
- [05. Isolation Modes](05-isolation-modes.md)
- [07. Data Access](07-data-access.md)
- [13. Troubleshooting](13-troubleshooting.md)
- [README](../../README.md)
