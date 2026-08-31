/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.htcom.protean.mcp.McpException;

/**
 * Shared argument extraction for the MCP tools, so every tool rejects a missing argument the same way.
 *
 * <p>The point is that <b>absent, null and blank are one case, not three</b>. Checking only
 * {@code hasNonNull} lets {@code ""} through, and an empty id then reaches the lookup and comes back as
 * "module not found: " — an agent that mis-built its arguments is told the module is missing and goes
 * hunting for a module it never actually named. Failing here instead names the real problem.
 *
 * <p>Blank arguments are rejected as a protocol-level {@code INVALID_ARGUMENT} rather than a tool result,
 * matching the pre-existing behavior for an absent argument.
 *
 * <p>A null {@code arguments} node is tolerated defensively, not because a dispatched call can produce one:
 * {@code McpDispatcher.callTool} substitutes an empty object for an absent or null {@code arguments}, so a
 * tool reached over {@code tools/call} always gets a node. {@link org.htcom.protean.mcp.McpTool} is public,
 * though, so a consumer can invoke a tool directly — hence the check rather than an NPE.
 */
final class ToolArgs {

    private ToolArgs() {
    }

    /**
     * Extracts a required text argument. Absent, null, or blank (whitespace-only) all fail identically.
     *
     * @param arguments the tool's raw arguments (may be null)
     * @param tool      tool name used to prefix the message, e.g. {@code "get_module"}
     * @param field     argument name
     * @return the trimmed-non-blank value, never null
     */
    static String require(JsonNode arguments, String tool, String field) {
        String value = arguments == null ? null : arguments.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw McpException.invalidParams(tool + ": " + field + " is required");
        }
        return value;
    }
}
