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
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolAnnotations;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool-object completeness — drives {@link McpDispatcher} directly to verify serialization of tools/list
 * title, outputSchema and annotations, plus the minimal conformance guard that blocks a successful result
 * violating its outputSchema by marking it isError. A pure unit test with no Spring context
 * (transport- and context-independent).
 */
class McpToolObjectTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleActionAuthorizer allowAll =
            (caller, action, moduleId) -> ModuleActionAuthorizer.Decision.allow();

    private McpDispatcher dispatcher(McpTool... tools) {
        return new McpDispatcher(mapper, List.of(tools), allowAll, null, null, null, null);
    }

    private JsonNode call(McpDispatcher d, String method, ObjectNode params) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        return d.dispatch(req, McpCallContext.anonymous());
    }

    /** A tool with all three fields filled in, producing {@code {value}}-shaped structured output. */
    private McpTool fullTool(boolean returnConforming) {
        return new McpTool() {
            @Override public String name() { return "test.full"; }
            @Override public String title() { return "Full Test Tool"; }
            @Override public String description() { return "test tool with all three fields filled in"; }
            @Override public ObjectNode inputSchema() {
                ObjectNode s = mapper.createObjectNode();
                s.put("type", "object");
                return s;
            }
            @Override public ObjectNode outputSchema() {
                ObjectNode s = mapper.createObjectNode();
                s.put("type", "object");
                s.putObject("properties").putObject("value").put("type", "string");
                s.putArray("required").add("value");
                return s;
            }
            @Override public McpToolAnnotations annotations() {
                return McpToolAnnotations.builder()
                        .readOnly(false).destructive(true).idempotent(false).openWorld(true).build();
            }
            @Override public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
                ObjectNode out = mapper.createObjectNode();
                if (returnConforming) {
                    out.put("value", "ok");
                } else {
                    out.put("wrong", "no required field"); // missing required "value" -> the guard must block it
                }
                return McpToolResult.ok("done", out);
            }
        };
    }

    @Test
    void tools_list_serializes_title_output_schema_and_annotations() {
        JsonNode resp = call(dispatcher(fullTool(true)), "tools/list", null);
        JsonNode tool = resp.path("result").path("tools").get(0);

        assertEquals("test.full", tool.path("name").asText());
        assertEquals("Full Test Tool", tool.path("title").asText());
        assertTrue(tool.path("outputSchema").isObject(), "outputSchema serialized");
        assertEquals("string", tool.path("outputSchema").path("properties").path("value").path("type").asText());

        JsonNode ann = tool.path("annotations");
        assertTrue(ann.isObject(), "annotations serialized");
        assertFalse(ann.path("readOnlyHint").asBoolean());
        assertTrue(ann.path("destructiveHint").asBoolean());
        assertFalse(ann.path("idempotentHint").asBoolean());
        assertTrue(ann.path("openWorldHint").asBoolean());
    }

    @Test
    void omitted_metadata_fields_are_not_serialized() {
        // A minimal tool leaving all three fields at their default (null) — they must not be exposed.
        McpTool bare = new McpTool() {
            @Override public String name() { return "test.bare"; }
            @Override public String description() { return "tool without metadata"; }
            @Override public ObjectNode inputSchema() {
                ObjectNode s = mapper.createObjectNode();
                s.put("type", "object");
                return s;
            }
            @Override public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
                return McpToolResult.ok("hi");
            }
        };
        JsonNode tool = call(dispatcher(bare), "tools/list", null).path("result").path("tools").get(0);
        assertFalse(tool.has("title"), "an unset title is omitted");
        assertFalse(tool.has("outputSchema"), "an unset outputSchema is omitted");
        assertFalse(tool.has("annotations"), "unset annotations are omitted");
    }

    @Test
    void conforming_structured_output_passes_guard() {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "test.full");
        params.set("arguments", mapper.createObjectNode());
        JsonNode result = call(dispatcher(fullTool(true)), "tools/call", params).path("result");
        assertFalse(result.path("isError").asBoolean(false), "conforming output passes: " + result);
        assertEquals("ok", result.path("structuredContent").path("value").asText());
    }

    @Test
    void nonconforming_structured_output_is_blocked_as_error() {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "test.full");
        params.set("arguments", mapper.createObjectNode());
        JsonNode result = call(dispatcher(fullTool(false)), "tools/call", params).path("result");
        assertTrue(result.path("isError").asBoolean(false), "a missing required field is blocked via isError");
        // RFC 9457: [CODE]-prefixed text + structuredContent.code.
        String text = result.path("content").get(0).path("text").asText();
        assertTrue(text.contains("[OUTPUT_SCHEMA_VIOLATION]"), text);
        assertEquals("OUTPUT_SCHEMA_VIOLATION", result.path("structuredContent").path("code").asText());
    }

    @Test
    void annotations_toJson_is_null_when_empty() {
        // When no hints are set, there must be nothing to serialize (unset != false).
        assertNull(McpToolAnnotations.builder().build().toJson(mapper));
    }
}
