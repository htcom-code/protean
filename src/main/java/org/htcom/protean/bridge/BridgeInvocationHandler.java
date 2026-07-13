/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Proxy handler for any shared interface — delegates method calls to the main-side {@code /__bridge/invoke}.
 * Works regardless of the interface (generalized). Supports primitive, wrapper, composite DTO, and
 * generic collection (e.g. List&lt;T&gt;) arguments/returns, and business exception propagation
 * (reconstructing the same type).
 */
public final class BridgeInvocationHandler implements InvocationHandler {

    private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
            int.class, Integer.class, long.class, Long.class, double.class, Double.class,
            boolean.class, Boolean.class, float.class, Float.class, short.class, Short.class,
            byte.class, Byte.class, char.class, Character.class);

    private final String bridgeUrl;
    private final Class<?> iface;
    private final ObjectMapper mapper;
    /** Target bean name (specify when an interface has multiple implementations). If null, main resolves a single bean by type. */
    private final String beanName;
    /** Shared secret presented to authenticate the call (null when auth is disabled). */
    private final String secret;
    /** Authentication scheme: {@code token} (bearer) or {@code hmac} (signed). */
    private final String authMode;
    private final HttpClient client = HttpClient.newHttpClient();
    private final SecureRandom random = new SecureRandom();

    public BridgeInvocationHandler(String bridgeUrl, Class<?> iface, ObjectMapper mapper) {
        this(bridgeUrl, iface, mapper, null, null, "token");
    }

    public BridgeInvocationHandler(String bridgeUrl, Class<?> iface, ObjectMapper mapper, String beanName) {
        this(bridgeUrl, iface, mapper, beanName, null, "token");
    }

    public BridgeInvocationHandler(String bridgeUrl, Class<?> iface, ObjectMapper mapper,
                                   String beanName, String secret) {
        this(bridgeUrl, iface, mapper, beanName, secret, "token");
    }

    public BridgeInvocationHandler(String bridgeUrl, Class<?> iface, ObjectMapper mapper,
                                   String beanName, String secret, String authMode) {
        this.bridgeUrl = bridgeUrl;
        this.iface = iface;
        this.mapper = mapper;
        this.beanName = beanName;
        this.secret = secret;
        this.authMode = authMode != null ? authMode : "token";
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "BridgeProxy[" + iface.getName() + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> null;
            };
        }
        List<String> argTypes = new ArrayList<>();
        for (Class<?> p : method.getParameterTypes()) {
            argTypes.add(p.getName());
        }
        BridgeInvocation inv = new BridgeInvocation(
                iface.getName(), method.getName(), argTypes,
                args == null ? List.of() : Arrays.asList(args), beanName);

        byte[] bodyBytes = mapper.writeValueAsBytes(inv);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(bridgeUrl + "/__bridge/invoke"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
        if (secret != null && !secret.isBlank()) {
            if ("hmac".equalsIgnoreCase(authMode)) {
                // Sign timestamp + nonce + exact body bytes so main can detect tampering/replay.
                long ts = System.currentTimeMillis();
                byte[] nonceBytes = new byte[16];
                random.nextBytes(nonceBytes);
                String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
                req.header(BridgeHmac.TS_HEADER, Long.toString(ts));
                req.header(BridgeHmac.NONCE_HEADER, nonce);
                req.header(BridgeHmac.SIG_HEADER, BridgeHmac.sign(secret, ts, nonce, bodyBytes));
            } else {
                req.header("Authorization", "Bearer " + secret);
            }
        }
        if (method.getReturnType() == InputStream.class) {
            // Streamed return: read the response body lazily instead of buffering the whole payload.
            return receiveStream(req);
        }
        HttpResponse<String> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            // Transport/infrastructure-level failure (bean/method not found, etc.) — distinct from a business exception
            throw new IllegalStateException("bridge call failed " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode errorNode = root.get("error");
        if (errorNode != null && !errorNode.isNull()) {
            // Business exception thrown by the main bean — restore type, message, remote stack, and
            // cause chain, then rethrow (the @Transactional rollback has already happened on main).
            throw reconstruct(mapper.convertValue(errorNode, BridgeError.class));
        }
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class) {
            return null;
        }
        // To restore a generic return (e.g. List<T>) without erasure, deserialize using the declared generic return type.
        return mapper.convertValue(root.get("result"), typeOf(method.getGenericReturnType()));
    }

    /**
     * Sends the invocation and returns the response body as a lazily-consumed {@link InputStream}
     * (octet-stream). If the method threw before returning a stream, main replies with a JSON error
     * envelope instead — that is reconstructed and rethrown.
     */
    private InputStream receiveStream(HttpRequest.Builder req) throws Throwable {
        HttpResponse<InputStream> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("bridge call failed " + resp.statusCode() + ": " + err);
        }
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        if (contentType.startsWith("application/json")) {
            JsonNode root = mapper.readTree(resp.body());
            JsonNode errorNode = root.get("error");
            if (errorNode != null && !errorNode.isNull()) {
                throw reconstruct(mapper.convertValue(errorNode, BridgeError.class));
            }
            return null;   // JSON without an error on a stream-returning method — nothing to stream
        }
        return resp.body();   // lazily-consumed stream backed by the live HTTP response
    }

    /** Builds a Jackson JavaType, keeping generic types as-is and mapping primitives to their wrappers. */
    private JavaType typeOf(Type type) {
        if (type instanceof Class<?> c && c.isPrimitive()) {
            return mapper.getTypeFactory().constructType(WRAPPERS.get(c));
        }
        return mapper.getTypeFactory().constructType(type);
    }

    /**
     * Reconstructs the remote exception as the same type (recursive: restore the cause chain first,
     * then link it, then restore the remote stack). Falls back to {@link BridgeRemoteException} when
     * the type/constructor is unavailable.
     */
    private Throwable reconstruct(BridgeError err) {
        if (err == null) {
            return new BridgeRemoteException("unknown", null);
        }
        Throwable cause = err.cause() != null ? reconstruct(err.cause()) : null;
        Throwable t = instantiate(err.type(), err.message(), cause);
        if (cause != null && t.getCause() == null) {
            try {
                t.initCause(cause);
            } catch (IllegalStateException ignored) {
                // Type whose cause is already set — ignore
            }
        }
        if (err.stackTrace() != null && !err.stackTrace().isEmpty()) {
            t.setStackTrace(parseFrames(err.stackTrace()));
        }
        return t;
    }

    /** Instantiates the exception, trying (String,Throwable) → (String) → (Throwable) → no-arg in order, falling back if none work. */
    private static Throwable instantiate(String type, String message, Throwable cause) {
        try {
            Class<?> c = Class.forName(type);
            if (Throwable.class.isAssignableFrom(c)) {
                try {
                    return (Throwable) c.getConstructor(String.class, Throwable.class).newInstance(message, cause);
                } catch (NoSuchMethodException ignored) { /* next */ }
                try {
                    return (Throwable) c.getConstructor(String.class).newInstance(message);
                } catch (NoSuchMethodException ignored) { /* next */ }
                try {
                    return (Throwable) c.getConstructor(Throwable.class).newInstance(cause);
                } catch (NoSuchMethodException ignored) { /* next */ }
                return (Throwable) c.getDeclaredConstructor().newInstance();
            }
        } catch (ReflectiveOperationException ignored) {
            // Type/constructor not available on the worker — fall back
        }
        return new BridgeRemoteException(type, message);
    }

    /** Best-effort restore of remote stack frame strings into StackTraceElements (frames that fail to parse are preserved as synthetic elements). */
    private static StackTraceElement[] parseFrames(List<String> frames) {
        StackTraceElement[] out = new StackTraceElement[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            out[i] = parseFrame(frames.get(i));
        }
        return out;
    }

    private static StackTraceElement parseFrame(String raw) {
        String f = raw.trim();
        if (f.startsWith("at ")) {
            f = f.substring(3);
        }
        int open = f.indexOf('(');
        int close = f.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return new StackTraceElement(raw, "", null, -1);   // unparseable → preserve raw text
        }
        String head = f.substring(0, open);            // [module/]decl.Class.method
        String loc = f.substring(open + 1, close);     // File.java:42 | Native Method | Unknown Source
        int slash = head.indexOf('/');
        if (slash >= 0) {
            head = head.substring(slash + 1);          // strip module prefix
        }
        int lastDot = head.lastIndexOf('.');
        if (lastDot <= 0) {
            return new StackTraceElement(raw, "", null, -1);
        }
        String declClass = head.substring(0, lastDot);
        String method = head.substring(lastDot + 1);
        String file = null;
        int line = -1;
        int colon = loc.lastIndexOf(':');
        if (colon >= 0) {
            file = loc.substring(0, colon);
            try {
                line = Integer.parseInt(loc.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) { /* keep line at -1 */ }
        } else if (!loc.isBlank()) {
            file = loc;
        }
        return new StackTraceElement(declClass, method, file, line);
    }
}
