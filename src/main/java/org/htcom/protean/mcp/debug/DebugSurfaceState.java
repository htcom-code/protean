/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.debug;

/**
 * <b>Execution gate</b> state for the debug surface. Debug tool beans are always registered and exposed when
 * {@code mcp.enabled}, but actual invocation is only permitted while this state is enabled
 * (the DEBUG choke point in {@link org.htcom.protean.mcp.McpDispatcher}).
 *
 * <p>The initial value is {@code protean.mcp.debug.enabled} (default false = production posture). <b>Runtime-mutable</b> —
 * when a consumer flips it via {@link #setEnabled(boolean)} from its own authorized admin path, debug execution
 * opens and closes <b>immediately, with no restart or reconnect</b> (the tools are already in the list, so there is
 * nothing new for the client to receive).
 *
 * <p>Security: when false, debug calls are immediately rejected with {@code isError}("debug surface disabled"), so there
 * are no side effects at all, such as spawning a JDWP worker. This flag gate plus the consumer's
 * {@code ModuleActionAuthorizer} (DEBUG action) provide a double layer of defense.
 */
public final class DebugSurfaceState {

    private volatile boolean enabled;

    public DebugSurfaceState(boolean initial) {
        this.enabled = initial;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
