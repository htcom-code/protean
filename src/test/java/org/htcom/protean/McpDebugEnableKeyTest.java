/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
import org.htcom.protean.mcp.debug.DebugSurfaceState;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the debug <b>execution gate</b>. Debug tools are <b>always exposed in tools/list</b>
 * when {@code mcp.enabled}; only the actual <b>call</b> is gated by {@code protean.mcp.debug.enabled}
 * (its initial value): when off, calls immediately return {@code isError} with {@code debug surface
 * disabled}; when on, they are not rejected for that reason.
 *
 * <p>(The earlier contract "disabled =&gt; not listed" is inverted here — now "always listed, only
 * execution gated" — so the surface can be opened and closed at runtime by flipping
 * {@link org.htcom.protean.mcp.debug.DebugSurfaceState} without a restart.)
 */
class McpDebugEnableKeyTest {

    private static List<String> toolNames(McpDispatcher dispatcher, ObjectMapper mapper) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 0);
        req.put("method", "tools/list");
        List<String> names = new ArrayList<>();
        dispatcher.dispatch(req, McpCallContext.anonymous())
                .path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        return names;
    }

    private static boolean hasDebugTool(List<String> names) {
        return names.stream().anyMatch(n -> n.startsWith("debug."));
    }

    private static boolean hasBaseMcpTool(List<String> names) {
        return names.stream().anyMatch(n -> n.startsWith("protean."));
    }

    /** Result of a debug.list_sessions call (a side-effect-free debug tool - used to judge the execution gate). */
    private static JsonNode callListSessions(McpDispatcher dispatcher, ObjectMapper mapper) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "debug.list_sessions");
        params.set("arguments", mapper.createObjectNode());
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/call");
        req.set("params", params);
        return dispatcher.dispatch(req, McpCallContext.anonymous()).path("result");
    }

    /** Execution gate ON (protean.mcp.debug.enabled=true): debug.* listed and calls allowed. */
    @Nested
    @SpringBootTest(properties = {"protean.mcp.enabled=true", "protean.mcp.debug.enabled=true"})
    class DebugEnabled {
        @Autowired McpDispatcher dispatcher;
        @Autowired ObjectMapper mapper;

        @DynamicPropertySource
        static void store(DynamicPropertyRegistry r) {
            r.add("protean.module-store.dir",
                    () -> Path.of(System.getProperty("java.io.tmpdir"), "protean-debuggate-on").toString());
        }

        @Test
        void debug_tools_listed_and_callable_when_enabled() {
            List<String> names = toolNames(dispatcher, mapper);
            assertTrue(hasDebugTool(names), "debug.* tools must be listed: " + names);
            JsonNode result = callListSessions(dispatcher, mapper);
            assertFalse(result.path("isError").asBoolean(false),
                    "when debug is on, debug.list_sessions must execute: " + result);
            assertTrue(result.path("structuredContent").isObject());
        }
    }

    /** Execution gate OFF (default): debug.* is still listed, but calls fail with a disabled error. */
    @Nested
    @SpringBootTest(properties = {"protean.mcp.enabled=true"})
    class DebugDisabled {
        @Autowired McpDispatcher dispatcher;
        @Autowired ObjectMapper mapper;
        @Autowired DebugSurfaceState debugState;

        @DynamicPropertySource
        static void store(DynamicPropertyRegistry r) {
            r.add("protean.module-store.dir",
                    () -> Path.of(System.getProperty("java.io.tmpdir"), "protean-debuggate-off").toString());
        }

        @Test
        void debug_tools_listed_but_call_denied_when_disabled() {
            List<String> names = toolNames(dispatcher, mapper);
            // Debug tools are always in the list even when the execution gate is off (no reconnect/listChanged needed).
            assertTrue(hasDebugTool(names), "debug.* tools must be listed even with the gate OFF: " + names);
            assertTrue(hasBaseMcpTool(names), "base MCP tools (protean.*) are listed too: " + names);

            JsonNode denied = callListSessions(dispatcher, mapper);
            assertTrue(denied.path("isError").asBoolean(false), "with the gate OFF, debug calls are isError: " + denied);
            assertTrue(denied.path("content").path(0).path("text").asText().contains("disabled"),
                    "the rejection reason must be clear (disabled): " + denied);
        }

        @Test
        void runtime_flip_opens_debug_without_restart() {
            // Initially OFF -> denied
            assertTrue(callListSessions(dispatcher, mapper).path("isError").asBoolean(false));
            try {
                // Runtime flip (admin path) -> the execution gate opens without restart/reconnect. Tools are already listed.
                debugState.setEnabled(true);
                JsonNode after = callListSessions(dispatcher, mapper);
                assertFalse(after.path("isError").asBoolean(false), "after the flip, debug calls must be allowed: " + after);
                assertTrue(after.path("structuredContent").isObject());
            } finally {
                debugState.setEnabled(false);   // restore
            }
        }
    }
}
