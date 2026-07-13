/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.runtime;

import jakarta.servlet.FilterChain;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Regression guard for the main leg — even when {@code request-timeout-ms>0} is enabled, debug-active
 * routes must be exempted from the execution watchdog so a breakpoint stop is not killed. Non-debug
 * routes must still time out (503), keeping the safety net. (Same package, so the watchdog's
 * package-private start/stop can be called.)
 */
class ModuleTimeoutFilterDebugTest {

    private static ObjectProvider<DebugRouteRegistry> provider(DebugRouteRegistry reg) {
        return new ObjectProvider<>() {
            @Override public DebugRouteRegistry getObject() { return reg; }
            @Override public DebugRouteRegistry getObject(Object... args) { return reg; }
            @Override public DebugRouteRegistry getIfAvailable() { return reg; }
            @Override public DebugRouteRegistry getIfUnique() { return reg; }
        };
    }

    /** When interrupted by the watchdog, raise a RuntimeException so the filter's timeout mapping kicks in. */
    private static FilterChain sleepingChain(long ms) {
        return (rq, rs) -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted by watchdog");
            }
        };
    }

    @Test
    void debug_route_is_exempt_but_normal_route_still_times_out() throws Exception {
        ModuleExecutionWatchdog watchdog = new ModuleExecutionWatchdog();
        watchdog.start();
        try {
            ProteanProperties props = new ProteanProperties();
            props.getModule().setRequestTimeoutMs(30);   // enable the watchdog
            DebugRouteRegistry reg = new DebugRouteRegistry();
            reg.add(List.of("/dbg/x"));
            ModuleTimeoutFilter filter = new ModuleTimeoutFilter(watchdog, props, provider(reg));

            // debug-active route: even taking 200ms is exempt -> not 503 (breakpoint stop allowed)
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/dbg/x");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, sleepingChain(200));
            assertNotEquals(503, res.getStatus(), "debug routes must be exempt from the watchdog");

            // non-debug route: watchdog fires -> 503 (safety net retained)
            MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/other/slow");
            MockHttpServletResponse res2 = new MockHttpServletResponse();
            filter.doFilter(req2, res2, sleepingChain(300));
            assertEquals(503, res2.getStatus(), "non-debug routes must return 503 on request-timeout");
        } finally {
            watchdog.stop();
        }
    }
}
