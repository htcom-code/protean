/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.proxy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.htcom.protean.dynamic.DynamicEndpointRegistrar.RouteInfo;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main-side reverse proxy. Registers the module paths owned by a worker on the main
 * DispatcherServlet and forwards incoming requests to the worker port (worker/container isolation).
 * Returns 502 when the worker is down.
 *
 * <p>Forwards the request verbatim — original HTTP method, body (streamed), and headers — and streams
 * the worker's response (status + headers + body) back, so an isolated module behaves the same as an
 * in-process one regardless of method (isolation transparency). Hop-by-hop and JDK-restricted headers
 * are stripped in both directions.
 */
@Component
@Profile("!worker")
public class ReverseProxy {

    /** Request headers not forwarded to the worker (hop-by-hop, or set by the JDK HttpClient / target URI itself). */
    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "upgrade",
            "keep-alive", "proxy-connection", "te", "trailer", "expect", "via");
    /** Response headers not copied back (hop-by-hop, or recomputed by the servlet container as it writes the body). */
    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
            "connection", "transfer-encoding", "keep-alive", "content-length",
            "proxy-connection", "te", "trailer", "upgrade");

    private final RequestMappingHandlerMapping handlerMapping;
    private final Method handleMethod;
    private final HttpClient client;

    /** path pattern -> RequestMappingInfo (for unregistration). */
    private final Map<String, RequestMappingInfo> routes = new ConcurrentHashMap<>();
    /** exact path -> worker port. */
    private final Map<String, Integer> portByPath = new ConcurrentHashMap<>();
    /** exact path -> owning module id, so request-trace attribution works for proxied (worker/container) routes. */
    private final Map<String, String> moduleIdByPath = new ConcurrentHashMap<>();
    /** exact path -> HTTP method set the worker's controller declared (empty = all methods). For route listing parity. */
    private final Map<String, Set<String>> methodsByPath = new ConcurrentHashMap<>();

    public ReverseProxy(HttpClient client, RequestMappingHandlerMapping handlerMapping) {
        this.client = client;
        this.handlerMapping = handlerMapping;
        try {
            this.handleMethod = ReverseProxy.class.getMethod("handle",
                    HttpServletRequest.class, HttpServletResponse.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Registers the path on the main mapping so it forwards to the worker port (no module attribution, no methods). */
    public synchronized void register(String path, int port) {
        register(path, Set.of(), port, null);
    }

    /** Registers the path forwarding to the worker port, recording the owning module (no declared methods). */
    public synchronized void register(String path, int port, String moduleId) {
        register(path, Set.of(), port, moduleId);
    }

    /**
     * Registers the path on the main mapping so it forwards to the worker port, recording the owning
     * {@code moduleId} (so request-trace attribution via {@link #moduleIdForPattern} works the same as in-process)
     * and the declared HTTP {@code methods} (so {@link #routesForModule} reports them — an empty set means the
     * worker mapping allowed all methods). The main mapping itself is registered method-agnostically so every
     * method reaches {@link #handle} and is forwarded verbatim.
     */
    public synchronized void register(String path, Set<String> methods, int port, String moduleId) {
        portByPath.put(path, port);
        if (moduleId != null) {
            moduleIdByPath.put(path, moduleId);
        }
        methodsByPath.put(path, methods == null ? Set.of() : Set.copyOf(methods));
        RequestMappingInfo.BuilderConfiguration cfg = new RequestMappingInfo.BuilderConfiguration();
        cfg.setPatternParser(handlerMapping.getPatternParser());
        RequestMappingInfo info = RequestMappingInfo.paths(path).options(cfg).build();
        handlerMapping.registerMapping(info, this, handleMethod);
        routes.put(path, info);
    }

    /** The module id owning the given path pattern, if this proxy registered it (for trace attribution). */
    public Optional<String> moduleIdForPattern(String pattern) {
        return pattern == null ? Optional.empty() : Optional.ofNullable(moduleIdByPath.get(pattern));
    }

    /**
     * Routes (HTTP method set + path) this proxy has registered for the given module (worker/container isolation),
     * aggregated by callers with the in-process {@code DynamicEndpointRegistrar} routes so the route listing is
     * identical across isolation modes. Empty list if the module is not proxied here.
     */
    public List<RouteInfo> routesForModule(String moduleId) {
        if (moduleId == null) {
            return List.of();
        }
        return moduleIdByPath.entrySet().stream()
                .filter(e -> moduleId.equals(e.getValue()))
                .map(e -> new RouteInfo(methodsByPath.getOrDefault(e.getKey(), Set.of()), List.of(e.getKey())))
                .toList();
    }

    /**
     * Swaps only the target port <b>atomically</b> while keeping the route (and its declared methods) in place
     * (zero-downtime worker hot-swap). No mapping unregister/register, so no 404 window opens.
     */
    public synchronized void repoint(String path, int port) {
        if (routes.containsKey(path)) {
            portByPath.put(path, port);   // methods unchanged
        } else {
            register(path, methodsByPath.getOrDefault(path, Set.of()), port, moduleIdByPath.get(path));
        }
    }

    /**
     * Atomic port swap that also refreshes the declared method set — used on hot-swap, where a new module version
     * may change a route's HTTP method(s).
     */
    public synchronized void repoint(String path, Set<String> methods, int port) {
        if (routes.containsKey(path)) {
            portByPath.put(path, port);
            methodsByPath.put(path, methods == null ? Set.of() : Set.copyOf(methods));
        } else {
            register(path, methods, port, moduleIdByPath.get(path));
        }
    }

    /** The worker port this path currently forwards to (null if unregistered). Used to record the
     *  original port before handing over to debug-launch. */
    public Integer portOf(String path) {
        return portByPath.get(path);
    }

    public synchronized void unregister(String path) {
        portByPath.remove(path);
        moduleIdByPath.remove(path);
        methodsByPath.remove(path);
        RequestMappingInfo info = routes.remove(path);
        if (info != null) {
            handlerMapping.unregisterMapping(info);
        }
    }

    /** Proxy handler — forwards the request verbatim to the worker port for the matched path and streams the response. */
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Integer port = portByPath.get(request.getRequestURI());
        if (port == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String query = request.getQueryString() != null ? "?" + request.getQueryString() : "";
        String target = "http://localhost:" + port + request.getRequestURI() + query;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target));
            copyRequestHeaders(request, b);
            b.method(request.getMethod(), requestBody(request));
            HttpResponse<InputStream> wr = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            response.setStatus(wr.statusCode());
            copyResponseHeaders(wr, response);
            try (InputStream in = wr.body(); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY); // 502
        } catch (Exception e) {
            // worker down / connection failure → gateway error (main is unaffected)
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY); // 502
        }
    }

    /** Streams the request body when one is present; no body for bodyless requests (e.g. plain GET). */
    private static HttpRequest.BodyPublisher requestBody(HttpServletRequest request) {
        long len = request.getContentLengthLong();
        boolean chunked = "chunked".equalsIgnoreCase(request.getHeader("Transfer-Encoding"));
        if (len <= 0 && !chunked) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                return request.getInputStream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder b) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String lower = name.toLowerCase(Locale.ROOT);
            if (SKIP_REQUEST_HEADERS.contains(lower) || lower.startsWith("sec-") || lower.startsWith("proxy-")) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                try {
                    b.header(name, values.nextElement());
                } catch (IllegalArgumentException ignored) {
                    // JDK HttpClient rejects a restricted header not in the skip set above — drop it rather than fail.
                }
            }
        }
    }

    private static void copyResponseHeaders(HttpResponse<?> wr, HttpServletResponse response) {
        wr.headers().map().forEach((name, values) -> {
            if (SKIP_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
    }
}
