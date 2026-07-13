/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

/**
 * Request cancellation token. When {@code notifications/cancelled} is received, the token of the
 * corresponding in-flight request is moved to the cancelled state and the worker thread is
 * interrupted. The cooperative cancellation points are progress notifications — the progress sink
 * wrapped by the dispatcher calls {@link #throwIfCancelled()} at each stage, so a long-running tool
 * aborts at the next stage boundary.
 */
public final class McpCancellation {

    /** Not cancellable (default) — for contexts that are not registration targets, such as notifications. */
    public static final McpCancellation NONE = new McpCancellation();

    private volatile boolean cancelled;
    private volatile String reason;

    public void cancel(String reason) {
        this.reason = reason;
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public String reason() {
        return reason;
    }

    /** Throws {@link Cancelled} if cancelled (mapped to the tool result's isError). */
    public void throwIfCancelled() {
        if (cancelled) {
            throw new Cancelled(reason);
        }
    }

    /** Cooperative cancellation signal exception. */
    public static final class Cancelled extends RuntimeException {
        public Cancelled(String reason) {
            super("Request cancelled" + (reason == null ? "" : ": " + reason));
        }
    }
}
