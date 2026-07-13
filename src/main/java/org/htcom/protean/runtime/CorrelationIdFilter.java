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
import org.htcom.protean.error.ProblemDetail;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Correlation ID filter — establishes a stable per-request ID in the MDC so that the error
 * envelope (RFC 9457 {@code traceId} extension), logs, and {@link RequestTrace} can all
 * cross-correlate the same request.
 *
 * <p>If the client supplies {@code X-Request-Id}, it is carried through (distributed correlation);
 * otherwise a UUID is generated. The same value is echoed back on the response as
 * {@code X-Request-Id}. It runs outermost (ahead of {@link RequestTraceFilter}) so that trace
 * recording, timeouts, and handler execution all run inside this MDC context. The MDC is cleared
 * when the request ends.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = (incoming != null && !incoming.isBlank())
                ? incoming.trim()
                : UUID.randomUUID().toString();
        MDC.put(ProblemDetail.TRACE_ID_MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(ProblemDetail.TRACE_ID_MDC_KEY);
        }
    }
}
