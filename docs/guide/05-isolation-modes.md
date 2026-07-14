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
    modules-per-worker: 4   # max modules per worker (1 = a dedicated JVM per module = full isolation). Default 4
    min-warm: 0             # number of empty workers to keep warm (reuse). Default 0
```

With `modules-per-worker=1` each module gets a dedicated JVM and is fully isolated, but the JVM count grows; with `>1`, modules within the same worker share their fate on a crash. Empty workers are kept up to `min-warm` for reuse and the excess is cleaned up.

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

To auto-provision a dedicated, isolated DB per module, use `protean.worker.db.auto-provision=true`. In that case, to guarantee isolation, it is forced to one module per worker (`capacity=1`) with no warm reuse (`min-warm=0`), and launches a dedicated worker with the provisioned scope credentials. For vendor-specific provisioning see [07. Data Access](07-data-access.md); for dialect extensions see [10. SPI Extension](10-spi-extension.md).

## container (Docker container worker)

`ContainerWorkerIsolation` launches the worker as a Docker container and confines it with cgroup, read-only, cap-drop, and seccomp. It is the untrusted-tier baseline, blocking at the OS level the host-resource, file, and syscall violations that a worker process alone cannot stop. It keeps no pool — "one container per module" is the essence of OS isolation, so packing would weaken isolation. The RPC bridge is unsupported (`supports()` rejects `needsSharedBeans` modules).

### Requirements

- The Docker daemon must be installed and running (if absent, it fails fast with a command failure).
- The embed runtime (default) unpacks the host bootJar into an exploded layout and read-only mounts it, so you **must run `gradle bootJar` first** to have `build/libs/*-boot.jar` (the explicit path is `protean.worker.container.jar`).

### Hardening configuration

```yaml
protean:
  worker:
    container:
      image: eclipse-temurin:21-jdk   # default
      memory: 256m                     # cgroup memory cap. Default 256m
      pids-limit: 512                  # fork-bomb-defense PID limit. Default 512
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
- **sidecar** (`SidecarWorkerRuntime`, opt-in): worker = a dedicated slim jar/image published by Protean. Better for minimizing isolation/attack surface, but the shared types a module references must be injected separately as a shared-api jar.

```yaml
protean:
  worker:
    runtime: sidecar
    sidecar:
      jar: /opt/protean/worker.jar          # process track
      image: registry/protean-worker:1.0     # container track
      shared-api: /opt/protean/shared-api.jar # shared types for worker compilation
```

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
