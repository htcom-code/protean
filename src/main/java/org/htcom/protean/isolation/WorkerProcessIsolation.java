/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.bridge.BridgeSecretHolder;
import org.htcom.protean.db.DbScope;
import org.htcom.protean.db.DbScopeProvisioner;
import org.htcom.protean.dynamic.DynamicEndpointRegistrar.RouteInfo;
import org.htcom.protean.gate.ServerPortHolder;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.SharedLibStore;
import org.htcom.protean.worker.WorkerSharedLibReceiver;
import org.htcom.protean.proxy.ReverseProxy;
import org.htcom.protean.runtime.DebugRouteRegistry;
import org.htcom.protean.worker.WorkerPortAnnouncer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strong-isolation strategy: launch a separate JVM worker, run the module in-process inside it, and have the main
 * process reverse-proxy to it.
 *
 * <b>Worker pool/reuse</b>: each worker loads up to {@code modules-per-worker} modules.
 * With capacity=1, each module gets a dedicated JVM (full isolation). With >1, one worker hosts multiple modules,
 * reducing the number of JVMs and startup cost (but modules in that worker share fate on a crash). Empty workers are
 * kept warm up to {@code min-warm} (for reuse); the excess is cleaned up.
 */
@Component
@Profile("!worker")
public class WorkerProcessIsolation implements IsolationStrategy, WorkerParentTierTarget {

    private static final Logger log = LoggerFactory.getLogger(WorkerProcessIsolation.class);

    /** JDWP agentlib injected into a debug-launch worker (ephemeral port, localhost, suspend=n → the worker starts normally; breakpoints after attach). */
    private static final String JDWP_ARG =
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0";
    /** Parses the JDWP port from the worker's stdout line "Listening for transport dt_socket at address: N". */
    private static final Pattern JDWP_LISTENING = Pattern.compile("address:\\s*(\\d+)");

    /**
     * A dedicated JDWP worker launched via debug-launch. Managed only for the session lifetime, outside the normal
     * pool ({@link #pool}) (not subject to crash-restart or warm reuse). {@code priorPorts} holds the original ports
     * before the route was taken over (for restoration; since null values are not allowed, unrecorded paths are omitted).
     */
    public record DebugWorkerHandle(UUID id, Process process, int workerPort, int jdwpPort,
                                    List<String> paths, Map<String, Integer> priorPorts) {
    }

    static final class WorkerHandle {
        /** Per-spawn identity, carried on the worker's command line ({@code -Dprotean.worker.id}) so an orphaned JVM
         * can be recognized and reaped after an unclean main exit (see {@link OrphanWorkerReaper}). */
        final UUID id;
        final Process process;
        final int port;
        final Set<String> modules = ConcurrentHashMap.newKeySet();
        /** Library-module ids published into this worker's parent tier (a dependent's {@code uses} closure). */
        final Set<String> libraries = ConcurrentHashMap.newKeySet();
        volatile boolean retiring;   // intentional-shutdown flag (distinguished from a crash — prevents auto-restart)

        WorkerHandle(UUID id, Process process, int port) {
            this.id = id;
            this.process = process;
            this.port = port;
        }
    }

    private final ReverseProxy proxy;
    private final ObjectMapper mapper;
    private final ServerPortHolder portHolder;
    private final WorkerRuntimeProvider runtimeProvider;
    private final HttpClient client;

    private final List<WorkerHandle> pool = new ArrayList<>();              // guarded by this
    private final Map<String, WorkerHandle> moduleToWorker = new ConcurrentHashMap<>();
    private final Map<String, List<String>> modulePaths = new ConcurrentHashMap<>();
    private final Map<String, ModuleDescriptor> moduleDescriptors = new ConcurrentHashMap<>();

    /**
     * Live properties. Read per operation so runtime changes apply: {@code worker.auto-restart} (Tier 1, per crash),
     * {@code worker.modules-per-worker}/{@code worker.min-warm}/{@code worker.datasource.url} (Tier 2, per spawn).
     */
    private final ProteanProperties props;
    private final boolean rpcBridge;
    /** Bridge auth secret, injected into spawned workers when RPC-bridge auth is enabled (null when disabled). */
    private final String bridgeSecret;
    /** Bridge auth scheme (token|hmac), injected into spawned workers alongside the secret. */
    private final String bridgeAuthMode;
    /** Worker /__admin/* auth (opt-in). When enabled the secret is injected into every spawned worker so its
     * WorkerAdminAuthFilter verifies the main's admin calls; the secret is null when disabled. */
    private final boolean adminAuthEnabled;
    private final String adminAuthSecret;
    private final String adminAuthMode;
    /** Shared-lib directory. When set, it is passed to the worker → the worker's ModuleSharedLibs reads the same dir (same host FS). */
    private final String sharedLibDir;
    /** Present only when auto-provision is enabled (null otherwise). Auto-provisions an isolated DB scope per module. */
    private final DbScopeProvisioner provisioner;
    /** DB scope provisioned per module (reused across hot-swap/crash recovery so the same DB is kept). */
    private final Map<String, DbScope> moduleScopes = new ConcurrentHashMap<>();
    /** Active debug-launch workers (for observation/leak detection). Removed when the session ends. */
    private final Set<DebugWorkerHandle> debugWorkers = ConcurrentHashMap.newKeySet();
    /** Registry of debug-active paths (main leg). Added on launch, removed on terminate → queried by the main ModuleTimeoutFilter. */
    private final DebugRouteRegistry debugRoutes;
    /** The main-side worker parent-tier control-plane client (shared with the container strategy). */
    private final WorkerAdminClient admin;
    /** Reaps worker JVMs orphaned by a prior unclean main exit (graceful exits are handled by {@link #shutdown()}). */
    private final OrphanWorkerReaper reaper;

    public WorkerProcessIsolation(HttpClient client, ReverseProxy proxy, ObjectMapper mapper, ServerPortHolder portHolder,
                                  WorkerRuntimeProvider runtimeProvider,
                                  ObjectProvider<DbScopeProvisioner> provisionerProvider,
                                  ObjectProvider<DebugRouteRegistry> debugRoutesProvider,
                                  ObjectProvider<BridgeSecretHolder> bridgeSecretProvider,
                                  ObjectProvider<WorkerAdminSecretHolder> adminSecretProvider,
                                  WorkerAdminClient admin,
                                  ProteanProperties props) {
        this.client = client;
        this.proxy = proxy;
        this.mapper = mapper;
        this.portHolder = portHolder;
        this.runtimeProvider = runtimeProvider;
        this.provisioner = provisionerProvider.getIfAvailable();
        this.debugRoutes = debugRoutesProvider.getIfAvailable();
        BridgeSecretHolder secretHolder = bridgeSecretProvider.getIfAvailable();
        this.bridgeSecret = secretHolder != null ? secretHolder.token() : null;
        this.bridgeAuthMode = props.getBridge().getAuthMode();
        this.adminAuthEnabled = props.getWorker().getAdminAuth().isEnabled();
        WorkerAdminSecretHolder adminSecretHolder = adminSecretProvider.getIfAvailable();
        this.adminAuthSecret = adminSecretHolder != null ? adminSecretHolder.token() : null;
        this.adminAuthMode = props.getWorker().getAdminAuth().getMode();
        this.admin = admin;
        this.props = props;
        this.rpcBridge = props.getWorker().isRpcBridge();
        this.sharedLibDir = props.getModule().getSharedLibDir();
        this.reaper = new OrphanWorkerReaper(Path.of(props.getModuleStore().getDir(), "workers"));
    }

    /**
     * On startup, reap any worker JVMs orphaned by a prior <b>unclean</b> exit (SIGKILL/crash/OOM), which the graceful
     * {@link #shutdown()} hook could not clean. Runs before any worker spawns, so it only ever touches markers from a
     * previous run. Best-effort — a failure here must not block startup.
     */
    @PostConstruct
    void reapOrphansOnStartup() {
        try {
            int reaped = reaper.reapOrphans();
            if (reaped > 0) {
                log.warn("startup: reaped {} orphan worker JVM(s) left by a previous unclean exit", reaped);
            }
        } catch (RuntimeException e) {
            log.warn("startup orphan-worker reap failed (ignored): {}", e.toString());
        }
    }

    /**
     * Modules per worker, read live (Tier 2 — applies to the next spawn). With auto-provision a dedicated DB per
     * module forces a dedicated worker per module (capacity=1); a warm worker lacks scope creds, so no reuse.
     */
    private int capacity() {
        return provisioner != null ? 1 : Math.max(1, props.getWorker().getModulesPerWorker());
    }

    /** Warm-worker target, read live (Tier 2). Zero under auto-provision (a warm worker runs without scope creds). */
    private int minWarm() {
        return provisioner != null ? 0 : Math.max(0, props.getWorker().getMinWarm());
    }

    @Override
    public String mode() {
        return "worker";
    }

    @Override
    public boolean supports(ModuleDescriptor descriptor) {
        // A worker cannot access shared beans directly → fail-fast by default. Allowed if the RPC bridge is enabled.
        return !descriptor.needsSharedBeans() || rpcBridge;
    }

    @Override
    public synchronized void deploy(ModuleDescriptor descriptor) {
        if (moduleToWorker.containsKey(descriptor.id())) {
            throw new IllegalStateException("worker module already deployed: " + descriptor.id());
        }
        WorkerHandle handle = acquireWorkerFor(descriptor.id(), null);
        ensureUsesClosure(handle, descriptor);   // publish the module's library `uses` closure into this worker first
        List<RouteInfo> routes = deployToWorker(handle.port, descriptor);
        for (RouteInfo route : routes) {
            for (String path : route.patterns()) {
                proxy.register(path, route.methods(), handle.port, descriptor.id());
            }
        }
        List<String> paths = pathsOf(routes);
        handle.modules.add(descriptor.id());
        moduleToWorker.put(descriptor.id(), handle);
        modulePaths.put(descriptor.id(), paths);
        moduleDescriptors.put(descriptor.id(), descriptor);
        log.info("worker module deploy: {} → port {} ({} workers, {} modules in this worker)",
                descriptor.id(), handle.port, pool.size(), handle.modules.size());
    }

    @Override
    public synchronized void hotSwap(ModuleDescriptor descriptor) {
        WorkerHandle oldHandle = moduleToWorker.get(descriptor.id());
        if (oldHandle == null) {
            deploy(descriptor);
            return;
        }
        List<String> oldPaths = modulePaths.get(descriptor.id());
        WorkerHandle newHandle = acquireWorkerFor(descriptor.id(), descriptor.id());  // a worker that does not already have this module
        try {
            ensureUsesClosure(newHandle, descriptor);   // the fresh worker needs the library `uses` closure too
            List<RouteInfo> newRoutes = deployToWorker(newHandle.port, descriptor);
            for (RouteInfo route : newRoutes) {
                for (String path : route.patterns()) {
                    proxy.repoint(path, route.methods(), newHandle.port);   // atomic switch + refresh methods (version may change them)
                }
            }
            List<String> newPaths = pathsOf(newRoutes);
            if (oldPaths != null) {
                for (String oldPath : oldPaths) {
                    if (!newPaths.contains(oldPath)) {
                        proxy.unregister(oldPath);
                    }
                }
            }
            newHandle.modules.add(descriptor.id());
            moduleToWorker.put(descriptor.id(), newHandle);
            modulePaths.put(descriptor.id(), newPaths);
            moduleDescriptors.put(descriptor.id(), descriptor);
            oldHandle.modules.remove(descriptor.id());
            log.info("worker hot-swap: {} → port {} (draining old port {})",
                    descriptor.id(), newHandle.port, oldHandle.port);
        } catch (RuntimeException e) {
            retireIfNewlySpawned(newHandle);  // v2 preparation failed → keep the old worker (rollback)
            throw new IllegalStateException("worker hot-swap failed (previous version retained): " + descriptor.id(), e);
        }
        scheduleDrainCleanup(oldHandle, descriptor.id());
    }

    @Override
    public synchronized void undeploy(String moduleId) {
        WorkerHandle handle = moduleToWorker.remove(moduleId);
        if (handle == null) {
            return;
        }
        List<String> paths = modulePaths.remove(moduleId);
        moduleDescriptors.remove(moduleId);
        if (paths != null) {
            for (String path : paths) {
                proxy.unregister(path);
            }
        }
        handle.modules.remove(moduleId);
        if (handle.modules.isEmpty()) {
            retireOrKeepWarm(handle);
        } else {
            postUndeploy(handle.port, moduleId);  // keep the worker, release only this module
        }
        if (provisioner != null) {
            moduleScopes.remove(moduleId);
            provisioner.deprovision(moduleId);  // actually removed only under the deprovision-on-undeploy option
        }
    }

    /** For tests: force-kill the worker hosting the module (crash simulation). The proxy route is kept → 502. */
    public synchronized void simulateCrash(String moduleId) {
        WorkerHandle handle = moduleToWorker.get(moduleId);
        if (handle != null) {
            handle.process.destroyForcibly();
        }
    }

    public synchronized int workerCount() {
        return pool.size();
    }

    /** For tests: a snapshot of the current pool worker JVM {@link Process} handles, for liveness assertions. */
    public synchronized List<Process> workerProcesses() {
        return pool.stream().map(h -> h.process).toList();
    }

    /**
     * On graceful main shutdown, terminate every worker JVM this instance spawned (the pool + any debug workers).
     * {@code ProcessBuilder} children are not killed when the parent JVM exits, so without this hook the workers would
     * survive as orphan JVMs (holding their random ports and heap) — the process-track counterpart to
     * {@link ContainerWorkerIsolation#shutdown()} (PR #34). Each worker is sent SIGTERM ({@link Process#destroy()}) for
     * a graceful Spring shutdown, then force-killed if it does not exit within {@code protean.worker.shutdown-grace-ms}
     * (read live; default 5000, {@code 0} = force-kill immediately, negative → treated as 0). The snapshot is taken
     * under the monitor and the blocking teardown runs lock-free in parallel (a slow worker must not serialize the
     * others), mirroring {@link #fanOut}. The {@code retiring} flag is set first so the crash-restart callback
     * ({@link #onWorkerExit}) does not respawn the workers this hook is killing.
     */
    @PreDestroy
    public void shutdown() {
        List<Process> processes = new ArrayList<>();
        synchronized (this) {
            for (WorkerHandle h : pool) {
                h.retiring = true;   // intentional shutdown — suppress auto-restart on the exit callback
                processes.add(h.process);
                reaper.forget(h.id);   // graceful teardown → drop the orphan marker (no reap needed next start)
            }
            pool.clear();
            for (DebugWorkerHandle h : debugWorkers) {
                processes.add(h.process());
                reaper.forget(h.id());   // debug workers have no onExit hook — forget here
            }
            debugWorkers.clear();
        }
        if (processes.isEmpty()) {
            return;
        }
        long graceMs = props.getWorker().getShutdownGraceMs();   // read live (default 5000, 0 = immediate force)
        if (graceMs < 0) {
            log.warn("protean.worker.shutdown-grace-ms is negative ({}) — treating as 0 (immediate force-kill)", graceMs);
            graceMs = 0;
        }
        final long grace = graceMs;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Process p : processes) {
                executor.execute(() -> terminateGracefully(p, grace));
            }
        }
        log.info("worker isolation shutdown: terminated {} worker JVM(s) (grace {}ms)", processes.size(), grace);
    }

    /**
     * SIGTERM ({@link Process#destroy()}) for a graceful exit, then SIGKILL ({@link Process#destroyForcibly()}) if it
     * does not exit within {@code graceMs} ({@code <= 0} skips straight to force). Always waits for the force-kill to
     * actually reap the process — the teardown must guarantee no orphan is left behind, and {@code destroyForcibly()}
     * only sends the signal (it does not wait).
     */
    private void terminateGracefully(Process p, long graceMs) {
        try {
            if (graceMs > 0) {
                p.destroy();
                if (p.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
                    return;   // exited gracefully within the grace period
                }
            }
            p.destroyForcibly();
            p.waitFor();   // confirm the SIGKILL actually reaped it (no orphan)
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    // --- worker pool ---

    /**
     * Assigns a worker to a module. Under auto-provision, provisions an isolated DB scope per module and launches a
     * dedicated worker with those creds (no pool reuse — warm workers have no scope creds).
     * Otherwise uses the existing pool-reuse logic ({@link #acquireWorker}).
     */
    private WorkerHandle acquireWorkerFor(String moduleId, String excludeModuleId) {
        if (provisioner != null) {
            DbScope scope = moduleScopes.computeIfAbsent(moduleId, provisioner::provision);
            WorkerHandle spawned = spawnAndReady(scope);
            pool.add(spawned);
            return spawned;
        }
        return acquireWorker(excludeModuleId);
    }

    /** Picks a live worker with spare capacity (that does not already have this module), or spawns a new one. */
    private WorkerHandle acquireWorker(String excludeModuleId) {
        for (WorkerHandle h : pool) {
            if (h.process.isAlive() && h.modules.size() < capacity()
                    && (excludeModuleId == null || !h.modules.contains(excludeModuleId))) {
                return h;
            }
        }
        WorkerHandle spawned = spawnAndReady(null);
        pool.add(spawned);
        return spawned;
    }

    private void retireOrKeepWarm(WorkerHandle handle) {
        long warmEmpty = pool.stream().filter(h -> h.modules.isEmpty()).count();
        if (warmEmpty > minWarm()) {
            handle.retiring = true;     // intentional shutdown — no auto-restart
            handle.process.destroy();
            pool.remove(handle);
        }
        // else: keep empty workers warm up to min-warm (for reuse)
    }

    private void retireIfNewlySpawned(WorkerHandle handle) {
        if (handle.modules.isEmpty()) {
            handle.retiring = true;
            handle.process.destroyForcibly();
            pool.remove(handle);
        }
    }

    /** After a hot-swap, drain the module from the old worker and then clean up (asynchronous outside the lock, re-synchronized during cleanup). */
    private void scheduleDrainCleanup(WorkerHandle oldHandle, String moduleId) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            synchronized (this) {
                if (oldHandle.modules.isEmpty()) {
                    oldHandle.retiring = true;
                    oldHandle.process.destroy();
                    pool.remove(oldHandle);
                } else {
                    postUndeploy(oldHandle.port, moduleId);
                }
            }
        }, "worker-drain-kill");
        t.setDaemon(true);
        t.start();
    }

    // --- worker process/communication ---

    private WorkerHandle spawnAndReady(DbScope scope) {
        UUID id = UUID.randomUUID();
        Process process = spawnWorker(scope, false, id);
        try {
            int port = awaitPort(process, 60);
            waitHealthy(port, 30);
            seedParentTier(port);   // fold the current live shared-lib generation in before any module deploys
            WorkerHandle handle = new WorkerHandle(id, process, port);
            // Always observe exit; whether a crash triggers a restart is decided live in onWorkerExit
            // (protean.worker.auto-restart, Tier 1) so a runtime toggle applies to every worker immediately.
            process.onExit().thenAccept(p -> onWorkerExit(handle));
            return handle;
        } catch (RuntimeException e) {
            process.destroyForcibly();
            reaper.forget(id);   // never became a tracked worker — drop its marker
            throw e;
        }
    }

    /** Worker-process exit callback. Ignored on an intentional shutdown (retiring); on a crash, restart the modules. */
    private synchronized void onWorkerExit(WorkerHandle dead) {
        pool.remove(dead);
        reaper.forget(dead.id);   // the process is gone — drop its orphan marker (covers retire/drain/crash alike)
        if (dead.retiring) {
            return;  // intentional shutdown
        }
        if (!props.getWorker().isAutoRestart()) {
            return;  // auto-restart disabled (read live) — leave crashed modules down
        }
        List<String> affected = new ArrayList<>(dead.modules);
        log.warn("worker crash detected (port {}) — restarting modules {}", dead.port, affected);
        for (String moduleId : affected) {
            if (moduleToWorker.get(moduleId) != dead) {
                continue;  // already moved to another worker (hot-swap etc.)
            }
            try {
                redeployAfterCrash(moduleId);
            } catch (RuntimeException e) {
                log.error("crashed module restart failed: {} - {}", moduleId, e.getMessage());
            }
        }
    }

    /** Redeploys a crashed module to a new worker and switches the proxy to the new port. */
    private void redeployAfterCrash(String moduleId) {
        ModuleDescriptor descriptor = moduleDescriptors.get(moduleId);
        if (descriptor == null) {
            return;
        }
        WorkerHandle handle = acquireWorkerFor(moduleId, null);
        ensureUsesClosure(handle, descriptor);   // restore the library `uses` closure on the recovery worker first
        List<String> paths = pathsOf(deployToWorker(handle.port, descriptor));
        for (String path : paths) {
            proxy.repoint(path, handle.port);  // keep the route (methods unchanged — same module), switch to the new port
        }
        handle.modules.add(moduleId);
        moduleToWorker.put(moduleId, handle);
        modulePaths.put(moduleId, paths);
        log.info("worker crash recovery: {} → port {}", moduleId, handle.port);
    }

    private Process spawnWorker(DbScope scope, boolean debug, UUID id) {
        // The JVM launch prefix (embed = host classpath / sidecar = dedicated jar) is decided by the runtime provider.
        // The common --spring.* arguments are appended here.
        List<String> command = new ArrayList<>(runtimeProvider.processLaunchPrefix());
        // Identity marker in the JVM-option area (before the main class; prefix index 0 = java binary → insert at 1) so
        // an orphaned worker JVM can be recognized on its command line and reaped after an unclean main exit.
        command.add(1, OrphanWorkerReaper.markerArg(id));
        if (debug) {
            // The JDWP agentlib must go in the JVM-option area (before the main class). Prefix index 0 = java binary → insert right after it (1).
            command.add(1, JDWP_ARG);
        }
        command.addAll(List.of(
                "--spring.profiles.active=worker",
                "--protean.isolation.mode=in-process",
                "--server.port=0"));
        if (debug) {
            // In a debug worker, forcibly disable the module-execution watchdog (request-timeout-ms=0). A breakpoint
            // pause holds the request thread indefinitely, so if the consumer has a global request-timeout enabled,
            // the watchdog would interrupt the paused request and make debugging impossible. Interactive debugging
            // and request timeout are mutually exclusive.
            command.add("--protean.module.request-timeout-ms=0");
        }
        if (sharedLibDir != null && !sharedLibDir.isBlank()) {
            // The worker is also a protean app → it reads the same dir (shared host FS) as its own ModuleSharedLibs to compile/load shared-lib.
            command.add("--protean.module.shared-lib-dir=" + sharedLibDir);
        }
        if (adminAuthEnabled && adminAuthSecret != null) {
            // Turn on the worker's /__admin/* auth filter and hand it the shared secret so it verifies the main's calls.
            command.add("--protean.worker.admin-auth.enabled=true");
            command.add("--protean.worker.admin-auth.secret=" + adminAuthSecret);
            command.add("--protean.worker.admin-auth.mode=" + adminAuthMode);
        }
        if (rpcBridge) {
            // Pass the bridge URL the worker will call the main shared beans through + the active flag
            command.add("--protean.worker.rpc-bridge=true");
            command.add("--protean.bridge.url=http://localhost:" + portHolder.port());
            if (bridgeSecret != null) {
                // Inject the shared secret + auth scheme so the worker can authenticate its calls to main /__bridge/*.
                command.add("--protean.bridge.secret=" + bridgeSecret);
                command.add("--protean.bridge.auth-mode=" + bridgeAuthMode);
            }
        }
        if (scope != null) {
            // auto-provision: pass the module-dedicated isolated DB scope creds (dedicated DB/schema + restricted user).
            command.add("--spring.datasource.url=" + scope.url());
            command.add("--spring.datasource.username=" + scope.username());
            command.add("--spring.datasource.password=" + scope.password());
        } else {
            // Manual global scope, read live (Tier 2). If unset, the worker uses its own default H2 (separate JVM = isolation).
            String workerDatasourceUrl = props.getWorker().getDatasource().getUrl();
            if (!workerDatasourceUrl.isBlank()) {
                command.add("--spring.datasource.url=" + workerDatasourceUrl);
            }
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            reaper.record(id, process.pid());   // durable marker so an unclean main exit can be cleaned up next start
            return process;
        } catch (Exception e) {
            throw new IllegalStateException("failed to create worker process", e);
        }
    }

    private int awaitPort(Process process, int timeoutSec) {
        CompletableFuture<Integer> portFuture = new CompletableFuture<>();
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!portFuture.isDone() && trimmed.startsWith(WorkerPortAnnouncer.MARKER)) {
                        portFuture.complete(Integer.parseInt(
                                trimmed.substring(WorkerPortAnnouncer.MARKER.length())));
                    }
                }
            } catch (Exception e) {
                portFuture.completeExceptionally(e);
            }
        }, "worker-stdout-drainer");
        drainer.setDaemon(true);
        drainer.start();
        try {
            return portFuture.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("worker port handshake timeout", e);
        } catch (Exception e) {
            throw new IllegalStateException("worker port handshake failed", e);
        }
    }

    private void waitHealthy(int port, int timeoutSec) {
        long deadline = System.nanoTime() + timeoutSec * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> r = client.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/__admin/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for health", ie);
            }
        }
        throw new IllegalStateException("worker health timeout: port " + port);
    }

    private List<RouteInfo> deployToWorker(int port, ModuleDescriptor descriptor) {
        // Routed through WorkerAdminClient so the outgoing /__admin/deploy carries admin-auth when enabled (single
        // signing point); WorkerAdminClient.deploy performs the same POST /__admin/deploy → registered-routes call.
        return admin.deploy(port, descriptor);
    }

    /** Flattens the worker's returned routes to their path patterns (bookkeeping for repoint/unregister). */
    private static List<String> pathsOf(List<RouteInfo> routes) {
        List<String> paths = new ArrayList<>();
        for (RouteInfo r : routes) {
            paths.addAll(r.patterns());
        }
        return paths;
    }

    private void postUndeploy(int port, String moduleId) {
        try {
            admin.undeploy(port, moduleId);
        } catch (RuntimeException e) {
            log.warn("worker undeploy request failed (ignored): {} port {}", moduleId, port);
        }
    }

    // --- live parent-tier (shared-lib) propagation to workers ---

    /**
     * Pushes the current live shared-lib generation to every live worker and rebinds each worker's affected modules.
     * Called by {@link org.htcom.protean.module.WorkerSharedLibPropagator} when the
     * store publishes a new generation. A byte-push to a down/retiring worker is skipped (log-and-continue — a failing
     * worker must not fail the main's store deploy); a per-module rebind failure is Plan B (sticky, logged loudly — the
     * module keeps serving on its prior generation). Synchronized with the spawn path so a worker is either seeded at
     * spawn or included here, never neither.
     */
    @Override
    public void pushSharedLibGeneration(List<SharedLibStore.IncomingLib> bundle, Set<String> changedJars) {
        fanOut(snapshotPool(), handle -> pushSharedLibGenerationToWorker(handle, bundle, changedJars));
    }

    private void pushSharedLibGenerationToWorker(WorkerHandle handle,
                                                 List<SharedLibStore.IncomingLib> bundle, Set<String> changedJars) {
        if (handle.retiring || !handle.process.isAlive()) {
            return;
        }
        WorkerSharedLibReceiver.PublishResult result;
        try {
            result = admin.pushSharedLibs(handle.port, bundle, changedJars);
        } catch (RuntimeException e) {
            log.warn("shared-lib generation push to worker port {} failed (skipped): {}", handle.port, e.toString());
            return;
        }
        for (String moduleId : result.affectedModuleIds()) {
            ModuleDescriptor descriptor = moduleDescriptors.get(moduleId);
            if (descriptor == null) {
                continue;
            }
            try {
                admin.redeploy(handle.port, descriptor);   // Plan A2: in-place recompile against the new generation
            } catch (RuntimeException e) {
                log.warn("worker shared-lib rebind failed for '{}' on port {} — Plan B (sticky): it stays on its "
                        + "prior generation. cause: {}", moduleId, handle.port, e.toString());
            }
        }
    }

    /**
     * Folds the current live shared-lib generation into a freshly spawned worker before any module deploys, so it
     * starts on the current parent tier rather than the stale boot seed (no-op when the store is empty).
     */
    private void seedParentTier(int port) {
        admin.seedSharedLibs(port);
    }

    // --- library (typed-sharing) uses-closure propagation to workers ---

    /**
     * Publishes every library in a module's transitive {@code uses} closure into {@code handle}'s worker (in
     * dependency-first order) before the module itself deploys, so the worker can compile and link the module against
     * those shared types. Type identity is per-JVM, so a dependent and its libraries must be co-located on one worker;
     * a library already present on the worker is skipped. No-op for a module with no {@code uses} (or when the module
     * store is unavailable — a worker JVM, where this path is not used).
     */
    private void ensureUsesClosure(WorkerHandle handle, ModuleDescriptor module) {
        for (ModuleDescriptor library : admin.usesClosure(module)) {
            if (handle.libraries.contains(library.id())) {
                continue;
            }
            admin.deploy(handle.port, library);   // publishes the library generation inside the worker (no routes)
            handle.libraries.add(library.id());
        }
    }

    /**
     * Debug-worker variant of {@link #ensureUsesClosure}: publishes a module's transitive {@code uses} closure into a
     * debug worker addressed by port. A debug worker is single-module and never pooled/reused, so no per-handle library
     * tracking (dedup) is needed — unlike {@link #ensureUsesClosure}, which skips libraries already on a shared worker.
     */
    private void ensureUsesClosureByPort(int port, ModuleDescriptor module) {
        for (ModuleDescriptor library : admin.usesClosure(module)) {
            admin.deploy(port, library);   // publishes the library generation inside the debug worker (no routes)
        }
    }

    @Override
    public void propagateLibraryUpdate(ModuleDescriptor library) {
        fanOut(snapshotPool(), handle -> propagateLibraryUpdateToWorker(handle, library));
    }

    private void propagateLibraryUpdateToWorker(WorkerHandle handle, ModuleDescriptor library) {
        if (handle.retiring || !handle.process.isAlive() || !handle.libraries.contains(library.id())) {
            return;
        }
        try {
            admin.redeploy(handle.port, library);   // worker republishes the library → new generation in its registry
        } catch (RuntimeException e) {
            log.warn("worker library republish failed for '{}' on port {} — Plan B: dependents stay on the prior "
                    + "generation. cause: {}", library.id(), handle.port, e.toString());
            return;
        }
        for (String moduleId : List.copyOf(handle.modules)) {
            ModuleDescriptor dependent = moduleDescriptors.get(moduleId);
            if (dependent == null || !admin.usesTransitively(dependent, library.id())) {
                continue;
            }
            try {
                admin.redeploy(handle.port, dependent);   // recompile the dependent against the new library generation
            } catch (RuntimeException e) {
                log.warn("worker library-dependent rebind failed for '{}' on port {} — Plan B (sticky): it stays "
                        + "on its prior generation. cause: {}", moduleId, handle.port, e.toString());
            }
        }
    }

    /** Snapshot of the live worker pool taken under the monitor; the HTTP fan-out then runs lock-free. */
    private synchronized List<WorkerHandle> snapshotPool() {
        return List.copyOf(pool);
    }

    /**
     * Runs {@code action} against every worker <b>in parallel on virtual threads</b> (blocking control-plane HTTP),
     * <b>off the isolation monitor</b>. Taking only a pool snapshot under the lock (see {@link #snapshotPool()}) and
     * fanning out lock-free means a slow or hung worker — now bounded by the admin-client request timeout — no longer
     * blocks the other workers or unrelated {@code deploy}/{@code undeploy}/{@code hotSwap}/crash-restart operations.
     * The try-with-resources {@code close()} awaits every task before returning (propagation stays synchronous to its
     * caller). Each {@code action} handles its own failures (log-and-continue / Plan B sticky), so tasks never throw.
     */
    private void fanOut(List<WorkerHandle> targets, Consumer<WorkerHandle> action) {
        if (targets.isEmpty()) {
            return;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (WorkerHandle handle : targets) {
                executor.execute(() -> action.accept(handle));
            }
        }
    }

    // --- debug-launch: spawn a dedicated JDWP-enabled worker → deploy the module → take over the route. Attach is done by the caller (DebugCore). ---

    /**
     * (Re)deploys a module to a <b>dedicated debug worker</b> with JDWP enabled and returns that worker's JDWP and
     * worker ports. Managed only for the session lifetime, outside the normal pool (no crash-restart or warm reuse).
     * If a route was already being routed, it is <b>atomically taken over</b> (zero-downtime) via
     * {@link ReverseProxy#repoint}, recording the original port so {@link #terminateDebugWorker} can restore it.
     * {@link #terminateDebugWorker} must be called when the session ends so the worker and route are cleaned up (leak prevention).
     */
    public synchronized DebugWorkerHandle launchDebugWorker(ModuleDescriptor descriptor) {
        UUID id = UUID.randomUUID();
        Process process = spawnWorker(null, true, id);
        try {
            int[] ports = awaitPortsDebug(process, 60);
            int workerPort = ports[0];
            int jdwpPort = ports[1];
            waitHealthy(workerPort, 30);
            seedParentTier(workerPort);                        // live shared-lib generation, not just the stale boot seed
            ensureUsesClosureByPort(workerPort, descriptor);   // publish the module's uses-closure libraries before it compiles
            List<String> paths = pathsOf(deployToWorker(workerPort, descriptor));
            Map<String, Integer> priorPorts = new LinkedHashMap<>();
            for (String path : paths) {
                Integer prior = proxy.portOf(path);
                if (prior != null) {
                    priorPorts.put(path, prior);   // record the original port before takeover (for restoration)
                }
                proxy.repoint(path, workerPort);   // atomic takeover if present, otherwise a new registration
            }
            DebugWorkerHandle handle = new DebugWorkerHandle(id, process, workerPort, jdwpPort, paths, priorPorts);
            debugWorkers.add(handle);
            if (debugRoutes != null) {
                debugRoutes.add(paths);   // the main ModuleTimeoutFilter skips the watchdog for these paths (allowing breakpoint pauses)
            }
            log.info("debug-launch worker: {} → port {} (JDWP {}), taken-over paths {}",
                    descriptor.id(), workerPort, jdwpPort, paths);
            return handle;
        } catch (RuntimeException e) {
            process.destroyForcibly();
            reaper.forget(id);   // never became a tracked debug worker — drop its marker
            throw e;
        }
    }

    /** Terminates a debug-launch worker — restores the route (repoint to the original port, or unregister if newly added during the session), then kills the process. Idempotent. */
    public synchronized void terminateDebugWorker(DebugWorkerHandle handle) {
        if (handle == null || !debugWorkers.remove(handle)) {
            return;  // already cleaned up (prevents double dispose)
        }
        reaper.forget(handle.id());   // debug workers have no onExit hook — drop the orphan marker on teardown
        if (debugRoutes != null) {
            debugRoutes.remove(handle.paths());   // clear the debug-path exception → the watchdog returns to normal
        }
        for (String path : handle.paths()) {
            Integer prior = handle.priorPorts().get(path);
            if (prior != null) {
                proxy.repoint(path, prior);   // restore to the original worker
            } else {
                proxy.unregister(path);       // remove a path newly registered during the session
            }
        }
        handle.process().destroyForcibly();
        log.info("debug-launch worker terminated: port {} (restored paths {})", handle.workerPort(), handle.paths());
    }

    /** Parses both the worker port (WORKER_PORT=) and the JDWP listen port from the debug worker's stdout with <b>a single drainer</b>. */
    private int[] awaitPortsDebug(Process process, int timeoutSec) {
        CompletableFuture<Integer> workerFuture = new CompletableFuture<>();
        CompletableFuture<Integer> jdwpFuture = new CompletableFuture<>();
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!workerFuture.isDone() && trimmed.startsWith(WorkerPortAnnouncer.MARKER)) {
                        workerFuture.complete(Integer.parseInt(
                                trimmed.substring(WorkerPortAnnouncer.MARKER.length())));
                    } else if (!jdwpFuture.isDone() && trimmed.contains("Listening for transport")) {
                        Matcher m = JDWP_LISTENING.matcher(trimmed);
                        if (m.find()) {
                            jdwpFuture.complete(Integer.parseInt(m.group(1)));
                        }
                    }
                }
                // stdout closed before both ports were filled (worker exited early) → notify the waiters of failure
                workerFuture.completeExceptionally(new IllegalStateException("debug worker stdout closed — WORKER_PORT not received"));
                jdwpFuture.completeExceptionally(new IllegalStateException("debug worker stdout closed — JDWP listen not received"));
            } catch (Exception e) {
                workerFuture.completeExceptionally(e);
                jdwpFuture.completeExceptionally(e);
            }
        }, "debug-worker-stdout-drainer");
        drainer.setDaemon(true);
        drainer.start();
        try {
            int jdwp = jdwpFuture.get(timeoutSec, TimeUnit.SECONDS);   // printed first during JVM init
            int worker = workerFuture.get(timeoutSec, TimeUnit.SECONDS);
            return new int[]{worker, jdwp};
        } catch (TimeoutException e) {
            throw new IllegalStateException("debug worker port handshake timeout", e);
        } catch (Exception e) {
            throw new IllegalStateException("debug worker port handshake failed", e);
        }
    }
}
