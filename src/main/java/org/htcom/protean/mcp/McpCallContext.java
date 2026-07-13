/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.security.Principal;

/**
 * Tool call context. {@code caller} is the authenticated principal populated by the consumer's Spring
 * Security (null = local stdio / unauthenticated). {@code progress} is the per-stage progress
 * notification sink for long-running deployments (only actually emitted when the client supplied a
 * progressToken; otherwise {@link #NOOP}). {@code sessionId} is the MCP session this call belongs to —
 * the destination for server-to-client requests (sampling/roots/elicitation). null = stateless/stdio
 * (requests not possible).
 */
public record McpCallContext(Principal caller, ProgressSink progress, String sessionId,
                             McpCancellation cancellation, JsonNode meta) {

    /** Backward compatibility — session-less (stateless/stdio) context. */
    public McpCallContext(Principal caller, ProgressSink progress) {
        this(caller, progress, null, McpCancellation.NONE, null);
    }

    /** Backward compatibility — session only (no cancellation or _meta). */
    public McpCallContext(Principal caller, ProgressSink progress, String sessionId) {
        this(caller, progress, sessionId, McpCancellation.NONE, null);
    }

    /** Gate-stage progress notification sink. */
    public interface ProgressSink {
        void report(int current, int total, String message);
    }

    public static final ProgressSink NOOP = (current, total, message) -> {
    };

    public static McpCallContext anonymous() {
        return new McpCallContext(null, NOOP, null, McpCancellation.NONE, null);
    }
}
