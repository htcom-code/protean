/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProblemDetail;

/**
 * Result of an MCP {@code tools/call}. {@code text} is the human-readable summary, {@code structured} is an optional
 * structured payload (MCP {@code structuredContent}), and {@code isError} flags a tool-execution failure.
 *
 * <p>For future-proofing, the deploy tool is designed so {@code structured} can carry either a terminal state
 * (ModuleStatus) or a {@code jobId} union. Currently it always returns only the terminal state.
 *
 * <p>Stable-code errors ({@link #error(ErrorCode, String)}) carry an RFC 9457 shape in {@code problem}, and the
 * boundary ({@code McpDispatcher.toolResultNode}) serializes it into {@code structuredContent}. The mapping lives
 * in one boundary — the mapper is not placed here. Plain-text errors without a {@code problem} are promoted to
 * codes in a later phase.
 */
public record McpToolResult(String text, boolean isError, JsonNode structured, JsonNode meta, ProblemDetail problem) {

    public static McpToolResult ok(String text) {
        return new McpToolResult(text, false, null, null, null);
    }

    public static McpToolResult ok(String text, JsonNode structured) {
        return new McpToolResult(text, false, structured, null, null);
    }

    /** Plain-text error without a code (legacy/not-yet-migrated path). {@code isError:true} + text only, no structuredContent. */
    public static McpToolResult error(String text) {
        return new McpToolResult(text, true, null, null, null);
    }

    /**
     * Stable-code error (RFC 9457). {@code text} is a {@code [CODE]}-prefixed detail for lossy harnesses, and
     * {@code problem} is the problem-detail shape that the boundary serializes into {@code structuredContent}.
     * Attach structured remediation data with {@link #with} (e.g. {@code gate}, {@code diagnostics}).
     */
    public static McpToolResult error(ErrorCode code, String detail) {
        return new McpToolResult(code.prefixed(detail), true, null, null,
                ProblemDetail.of(code).detail(detail));
    }

    /** Attach an extension member (structured remediation data), fluent. Valid only on {@link #error(ErrorCode, String)} results. */
    public McpToolResult with(String key, Object value) {
        if (problem != null) {
            problem.ext(key, value);
        }
        return this;
    }

    /** Return the result carrying {@code _meta} (arbitrary JSON). Used when a tool passes along supplementary metadata. */
    public McpToolResult withMeta(JsonNode meta) {
        return new McpToolResult(text, isError, structured, meta, problem);
    }
}
