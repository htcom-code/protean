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
import org.htcom.protean.mcp.McpException;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the transport-independent {@link McpDispatcher} directly to verify the protocol core:
 * initialize version negotiation, tools/list, tools/call (read tools), and the JSON-RPC error envelope.
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
class McpDispatcherTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-disp-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;
    @Autowired ModulePlatform platform;

    static final String ID = "mcp-mod";
    static final String FQCN = "runtime.mcp.McpController";
    static final String TEST_FQCN = "runtime.mcp.McpControllerTest";

    static ModuleDescriptor descriptor() {
        String src = """
                package runtime.mcp;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class McpController {
                    @GetMapping("/mcp-mod/ping") public String ping() { return "ok"; }
                }
                """;
        String test = """
                package runtime.mcp;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class McpControllerTest {
                    @Test void ping() { assertEquals("ok", new McpController().ping()); }
                }
                """;
        return ModuleDescriptor.builder()
                .id(ID).version("1.0.0")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN))
                .sources(Map.of(FQCN, src)).tests(Map.of(TEST_FQCN, test))
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall(ID);
        } catch (RuntimeException ignored) {
        }
    }

    private JsonNode call(String method, ObjectNode params) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        return dispatcher.dispatch(req, McpCallContext.anonymous());
    }

    @Test
    void initialize_echoes_supported_version() {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2025-11-25");
        JsonNode resp = call("initialize", params);
        assertEquals("2025-11-25", resp.path("result").path("protocolVersion").asText());
        assertTrue(resp.path("result").path("capabilities").has("tools"));
        assertEquals("protean", resp.path("result").path("serverInfo").path("name").asText());
    }

    @Test
    void initialize_responds_latest_for_unsupported_version() {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2024-01-01");
        JsonNode resp = call("initialize", params);
        assertEquals("2025-11-25", resp.path("result").path("protocolVersion").asText());
    }

    @Test
    void tools_list_contains_read_tools() {
        JsonNode resp = call("tools/list", null);
        List<String> names = new ArrayList<>();
        resp.path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        assertTrue(names.contains("protean.list_modules"));
        assertTrue(names.contains("protean.get_module"));
        assertTrue(names.contains("protean.module_versions"));
        assertTrue(names.contains("protean.get_module_source"));
    }

    @Test
    void tools_list_exposes_tool_object_metadata() {
        // Real Spring-wired protean tools expose title, annotations, and outputSchema.
        JsonNode tools = call("tools/list", null).path("result").path("tools");
        JsonNode list = named(tools, "protean.list_modules");
        assertEquals("List Modules", list.path("title").asText());
        assertTrue(list.path("annotations").path("readOnlyHint").asBoolean(), "read tools have readOnlyHint=true");
        assertTrue(list.path("outputSchema").path("properties").path("modules").isObject(), "outputSchema exposed");

        JsonNode deploy = named(tools, "protean.deploy_module");
        assertEquals("Deploy Module", deploy.path("title").asText());
        assertFalse(deploy.path("annotations").path("readOnlyHint").asBoolean(), "write tools have readOnlyHint=false");
        assertFalse(deploy.path("annotations").path("destructiveHint").asBoolean(), "deploy is non-destructive");
        assertTrue(deploy.path("outputSchema").isObject(), "deploy exposes outputSchema too");
    }

    @Test
    void deploy_input_schema_declares_full_contract() {
        // Schema completeness - deploy_module inputSchema fully declares its own contract.
        JsonNode tools = call("tools/list", null).path("result").path("tools");
        JsonNode deploy = named(tools, "protean.deploy_module");
        JsonNode schema = deploy.path("inputSchema");
        JsonNode props = schema.path("properties");

        // Runtime fields are declared in the schema
        assertTrue(props.path("trustTier").path("enum").isArray(), "trustTier enum declared");
        assertTrue(props.has("needsSharedBeans"), "needsSharedBeans declared");
        assertEquals("array", props.path("components").path("type").asText());
        assertEquals("array", props.path("bridgedInterfaces").path("type").asText());
        assertTrue(props.path("verification").path("properties").path("integration").isObject(),
                "verification nested schema inlined");

        // files item required (filename, content)
        List<String> itemReq = new ArrayList<>();
        props.path("files").path("items").path("required").forEach(n -> itemReq.add(n.asText()));
        assertTrue(itemReq.contains("filename") && itemReq.contains("content"), "files item required: " + itemReq);

        // files XOR manifest as two oneOf branches, each with its own required
        JsonNode oneOf = schema.path("oneOf");
        assertTrue(oneOf.isArray() && oneOf.size() == 2, "two oneOf branches: " + oneOf);
        List<String> filesReq = new ArrayList<>();
        oneOf.get(0).path("required").forEach(n -> filesReq.add(n.asText()));
        assertTrue(filesReq.containsAll(List.of("id", "version", "controller", "files")),
                "files branch required: " + filesReq);
        assertEquals("manifest", oneOf.get(1).path("required").get(0).asText(), "manifest branch required");
    }

    @Test
    void update_input_schema_matches_deploy_contract() {
        // update_module has the same input contract as deploy (shared normalizer) -> must be identical via the shared schema.
        JsonNode tools = call("tools/list", null).path("result").path("tools");
        JsonNode update = named(tools, "protean.update_module").path("inputSchema");
        JsonNode deploy = named(tools, "protean.deploy_module").path("inputSchema");
        assertTrue(update.path("oneOf").isArray() && update.path("oneOf").size() == 2, "update has 2 oneOf branches");
        assertTrue(update.path("properties").path("verification").path("properties").path("integration").isObject(),
                "update inlines verification too");
        assertTrue(update.path("properties").path("trustTier").path("enum").isArray(), "update trustTier enum");
        assertEquals(deploy, update, "deploy and update input schemas are identical (shared builder)");
    }

    @Test
    void patch_input_schema_declares_delta_contract() {
        // patch_module has a delta-only contract (different from deploy/update): only id required, no oneOf.
        JsonNode tools = call("tools/list", null).path("result").path("tools");
        JsonNode patch = named(tools, "protean.patch_module").path("inputSchema");
        List<String> req = new ArrayList<>();
        patch.path("required").forEach(n -> req.add(n.asText()));
        assertEquals(List.of("id"), req, "patch required=[id]");
        assertTrue(patch.path("oneOf").isMissingNode(), "patch has no oneOf (single shape)");
        List<String> itemReq = new ArrayList<>();
        patch.path("properties").path("files").path("items").path("required").forEach(n -> itemReq.add(n.asText()));
        assertTrue(itemReq.contains("filename") && itemReq.contains("content"), "files item required: " + itemReq);
        assertEquals("string", patch.path("properties").path("removeFiles").path("items").path("type").asText(),
                "removeFiles items=string");
    }

    @Test
    void deploy_still_works_via_files_and_manifest_after_schema_change() {
        // A schema change is only a contract specification - runtime behavior (normalizer) is unchanged. Both paths still deploy.
        ObjectNode filesArgs = mapper.createObjectNode();
        filesArgs.put("id", "schema-files");
        filesArgs.put("version", "1");
        filesArgs.put("controller", "gen.SchemaFilesController");
        var arr = filesArgs.putArray("files");
        ObjectNode src = arr.addObject();
        src.put("kind", "source");
        src.put("filename", "SchemaFilesController.java");
        src.put("content", "package gen;\nimport org.springframework.web.bind.annotation.*;\n@RestController\npublic class SchemaFilesController { @GetMapping(\"/schema-files/x\") public String x(){ return \"x\"; } }\n");
        ObjectNode tst = arr.addObject();
        tst.put("kind", "test");
        tst.put("filename", "SchemaFilesControllerTest.java");
        tst.put("content", "package gen;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.*;\nclass SchemaFilesControllerTest { @Test void x(){ assertEquals(\"x\", new SchemaFilesController().x()); } }\n");
        try {
            JsonNode r = callTool("protean.deploy_module", filesArgs);
            assertFalse(r.path("isError").asBoolean(false), "files-path deploy: " + r);
            assertEquals("schema-files", r.path("structuredContent").path("id").asText());
        } finally {
            try {
                platform.uninstall("schema-files");
            } catch (RuntimeException ignored) {
            }
        }
    }

    private JsonNode named(JsonNode tools, String name) {
        for (JsonNode t : tools) {
            if (name.equals(t.path("name").asText())) {
                return t;
            }
        }
        throw new AssertionError("tool not found: " + name);
    }

    @Test
    void resources_templates_list_returns_uri_templates() {
        // Parameterized resource URI templates.
        JsonNode templates = call("resources/templates/list", null).path("result").path("resourceTemplates");
        List<String> uris = new ArrayList<>();
        templates.forEach(t -> uris.add(t.path("uriTemplate").asText()));
        assertTrue(uris.contains("protean://modules/{id}/source"), "source template: " + uris);
        assertTrue(uris.contains("protean://modules/{id}/versions"), "versions template: " + uris);
    }

    @Test
    void completion_complete_suggests_deployed_module_ids() {
        // Autocomplete the resource template {id} with deployed module ids.
        platform.install(descriptor());
        ObjectNode params = mapper.createObjectNode();
        ObjectNode ref = params.putObject("ref");
        ref.put("type", "ref/resource");
        ref.put("uri", "protean://modules/{id}/source");
        ObjectNode arg = params.putObject("argument");
        arg.put("name", "id");
        arg.put("value", "mcp");   // ID is "mcp-mod" so the prefix matches
        JsonNode completion = call("completion/complete", params).path("result").path("completion");
        List<String> values = new ArrayList<>();
        completion.path("values").forEach(v -> values.add(v.asText()));
        assertTrue(values.contains(ID), "suggests module ids matching the prefix: " + values);
        assertFalse(completion.path("hasMore").asBoolean(true));

        // Free-text prompt arguments have no candidates (honest).
        ObjectNode pref = mapper.createObjectNode();
        ObjectNode pr = pref.putObject("ref");
        pr.put("type", "ref/prompt");
        pr.put("name", "create-module");
        ObjectNode pa = pref.putObject("argument");
        pa.put("name", "purpose");
        pa.put("value", "any");
        JsonNode pc = call("completion/complete", pref).path("result").path("completion");
        assertEquals(0, pc.path("values").size());
    }

    @Test
    void unknown_method_returns_method_not_found() {
        JsonNode resp = call("no/such", null);
        assertEquals(McpException.METHOD_NOT_FOUND, resp.path("error").path("code").asInt());
    }

    @Test
    void tools_call_unknown_tool_is_invalid_params() {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "protean.nope");
        JsonNode resp = call("tools/call", params);
        assertEquals(McpException.INVALID_PARAMS, resp.path("error").path("code").asInt());
    }

    @Test
    void tools_call_lifecycle_read_tools_reflect_deployed_module() {
        // Not in the list before deployment
        assertFalse(listIds().contains(ID));

        platform.install(descriptor());

        // list_modules reflects the deployed module
        JsonNode list = callTool("protean.list_modules", mapper.createObjectNode());
        assertFalse(list.path("isError").asBoolean(false));
        // MCP spec: structuredContent must be an object (arrays not allowed) -> wrap under a modules key.
        assertTrue(list.path("structuredContent").isObject(), "structuredContent must be an object: " + list);
        assertTrue(list.path("structuredContent").path("modules").isArray(), "modules must be an array");
        assertTrue(listIds().contains(ID));

        // Single lookup via get_module
        ObjectNode getArgs = mapper.createObjectNode();
        getArgs.put("id", ID);
        JsonNode get = callTool("protean.get_module", getArgs);
        assertFalse(get.path("isError").asBoolean(false));
        assertEquals(ID, get.path("structuredContent").path("id").asText());

        // Missing module -> tool result isError (not a protocol error) + RFC 9457 code shape
        ObjectNode missArgs = mapper.createObjectNode();
        missArgs.put("id", "does-not-exist");
        JsonNode miss = callTool("protean.get_module", missArgs);
        assertTrue(miss.path("isError").asBoolean(false));
        JsonNode problem = miss.path("structuredContent");
        assertEquals("MODULE_NOT_FOUND", problem.path("code").asText(), "discriminator key");
        assertEquals("urn:protean:error:module-not-found", problem.path("type").asText());
        assertEquals("does-not-exist", problem.path("moduleId").asText(), "moduleId extension for correlation");
        assertFalse(problem.has("status"), "the tool-result problem does not carry status (that is the envelope/data.code's job)");
        assertTrue(miss.path("content").get(0).path("text").asText().contains("[MODULE_NOT_FOUND]"),
                "code-prefixed text for lossy harnesses");
    }

    @Test
    void get_module_source_returns_source_and_supports_class_targeting() {
        platform.install(descriptor());

        // Default: the full source map (tests not included)
        ObjectNode allArgs = mapper.createObjectNode();
        allArgs.put("id", ID);
        JsonNode all = callTool("protean.get_module_source", allArgs);
        assertFalse(all.path("isError").asBoolean(false));
        assertEquals(ID, all.path("structuredContent").path("id").asText());
        JsonNode files = all.path("structuredContent").path("files");
        assertTrue(files.has(FQCN), "the source map includes the controller: " + files);
        assertFalse(files.has(TEST_FQCN), "by default tests are not included");
        assertTrue(files.path(FQCN).asText().contains("class McpController"), "returns the raw source");

        // includeTests -> merges tests
        ObjectNode withTests = mapper.createObjectNode();
        withTests.put("id", ID);
        withTests.put("includeTests", true);
        JsonNode inc = callTool("protean.get_module_source", withTests);
        assertTrue(inc.path("structuredContent").path("files").has(TEST_FQCN), "test included when includeTests is set");

        // className target - only that file
        ObjectNode one = mapper.createObjectNode();
        one.put("id", ID);
        one.put("className", FQCN);
        JsonNode single = callTool("protean.get_module_source", one);
        assertEquals(1, single.path("structuredContent").path("files").size());
        assertTrue(single.path("structuredContent").path("files").has(FQCN));

        // Missing class -> tool result isError (guidance for external/dependency classes) + code=MODULE_NOT_FOUND + resource extension
        ObjectNode missClass = mapper.createObjectNode();
        missClass.put("id", ID);
        missClass.put("className", "no.such.Clazz");
        JsonNode missClassRes = callTool("protean.get_module_source", missClass);
        assertTrue(missClassRes.path("isError").asBoolean(false));
        assertEquals("MODULE_NOT_FOUND", missClassRes.path("structuredContent").path("code").asText());
        assertEquals("no.such.Clazz", missClassRes.path("structuredContent").path("resource").asText());

        // Missing module -> tool result isError + code
        ObjectNode missMod = mapper.createObjectNode();
        missMod.put("id", "does-not-exist");
        JsonNode missModRes = callTool("protean.get_module_source", missMod);
        assertTrue(missModRes.path("isError").asBoolean(false));
        assertEquals("MODULE_NOT_FOUND", missModRes.path("structuredContent").path("code").asText());
    }

    private JsonNode callTool(String tool, ObjectNode args) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", tool);
        params.set("arguments", args);
        return call("tools/call", params).path("result");
    }

    private List<String> listIds() {
        JsonNode result = callTool("protean.list_modules", mapper.createObjectNode());
        List<String> ids = new ArrayList<>();
        result.path("structuredContent").path("modules").forEach(s -> ids.add(s.path("id").asText()));
        return ids;
    }
}
