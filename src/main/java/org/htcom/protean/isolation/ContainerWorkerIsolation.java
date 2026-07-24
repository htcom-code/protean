/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.db.DbScope;
import org.htcom.protean.db.DbScopeProvisioner;
import org.htcom.protean.db.ScopeManager;
import org.htcom.protean.dynamic.DynamicEndpointRegistrar.RouteInfo;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.SharedLibStore;
import org.htcom.protean.proxy.ReverseProxy;
import org.htcom.protean.worker.WorkerSharedLibReceiver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * OS-isolation worker strategy (mode="container") — launches the worker as a Docker container confined by
 * cgroup/read-only/cap-drop/seccomp. Strong isolation (baseline for untrusted tiers). Blocks host-resource, file,
 * and syscall violations at the OS level that a worker process alone cannot prevent.
 *
 * <p>Production hardening: <b>atomic zero-downtime hot-swap</b> (fully start the new container, then repoint the
 * proxy and retire the old container) + <b>security hardening</b> (no-new-privileges, pids-limit, egress network,
 * seccomp profile) + <b>supervision/auto-restart</b> (detect container crash → redeploy, opt-in).
 *
 * <p>seccomp: when {@code protean.worker.container.seccomp} is {@code "bundled"}, the bundled default profile on the
 * classpath ({@code /seccomp/protean-default.json}, defaultAction=ALLOW + EPERM on dangerous syscalls) is extracted
 * to a temp file and applied; any other value is passed through as a user profile file path (empty = docker default).
 * Runtime enforcement is demonstrated by ContainerSeccompTest (symlinkat blocked, mkdirat allowed).
 *
 * <p><b>Pooling / isolation boundary</b>: a container hosts up to {@code worker.modules-per-worker} modules, packed by
 * scope — the isolation boundary is the <b>scope</b> (tenant/business-domain), not the individual module. Modules of
 * the same scope trust each other and share their scope's container(s) and provisioned database; different scopes get
 * separate containers (OS isolation between tenants). This mirrors the process-worker pool and lets container mode
 * scale to many modules across a handful of scopes. Set {@code worker.modules-per-worker=1} for the strict
 * one-container-per-module boundary (maximum isolation, no packing). Without auto-provision, modules share a general
 * pool (scope is not tracked) up to the same cap.
 */
@Component
@Profile("!worker")
public class ContainerWorkerIsolation implements IsolationStrategy, WorkerParentTierTarget,
        org.htcom.protean.db.ScopeReclaimable {

    private static final Logger log = LoggerFactory.getLogger(ContainerWorkerIsolation.class);
    private static final int CONTAINER_PORT = 8080;
    private static final String NAME_PREFIX = "protean-worker-";
    /** When protean.worker.container.seccomp equals this value, use the bundled default profile (resource extracted to a temp file). */
    private static final String BUNDLED_SECCOMP = "bundled";
    private static final String BUNDLED_SECCOMP_RESOURCE = "/seccomp/protean-default.json";
    /** In-container mount target for the shared-lib dir. The host path is bind-mounted here (read-only). */
    private static final String CONTAINER_SHARED_LIB = "/shared-lib";

    /**
     * A running container hosting one or more modules of a single scope. {@code retiring} = intentional-shutdown flag
     * (distinguished from a crash — the supervisor does not restart it). Per-module routes/descriptors are tracked in
     * the isolation-level maps ({@link #moduleToContainer}/{@link #modulePaths}/{@link #moduleDescriptors}).
     */
    private static final class Container {
        final String name;
        final int hostPort;
        /** The DB scope this container is bound to (its injected datasource creds); null when auto-provision is off. */
        final String scope;
        final Set<String> modules = ConcurrentHashMap.newKeySet();
        /** Library-module ids published into this container's parent tier (the modules' {@code uses} closure). */
        final Set<String> libraries = ConcurrentHashMap.newKeySet();
        volatile boolean retiring;

        Container(String name, int hostPort, String scope) {
            this.name = name;
            this.hostPort = hostPort;
            this.scope = scope;
        }
    }

    private final ReverseProxy proxy;
    private final ObjectMapper mapper;
    private final WorkerRuntimeProvider runtimeProvider;
    private final WorkerAdminClient admin;
    private final HttpClient client;

    private final List<Container> pool = new ArrayList<>();               // guarded by this
    private final Map<String, Container> moduleToContainer = new ConcurrentHashMap<>();
    private final Map<String, List<String>> modulePaths = new ConcurrentHashMap<>();
    private final Map<String, ModuleDescriptor> moduleDescriptors = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    /**
     * Live properties. Read per operation so runtime changes apply: {@code worker.container.auto-restart} (Tier 1,
     * per crash) and {@code worker.container.*} spawn settings — memory/pids-limit/network/seccomp/db-host (Tier 2,
     * per container spawn). {@code worker.modules-per-worker} (Tier 2, per spawn) sets the packing capacity.
     */
    private final ProteanProperties props;
    /** Present only when auto-provision is enabled (null otherwise). Auto-provisions an isolated DB scope per scope. */
    private final DbScopeProvisioner provisioner;
    /** Shared-lib directory on the host. When set, it is bind-mounted read-only into the container and passed to the worker
     * (unlike the process worker, the container has a separate FS namespace, so the in-container path differs from the host path). */
    private final String sharedLibDir;
    /** DB scope provisioned per scope name (shared by all modules/containers of that scope). */
    private final Map<String, DbScope> scopeProvisions = new ConcurrentHashMap<>();
    /** Scope registry facade (allowlist + persistence); null when host beans are absent. */
    private final ScopeManager scopeManager;
    /** Worker /__admin/* auth (opt-in). When enabled the secret is injected into every spawned container so its
     * WorkerAdminAuthFilter verifies the main's admin calls; the secret is null when disabled. */
    private final boolean adminAuthEnabled;
    private final String adminAuthSecret;
    private final String adminAuthMode;

    public ContainerWorkerIsolation(HttpClient client, ReverseProxy proxy, ObjectMapper mapper,
                                    WorkerRuntimeProvider runtimeProvider,
                                    ObjectProvider<DbScopeProvisioner> provisionerProvider,
                                    ObjectProvider<ScopeManager> scopeManagerProvider,
                                    ObjectProvider<WorkerAdminSecretHolder> adminSecretProvider,
                                    WorkerAdminClient admin,
                                    ProteanProperties props) {
        this.client = client;
        this.proxy = proxy;
        this.mapper = mapper;
        this.runtimeProvider = runtimeProvider;
        this.provisioner = provisionerProvider.getIfAvailable();
        this.scopeManager = scopeManagerProvider.getIfAvailable();
        this.adminAuthEnabled = props.getWorker().getAdminAuth().isEnabled();
        WorkerAdminSecretHolder adminSecretHolder = adminSecretProvider.getIfAvailable();
        this.adminAuthSecret = adminSecretHolder != null ? adminSecretHolder.token() : null;
        this.adminAuthMode = props.getWorker().getAdminAuth().getMode();
        this.admin = admin;
        this.props = props;
        this.sharedLibDir = props.getModule().getSharedLibDir();
    }

    private ProteanProperties.Container containerCfg() {
        return props.getWorker().getContainer();
    }

    /** Modules per container, read live (Tier 2 — applies to the next spawn). 1 = strict one-container-per-module. */
    private int capacity() {
        return Math.max(1, props.getWorker().getModulesPerWorker());
    }

    @Override
    public String mode() {
        return "container";
    }

    @Override
    public boolean supports(ModuleDescriptor descriptor) {
        return !descriptor.needsSharedBeans();  // container track: RPC bridge not supported
    }

    @Override
    public synchronized void deploy(ModuleDescriptor descriptor) {
        if (moduleToContainer.containsKey(descriptor.id())) {
            throw new IllegalStateException("container module already deployed: " + descriptor.id());
        }
        Container c = acquireContainerFor(resolveScope(descriptor), null);
        List<String> paths;
        try {
            ensureUsesClosure(c, descriptor);   // publish the module's library `uses` closure into this container first
            List<RouteInfo> routes = deployToWorker(c.hostPort, descriptor);
            for (RouteInfo route : routes) {
                for (String path : route.patterns()) {
                    proxy.register(path, route.methods(), c.hostPort, descriptor.id());
                }
            }
            paths = pathsOf(routes);
        } catch (RuntimeException e) {
            retireIfEmpty(c);   // spawned for this deploy and failed → do not leak an empty container
            throw new IllegalStateException("container worker deploy failed: " + descriptor.id(), e);
        }
        c.modules.add(descriptor.id());
        moduleToContainer.put(descriptor.id(), c);
        modulePaths.put(descriptor.id(), paths);
        moduleDescriptors.put(descriptor.id(), descriptor);
        log.info("container module deploy: {} → {} (host port {}, {} containers, {} modules in this container)",
                descriptor.id(), c.name, c.hostPort, pool.size(), c.modules.size());
    }

    /**
     * Zero-downtime hot-swap: fully start the new version in a container that does not already host this module (a
     * fresh one when none has spare capacity), then <b>atomically repoint</b> the proxy and drain the old version from
     * its prior container. There is no 404/502 window where the route is broken.
     */
    @Override
    public synchronized void hotSwap(ModuleDescriptor descriptor) {
        Container oldC = moduleToContainer.get(descriptor.id());
        if (oldC == null) {
            deploy(descriptor);
            return;
        }
        List<String> oldPaths = modulePaths.get(descriptor.id());
        Container newC = acquireContainerFor(resolveScope(descriptor), descriptor.id());
        try {
            ensureUsesClosure(newC, descriptor);
            List<RouteInfo> newRoutes = deployToWorker(newC.hostPort, descriptor);
            for (RouteInfo route : newRoutes) {
                for (String path : route.patterns()) {
                    proxy.repoint(path, route.methods(), newC.hostPort);  // atomic switch + refresh methods (version may change them)
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
            newC.modules.add(descriptor.id());
            moduleToContainer.put(descriptor.id(), newC);
            modulePaths.put(descriptor.id(), newPaths);
            moduleDescriptors.put(descriptor.id(), descriptor);
            oldC.modules.remove(descriptor.id());
        } catch (RuntimeException e) {
            retireIfEmpty(newC);   // v2 preparation failed → keep the old version (rollback)
            throw new IllegalStateException("container hot-swap failed (previous version retained): " + descriptor.id(), e);
        }
        scheduleDrainCleanup(oldC, descriptor.id());
        log.info("container zero-downtime hot-swap: {} → {} (draining old {})", descriptor.id(), newC.name, oldC.name);
    }

    @Override
    public synchronized void undeploy(String moduleId) {
        Container c = moduleToContainer.remove(moduleId);
        if (c == null) {
            return;
        }
        List<String> paths = modulePaths.remove(moduleId);
        moduleDescriptors.remove(moduleId);
        if (paths != null) {
            for (String path : paths) {
                proxy.unregister(path);
            }
        }
        c.modules.remove(moduleId);
        if (c.modules.isEmpty()) {
            retire(c);   // last module gone → tear the container down (containers are heavy; no warm pool)
        } else {
            postUndeploy(c.hostPort, moduleId);   // keep the container, release only this module
        }
        // Scope model: a scope's DB is shared and outlives modules — undeploy never deprovisions it (operator-driven).
    }

    /** For tests: the cgroup memory limit (bytes) applied to the module's container. */
    public long inspectMemoryLimit(String moduleId) {
        return Long.parseLong(inspect(moduleId, "{{.HostConfig.Memory}}"));
    }

    /** For tests: the container PID limit (fork-bomb defense). */
    public long inspectPidsLimit(String moduleId) {
        return Long.parseLong(inspect(moduleId, "{{.HostConfig.PidsLimit}}"));
    }

    /** For tests: the applied security-opt list (no-new-privileges/seccomp verification). */
    public String inspectSecurityOpt(String moduleId) {
        return inspect(moduleId, "{{.HostConfig.SecurityOpt}}").trim();
    }

    /** For tests: the container name hosting the module (for supervision/crash simulation). */
    public String containerName(String moduleId) {
        Container c = moduleToContainer.get(moduleId);
        return c == null ? null : c.name;
    }

    /** For tests: number of live containers in the pool. */
    public synchronized int containerCount() {
        return pool.size();
    }

    /**
     * If the JDBC URL host is localhost/127.0.0.1, replace it with newHost (keeping port/path/query).
     * This lets the container reach the host's published port. A real remote host is left unchanged.
     */
    public static String rewriteHost(String url, String newHost) {
        if (newHost == null || newHost.isBlank()) {
            return url;
        }
        int schemeEnd = url.indexOf("://") + 3;
        if (schemeEnd < 3) {
            return url;
        }
        int authEnd = url.indexOf('/', schemeEnd);
        if (authEnd < 0) {
            authEnd = url.length();
        }
        String authority = url.substring(schemeEnd, authEnd);   // host[:port]
        int colon = authority.indexOf(':');
        String host = colon < 0 ? authority : authority.substring(0, colon);
        String portPart = colon < 0 ? "" : authority.substring(colon);
        if (!host.equals("localhost") && !host.equals("127.0.0.1")) {
            return url;
        }
        return url.substring(0, schemeEnd) + newHost + portPart + url.substring(authEnd);
    }

    // --- container pool ---

    /**
     * Resolves and validates the scope a module binds to under auto-provision (returns null when auto-provision is
     * off, warning if a scope was declared anyway). Mirrors {@link WorkerProcessIsolation#resolveScope}.
     */
    private String resolveScope(ModuleDescriptor descriptor) {
        if (provisioner == null) {
            String declared = descriptor.scope();
            if (declared != null && !declared.isBlank()) {
                log.warn("module '{}' declares scope '{}' but worker.db.auto-provision is off — the scope is ignored "
                        + "(scopes apply only under auto-provision)", descriptor.id(), declared);
            }
            return null;
        }
        String scope = descriptor.scope();
        if (scope == null || scope.isBlank()) {
            throw new IllegalStateException("auto-provision is on: module '" + descriptor.id()
                    + "' must declare a scope (see protean.worker.db.scopes / scope admin API)");
        }
        if (scopeManager != null && !scopeManager.isDeployable(scope)) {
            String detail = scopeManager.isKnown(scope)
                    ? "scope '" + scope + "' is not ACTIVE (closed/detached) — reopen it via the scope admin API"
                    : "unknown scope '" + scope + "' — declare it in protean.worker.db.scopes or create it via the scope admin API";
            throw new IllegalStateException(detail + " (module '" + descriptor.id() + "')");
        }
        return scope;
    }

    /**
     * Assigns a container to a module. Under auto-provision, provisions the scope's isolated DB (once per scope) and
     * packs same-scope modules into that scope's container(s) up to {@link #capacity()}; otherwise uses a general pool.
     * Spawns a new container when none of the eligible ones has spare capacity.
     */
    private Container acquireContainerFor(String scopeName, String excludeModuleId) {
        if (provisioner != null) {
            DbScope scope = scopeProvisions.computeIfAbsent(scopeName, name -> {
                DbScope s = provisioner.provision(name);
                if (scopeManager != null) {
                    scopeManager.markActive(name, provisioner.dialect().id());
                }
                return s;
            });
            for (Container c : pool) {
                if (!c.retiring && scopeName.equals(c.scope) && c.modules.size() < capacity()
                        && (excludeModuleId == null || !c.modules.contains(excludeModuleId))) {
                    return c;
                }
            }
            Container spawned = spawnContainer(scope, scopeName);
            pool.add(spawned);
            return spawned;
        }
        for (Container c : pool) {
            if (!c.retiring && c.modules.size() < capacity()
                    && (excludeModuleId == null || !c.modules.contains(excludeModuleId))) {
                return c;
            }
        }
        Container spawned = spawnContainer(null, null);
        pool.add(spawned);
        return spawned;
    }

    /** {@link org.htcom.protean.db.ScopeReclaimable}: drop the cached provision so a later deploy re-provisions the scope. */
    @Override
    public synchronized void forgetScope(String scopeName) {
        scopeProvisions.remove(scopeName);
    }

    private void retire(Container c) {
        c.retiring = true;   // intentional shutdown — the watcher will not treat the exit as a crash
        pool.remove(c);
        dockerRemoveQuiet(c.name);
    }

    private void retireIfEmpty(Container c) {
        if (c.modules.isEmpty()) {
            retire(c);
        }
    }

    /**
     * After a hot-swap, drain the module from the old container. Called while holding the isolation monitor (from
     * {@link #hotSwap}). If the old container is left empty it is marked retiring and pulled from the pool
     * <b>immediately</b>, so a concurrent deploy cannot reuse a container we are about to remove (the earlier bug: the
     * async remove killed a container a new deploy had just acquired → worker /__admin/deploy 500). The actual
     * {@code docker rm} is deferred by the grace period to let in-flight requests to the old version drain.
     */
    private void scheduleDrainCleanup(Container oldC, String moduleId) {
        boolean retireAfterGrace = oldC.modules.isEmpty();
        if (retireAfterGrace) {
            oldC.retiring = true;   // stop reuse now; defer only the docker rm
            pool.remove(oldC);
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (retireAfterGrace) {
                dockerRemoveQuiet(oldC.name);
            } else {
                synchronized (this) {
                    postUndeploy(oldC.hostPort, moduleId);
                }
            }
        }, "container-drain-kill");
        t.setDaemon(true);
        t.start();
    }

    // --- container process/communication ---

    /**
     * Starts an empty worker container (bound to {@code scope}'s DB when provided), waits for health, and seeds the
     * current live parent tier — then returns it ready for modules to deploy into. Mirrors
     * {@link WorkerProcessIsolation#spawnAndReady}.
     */
    private Container spawnContainer(DbScope scope, String scopeName) {
        String name = NAME_PREFIX + nameKey(scopeName) + "-" + seq.incrementAndGet();
        // A main that exited uncleanly (crash/kill -9) leaves detached containers running; the seq counter
        // resets on restart, so reconcile re-derives this exact name and `docker run --name` would fail with a
        // 125 name conflict — skipping the module and 404-ing its route. Remove any stale same-name container
        // first. No-op for a fresh name; hot-swap spawns with a new seq, so a draining old container is never hit.
        dockerRemoveQuiet(name);
        // Spawn settings read live (Tier 2 — apply to the next container spawn, not running ones).
        ProteanProperties.Container c = containerCfg();
        String network = c.getNetwork();
        String seccompProfile = resolveSeccompProfile(c.getSeccomp());
        // Strong isolation: cgroup memory cap + read-only rootfs + tmpfs /tmp + drop all capabilities
        //  + no-new-privileges (block privilege escalation) + pids-limit (fork-bomb defense) [+ egress network/seccomp options]
        List<String> run = new ArrayList<>(List.of("docker", "run", "-d", "--rm", "--name", name,
                "--memory=" + c.getMemory(), "--read-only", "--tmpfs", "/tmp",
                "--cap-drop=ALL", "--security-opt", "no-new-privileges",
                "--pids-limit=" + c.getPidsLimit()));
        if ("host.docker.internal".equals(c.getDbHost())) {
            // On Linux Docker Engine host.docker.internal is not resolvable by default (Docker Desktop on
            // macOS/Windows provides it automatically); map it to the host gateway so a container worker can
            // reach a host-provisioned DB (worker.db.auto-provision). Harmless where it already resolves.
            run.add("--add-host=host.docker.internal:host-gateway");
        }
        if (!network.isBlank()) {
            run.add("--network=" + network);  // egress isolation: e.g. an internal network with internet blocked
        }
        if (!seccompProfile.isBlank()) {
            run.add("--security-opt");
            run.add("seccomp=" + seccompProfile);  // if unspecified, docker's default seccomp applies
        }
        if (sharedLibDir != null && !sharedLibDir.isBlank()) {
            // Bind-mount the host shared-lib dir read-only (rootfs is --read-only, so ro is consistent with hardening).
            // The worker (a protean app in in-process mode) reads the in-container path via ModuleSharedLibs (arg below).
            run.add("-v");
            run.add(Path.of(sharedLibDir).toAbsolutePath() + ":" + CONTAINER_SHARED_LIB + ":ro");
        }
        // The image, mounts, and in-container command prefix (embed = bootJar-explode mount / sidecar = dedicated image) are decided by the provider.
        WorkerRuntimeProvider.ContainerLaunchSpec spec = runtimeProvider.containerLaunchSpec();
        run.add("-p");
        // Publish only to the host loopback: the main reaches the worker admin/route plane via localhost, but the
        // published port is not bound on all host interfaces (0.0.0.0), so it is not reachable from other hosts —
        // matching the process worker's host-only exposure. (Auth on /__admin/* is the opt-in second layer.)
        run.add("127.0.0.1:0:" + CONTAINER_PORT);
        run.addAll(spec.mountArgs());
        run.add(spec.image());
        run.addAll(spec.commandPrefix());
        run.addAll(List.of(
                "--spring.profiles.active=worker", "--protean.isolation.mode=in-process",
                "--server.port=" + CONTAINER_PORT, "--server.address=0.0.0.0"));
        if (sharedLibDir != null && !sharedLibDir.isBlank()) {
            // Point the worker's ModuleSharedLibs at the mounted in-container path (not the host path).
            run.add("--protean.module.shared-lib-dir=" + CONTAINER_SHARED_LIB);
        }
        if (adminAuthEnabled && adminAuthSecret != null) {
            // Turn on the container worker's /__admin/* auth filter and hand it the shared secret (docker CMD args,
            // as with the process worker's bridge secret). Matters most here: the published port is host-reachable.
            run.add("--protean.worker.admin-auth.enabled=true");
            run.add("--protean.worker.admin-auth.secret=" + adminAuthSecret);
            run.add("--protean.worker.admin-auth.mode=" + adminAuthMode);
        }
        if (scope != null) {
            // Inside a container localhost is the container itself → rewrite the DB host to db-host (read live) before passing it.
            run.add("--spring.datasource.url=" + rewriteHost(scope.url(), c.getDbHost()));
            run.add("--spring.datasource.username=" + scope.username());
            run.add("--spring.datasource.password=" + scope.password());
        }
        exec(run, 120);

        try {
            int hostPort = discoverHostPort(name);
            waitHealthy(hostPort, 90);
            // Seed the container's parent tier with the current live generation before any module deploys — the
            // read-only /shared-lib mount only carries the boot seed. Every spawn (deploy/hot-swap/crash) goes through here.
            admin.seedSharedLibs(hostPort);
            Container container = new Container(name, hostPort, scopeName);
            startWatcher(container);
            return container;
        } catch (RuntimeException e) {
            dockerRemoveQuiet(name);
            throw new IllegalStateException("container worker spawn failed (scope=" + scopeName + ")", e);
        }
    }

    /** Docker-name-safe fragment for a scope (or "pool" for the no-auto-provision general pool). */
    private static String nameKey(String scopeName) {
        if (scopeName == null || scopeName.isBlank()) {
            return "pool";
        }
        return scopeName.replaceAll("[^a-zA-Z0-9_.-]", "-");
    }

    private List<RouteInfo> deployToWorker(int port, ModuleDescriptor descriptor) {
        // Routed through WorkerAdminClient so the outgoing /__admin/deploy carries admin-auth when enabled (single
        // signing point), identical POST /__admin/deploy → registered-routes call as the inline version.
        return admin.deploy(port, descriptor);
    }

    private void postUndeploy(int port, String moduleId) {
        try {
            admin.undeploy(port, moduleId);
        } catch (RuntimeException e) {
            log.warn("container undeploy request failed (ignored): {} port {}", moduleId, port);
        }
    }

    /** Flattens routes to their path patterns (bookkeeping for repoint/unregister). */
    private static List<String> pathsOf(List<RouteInfo> routes) {
        List<String> paths = new ArrayList<>();
        for (RouteInfo r : routes) {
            paths.addAll(r.patterns());
        }
        return paths;
    }

    /**
     * Publishes every library in a module's transitive {@code uses} closure into the container (dependency-first)
     * before the module deploys; a library already present is skipped. Mirrors {@link WorkerProcessIsolation#ensureUsesClosure}.
     */
    private void ensureUsesClosure(Container c, ModuleDescriptor module) {
        for (ModuleDescriptor library : admin.usesClosure(module)) {
            if (c.libraries.contains(library.id())) {
                continue;
            }
            admin.deploy(c.hostPort, library);   // publishes the library generation inside the container (no routes)
            c.libraries.add(library.id());
        }
    }

    // --- supervision (crash → restart) ---

    /** Watches for container exit; whether a crash triggers a redeploy is decided live in {@link #onContainerExit}. */
    private void startWatcher(Container c) {
        Thread t = new Thread(() -> {
            try {
                // docker wait blocks until the container stops → when it returns, the container has exited
                Process p = new ProcessBuilder("docker", "wait", c.name).redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // docker missing/already removed etc. — decided below in onExit based on the retiring flag
            }
            onContainerExit(c);
        }, "container-watch-" + c.name);
        t.setDaemon(true);
        t.start();
    }

    /** Container-exit callback. Ignored on an intentional shutdown (retiring); on a crash, restart every module it hosted. */
    private synchronized void onContainerExit(Container dead) {
        pool.remove(dead);
        if (dead.retiring) {
            return;
        }
        if (!props.getWorker().getContainer().isAutoRestart()) {
            return;  // auto-restart disabled (read live) — leave the crashed modules down
        }
        List<String> affected = new ArrayList<>(dead.modules);
        log.warn("container crash detected: {} (port {}) — restarting modules {}", dead.name, dead.hostPort, affected);
        for (String moduleId : affected) {
            if (moduleToContainer.get(moduleId) != dead) {
                continue;  // already moved to another container (hot-swap etc.)
            }
            try {
                redeployAfterCrash(moduleId);
            } catch (RuntimeException e) {
                log.error("container crashed module restart failed: {} - {}", moduleId, e.getMessage());
            }
        }
    }

    /** Redeploys a crashed module to a container and switches the proxy to the new port. */
    private void redeployAfterCrash(String moduleId) {
        ModuleDescriptor descriptor = moduleDescriptors.get(moduleId);
        if (descriptor == null) {
            return;
        }
        Container c = acquireContainerFor(resolveScope(descriptor), null);
        ensureUsesClosure(c, descriptor);
        List<String> paths = pathsOf(deployToWorker(c.hostPort, descriptor));
        for (String path : paths) {
            proxy.repoint(path, c.hostPort);  // keep the route (methods unchanged — same module), switch to the new port
        }
        c.modules.add(moduleId);
        moduleToContainer.put(moduleId, c);
        modulePaths.put(moduleId, paths);
        log.info("container crash recovery: {} → {} (host port {})", moduleId, c.name, c.hostPort);
    }

    /**
     * On graceful main shutdown, retire every container this instance owns. Detached containers (`docker run -d`)
     * outlive the main otherwise, and the next start's reconcile would collide on their names (see
     * {@link #spawnContainer}). This closes the normal-restart path cleanly; the {@code spawnContainer} same-name
     * removal remains the safety net for an unclean exit where this hook never runs.
     */
    @PreDestroy
    synchronized void shutdown() {
        for (Container c : List.copyOf(pool)) {
            retire(c);
        }
    }

    // --- live parent-tier propagation to running containers ---

    @Override
    public void pushSharedLibGeneration(List<SharedLibStore.IncomingLib> bundle, Set<String> changedJars) {
        fanOut(snapshotContainers(), c -> pushSharedLibGenerationToContainer(c, bundle, changedJars));
    }

    private void pushSharedLibGenerationToContainer(Container c,
                                                    List<SharedLibStore.IncomingLib> bundle, Set<String> changedJars) {
        if (c.retiring) {
            return;
        }
        WorkerSharedLibReceiver.PublishResult result;
        try {
            result = admin.pushSharedLibs(c.hostPort, bundle, changedJars);
        } catch (RuntimeException e) {
            log.warn("shared-lib generation push to container {} (port {}) failed (skipped): {}",
                    c.name, c.hostPort, e.toString());
            return;
        }
        for (String moduleId : result.affectedModuleIds()) {
            ModuleDescriptor descriptor = moduleDescriptors.get(moduleId);
            if (descriptor == null) {
                continue;
            }
            try {
                admin.redeploy(c.hostPort, descriptor);   // Plan A2: in-place recompile against the new generation
            } catch (RuntimeException e) {
                log.warn("container shared-lib rebind failed for '{}' in {} — Plan B (sticky): it stays on its "
                        + "prior generation. cause: {}", moduleId, c.name, e.toString());
            }
        }
    }

    @Override
    public void propagateLibraryUpdate(ModuleDescriptor library) {
        fanOut(snapshotContainers(), c -> propagateLibraryUpdateToContainer(c, library));
    }

    private void propagateLibraryUpdateToContainer(Container c, ModuleDescriptor library) {
        if (c.retiring || !c.libraries.contains(library.id())) {
            return;
        }
        try {
            admin.redeploy(c.hostPort, library);   // the container republishes the library → new generation
        } catch (RuntimeException e) {
            log.warn("container library republish failed for '{}' in {} — Plan B: dependents stay on the prior "
                    + "generation. cause: {}", library.id(), c.name, e.toString());
            return;
        }
        for (String moduleId : List.copyOf(c.modules)) {
            ModuleDescriptor dependent = moduleDescriptors.get(moduleId);
            if (dependent == null || !admin.usesTransitively(dependent, library.id())) {
                continue;
            }
            try {
                admin.redeploy(c.hostPort, dependent);   // recompile the dependent against the new library gen
            } catch (RuntimeException e) {
                log.warn("container library-dependent rebind failed for '{}' in {} — Plan B (sticky): it stays "
                        + "on its prior generation. cause: {}", moduleId, c.name, e.toString());
            }
        }
    }

    /** Snapshot of the live containers taken under the monitor; the HTTP fan-out then runs lock-free. */
    private synchronized List<Container> snapshotContainers() {
        return List.copyOf(pool);
    }

    /**
     * Runs {@code action} against every container <b>in parallel on virtual threads</b> (blocking control-plane HTTP,
     * which for the container strategy also fronts Docker-side work), <b>off the isolation monitor</b>. Taking only a
     * snapshot under the lock (see {@link #snapshotContainers()}) and fanning out lock-free means a slow or hung
     * container — now bounded by the admin-client request timeout — no longer blocks the others or unrelated
     * {@code deploy}/{@code undeploy}/{@code hotSwap}/crash-restart operations. The try-with-resources {@code close()}
     * awaits every task (propagation stays synchronous to its caller). Each {@code action} handles its own failures
     * (log-and-continue / Plan B sticky), so tasks never throw.
     */
    private void fanOut(List<Container> targets, Consumer<Container> action) {
        if (targets.isEmpty()) {
            return;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Container c : targets) {
                executor.execute(() -> action.accept(c));
            }
        }
    }

    // --- internal ---

    /**
     * Resolves the seccomp config value to the actual path passed to docker.
     * When {@code "bundled"}, the bundled default profile on the classpath is extracted to a temp file and its
     * absolute path is used; any other non-empty value is passed through as the file path given by the user
     * (empty = docker default profile).
     */
    private static String resolveSeccompProfile(String configured) {
        if (configured == null || configured.isBlank() || !configured.equalsIgnoreCase(BUNDLED_SECCOMP)) {
            return configured == null ? "" : configured;
        }
        try (InputStream in = ContainerWorkerIsolation.class.getResourceAsStream(BUNDLED_SECCOMP_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("bundled seccomp profile resource not found: " + BUNDLED_SECCOMP_RESOURCE);
            }
            // The docker daemon must read it, so it must be a filesystem path, not a classpath resource → extract to a temp file.
            Path tmp = Files.createTempFile("protean-seccomp-", ".json");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tmp.toAbsolutePath().toString();
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("failed to extract bundled seccomp profile", e);
        }
    }

    private int discoverHostPort(String name) {
        // "0.0.0.0:55001" or "[::]:55001\n0.0.0.0:55001" → port after the last colon
        String out = exec(List.of("docker", "port", name, String.valueOf(CONTAINER_PORT)), 20);
        for (String line : out.split("\\R")) {
            int colon = line.lastIndexOf(':');
            if (colon > 0) {
                try {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        throw new IllegalStateException("failed to resolve container port: " + out);
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
            }
            sleep(200);
        }
        throw new IllegalStateException("container worker health timeout: port " + port);
    }

    private String inspect(String moduleId, String format) {
        Container c = moduleToContainer.get(moduleId);
        if (c == null) {
            return "-1";
        }
        return exec(List.of("docker", "inspect", "--format", format, c.name), 20).trim();
    }

    private void dockerRemoveQuiet(String name) {
        try {
            exec(List.of("docker", "rm", "-f", name), 20);
        } catch (RuntimeException ignored) {
        }
    }

    private String exec(List<String> command, int timeoutSec) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("docker command timeout: " + command);
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("docker command failed(" + p.exitValue() + "): " + command + "\n" + out);
            }
            return out.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker command interrupted: " + command, e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("docker execution failed (check installation/PATH): " + command, e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
