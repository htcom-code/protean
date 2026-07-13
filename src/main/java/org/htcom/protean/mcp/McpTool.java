/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A single MCP tool. The built-in protean tools (module lifecycle) implement this, and <b>consumers can
 * implement and register their own tool beans to have them exposed alongside (open core)</b> — because
 * {@code McpDispatcher} collects every {@code McpTool} bean in the context. Every tool call passes through
 * {@link ModuleActionAuthorizer} in common.
 */
public interface McpTool {

    /** MCP tool name (unique). Example: {@code protean.deploy_module}. */
    String name();

    /** Description for humans/agents. */
    String description();

    /** Input-argument JSON Schema (exposed to the agent). */
    ObjectNode inputSchema();

    /**
     * Display title (optional; spec 2025-11-25, Tool). A human-facing name distinct from {@code name} —
     * UIs prefer this for display. {@code null} = unset (the client shows {@code name}).
     */
    default String title() {
        return null;
    }

    /**
     * Output JSON Schema (optional). When declared, a successful result's {@code structuredContent} must conform
     * to it; the dispatcher validates a minimal fit (is-object and top-level {@code required}) and blocks with
     * {@code isError} on a mismatch (guarding against structuredContent-class bugs). Full schema validation is the
     * client's responsibility (per spec). {@code null} = not declared.
     */
    default ObjectNode outputSchema() {
        return null;
    }

    /** Behavior hints (optional; {@link McpToolAnnotations}). {@code null} = unset. */
    default McpToolAnnotations annotations() {
        return null;
    }

    /** Execute. Throw an exception on domain failure and the dispatcher maps it to a tool result {@code isError}. */
    McpToolResult call(JsonNode arguments, McpCallContext ctx);

    /** Authorization classification (default CUSTOM — consumer tool). Built-in protean tools override this individually. */
    default ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.CUSTOM;
    }

    /** Target module id for authorization (if any). Defaults to the {@code id} field of the arguments. */
    default String targetModuleId(JsonNode arguments) {
        return arguments != null && arguments.hasNonNull("id") ? arguments.get("id").asText() : null;
    }
}
