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
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.htcom.protean.mcp.SchemaValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strict-schema mode (opt-in): full JSON-Schema validation of tool arguments and results at the dispatch boundary,
 * the runtime guarantee for consumer custom tools that the library's own tests cannot cover. Contrasts strict with
 * the default zero-dep top-level guard, and verifies graceful fallback when the validator is unavailable.
 */
class McpStrictSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleActionAuthorizer allowAll =
            (caller, action, moduleId) -> ModuleActionAuthorizer.Decision.allow();

    /** Custom tool: inputSchema requires integer {@code n}; outputSchema requires string {@code value}. */
    private McpTool customTool(String valueToReturn, boolean returnAsInt) {
        return new McpTool() {
            @Override public String name() { return "custom.tool"; }
            @Override public String description() { return "consumer custom tool"; }
            @Override public ObjectNode inputSchema() {
                ObjectNode s = mapper.createObjectNode();
                s.put("type", "object");
                s.putObject("properties").putObject("n").put("type", "integer");
                s.putArray("required").add("n");
                return s;
            }
            @Override public ObjectNode outputSchema() {
                ObjectNode s = mapper.createObjectNode();
                s.put("type", "object");
                s.putObject("properties").putObject("value").put("type", "string");
                s.putArray("required").add("value");
                return s;
            }
            @Override public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
                ObjectNode out = mapper.createObjectNode();
                if (returnAsInt) {
                    out.put("value", Integer.parseInt(valueToReturn)); // wrong type: integer, not string
                } else {
                    out.put("value", valueToReturn);
                }
                return McpToolResult.ok("done", out);
            }
        };
    }

    private McpDispatcher strict(McpTool tool, SchemaValidator validator) {
        return new McpDispatcher(mapper, List.of(tool), allowAll, null, null, null, null, true, validator);
    }

    private McpDispatcher lenient(McpTool tool) {
        return new McpDispatcher(mapper, List.of(tool), allowAll, null, null, null, null);
    }

    private JsonNode callTool(McpDispatcher d, ObjectNode arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "custom.tool");
        params.set("arguments", arguments);
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/call");
        req.set("params", params);
        return d.dispatch(req, McpCallContext.anonymous()).path("result");
    }

    private ObjectNode argN(int n) {
        ObjectNode a = mapper.createObjectNode();
        a.put("n", n);
        return a;
    }

    @Test
    void strict_valid_input_and_output_pass() {
        JsonNode result = callTool(strict(customTool("ok", false), SchemaValidator.create()), argN(1));
        assertFalse(result.path("isError").asBoolean(false), "conforming call passes: " + result);
        assertEquals("ok", result.path("structuredContent").path("value").asText());
    }

    @Test
    void strict_rejects_wrong_typed_input() {
        ObjectNode badArg = mapper.createObjectNode();
        badArg.put("n", "not-a-number"); // string where integer is required — top-level presence check would miss this
        JsonNode result = callTool(strict(customTool("ok", false), SchemaValidator.create()), badArg);
        assertTrue(result.path("isError").asBoolean(false), "wrong-typed input rejected: " + result);
        assertEquals("INVALID_ARGUMENT", result.path("structuredContent").path("code").asText());
    }

    @Test
    void strict_rejects_missing_required_input() {
        JsonNode result = callTool(strict(customTool("ok", false), SchemaValidator.create()), mapper.createObjectNode());
        assertTrue(result.path("isError").asBoolean(false), "missing required arg rejected: " + result);
        assertEquals("INVALID_ARGUMENT", result.path("structuredContent").path("code").asText());
    }

    @Test
    void strict_rejects_nested_type_output_violation() {
        // "value" IS present (so the top-level guard passes) but is an integer, not the declared string.
        JsonNode result = callTool(strict(customTool("123", true), SchemaValidator.create()), argN(1));
        assertTrue(result.path("isError").asBoolean(false), "wrong-typed output rejected under strict: " + result);
        assertEquals("OUTPUT_SCHEMA_VIOLATION", result.path("structuredContent").path("code").asText());
    }

    @Test
    void without_strict_a_nested_type_violation_slips_past_the_top_level_guard() {
        // Same wrong-typed output, but the default zero-dep guard only checks that required keys are present.
        JsonNode result = callTool(lenient(customTool("123", true)), argN(1));
        assertFalse(result.path("isError").asBoolean(false),
                "the top-level guard does not catch a nested type mismatch: " + result);
    }

    @Test
    void strict_without_validator_falls_back_to_top_level_guard() {
        // strict flag on, but the validator jar is "absent" → unavailable(): behaves like the lenient guard.
        JsonNode result = callTool(strict(customTool("123", true), SchemaValidator.unavailable()), argN(1));
        assertFalse(result.path("isError").asBoolean(false),
                "strict degrades gracefully when the validator is unavailable: " + result);
    }
}
