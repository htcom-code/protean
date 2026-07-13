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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main-side reverse proxy. Registers the module paths owned by a worker on the main
 * DispatcherServlet and forwards incoming requests to the worker port (worker isolation).
 * Returns 502 when the worker is down.
 *
 * PoC scope: GET forwarding (bodyless requests). Full-fidelity forwarding of headers,
 * streaming, and all HTTP methods is future work.
 */
@Component
@Profile("!worker")
public class ReverseProxy {

    private final RequestMappingHandlerMapping handlerMapping;
    private final Method handleMethod;
    private final HttpClient client;

    /** path pattern -> RequestMappingInfo (for unregistration). */
    private final Map<String, RequestMappingInfo> routes = new ConcurrentHashMap<>();
    /** exact path -> worker port. */
    private final Map<String, Integer> portByPath = new ConcurrentHashMap<>();
    /** exact path -> owning module id, so request-trace attribution works for proxied (worker/container) routes. */
    private final Map<String, String> moduleIdByPath = new ConcurrentHashMap<>();

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

    /** Registers the path on the main mapping so it forwards to the worker port (no module attribution). */
    public synchronized void register(String path, int port) {
        register(path, port, null);
    }

    /**
     * Registers the path on the main mapping so it forwards to the worker port, recording the owning
     * {@code moduleId} so request-trace attribution ({@link #moduleIdForPattern}) works for this
     * proxied route the same way it does for in-process modules.
     */
    public synchronized void register(String path, int port, String moduleId) {
        portByPath.put(path, port);
        if (moduleId != null) {
            moduleIdByPath.put(path, moduleId);
        }
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
     * Path patterns this proxy has registered for the given module (worker/container isolation).
     * The forwarded HTTP method is not tracked (GET-only PoC — see class javadoc), so callers that
     * surface routes must treat these as method-less. Empty list if the module is not proxied here.
     */
    public List<String> pathsForModule(String moduleId) {
        if (moduleId == null) {
            return List.of();
        }
        return moduleIdByPath.entrySet().stream()
                .filter(e -> moduleId.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Swaps only the target port <b>atomically</b> while keeping the route in place
     * (zero-downtime worker hot-swap). Because there is no mapping unregister/register,
     * no 404 window opens.
     */
    public synchronized void repoint(String path, int port) {
        if (routes.containsKey(path)) {
            portByPath.put(path, port);   // single put — instant switchover (module attribution preserved)
        } else {
            register(path, port, moduleIdByPath.get(path));   // new path — register it (keep attribution if known)
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
        RequestMappingInfo info = routes.remove(path);
        if (info != null) {
            handlerMapping.unregisterMapping(info);
        }
    }

    /** Proxy handler — forwards to the worker port for the matched path. */
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Integer port = portByPath.get(request.getRequestURI());
        if (port == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String query = request.getQueryString() != null ? "?" + request.getQueryString() : "";
        String target = "http://localhost:" + port + request.getRequestURI() + query;
        try {
            HttpResponse<byte[]> wr = client.send(
                    HttpRequest.newBuilder(URI.create(target)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            response.setStatus(wr.statusCode());
            wr.headers().firstValue("content-type").ifPresent(response::setContentType);
            response.getOutputStream().write(wr.body());
        } catch (Exception e) {
            // worker down / connection failure → gateway error (main is unaffected)
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY); // 502
        }
    }
}
