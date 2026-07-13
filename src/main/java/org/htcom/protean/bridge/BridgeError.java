/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Remote exception information propagated over the RPC bridge. In addition to the type/message of a
 * business exception thrown by a main-side bean, it carries the <b>stack trace and cause chain</b> to
 * the worker (which reconstructs the same type and rethrows it, restoring the remote stack and cause).
 * This ensures the root cause and origin are not lost when crossing the boundary.
 *
 * @param type       FQCN of the exception class
 * @param message    exception message (may be null)
 * @param stackTrace list of remote stack frame strings ({@link StackTraceElement#toString()})
 * @param cause      cause exception (recursive; null if none)
 */
public record BridgeError(String type, String message, List<String> stackTrace, BridgeError cause) {

    /** Upper bound guarding against cause-chain cycles and excessive depth. */
    private static final int MAX_DEPTH = 12;

    /** Recursively converts a Throwable to a BridgeError, preserving the cause chain and stack while guarding against cycles/depth. */
    public static BridgeError of(Throwable t) {
        return of(t, 0);
    }

    private static BridgeError of(Throwable t, int depth) {
        if (t == null || depth >= MAX_DEPTH) {
            return null;
        }
        List<String> frames = new ArrayList<>();
        for (StackTraceElement e : t.getStackTrace()) {
            frames.add(e.toString());
        }
        Throwable c = t.getCause();
        BridgeError cause = (c == null || c == t) ? null : of(c, depth + 1);
        return new BridgeError(t.getClass().getName(), t.getMessage(), frames, cause);
    }
}
