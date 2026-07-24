**English** | [한국어](05-isolation-modes.ko.md)

# 05. Isolation Modes

The axis that chooses where and how a module runs. Behind the `IsolationStrategy` SPI, Protean offers three modes — same JVM (`in-process`), a separate JVM worker (`worker`), and a Docker container worker (`container`). The stronger the isolation, the smaller the blast-radius but the higher the startup cost and operational requirements.

## Mode comparison and selection criteria

| Axis | `in-process` | `worker` | `container` |
| --- | --- | --- | --- |
| Execution location | Main JVM (dedicated ClassLoader + child context) | Separate JVM process | Docker container |
| Isolation strength | Weak (ClassLoader) | Strong (process) | Strongest (OS/cgroup) |
| Direct shared-bean access | Yes | No (only via RPC bridge) | No |
| Crash isolation | No (shares the main's fate) | Yes | Yes |
| Startup cost | Lowest | JVM startup | Image + container |
| External requirement | None | None | Docker daemon |
| Strategy class | `InProcessIsolation` | `WorkerProcessIsolation` | `ContainerWorkerIsolation` |

Selection criteria:
- A trusted in-house module that must inject shared service beans directly via DI → `in-process`.
- An unvetted module whose crash or memory blow-up must not kill the main → `worker`.
- Untrusted (multi-tenant / externally submitted) code that must confine even host files, network, and syscalls → `container`.

Each strategy pre-decides module-capability compatibility with `supports(ModuleDescriptor)`, so an incompatible combination (e.g. a worker that needs shared beans but has the bridge off) fails fast at deploy time.

## Specifying the mode: global vs per-module

The global default is set with `protean.isolation.mode` (`in-process` when unset).

```yaml
protean:
  isolation:
    mode: worker   # in-process | worker | container
```

An individual module can override the global default with `ModuleDescriptor.isolationMode` (`"in-process"` | `"worker"` | `"container"`; `null` follows the global default).

```java
new ModuleDescriptor(
    "orders", "1.0.0",
    ModuleDescriptor.TrustTier.TRUSTED,
    ModuleDescriptor.DesiredState.ACTIVE,
    "com.acme.OrdersController",
    List.of("com.acme.OrdersController"),
    sources, tests,
    /* needsSharedBeans */ false,
    /* verification    */ null,
    /* isolationMode   */ "worker");   // this module only, as a worker
```

## in-process (default)

Runs the module inside the same JVM with a dedicated `ModuleClassLoader` + a child `ApplicationContext` (parent = root). Because the child context takes the root as its parent, it injects shared in-process beans as-is — `supports()` is always `true`, so it accepts every module.

- Supports resource live-reload: `reloadResources()` swaps only the resource bytes in place with no compile or context rebuild (other modes don't support it → they fall back to a full update).
- No setup needed (the default). There is no separate process/image.

## worker (separate JVM)

`WorkerProcessIsolation` launches a separate JVM, runs the module `in-process` inside it, and the main forwards to that worker port via `ReverseProxy`. Even if the worker dies, the main stays alive and merely returns 502.

### Worker launch and handshake

1. The main builds the JVM launch command with `WorkerRuntimeProvider.processLaunchPrefix()`, appends `--spring.profiles.active=worker --protean.isolation.mode=in-process --server.port=0`, and launches the process.
2. After startup, the worker prints its actual bound port to stdout as a single line `WORKER_PORT=<port>` (`WorkerPortAnnouncer`). The main parses this marker to obtain the port (handshake).
3. The main waits for readiness with `GET /__admin/health`, then sends the descriptor with `POST /__admin/deploy`. The worker (`WorkerAdminController`) compiles and serves the sources inside itself and returns the list of registered paths, which the main registers with the proxy.

### Worker pool

One worker hosts multiple modules to cut JVM count and startup cost.

```yaml
protean:
  worker:
    modules-per-worker: 128 # max modules packed per worker JVM (1 = a dedicated JVM per module = full isolation). Default 128
    jvm-args: []            # extra JVM args per worker, e.g. ["-Xmx512m"] — size heap for the process/embed/sidecar tracks
    min-warm: 0             # number of empty workers to keep warm (reuse). Default 0
```

With `modules-per-worker=1` each module gets a dedicated JVM and is fully isolated, but the JVM count grows; with `>1`, modules within the same worker share their fate on a crash. The default `128` favors production density (verified code, low crash risk, and a worker JVM's ~200–300 MB base overhead dominates at small values); lower it in development for tighter crash isolation. Empty workers are kept up to `min-warm` for reuse and the excess is cleaned up.

**Sizing note (scale together).** `modules-per-worker`, memory, and heap move as a set. For the **container** track, a worker gets `-XX:MaxRAMPercentage=75.0` inside a `worker.container.memory` cgroup cap (default `512m` for 128 modules) — raise `container.memory` proportionally if you raise `modules-per-worker`. For the **process/embed/sidecar** tracks there is no memory bound (a percentage would size against the whole host), so size heap explicitly with `worker.jvm-args` (e.g. `["-Xmx512m"]`) — this is the operator's responsibility.

### Supervision (auto-restart)

```yaml
protean:
  worker:
    auto-restart: true   # default false
```

When on, it detects a worker's unexpected exit (crash) via `Process.onExit()`, redeploys the modules that worker hosted to a new worker, and `repoint`s the proxy to the new port. Intentional exits (retire / hot-swap draining) are not mistaken for crashes.

### RPC bridge (calling shared beans)

A worker cannot directly access the main's shared beans. If needed, turn on the bridge.

```yaml
protean:
  worker:
    rpc-bridge: true   # default false
```

When on, `supports()` also admits `needsSharedBeans` modules, and injects `--protean.worker.rpc-bridge=true` and `--protean.bridge.url=http://localhost:<main port>` into the worker. On the worker side, `WorkerBridgeRegistrar` registers a dynamic-proxy bean in the worker root context for each interface declared in `ModuleDescriptor.bridgedInterfaces`; when the module injects that type and calls it, the main's `BridgeController` (`/__bridge/invoke`) runs the real bean via reflection. It supports composite DTOs, generic-collection arguments/returns, and business-exception propagation (reconstructing the same type), and if the main bean is a `@Transactional` proxy, the call runs within the main transaction boundary. If the return type is `InputStream`, instead of packing it whole into a JSON envelope it chunk-streams as `application/octet-stream` (the main can produce it lazily), and the worker receives it as a lazily-consumed stream — avoiding memory buffering for large returns. For detailed interface declaration/injection, see [10. SPI Extension](10-spi-extension.md).

### Worker DB

By default each worker has its own H2 (separate JVM = DB isolation). To give a manual global scope:

```yaml
protean:
  worker:
    datasource:
      url: jdbc:mysql://db:3306/app
```

To auto-provision an isolated DB per **scope** (a tenant / business-domain grouping), use `protean.worker.db.auto-provision=true`. A deploy then selects a scope, and same-scope modules pack into that scope's worker up to `modules-per-worker` while different scopes get separate workers — the isolation boundary is the scope, not the module. Set `modules-per-worker=1` for a dedicated worker per module. For vendor-specific provisioning and the scope model see [07. Data Access](07-data-access.md); for dialect extensions see [10. SPI Extension](10-spi-extension.md).

### Typed sharing (LIBRARY modules) across isolation modes

A `LIBRARY` module's typed sharing (`uses`/`exports`, see [02. Module Authoring §8](02-module-authoring.md#8-library-modules-shared-module-typed-sharing)) works across all three isolation modes, not just in-process. A worker is itself a Protean app: when the main pushes a dependent's `uses` closure (the library descriptors and sources) to a worker, the worker **independently compiles and publishes those libraries into its own `SharedModuleRegistry`** and links the dependent against them — it re-derives the shared-type identity locally rather than relaying bytes. (`SharedModuleRegistry` is present in every profile, including `worker`, for exactly this reason; the eager-propagation beans `SharedModuleInvalidator`/`SharedModuleUsageIndex` stay main-only, since the main drives worker rebinds.)

On a library update, the main-side `WorkerSharedModulePropagator` reacts (after the store commit, so the new sources are available) and, for each worker hosting the library, calls `POST /__admin/redeploy` to republish the library in that worker, then `POST /__admin/redeploy` for each co-located dependent that transitively `uses` it (recompiling it against the new generation). This reuses the generic module redeploy endpoint — unlike native shared-lib jars, which have their own `POST /__admin/shared-libs` push (see [07. Data Access](07-data-access.md)). It is governed by `protean.module.eager-shared-module-invalidation` (default `true`). The container track follows the identical protocol via the same `WorkerParentTierTarget` contract.

## container (Docker container worker)

`ContainerWorkerIsolation` launches the worker as a Docker container and confines it with cgroup, read-only, cap-drop, and seccomp. It is the untrusted-tier baseline, blocking at the OS level the host-resource, file, and syscall violations that a worker process alone cannot stop. It pools containers and packs modules up to `modules-per-worker` (default 128); under auto-provision the pool is keyed by **scope** (tenant), so same-scope modules share a container while different scopes are isolated in separate containers — the OS-isolation boundary is the scope. Set `modules-per-worker=1` for the strict one-container-per-module boundary (maximum isolation, no packing). The RPC bridge is unsupported (`supports()` rejects `needsSharedBeans` modules).

### Requirements

- The Docker daemon must be installed and running (if absent, it fails fast with a command failure).
- The embed runtime (default) unpacks the host bootJar into an exploded layout and read-only mounts it, so you **must run `gradle bootJar` first** to have `build/libs/*-boot.jar` (the explicit path is `protean.worker.container.jar`).

### Hardening configuration

```yaml
protean:
  worker:
    container:
      image: eclipse-temurin:21-jdk   # default
      memory: 512m                     # cgroup memory cap (holds modules-per-worker=128). Default 512m
      pids-limit: 1024                 # fork-bomb-defense PID limit. Default 1024
      network: ""                      # network for egress isolation (e.g. internal). Empty = docker default
      seccomp: ""                      # profile path | "bundled" | empty = docker default
      auto-restart: false              # container crash detection → redeploy
      db-host: host.docker.internal    # hostname-rewrite target for reaching the host DB from the container
```

The container always applies these options: `--memory` + `--read-only` rootfs + `--tmpfs /tmp` + `--cap-drop=ALL` + `--security-opt no-new-privileges` + `--pids-limit`. If `network` is non-empty it adds `--network`, and if `seccomp` is non-empty it adds `--security-opt seccomp=<profile>`.

With `seccomp: bundled`, it extracts the bundled default profile on the classpath (`/seccomp/protean-default.json`, defaultAction=ALLOW + EPERM-deny for dangerous syscalls) to a temp file and applies it. Any other value is passed through as a user profile file path.

The container publishes its internal `8080` port to a random host port via `-p 0:8080`; the main finds the host port with `docker port`, waits for health (`/__admin/health`), then deploys. `db-host` is the rewrite target for the `localhost`/`127.0.0.1` host in JDBC URLs, solving the problem that inside the container `localhost` refers to the container itself (Docker Desktop = `host.docker.internal`).

## Worker runtime: embed vs sidecar

Common to both the `worker` and `container` tracks, "what launches the worker JVM" is decided by the `WorkerRuntimeProvider` SPI. Choose it with `protean.worker.runtime`.

```yaml
protean:
  worker:
    runtime: embed   # embed(default) | sidecar
```

- **embed** (`EmbeddedWorkerRuntime`, default): worker = host artifact. The process track re-runs the host classpath (`java.class.path`), the container track re-runs an explode of the host bootJar. Because the worker runtime-compiles the sources, the key benefit is that classpath parity comes for free.
- **sidecar** (`SidecarWorkerRuntime`, opt-in): worker = a dedicated slim artifact published by Protean. Better for minimizing isolation/attack surface, but the shared types a module references must be injected separately as a shared-api jar.

```yaml
protean:
  worker:
    runtime: sidecar
    sidecar:
      jar: /opt/protean/protean-worker.jar    # process track
      image: ghcr.io/htcom-code/protean-worker:0.0.1  # container track
      shared-api: /opt/protean/shared-api.jar # shared types for worker compilation
```

**Obtaining the published artifacts.** Protean's build emits both, so you do not build the worker yourself:
- process track (`jar`) — the flat shaded uber-jar published to GitHub Packages under the `worker` classifier (`org.htcom:protean:<version>:worker`). Download it and point `jar` at the file. (A Spring Boot `-boot.jar` will **not** work here — the process track launches with a bare `java -cp`, which the nested `BOOT-INF` layout cannot satisfy; the `worker` jar is flat for exactly this reason.)
- container track (`image`) — the OCI image published to GHCR at `ghcr.io/<owner>/protean-worker:<version>`. It bundles the worker at `/app/protean-worker.jar` and runs on the `/app/*` classpath; no host mount is needed.

**`shared-api` is yours to curate**, not a Protean artifact: it holds the shared types your modules reference at compile time (your own domain types plus any bridged interfaces). Embed avoids this because it inherits the host classpath for free; supplying `shared-api` is sidecar's cost.

If unset, it fails fast with a clear error (the process track needs `sidecar.jar`, the container track needs `sidecar.image`). For how to plug in your own custom runtime provider, see [10. SPI Extension](10-spi-extension.md).

## Zero-downtime hot-swap

In all three modes, hot-swap fully prepares the new instance and then switches the proxy route **atomically** to the new target — there is no 404/502 window. This is because `ReverseProxy.repoint(path, port)` swaps only the target port in a single step, without unregistering/registering the mapping.

- `in-process`: rebuilds the child context under a new ClassLoader and atomically swaps.
- `worker`: deploys the new version to a worker that doesn't already hold this module → `repoint`s each path → drains and cleans up the old worker (if v2 preparation fails, keeps the old worker = rollback).
- `container`: fully starts the new container through health + deploy → `repoint`s → retires the old container.

## Related docs

- [02. Module Authoring](02-module-authoring.md)
- [03. Configuration Reference](03-configuration.md)
- [07. Data Access](07-data-access.md)
- [10. SPI Extension](10-spi-extension.md)
- [12. Security](12-security.md)
- [README](../../README.md)
