/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.ParentTier;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.compiler.SharedModuleRegistry;
import org.htcom.protean.db.DbScopeProvisioner;
import org.htcom.protean.module.ModuleContainer;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Weak-isolation strategy that runs modules within the same JVM (initial implementation).
 * Delegates directly to the proven in-process deployment engine (RuntimeCompiler + ModuleContainer).
 *
 * Because shared in-process beans (business logic) can be used directly via child-context DI, it also supports
 * {@code needsSharedBeans} modules.
 */
@Component
public class InProcessIsolation implements IsolationStrategy {

    private static final Logger log = LoggerFactory.getLogger(InProcessIsolation.class);

    private final RuntimeCompiler compiler;
    private final ModuleContainer container;
    /** True on the host when auto-provision is on (a {@link DbScopeProvisioner} bean is present). */
    private final boolean autoProvision;
    /** True inside a worker JVM, where in-process is the execution engine and scope is a host concern (not re-checked). */
    private final boolean workerProfile;

    public InProcessIsolation(RuntimeCompiler compiler, ModuleContainer container,
                              ObjectProvider<DbScopeProvisioner> provisionerProvider, Environment env) {
        this.compiler = compiler;
        this.container = container;
        this.autoProvision = provisionerProvider.getIfAvailable() != null;
        this.workerProfile = env.matchesProfiles("worker");
    }

    @Override
    public String mode() {
        return "in-process";
    }

    /**
     * Guards the scope contract for in-process. A DB scope can only be honored by a separate-JVM worker/container (its
     * own scoped datasource); in-process runs in the main JVM with the main datasource. So under auto-provision a module
     * that declares a {@code scope} but lands in-process is <b>rejected</b> (closing the ap=true + default in-process
     * trap). With auto-provision off the scope is simply inert → <b>warn</b> and ignore (kept lenient for compatibility).
     * Skipped inside a worker JVM, where in-process is merely the execution engine and scope was already resolved on the host.
     */
    private void checkScope(ModuleDescriptor descriptor) {
        String scope = descriptor.scope();
        if (scope == null || scope.isBlank() || workerProfile) {
            return;
        }
        if (autoProvision) {
            throw new IllegalStateException("module '" + descriptor.id() + "' declares scope '" + scope
                    + "' but is routed to in-process isolation, which cannot bind to a per-scope database — "
                    + "deploy it with worker or container isolation (protean.isolation.mode / the module's isolationMode)");
        }
        log.warn("module '{}' declares scope '{}' but worker.db.auto-provision is off — the scope is ignored "
                + "(scopes apply only under auto-provision)", descriptor.id(), scope);
    }

    @Override
    public boolean supports(ModuleDescriptor descriptor) {
        // Same JVM, so everything including shared-bean access is supported.
        return true;
    }

    @Override
    public void deploy(ModuleDescriptor descriptor) {
        checkScope(descriptor);
        if (descriptor.isLibrary()) {
            publishLibrary(descriptor);   // activation = publish a generation onto the parent tier, not route registration
            return;
        }
        ParentTier tier = compiler.resolveParentTier(descriptor.uses());
        ModuleClassLoader loader = compiler.compileAll(descriptor.sources(),
                ModuleResource.decodeAll(descriptor.resources()), descriptor.id(), tier);
        container.deploy(descriptor.id(), loader, descriptor.componentFqcns(), descriptor.controllerFqcn());
    }

    /**
     * Compiles into the shared compile cache (keyed by module id + sources) without deploying, so the
     * subsequent {@link #deploy} call hits the cache fast-path and skips javac. Safe to run concurrently
     * for distinct modules (javac tasks are independent; the cache is a ConcurrentHashMap). The returned
     * loader is intentionally discarded — deploy rebuilds its own from the cached bytecode.
     */
    @Override
    public void prewarm(ModuleDescriptor descriptor) {
        ParentTier tier = compiler.resolveParentTier(descriptor.uses());
        if (descriptor.isLibrary()) {
            compiler.compileLibrary(descriptor.sources(), descriptor.id(), tier);
            return;
        }
        compiler.compileAll(descriptor.sources(),
                ModuleResource.decodeAll(descriptor.resources()), descriptor.id(), tier);
    }

    @Override
    public void hotSwap(ModuleDescriptor descriptor) {
        checkScope(descriptor);
        if (descriptor.isLibrary()) {
            publishLibrary(descriptor);   // publishes a new library generation; dependents pick it up on their next (re)compile
            return;
        }
        ParentTier tier = compiler.resolveParentTier(descriptor.uses());
        ModuleClassLoader loader = compiler.compileAll(descriptor.sources(),
                ModuleResource.decodeAll(descriptor.resources()), descriptor.id(), tier);
        container.hotSwap(descriptor.id(), loader, descriptor.componentFqcns(), descriptor.controllerFqcn());
    }

    @Override
    public void undeploy(String moduleId) {
        // Safe for both kinds: container.undeploy is a no-op for a library (never route-registered); retire is a no-op
        // for a normal module (never published as a library).
        container.undeploy(moduleId);
        SharedModuleRegistry registry = compiler.sharedModuleRegistry();
        if (registry != null) {
            registry.retire(moduleId);
        }
        compiler.evict(moduleId);   // drop the compile cache + release generations → recompile on redeploy
    }

    /** Compiles a library module and publishes its compiled {@code exports} as a new parent-tier generation. */
    private void publishLibrary(ModuleDescriptor descriptor) {
        SharedModuleRegistry registry = compiler.sharedModuleRegistry();
        if (registry == null) {
            throw new IllegalStateException("library modules require the in-process shared-module registry "
                    + "(typed sharing is supported in in-process mode only): " + descriptor.id());
        }
        ParentTier tier = compiler.resolveParentTier(descriptor.uses());
        Map<String, byte[]> bytecode = compiler.compileLibrary(descriptor.sources(), descriptor.id(), tier);
        registry.publish(descriptor.id(), descriptor.exports(), bytecode, tier);
    }

    @Override
    public boolean reloadResources(ModuleDescriptor descriptor) {
        ModuleClassLoader loader = container.currentLoader(descriptor.id());
        if (loader == null) {
            return false;   // not deployed → fall back (full update)
        }
        // Replace only the resource bytes in place, with no recompile or context rebuild.
        loader.replaceResources(ModuleResource.decodeAll(descriptor.resources()));
        return true;
    }

    @Override
    public boolean retargetLibraries(ModuleDescriptor descriptor) {
        // A library is retargeted by recompiling+republishing (Plan A2) so it publishes a new generation of its own;
        // only a normal, route-registered module can be re-parented in place with its existing bytecode.
        if (descriptor.isLibrary() || compiler.sharedModuleRegistry() == null) {
            return false;
        }
        ParentTier tier = compiler.resolveParentTier(descriptor.uses());
        ModuleClassLoader loader = compiler.retarget(descriptor.id(),
                ModuleResource.decodeAll(descriptor.resources()), tier);
        container.hotSwap(descriptor.id(), loader, descriptor.componentFqcns(), descriptor.controllerFqcn());
        return true;
    }
}
