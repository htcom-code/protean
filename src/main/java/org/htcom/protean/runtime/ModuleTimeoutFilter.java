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
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Request execution timeout filter (gate 2 runtime guard).
 * When {@code protean.module.request-timeout-ms} > 0, requests are registered with the watchdog,
 * which interrupts the thread if the deadline is exceeded.
 *
 * Cooperative limitation: only blocking work is interruptible (CPU spins cannot be stopped —
 * see {@link ModuleExecutionWatchdog}).
 */
@Component
public class ModuleTimeoutFilter extends OncePerRequestFilter {

    private final ModuleExecutionWatchdog watchdog;
    private final ProteanProperties props;
    /** Registry of debug-active paths. Absent (null) on the worker profile — in that case it behaves normally without error. */
    private final DebugRouteRegistry debugRoutes;

    public ModuleTimeoutFilter(ModuleExecutionWatchdog watchdog, ProteanProperties props,
                               ObjectProvider<DebugRouteRegistry> debugRoutesProvider) {
        this.watchdog = watchdog;
        this.props = props;
        this.debugRoutes = debugRoutesProvider.getIfAvailable();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Read live so protean.module.request-timeout-ms changes take effect on the next request (Tier 1).
        long timeoutMs = props.getModule().getRequestTimeoutMs();
        // Do not apply the execution timeout if the watchdog is disabled, or if this path is
        // being served by a debug-launch session. A breakpoint pause holds the request thread
        // indefinitely, so killing it via the watchdog would make debugging impossible.
        if (timeoutMs <= 0
                || (debugRoutes != null && debugRoutes.isActive(request.getRequestURI()))) {
            chain.doFilter(request, response);
            return;
        }
        long token = watchdog.register(timeoutMs);
        boolean timedOut = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            // The handler may have thrown due to an interrupt — decide the mapping by whether it timed out
            if (watchdog.deregister(token)) {
                handleTimeout(response);
                return;
            }
            throw e;
        } finally {
            // Normal path: deregister + clear the pool thread's interrupt state
            timedOut = watchdog.deregister(token) || timedOut;
            Thread.interrupted();
        }
        if (timedOut && !response.isCommitted()) {
            handleTimeout(response);
        }
    }

    private void handleTimeout(HttpServletResponse response) throws IOException {
        Thread.interrupted(); // clear interrupt state (in case the pool thread is reused)
        if (!response.isCommitted()) {
            response.reset();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE); // 503
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("module execution timeout");
        }
    }
}
