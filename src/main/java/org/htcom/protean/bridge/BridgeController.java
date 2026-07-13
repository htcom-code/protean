/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JavaType;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * Main-side RPC bridge endpoint. When a worker delegates a shared-bean method call,
 * the actual main-side bean is executed via reflection and the result is returned
 * (worker-to-main shared business logic invocation).
 *
 * Type handling: resolves primitive type names and converts arguments to their declared
 * types (including DTOs and composite types, via Jackson convertValue).
 */
@RestController
@Profile("!worker")
public class BridgeController {

    private static final Map<String, Class<?>> PRIMITIVES = Map.of(
            "int", int.class, "long", long.class, "double", double.class, "boolean", boolean.class,
            "float", float.class, "short", short.class, "byte", byte.class, "char", char.class);

    private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
            int.class, Integer.class, long.class, Long.class, double.class, Double.class,
            boolean.class, Boolean.class, float.class, Float.class, short.class, Short.class,
            byte.class, Byte.class, char.class, Character.class);

    private final ApplicationContext context;
    private final ObjectMapper mapper;

    public BridgeController(ApplicationContext context, ObjectMapper mapper) {
        this.context = context;
        this.mapper = mapper;
    }

    @PostMapping("/__bridge/invoke")
    public ResponseEntity<?> invoke(@RequestBody BridgeInvocation inv) throws Exception {
        Class<?> iface = Class.forName(inv.iface());
        // If beanName is present, select a specific bean by name (for multiple implementations of the
        // same interface); otherwise resolve a single bean by type.
        Object bean = (inv.beanName() != null && !inv.beanName().isBlank())
                ? context.getBean(inv.beanName(), iface)
                : context.getBean(iface);

        Class<?>[] types = new Class<?>[inv.argTypes().size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = resolveType(inv.argTypes().get(i));
        }
        // Resolve the method from the public interface rather than the impl class — this way invoke
        // works without IllegalAccessException even for lambdas, JDK/CGLIB proxies, and package-private
        // implementation beans, and the generic signature (e.g. List<T>) is obtained as declared so
        // arguments deserialize accurately.
        Method method = iface.getMethod(inv.method(), types);
        Type[] genericParams = method.getGenericParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            JavaType jt = typeOf(genericParams[i]);
            args[i] = mapper.convertValue(inv.args().get(i), jt);
        }

        // If the bean is a @Transactional proxy, the call runs inside the main transaction boundary
        // (via the getBean proxy). A business exception is a normal result, not a transport failure —
        // its type/message is wrapped in the envelope and propagated to the worker.
        try {
            Object result = method.invoke(bean, args);
            if (result instanceof InputStream stream) {
                // Streamed return: transfer as octet-stream (chunked) instead of buffering the whole
                // payload into the JSON envelope. The worker reads it as a lazily-consumed InputStream.
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(new InputStreamResource(stream));
            }
            return ResponseEntity.ok(BridgeResult.ok(result));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            // A business exception is a normal result (HTTP 200 + JSON error envelope), not a transport
            // failure. Wrap type/message + remote stack + cause chain so the worker can reconstruct it.
            return ResponseEntity.ok(BridgeResult.failed(BridgeError.of(cause)));
        }
    }

    /** Builds a Jackson JavaType, keeping generic types as-is and mapping primitives to their wrappers. */
    private JavaType typeOf(Type type) {
        if (type instanceof Class<?> c && c.isPrimitive()) {
            return mapper.getTypeFactory().constructType(WRAPPERS.get(c));
        }
        return mapper.getTypeFactory().constructType(type);
    }

    private static Class<?> resolveType(String name) throws ClassNotFoundException {
        Class<?> primitive = PRIMITIVES.get(name);
        return primitive != null ? primitive : Class.forName(name);
    }
}
