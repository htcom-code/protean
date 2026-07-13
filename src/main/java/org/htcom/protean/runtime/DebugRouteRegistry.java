/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.runtime;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of proxy paths currently being served by a debug-launch session (main-side leg).
 * {@link ModuleTimeoutFilter} skips the execution watchdog for these paths — since a breakpoint
 * pause holds the request thread indefinitely, debug paths must not be killed even when the
 * consumer has enabled {@code request-timeout-ms>0}.
 *
 * <p>Main-only ({@code @Profile("!worker")}). Uses exact-path matching, consistent with the
 * current model in which {@link org.htcom.protean.proxy.ReverseProxy} forwards the request URI
 * exactly. {@link org.htcom.protean.isolation.WorkerProcessIsolation} adds/removes entries on launch/terminate, and the
 * filter queries them via {@link #isActive(String)}.
 */
@Component
@Profile("!worker")
public class DebugRouteRegistry {

    private final Set<String> paths = ConcurrentHashMap.newKeySet();

    public void add(Collection<String> routePaths) {
        paths.addAll(routePaths);
    }

    public void remove(Collection<String> routePaths) {
        paths.removeAll(routePaths);
    }

    /** Whether this request path is currently being served by a debug session. */
    public boolean isActive(String path) {
        return paths.contains(path);
    }
}
