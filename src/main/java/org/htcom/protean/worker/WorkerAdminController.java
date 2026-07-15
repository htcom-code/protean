/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.worker;

import org.htcom.protean.bridge.WorkerBridgeRegistrar;
import org.htcom.protean.dynamic.DynamicEndpointRegistrar;
import org.htcom.protean.gate.PromotionPipeline;
import org.htcom.protean.isolation.InProcessIsolation;
import org.htcom.protean.module.ModuleDescriptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin API of the worker JVM (active only on the worker profile).
 * When the main process sends sources, the worker compiles and serves them in-process.
 * After deployment it returns the list of actually registered paths, so the main process can set up
 * a reverse-proxy to them.
 */
@RestController
@Profile("worker")
public class WorkerAdminController {

    private final InProcessIsolation isolation;
    private final DynamicEndpointRegistrar registrar;
    private final ObjectProvider<WorkerBridgeRegistrar> bridgeRegistrar;
    private final WorkerSharedLibReceiver sharedLibReceiver;
    private final PromotionPipeline promotionPipeline;

    public WorkerAdminController(InProcessIsolation isolation, DynamicEndpointRegistrar registrar,
                                 ObjectProvider<WorkerBridgeRegistrar> bridgeRegistrar,
                                 WorkerSharedLibReceiver sharedLibReceiver,
                                 PromotionPipeline promotionPipeline) {
        this.isolation = isolation;
        this.registrar = registrar;
        this.bridgeRegistrar = bridgeRegistrar;
        this.sharedLibReceiver = sharedLibReceiver;
        this.promotionPipeline = promotionPipeline;
    }

    @GetMapping("/__admin/health")
    public String health() {
        return "ok";
    }

    /** After deploying a module, return the registered routes (HTTP method set + path patterns). */
    @PostMapping("/__admin/deploy")
    public List<DynamicEndpointRegistrar.RouteInfo> deploy(@RequestBody ModuleDescriptor descriptor) {
        // Register bridge interface proxies before deployment (so the module child context can inject them)
        if (descriptor.bridgedInterfaces() != null && !descriptor.bridgedInterfaces().isEmpty()) {
            WorkerBridgeRegistrar reg = bridgeRegistrar.getIfAvailable();
            if (reg == null) {
                throw new IllegalStateException("bridgedInterfaces declared but rpc-bridge is not enabled: " + descriptor.id());
            }
            for (String iface : descriptor.bridgedInterfaces()) {
                reg.register(iface);
            }
        }
        isolation.deploy(descriptor);
        return registrar.routesOf(descriptor.id());
    }

    @PostMapping("/__admin/undeploy/{id}")
    public void undeploy(@PathVariable String id) {
        isolation.undeploy(id);
    }

    /**
     * Accepts a live shared-lib generation pushed from the main and publishes it into this worker's parent tier.
     * Returns the new generation id and the ACTIVE modules the main must rebind.
     */
    @PostMapping("/__admin/shared-libs")
    public WorkerSharedLibReceiver.PublishResult receiveSharedLibs(
            @RequestBody WorkerSharedLibReceiver.PushRequest request) {
        return sharedLibReceiver.publish(request);
    }

    /**
     * Rebinds an already-deployed module in place by recompiling it against this worker's current parent tier and
     * hot-swapping it (no new JVM, same port). Used by the main to move a module onto a freshly pushed generation.
     * Returns the (unchanged) registered routes (HTTP method set + path patterns).
     */
    @PostMapping("/__admin/redeploy")
    public List<DynamicEndpointRegistrar.RouteInfo> redeploy(@RequestBody ModuleDescriptor descriptor) {
        // Gate the dependent against THIS worker's just-republished parent tier before the in-place recompile+swap
        // (shared-module-a1-gate-drift, worker/container arm). A binary-compatible library impl change that alters
        // a NORMAL dependent's asserted behavior fails its test gate here and throws → HTTP 500 → the main-side
        // propagator records Plan B (sticky), matching the in-process rebindFast gate. Libraries are skipped: they
        // were gated at their own publish and carry no dependent contract to re-verify here.
        if (!descriptor.isLibrary()) {
            promotionPipeline.enforceTestGate(descriptor);
        }
        isolation.hotSwap(descriptor);
        return registrar.routesOf(descriptor.id());
    }
}
