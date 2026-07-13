/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.dynamic.DynamicEndpointRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.method.ControllerAdviceBean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A module = a pair of (child ApplicationContext + dedicated ModuleClassLoader).
 *
 * The parent context holds the shared infrastructure, and each module is brought up as a child
 * context that uses its own ClassLoader.
 * → A module's internal @Service/@Repository dependencies are injected via DI, and
 *   on unload, closing the child context discards that ClassLoader entirely (making it eligible for GC).
 *
 * <p>Each child context registers a managed {@link ProteanTaskExecutor} as a lazy bean (created only when
 * injected), which is shut down automatically on close. Just before close, the {@link ModuleUnloadCallback}
 * beans are invoked to give a chance to clean up resources living outside the context.
 */
@Component
public class ModuleContainer {

    private static final Logger log = LoggerFactory.getLogger(ModuleContainer.class);

    private final ApplicationContext rootContext;
    private final DynamicEndpointRegistrar registrar;
    private final ProteanProperties properties;
    private final Map<String, AnnotationConfigApplicationContext> modules = new ConcurrentHashMap<>();

    public ModuleContainer(ApplicationContext rootContext, DynamicEndpointRegistrar registrar,
                           ProteanProperties properties) {
        this.rootContext = rootContext;
        this.registrar = registrar;
        this.properties = properties;
    }

    /**
     * Deploys a module.
     *
     * @param moduleId       module identifier
     * @param loader         module-dedicated ClassLoader (the compilation result)
     * @param componentFqcns component classes to register in the child context (Controller, Service, etc.)
     * @param controllerFqcn the controller class whose REST mappings will be registered
     */
    public synchronized void deploy(String moduleId, ModuleClassLoader loader,
                                    List<String> componentFqcns, String controllerFqcn) {
        if (modules.containsKey(moduleId)) {
            throw new IllegalStateException("module already deployed: " + moduleId);
        }
        AnnotationConfigApplicationContext child = createChild(moduleId, loader);
        try {
            for (String fqcn : componentFqcns) {
                child.register(loader.loadClass(fqcn));
            }
            child.refresh();

            Object controller = child.getBean(loader.loadClass(controllerFqcn));
            registrar.register(moduleId, controller);
            // Register the module's declared global @ControllerAdvice into the root resolver (a child scan won't pick it up).
            List<ControllerAdviceBean> adviceBeans = ControllerAdviceBean.findAnnotatedBeans(child);
            registrar.registerControllerAdvice(moduleId, adviceBeans);
            modules.put(moduleId, child);
        } catch (RuntimeException | ClassNotFoundException e) {
            child.close();
            throw new IllegalStateException("module deployment failed: " + moduleId, e);
        }
    }

    /**
     * Swaps a module without downtime.
     * A new child context is fully brought up in advance, then only the mapping is switched atomically,
     * and the old context is closed after the swap (in-flight requests keep the old instance on their stack, so they drain naturally).
     */
    public synchronized void hotSwap(String moduleId, ModuleClassLoader newLoader,
                                     List<String> componentFqcns, String controllerFqcn) {
        AnnotationConfigApplicationContext old = modules.get(moduleId);
        if (old == null) {
            throw new IllegalStateException("no module to swap: " + moduleId);
        }
        AnnotationConfigApplicationContext child = createChild(moduleId, newLoader);
        try {
            for (String fqcn : componentFqcns) {
                child.register(newLoader.loadClass(fqcn));
            }
            child.refresh();
            Object controller = child.getBean(newLoader.loadClass(controllerFqcn));

            registrar.swap(moduleId, controller);   // ← atomic mapping swap
            modules.put(moduleId, child);
        } catch (RuntimeException | ClassNotFoundException e) {
            child.close();
            throw new IllegalStateException("hot-swap failed: " + moduleId, e);
        }
        invokeUnloadCallbacks(moduleId, old);
        old.close();  // clean up the old version after it drains
    }

    /** Unloads a module — unregister mappings + unload callbacks + adapter cache eviction + child context close. */
    public synchronized void undeploy(String moduleId) {
        AnnotationConfigApplicationContext child = modules.remove(moduleId);
        if (child == null) {
            return;
        }
        registrar.unregister(moduleId);
        invokeUnloadCallbacks(moduleId, child);
        child.close();
    }

    public boolean isDeployed(String moduleId) {
        return modules.containsKey(moduleId);
    }

    /** The ids of every module currently deployed in this container (snapshot). For worker-side parent-tier rebind. */
    public java.util.Set<String> deployedModuleIds() {
        return java.util.Set.copyOf(modules.keySet());
    }

    /** The current ModuleClassLoader of a deployed module (for live-reload). null if none. */
    public ModuleClassLoader currentLoader(String moduleId) {
        AnnotationConfigApplicationContext ctx = modules.get(moduleId);
        return ctx == null ? null : (ModuleClassLoader) ctx.getClassLoader();
    }

    /** Creates a child context (before refresh) with parent=root, a dedicated ClassLoader, and a managed executor (lazy). */
    private AnnotationConfigApplicationContext createChild(String moduleId, ModuleClassLoader loader) {
        // ModuleApplicationContext uses a custom resolver so that classpath*: scans enumerate in-memory module resources and classes.
        AnnotationConfigApplicationContext child = new ModuleApplicationContext();
        child.setParent(rootContext);
        child.setClassLoader(loader);
        int poolSize = properties.getModule().getExecutor().getPoolSize();
        // lazy: created only when the module injects it → zero threads if unused. It is AutoCloseable, so close() shuts it down automatically.
        child.registerBean(ProteanTaskExecutor.class,
                () -> new ProteanTaskExecutor(moduleId, poolSize),
                bd -> bd.setLazyInit(true));
        return child;
    }

    /** Invokes the module's/consumer's {@link ModuleUnloadCallback} just before child.close() (best-effort). */
    private void invokeUnloadCallbacks(String moduleId, AnnotationConfigApplicationContext ctx) {
        Map<String, ModuleUnloadCallback> callbacks;
        try {
            callbacks = ctx.getBeansOfType(ModuleUnloadCallback.class);
        } catch (RuntimeException e) {
            log.warn("failed to look up unload callbacks for module '{}': {}", moduleId, e.toString());
            return;
        }
        for (ModuleUnloadCallback cb : callbacks.values()) {
            try {
                cb.onUnload(moduleId);
            } catch (RuntimeException e) {
                log.warn("unload callback failed for module '{}': {}", moduleId, e.toString());
            }
        }
    }
}
