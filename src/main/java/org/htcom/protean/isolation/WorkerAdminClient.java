/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.bridge.BridgeHmac;
import org.htcom.protean.dynamic.DynamicEndpointRegistrar;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleStore;
import org.htcom.protean.module.SharedLibStore;
import org.htcom.protean.worker.WorkerSharedLibReceiver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * The main-side client for a worker JVM's parent-tier control plane. It concentrates
 * the {@code /__admin/*} push HTTP and the {@code uses}-closure computation shared by both worker strategies — the
 * in-process {@link WorkerProcessIsolation} (localhost port) and the OS-isolated {@link ContainerWorkerIsolation}
 * (container published port) — so a worker's parent tier is driven identically whichever way it is isolated.
 *
 * <p>Stateless: per-handle tracking (which worker hosts which module/library) stays in each strategy; this client only
 * performs port-addressed operations and reads the main-only stores it is given.
 */
@Component
@Profile("!worker")
public class WorkerAdminClient {

    /**
     * Per-request ceiling on a worker control-plane call. The heaviest operation is a redeploy that recompiles a
     * module inside the worker, so this is generous; its purpose is to bound a <b>hung</b> worker (one that accepts the
     * connection but never responds) to a finite failure instead of blocking the caller thread forever. With the
     * lock-free parallel fan-out in the isolation strategies, a hung worker then neither stalls the other workers nor
     * unrelated deploy/undeploy operations, and self-heals once this elapses (Plan B sticky on the affected module).
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final ObjectProvider<SharedLibStore> sharedLibStore;
    private final ObjectProvider<ModuleStore> moduleStore;
    private final ProteanProperties props;
    /** Present only when protean.worker.admin-auth.enabled=true (opt-in); null otherwise, and admin calls go unsigned. */
    private final ObjectProvider<WorkerAdminSecretHolder> adminSecret;
    private final SecureRandom nonces = new SecureRandom();

    public WorkerAdminClient(HttpClient http, ObjectMapper mapper, ObjectProvider<SharedLibStore> sharedLibStore,
                             ObjectProvider<ModuleStore> moduleStore, ProteanProperties props,
                             ObjectProvider<WorkerAdminSecretHolder> adminSecret) {
        this.http = http;
        this.mapper = mapper;
        this.sharedLibStore = sharedLibStore;
        this.moduleStore = moduleStore;
        this.props = props;
        this.adminSecret = adminSecret;
    }

    // --- shared-lib generation push ---

    /** The current live store jars (bytes) to push to workers, or empty when nothing was uploaded at runtime. */
    public List<SharedLibStore.IncomingLib> currentBundle() {
        SharedLibStore store = sharedLibStore.getIfAvailable();
        return store == null ? List.of() : store.pushBundle();
    }

    /**
     * Seeds a freshly started worker at {@code port} with the current live shared-lib generation before any module
     * deploys, so it starts on the current parent tier rather than the stale boot seed. No-op when the store is
     * unavailable or empty (the boot seed dir already covers the empty case). No modules exist yet → no rebind.
     */
    public void seedSharedLibs(int port) {
        List<SharedLibStore.IncomingLib> bundle = currentBundle();
        if (!bundle.isEmpty()) {
            pushSharedLibs(port, bundle, List.of());
        }
    }

    /** POST {@code /__admin/shared-libs}: publish the bundle as a new generation in the worker; returns its ack. */
    public WorkerSharedLibReceiver.PublishResult pushSharedLibs(int port, List<SharedLibStore.IncomingLib> bundle,
                                                                Collection<String> changedJars) {
        List<WorkerSharedLibReceiver.IncomingJar> jars = bundle.stream()
                .map(lib -> new WorkerSharedLibReceiver.IncomingJar(lib.name(), lib.bytes()))
                .toList();
        WorkerSharedLibReceiver.PushRequest request =
                new WorkerSharedLibReceiver.PushRequest(jars, List.copyOf(changedJars));
        return post(port, "/__admin/shared-libs", request, WorkerSharedLibReceiver.PublishResult.class);
    }

    // --- module / library deploy + in-place rebind ---

    /** POST {@code /__admin/deploy}: deploy a module (or publish a library) in the worker; returns registered routes. */
    public List<DynamicEndpointRegistrar.RouteInfo> deploy(int port, ModuleDescriptor descriptor) {
        return postForRoutes(port, "/__admin/deploy", descriptor);
    }

    /** POST {@code /__admin/redeploy}: recompile+hot-swap a module in place against the worker's current generation. */
    public List<DynamicEndpointRegistrar.RouteInfo> redeploy(int port, ModuleDescriptor descriptor) {
        return postForRoutes(port, "/__admin/redeploy", descriptor);
    }

    /** POST {@code /__admin/undeploy/{id}} (no body): release a single module in the worker. */
    public void undeploy(int port, String moduleId) {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/__admin/undeploy/" + moduleId))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody());
        applyAdminAuth(b, new byte[0]);
        try {
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("worker /__admin/undeploy response " + resp.statusCode() + ": " + resp.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("worker undeploy request failed", e);
        }
    }

    // --- uses-closure resolution (typed sharing) ---

    /**
     * The transitive {@code uses} closure of {@code module}, as library descriptors in dependency-first (topo) order —
     * the libraries that must be published into a worker before the module. Empty when the module has no {@code uses}
     * or the module store is unavailable.
     */
    public List<ModuleDescriptor> usesClosure(ModuleDescriptor module) {
        ModuleStore store = moduleStore.getIfAvailable();
        if (store == null || module.uses().isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, ModuleDescriptor> ordered = new LinkedHashMap<>();
        for (String libId : module.uses()) {
            visitLibrary(libId, store, ordered, new HashSet<>());
        }
        return new ArrayList<>(ordered.values());
    }

    private void visitLibrary(String libId, ModuleStore store, LinkedHashMap<String, ModuleDescriptor> ordered,
                              Set<String> onStack) {
        if (ordered.containsKey(libId) || !onStack.add(libId)) {
            return;   // already ordered, or a cycle (cycles are rejected at install — guard defensively)
        }
        ModuleDescriptor library = store.load(libId)
                .orElseThrow(() -> new IllegalStateException(
                        "worker module uses library '" + libId + "', which is not installed"));
        for (String dep : library.uses()) {
            visitLibrary(dep, store, ordered, onStack);
        }
        onStack.remove(libId);
        ordered.put(libId, library);   // placed after its own dependencies → dependency-first order
    }

    /** Whether {@code libId} is in {@code module}'s transitive {@code uses} closure. */
    public boolean usesTransitively(ModuleDescriptor module, String libId) {
        return usesTransitively(module, libId, moduleStore.getIfAvailable(), new HashSet<>());
    }

    private boolean usesTransitively(ModuleDescriptor module, String libId, ModuleStore store, Set<String> seen) {
        for (String used : module.uses()) {
            if (used.equals(libId)) {
                return true;
            }
            if (store != null && seen.add(used)) {
                ModuleDescriptor lib = store.load(used).orElse(null);
                if (lib != null && usesTransitively(lib, libId, store, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- HTTP ---

    private <T> T post(int port, String path, Object body, Class<T> responseType) {
        try {
            HttpResponse<String> resp = send(port, path, body);
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("worker " + path + " response " + resp.statusCode() + ": " + resp.body());
            }
            return mapper.readValue(resp.body(), responseType);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("worker " + path + " request failed", e);
        }
    }

    private List<DynamicEndpointRegistrar.RouteInfo> postForRoutes(int port, String path, Object body) {
        try {
            HttpResponse<String> resp = send(port, path, body);
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("worker " + path + " response " + resp.statusCode() + ": " + resp.body());
            }
            return mapper.readValue(resp.body(), mapper.getTypeFactory()
                    .constructCollectionType(List.class, DynamicEndpointRegistrar.RouteInfo.class));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("worker " + path + " request failed", e);
        }
    }

    private HttpResponse<String> send(int port, String path, Object body) throws Exception {
        byte[] json = mapper.writeValueAsBytes(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)   // bound a hung worker (see REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json));
        applyAdminAuth(b, json);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Attaches the worker-admin auth header(s) to an outgoing {@code /__admin/*} request when
     * {@code protean.worker.admin-auth.enabled=true}, so the worker's {@code WorkerAdminAuthFilter} accepts it. The
     * signature must be computed over the <b>exact</b> body bytes the worker will read. No-op (unsigned) when auth is
     * off. Mirrors {@code BridgeInvocationHandler}'s client-side scheme.
     */
    private void applyAdminAuth(HttpRequest.Builder b, byte[] body) {
        ProteanProperties.AdminAuth cfg = props.getWorker().getAdminAuth();
        if (!cfg.isEnabled()) {
            return;
        }
        WorkerAdminSecretHolder holder = adminSecret.getIfAvailable();
        if (holder == null) {
            return;   // enabled but no secret holder: leave unsigned → the worker rejects with a clear 401 (never silent)
        }
        String secret = holder.token();
        if ("hmac".equalsIgnoreCase(cfg.getMode())) {
            long ts = System.currentTimeMillis();
            byte[] nonceBytes = new byte[16];
            nonces.nextBytes(nonceBytes);
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
            b.header(BridgeHmac.TS_HEADER, Long.toString(ts));
            b.header(BridgeHmac.NONCE_HEADER, nonce);
            b.header(BridgeHmac.SIG_HEADER, BridgeHmac.sign(secret, ts, nonce, body));
        } else {
            b.header("Authorization", "Bearer " + secret);
        }
    }
}
