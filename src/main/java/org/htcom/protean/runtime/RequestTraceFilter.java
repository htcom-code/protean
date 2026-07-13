/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.runtime;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.htcom.protean.dynamic.DynamicEndpointRegistrar;
import org.htcom.protean.error.ProblemDetail;
import org.htcom.protean.proxy.ReverseProxy;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Request execution trace filter (runtime trace PoC).
 * Records the entry-to-response elapsed time, status, and exception of every request, and
 * attributes it to a dynamic module via the matched handler pattern.
 *
 * <p>Runs outermost (highest precedence) so it covers both the timeout filter and handler
 * execution time. The trace query endpoint itself (`/platform/traces`) is not recorded, to avoid
 * self-noise.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceFilter extends OncePerRequestFilter {

    private final TraceStore store;
    private final DynamicEndpointRegistrar registrar;
    private final TraceMetrics metrics;
    /**
     * Host-only bean ({@code @Profile("!worker")}), so injected lazily via a provider: this filter
     * also runs in the worker profile where no {@link ReverseProxy} exists. Used to attribute
     * proxied (worker/container) routes, which the {@code registrar} does not know about.
     */
    private final ObjectProvider<ReverseProxy> proxy;

    public RequestTraceFilter(TraceStore store, DynamicEndpointRegistrar registrar, TraceMetrics metrics,
                              ObjectProvider<ReverseProxy> proxy) {
        this.store = store;
        this.registrar = registrar;
        this.metrics = metrics;
        this.proxy = proxy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!store.enabled() || request.getRequestURI().startsWith("/platform/traces")) {
            chain.doFilter(request, response);
            return;
        }
        long start = System.nanoTime();
        boolean recorded = false;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            record(request, response, start, e.getClass().getName());
            recorded = true;
            throw e;
        } finally {
            if (!recorded) {
                record(request, response, start, null);
            }
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, long startNanos, String error) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        long now = System.currentTimeMillis();
        int status = response.getStatus();
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        // In-process modules are known to the registrar; worker/container modules are proxied routes,
        // so fall back to the reverse proxy's path→moduleId map for their attribution.
        String moduleId = registrar.moduleIdForPattern(pattern).orElse(null);
        if (moduleId == null && pattern != null) {
            ReverseProxy p = proxy.getIfAvailable();
            if (p != null) {
                moduleId = p.moduleIdForPattern(pattern).orElse(null);
            }
        }
        // Aggregate first (lock-free), off the ring-buffer lock, to keep that critical section minimal.
        metrics.observe(moduleId, status, latencyMs, error, now);
        // CorrelationIdFilter runs outermost, so the traceId is present in the MDC at record time.
        String traceId = MDC.get(ProblemDetail.TRACE_ID_MDC_KEY);
        store.record(now, request.getMethod(), request.getRequestURI(),
                pattern, moduleId, status, latencyMs, error, traceId);
    }
}
