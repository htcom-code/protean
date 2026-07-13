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
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Input-contract parity: keeps each tool's declared {@code inputSchema} honest against its runtime behavior, so the
 * two cannot silently drift. Two guarantees:
 * <ol>
 *   <li>every field named in {@code inputSchema.required} actually exists in {@code inputSchema.properties}
 *       (no dangling/typo'd required) — checked for all tools;</li>
 *   <li>omitting any declared-required field makes the call fail (JSON-RPC error or {@code isError}) — it can never
 *       succeed without it — checked for tools that declare a flat top-level {@code required} list.</li>
 * </ol>
 * Tools whose input is a {@code oneOf} (deploy/update) have no flat top-level required and are covered by
 * {@code McpDispatcherTest} instead.
 */
@SpringBootTest(properties = {"protean.mcp.enabled=true", "protean.mcp.debug.enabled=true"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpInputContractTest {

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;

    private JsonNode toolsList() {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/list");
        return dispatcher.dispatch(req, McpCallContext.anonymous()).path("result").path("tools");
    }

    private JsonNode callTool(String name, ObjectNode arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 2);
        req.put("method", "tools/call");
        req.set("params", params);
        return dispatcher.dispatch(req, McpCallContext.anonymous());
    }

    // ---- (1) required ⊆ properties, for every tool ----

    Stream<Arguments> allInputSchemas() {
        List<Arguments> out = new ArrayList<>();
        for (JsonNode t : toolsList()) {
            out.add(Arguments.of(t.path("name").asText(), t.path("inputSchema")));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}: required fields exist in properties")
    @MethodSource("allInputSchemas")
    void declared_required_fields_exist_in_properties(String name, JsonNode inputSchema) {
        JsonNode properties = inputSchema.path("properties");
        for (JsonNode r : inputSchema.path("required")) {
            assertTrue(properties.has(r.asText()),
                    name + " declares required '" + r.asText() + "' that is absent from properties");
        }
    }

    // ---- (2) omitting a declared-required field is rejected ----

    Stream<Arguments> requiredFieldOmissions() {
        List<Arguments> out = new ArrayList<>();
        for (JsonNode t : toolsList()) {
            JsonNode required = t.path("inputSchema").path("required");
            if (required.isArray()) {
                for (JsonNode r : required) {
                    out.add(Arguments.of(t.path("name").asText(), r.asText(), t.path("inputSchema")));
                }
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}: rejects a call missing required '{1}'")
    @MethodSource("requiredFieldOmissions")
    void omitting_a_declared_required_field_is_rejected(String name, String omit, JsonNode inputSchema) {
        JsonNode resp = callTool(name, argsWithoutField(inputSchema, omit));
        boolean rpcError = resp.has("error");
        boolean toolError = resp.path("result").path("isError").asBoolean(false);
        assertTrue(rpcError || toolError,
                name + " accepted a call missing required '" + omit + "' as success: " + resp);
    }

    /** Builds an arguments object with every OTHER required field set to a type-appropriate placeholder. */
    private ObjectNode argsWithoutField(JsonNode inputSchema, String omit) {
        ObjectNode args = mapper.createObjectNode();
        JsonNode properties = inputSchema.path("properties");
        for (JsonNode r : inputSchema.path("required")) {
            String field = r.asText();
            if (field.equals(omit)) {
                continue;
            }
            String type = typeOf(properties.path(field));
            switch (type) {
                case "integer", "number" -> args.put(field, 1);
                case "boolean" -> args.put(field, true);
                case "array" -> args.putArray(field);
                case "object" -> args.putObject(field);
                default -> args.put(field, "placeholder");
            }
        }
        return args;
    }

    private String typeOf(JsonNode propertySchema) {
        JsonNode type = propertySchema.path("type");
        if (type.isTextual()) {
            return type.asText();
        }
        if (type.isArray() && type.size() > 0) {
            return type.get(0).asText();
        }
        return "string";
    }
}
