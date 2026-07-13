/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.dynamic;

import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.handler.AbstractHandlerMethodMapping;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * Registers/unregisters controller handlers on a live DispatcherServlet at runtime.
 *
 * Core idea: reuse Spring's own mapping-parsing logic ({@code getMappingForMethod})
 * via reflection to build the {@link RequestMappingInfo}. Because paths/methods are
 * not assembled by hand, framework settings such as path prefix and content
 * negotiation are honored as-is.
 *
 * Tracks the mappings registered per module ID so they can be reverted precisely on
 * unregistration (prevents zombie mappings).
 */
@Component
public class DynamicEndpointRegistrar {

    /** Adapter caches keyed by controller Class that get populated on invocation and are not
     *  cleared by mapping unregistration. */
    private static final String[] ADAPTER_CLASS_CACHES = {
            "sessionAttributesHandlerCache",
            "initBinderCache",
            "modelAttributeCache",
    };

    /** Exception resolver cache keyed by controller Class, populated when @ExceptionHandler is invoked. */
    private static final String EXCEPTION_HANDLER_CACHE = "exceptionHandlerCache";

    /** The adapter's argument-resolver composites, populated with MethodParameter keys
     *  (→Method→module Class) when a handler with arguments is invoked. They are not cleared by
     *  mapping unregistration and hold onto the ClassLoader → purge them directly. */
    private static final String[] ADAPTER_ARG_RESOLVER_COMPOSITES = {
            "argumentResolvers",
            "initBinderArgumentResolvers",
    };
    /** Field name of the MethodParameter-keyed cache inside HandlerMethodArgumentResolverComposite. */
    private static final String ARGUMENT_RESOLVER_CACHE = "argumentResolverCache";

    /** Cache holding @ExceptionHandler of global @ControllerAdvice (key=ControllerAdviceBean). */
    private static final String EXCEPTION_HANDLER_ADVICE_CACHE = "exceptionHandlerAdviceCache";

    /** Cache holding @ModelAttribute of global @ControllerAdvice (RequestMappingHandlerAdapter, key=ControllerAdviceBean). */
    private static final String MODEL_ATTRIBUTE_ADVICE_CACHE = "modelAttributeAdviceCache";

    /** Cache holding @InitBinder of global @ControllerAdvice (RequestMappingHandlerAdapter, key=ControllerAdviceBean). */
    private static final String INIT_BINDER_ADVICE_CACHE = "initBinderAdviceCache";

    private final RequestMappingHandlerMapping handlerMapping;
    private final RequestMappingHandlerAdapter handlerAdapter;
    /** Holder of the local @ExceptionHandler cache. It may be hidden inside a composite so direct
     *  injection can fail (null if not found). */
    private final ExceptionHandlerExceptionResolver exceptionResolver;
    private final Method getMappingForMethod;
    /** The write lock of the MappingRegistry inside RequestMappingHandlerMapping — for atomic swap. */
    private final Lock mappingWriteLock;
    private final Map<String, Registration> registry = new ConcurrentHashMap<>();
    /** Global @ControllerAdvice keys registered by a module — for eviction on undeploy. */
    private final Map<String, List<ControllerAdviceBean>> adviceRegistry = new ConcurrentHashMap<>();

    private record Registration(List<RequestMappingInfo> infos, List<Class<?>> handlerTypes) {}

    /** A (RequestMappingInfo, the Method that mapping points to) pair. */
    private record Mapping(RequestMappingInfo info, Method method) {}

    public DynamicEndpointRegistrar(RequestMappingHandlerMapping handlerMapping,
                                    RequestMappingHandlerAdapter handlerAdapter,
                                    ApplicationContext context) {
        this.handlerMapping = handlerMapping;
        this.handlerAdapter = handlerAdapter;
        try {
            this.getMappingForMethod = RequestMappingHandlerMapping.class
                    .getDeclaredMethod("getMappingForMethod", Method.class, Class.class);
            this.getMappingForMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Spring internal API signature changed — getMappingForMethod not found", e);
        }
        this.mappingWriteLock = resolveMappingWriteLock(handlerMapping);
        this.exceptionResolver = findExceptionResolver(context);
    }

    /** Registers all @RequestMapping methods of the handler instance and returns the number of mappings registered. */
    public synchronized int register(String moduleId, Object handler) {
        if (registry.containsKey(moduleId)) {
            throw new IllegalStateException("module already registered: " + moduleId);
        }
        List<Mapping> mappings = buildMappings(handler);
        List<RequestMappingInfo> registered = new ArrayList<>();
        for (Mapping m : mappings) {
            handlerMapping.registerMapping(m.info(), handler, m.method());
            registered.add(m.info());
        }
        registry.put(moduleId, new Registration(registered, typesOf(handler)));
        return registered.size();
    }

    /** List of path patterns registered by a module (used for main-proxy routing in worker isolation). */
    public synchronized List<String> pathsOf(String moduleId) {
        Registration reg = registry.get(moduleId);
        if (reg == null) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (RequestMappingInfo info : reg.infos()) {
            if (info.getPathPatternsCondition() != null) {
                paths.addAll(info.getPathPatternsCondition().getPatternValues());
            } else if (info.getPatternsCondition() != null) {
                paths.addAll(info.getPatternsCondition().getPatterns());
            }
        }
        return paths;
    }

    /**
     * List of routes (HTTP method + path pattern) that the module <b>actually registered on the
     * live mapping</b>. This reflects the measured runtime registration rather than the store's
     * desiredState, so it surfaces "ACTIVE but 404" mismatches (0 registrations due to a reconcile
     * compile failure). Empty list if nothing is registered. Read-only
     * (MCP {@code protean://modules/{id}/routes}).
     */
    public synchronized List<RouteInfo> routesOf(String moduleId) {
        Registration reg = registry.get(moduleId);
        if (reg == null) {
            return List.of();
        }
        List<RouteInfo> routes = new ArrayList<>();
        for (RequestMappingInfo info : reg.infos()) {
            List<String> patterns = new ArrayList<>();
            if (info.getPathPatternsCondition() != null) {
                patterns.addAll(info.getPathPatternsCondition().getPatternValues());
            } else if (info.getPatternsCondition() != null) {
                patterns.addAll(info.getPatternsCondition().getPatterns());
            }
            Set<String> methods = new LinkedHashSet<>();
            for (org.springframework.web.bind.annotation.RequestMethod rm
                    : info.getMethodsCondition().getMethods()) {
                methods.add(rm.name());
            }
            routes.add(new RouteInfo(methods, patterns));
        }
        return routes;
    }

    /** The set of HTTP methods for one mapping (empty means all methods allowed) and its path patterns. Element returned by {@link #routesOf}. */
    public record RouteInfo(Set<String> methods, List<String> patterns) {}

    /** For request-trace attribution: reverse-lookup the registering module id from a mapping pattern (e.g. "/foo/{id}") (best-effort). */
    public synchronized java.util.Optional<String> moduleIdForPattern(String pattern) {
        if (pattern == null) {
            return java.util.Optional.empty();
        }
        for (String moduleId : registry.keySet()) {
            if (pathsOf(moduleId).contains(pattern)) {
                return java.util.Optional.of(moduleId);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Replaces the handler for the same module ID with a new handler <b>atomically</b>
     * (zero-downtime hot-swap). Because both unregister and register are performed while holding
     * the write lock, no request ever observes the intermediate state where the mapping is empty
     * (eliminates the 404 window).
     */
    public synchronized int swap(String moduleId, Object newHandler) {
        Registration old = registry.get(moduleId);
        if (old == null) {
            throw new IllegalStateException("no module to replace: " + moduleId);
        }
        // Finish the heavy parsing up front, outside the lock, to minimize hold time.
        List<Mapping> newMappings = buildMappings(newHandler);

        mappingWriteLock.lock();
        try {
            for (RequestMappingInfo info : old.infos()) {
                handlerMapping.unregisterMapping(info);
            }
            for (Mapping m : newMappings) {
                handlerMapping.registerMapping(m.info(), newHandler, m.method());
            }
        } finally {
            mappingWriteLock.unlock();
        }

        // Remove the old version's per-Class caches (prevents leaks).
        for (Class<?> handlerType : old.handlerTypes()) {
            evictModuleCaches(handlerType);
        }
        List<RequestMappingInfo> newInfos = new ArrayList<>();
        for (Mapping m : newMappings) {
            newInfos.add(m.info());
        }
        registry.put(moduleId, new Registration(newInfos, typesOf(newHandler)));
        return newInfos.size();
    }

    /**
     * Registers a module's global @ControllerAdvice beans directly into the root MVC infrastructure.
     * Because the resolver/adapter scan only the root context at startup, advice from a child
     * context must be injected this way to apply globally. Three kinds are supported:
     * <ul>
     *   <li>{@code @ExceptionHandler} → {@link ExceptionHandlerExceptionResolver#exceptionHandlerAdviceCache}</li>
     *   <li>{@code @ModelAttribute} → {@link RequestMappingHandlerAdapter#modelAttributeAdviceCache}</li>
     *   <li>{@code @InitBinder} → {@link RequestMappingHandlerAdapter#initBinderAdviceCache}</li>
     * </ul>
     * Method selection reuses Spring's own {@code MODEL_ATTRIBUTE_METHODS}/{@code INIT_BINDER_METHODS}
     * filters, guaranteeing the same rules as the framework's startup scan.
     *
     * Note: these advice caches are not synchronized collections. There may be visibility issues if
     * registration/unregistration happens concurrently with request processing in production, so this
     * implementation assumes deploy/undeploy occurs rarely.
     *
     * @return the number of distinct advice beans that contributed to (were registered in) at least one advice cache
     */
    public synchronized int registerControllerAdvice(String moduleId, List<ControllerAdviceBean> adviceBeans) {
        if (adviceBeans.isEmpty()) {
            return 0;
        }
        Map<Object, Object> ehCache = exceptionResolver != null ? adviceCache() : null;
        Map<Object, Object> maCache = adapterAdviceCache(MODEL_ATTRIBUTE_ADVICE_CACHE);
        Map<Object, Object> ibCache = adapterAdviceCache(INIT_BINDER_ADVICE_CACHE);
        // Advice beans that contributed to any of the three caches — tracked as a union so they can be evicted from all caches on undeploy.
        Set<ControllerAdviceBean> keys = new LinkedHashSet<>();
        for (ControllerAdviceBean adviceBean : adviceBeans) {
            Class<?> beanType = adviceBean.getBeanType();
            if (beanType == null) {
                continue;
            }
            // (1) @ExceptionHandler (if the resolver isn't found in the composite, ehCache=null → skip)
            if (ehCache != null) {
                ExceptionHandlerMethodResolver methodResolver = new ExceptionHandlerMethodResolver(beanType);
                if (methodResolver.hasExceptionMappings()) {
                    ehCache.put(adviceBean, methodResolver);
                    keys.add(adviceBean);
                }
            }
            // (2) @ModelAttribute (but not @RequestMapping methods) — reuse Spring's filter.
            Set<Method> attrMethods = MethodIntrospector.selectMethods(
                    beanType, RequestMappingHandlerAdapter.MODEL_ATTRIBUTE_METHODS);
            if (!attrMethods.isEmpty()) {
                maCache.put(adviceBean, attrMethods);
                keys.add(adviceBean);
            }
            // (3) @InitBinder
            Set<Method> binderMethods = MethodIntrospector.selectMethods(
                    beanType, RequestMappingHandlerAdapter.INIT_BINDER_METHODS);
            if (!binderMethods.isEmpty()) {
                ibCache.put(adviceBean, binderMethods);
                keys.add(adviceBean);
            }
        }
        adviceRegistry.put(moduleId, new ArrayList<>(keys));
        return keys.size();
    }

    /** Unregisters all mappings the module had registered and returns the number of mappings unregistered. */
    public synchronized int unregister(String moduleId) {
        Registration reg = registry.remove(moduleId);
        if (reg == null) {
            return 0;
        }
        evictControllerAdvice(moduleId);
        mappingWriteLock.lock();
        try {
            for (RequestMappingInfo info : reg.infos()) {
                handlerMapping.unregisterMapping(info);
            }
        } finally {
            mappingWriteLock.unlock();
        }
        // Unregistering the mapping alone is not enough — the caches populated with the controller
        // Class on invocation must be cleared so the ModuleClassLoader is GC'd and Metaspace is reclaimed.
        for (Class<?> handlerType : reg.handlerTypes()) {
            evictModuleCaches(handlerType);
        }
        return reg.infos().size();
    }

    private List<Mapping> buildMappings(Object handler) {
        Class<?> handlerType = handler.getClass();
        List<Mapping> mappings = new ArrayList<>();
        for (Method method : handlerType.getDeclaredMethods()) {
            RequestMappingInfo info = mappingFor(method, handlerType);
            if (info != null) {
                mappings.add(new Mapping(info, method));
            }
        }
        return mappings;
    }

    private static List<Class<?>> typesOf(Object handler) {
        List<Class<?>> types = new ArrayList<>();
        // The adapter caches use getBeanType()=user class as the key. For an AOP proxy this differs
        // from handler.getClass() (the CGLIB subclass), so eviction must use the user class to avoid a leak.
        types.add(ClassUtils.getUserClass(handler));
        return types;
    }

    private static Lock resolveMappingWriteLock(RequestMappingHandlerMapping handlerMapping) {
        try {
            Field mrField = AbstractHandlerMethodMapping.class.getDeclaredField("mappingRegistry");
            mrField.setAccessible(true);
            Object mappingRegistry = mrField.get(handlerMapping);
            Field lockField = mappingRegistry.getClass().getDeclaredField("readWriteLock");
            lockField.setAccessible(true);
            ReadWriteLock rwLock = (ReadWriteLock) lockField.get(mappingRegistry);
            return rwLock.writeLock();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to access MappingRegistry write lock (Spring internal structure changed?)", e);
        }
    }

    /** Clears all MVC infrastructure caches populated with the controller Class key on invocation (prevents ClassLoader leaks). */
    private void evictModuleCaches(Class<?> handlerType) {
        for (String cacheField : ADAPTER_CLASS_CACHES) {
            evictClassKeyedCache(RequestMappingHandlerAdapter.class, handlerAdapter, cacheField, handlerType);
        }
        if (exceptionResolver != null) {
            evictClassKeyedCache(ExceptionHandlerExceptionResolver.class, exceptionResolver,
                    EXCEPTION_HANDLER_CACHE, handlerType);
        }
        // Handlers/advice with arguments are cached in the argument-resolver composite as MethodParameters.
        // Remove all parameter entries belonging to classes loaded by the module ClassLoader (controller + same-module advice).
        for (String composite : ADAPTER_ARG_RESOLVER_COMPOSITES) {
            evictArgResolverCache(composite, handlerType);
        }
    }

    /**
     * Removes entries belonging to handlerType (and classes loaded by the same module ClassLoader)
     * from the MethodParameter-keyed cache inside RequestMappingHandlerAdapter's argument-resolver composite.
     */
    private void evictArgResolverCache(String compositeField, Class<?> handlerType) {
        Object composite;
        try {
            Field cf = RequestMappingHandlerAdapter.class.getDeclaredField(compositeField);
            cf.setAccessible(true);
            composite = cf.get(handlerAdapter);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "RequestMappingHandlerAdapter composite field not found: " + compositeField + " (Spring version changed?)", e);
        }
        if (composite == null) {
            return;
        }
        Field cacheField;
        try {
            cacheField = composite.getClass().getDeclaredField(ARGUMENT_RESOLVER_CACHE);
        } catch (NoSuchFieldException e) {
            // This composite implementation has no cache — not a leak vector.
            return;
        }
        cacheField.setAccessible(true);
        ClassLoader moduleLoader = handlerType.getClassLoader();
        try {
            if (cacheField.get(composite) instanceof Map<?, ?> cache) {
                cache.keySet().removeIf(key -> key instanceof MethodParameter mp
                        && belongsToModule(mp.getDeclaringClass(), handlerType, moduleLoader));
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /** Determines whether the parameter's declaring class belongs to this module (= handlerType itself or loaded by the same ClassLoader). */
    private static boolean belongsToModule(Class<?> declaringClass, Class<?> handlerType, ClassLoader moduleLoader) {
        if (declaringClass == handlerType) {
            return true;
        }
        return moduleLoader != null && declaringClass != null && declaringClass.getClassLoader() == moduleLoader;
    }

    /** Removes the global advice entries registered by a module from all three caches
     *  (exception/modelAttribute/initBinder) (key=ControllerAdviceBean → prevents bean/ClassLoader leaks). */
    private void evictControllerAdvice(String moduleId) {
        List<ControllerAdviceBean> keys = adviceRegistry.remove(moduleId);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Map<Object, Object> ehCache = exceptionResolver != null ? adviceCache() : null;
        Map<Object, Object> maCache = adapterAdviceCache(MODEL_ATTRIBUTE_ADVICE_CACHE);
        Map<Object, Object> ibCache = adapterAdviceCache(INIT_BINDER_ADVICE_CACHE);
        for (ControllerAdviceBean key : keys) {
            if (ehCache != null) {
                ehCache.remove(key);
            }
            maCache.remove(key);
            ibCache.remove(key);
        }
    }

    /** Accesses ExceptionHandlerExceptionResolver.exceptionHandlerAdviceCache via reflection. */
    @SuppressWarnings("unchecked")
    private Map<Object, Object> adviceCache() {
        try {
            Field field = ExceptionHandlerExceptionResolver.class.getDeclaredField(EXCEPTION_HANDLER_ADVICE_CACHE);
            field.setAccessible(true);
            return (Map<Object, Object>) field.get(exceptionResolver);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "ExceptionHandlerExceptionResolver cache field not found: " + EXCEPTION_HANDLER_ADVICE_CACHE
                            + " (Spring version changed?)", e);
        }
    }

    /** Reflectively accesses RequestMappingHandlerAdapter's advice caches (modelAttributeAdviceCache/initBinderAdviceCache). */
    @SuppressWarnings("unchecked")
    private Map<Object, Object> adapterAdviceCache(String fieldName) {
        try {
            Field field = RequestMappingHandlerAdapter.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (Map<Object, Object>) field.get(handlerAdapter);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "RequestMappingHandlerAdapter cache field not found: " + fieldName + " (Spring version changed?)", e);
        }
    }

    /** Removes the handlerType entry from owner's {@code cacheField} (a Class-keyed Map). */
    private static void evictClassKeyedCache(Class<?> ownerType, Object owner, String cacheField, Class<?> handlerType) {
        try {
            Field field = ownerType.getDeclaredField(cacheField);
            field.setAccessible(true);
            Object value = field.get(owner);
            if (value instanceof Map<?, ?> cache) {
                cache.remove(handlerType);
            }
        } catch (NoSuchFieldException e) {
            // Case where a Spring version renamed the cache field — fail fast to avoid a latent leak.
            throw new IllegalStateException(
                    ownerType.getSimpleName() + " cache field not found: " + cacheField + " (Spring version changed?)", e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /** Finds the ExceptionHandlerExceptionResolver hidden inside a composite (null if not found). */
    private static ExceptionHandlerExceptionResolver findExceptionResolver(ApplicationContext context) {
        for (HandlerExceptionResolver resolver : context.getBeansOfType(HandlerExceptionResolver.class).values()) {
            ExceptionHandlerExceptionResolver found = unwrapExceptionResolver(resolver);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static ExceptionHandlerExceptionResolver unwrapExceptionResolver(HandlerExceptionResolver resolver) {
        if (resolver instanceof ExceptionHandlerExceptionResolver ehr) {
            return ehr;
        }
        if (resolver instanceof HandlerExceptionResolverComposite composite) {
            for (HandlerExceptionResolver child : composite.getExceptionResolvers()) {
                ExceptionHandlerExceptionResolver found = unwrapExceptionResolver(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private RequestMappingInfo mappingFor(Method method, Class<?> handlerType) {
        try {
            return (RequestMappingInfo) getMappingForMethod.invoke(handlerMapping, method, handlerType);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("mapping parse failed: " + method, e);
        }
    }
}
