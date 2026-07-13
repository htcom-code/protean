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
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Library-completeness guarantee: every declared MCP {@code outputSchema} is (1) a structurally valid JSON Schema
 * and (2) the full nested/type shape that the tool actually emits — not just the top-level {@code required} the
 * runtime guard checks. Core tools are driven with real calls here; the debug tools' real-output conformance is
 * asserted in {@code McpDebugToolsTest} (which owns the JDI session harness).
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpOutputSchemaConformanceTest {

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;
    @Autowired ModulePlatform platform;

    static final String ID = "conf-mod";
    static final String FQCN = "runtime.conf.ConfController";

    private JsonNode toolsList() {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/list");
        return dispatcher.dispatch(req, McpCallContext.anonymous()).path("result").path("tools");
    }

    private JsonNode schemaOf(String name) {
        for (JsonNode t : toolsList()) {
            if (name.equals(t.path("name").asText())) {
                return t.path("outputSchema");
            }
        }
        return mapper.missingNode();
    }

    private JsonNode callTool(String name, ObjectNode arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 2);
        req.put("method", "tools/call");
        req.set("params", params);
        return dispatcher.dispatch(req, McpCallContext.anonymous()).path("result");
    }

    Stream<Arguments> declaredOutputSchemas() {
        List<Arguments> out = new ArrayList<>();
        for (JsonNode t : toolsList()) {
            JsonNode os = t.path("outputSchema");
            if (os.isObject()) {
                out.add(Arguments.of(t.path("name").asText(), os));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} declares a structurally valid JSON Schema")
    @MethodSource("declaredOutputSchemas")
    void declared_output_schema_is_structurally_valid(String name, JsonNode schema) {
        SchemaConformance.compile(schema, name); // throws if the declared schema is malformed
    }

    @Test
    void real_core_tool_outputs_conform_to_their_output_schema() {
        JsonNode deploy = callTool("protean.deploy_module", deployArgs());
        try {
            conforms("protean.deploy_module", deploy);
            conforms("protean.list_modules", callTool("protean.list_modules", null));
            conforms("protean.get_module", callTool("protean.get_module", idArgs()));
            conforms("protean.get_module_source", callTool("protean.get_module_source", idArgs()));
            conforms("protean.module_versions", callTool("protean.module_versions", idArgs()));
            conforms("protean.module_metrics", callTool("protean.module_metrics", null));
            conforms("protean.query_traces", callTool("protean.query_traces", null));
        } finally {
            try {
                platform.uninstall(ID);
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
        }
    }

    private void conforms(String name, JsonNode result) {
        assertFalse(result.path("isError").asBoolean(false), name + " unexpectedly errored: " + result);
        SchemaConformance.assertConforms(schemaOf(name), result.path("structuredContent"), name);
    }

    private ObjectNode idArgs() {
        ObjectNode a = mapper.createObjectNode();
        a.put("id", ID);
        return a;
    }

    private ObjectNode deployArgs() {
        ObjectNode a = mapper.createObjectNode();
        a.put("id", ID);
        a.put("version", "1.0.0");
        a.put("controller", FQCN);
        var files = a.putArray("files");
        var src = files.addObject();
        src.put("kind", "source");
        src.put("filename", "ConfController.java");
        src.put("content", SRC);
        var test = files.addObject();
        test.put("kind", "test");
        test.put("filename", "ConfControllerTest.java");
        test.put("content", TEST);
        return a;
    }

    static final String SRC = """
            package runtime.conf;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class ConfController {
                @GetMapping("/conf-mod/ping") public String ping() { return "ok"; }
            }
            """;

    static final String TEST = """
            package runtime.conf;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class ConfControllerTest {
                @Test void ping() { assertEquals("ok", new ConfController().ping()); }
            }
            """;
}
