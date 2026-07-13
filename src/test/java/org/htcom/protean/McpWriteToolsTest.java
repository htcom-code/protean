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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
import org.htcom.protean.mcp.McpException;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Write tools end-to-end (deploy -> serve -> uninstall) with input schemas (files[] and manifest) and
 * state/error mapping (gate rejection isError, mutually-exclusive input error). Drives the dispatcher
 * directly and confirms real serving with MockMvc.
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
@AutoConfigureMockMvc
class McpWriteToolsTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-write-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;

    static final String SRC = """
            package com.acme;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class Greet { @GetMapping("/greet/hi") public String hi() { return "hi"; } }
            """;
    static final String TEST = """
            package com.acme;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class GreetTest { @Test void hi() { assertEquals("hi", new Greet().hi()); } }
            """;

    @AfterEach
    void cleanup() {
        for (String id : new String[] {"greet", "greetb"}) {
            try {
                platform.uninstall(id);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** files[]-style deploy arguments. */
    private ObjectNode filesArgs(String id, String version) {
        ObjectNode args = mapper.createObjectNode();
        args.put("id", id);
        args.put("version", version);
        args.put("controller", "com.acme.Greet");
        ArrayNode files = args.putArray("files");
        ObjectNode src = files.addObject();
        src.put("kind", "source");
        src.put("filename", "Greet.java");
        src.put("content", SRC);
        ObjectNode test = files.addObject();
        test.put("kind", "test");
        test.put("filename", "GreetTest.java");
        test.put("content", TEST);
        return args;
    }

    private JsonNode callTool(String tool, ObjectNode args) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", tool);
        params.set("arguments", args);
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/call");
        req.set("params", params);
        return dispatcher.dispatch(req, McpCallContext.anonymous());
    }

    @Test
    void deploy_via_files_serves_then_uninstall_removes() throws Exception {
        mockMvc.perform(get("/greet/hi")).andExpect(status().isNotFound());

        JsonNode resp = callTool("protean.deploy_module", filesArgs("greet", "1.0.0"));
        JsonNode result = resp.path("result");
        assertFalse(result.path("isError").asBoolean(false), "deploy must succeed");
        assertEquals("ACTIVE", result.path("structuredContent").path("desiredState").asText());

        // real serving
        mockMvc.perform(get("/greet/hi")).andExpect(status().isOk()).andExpect(content().string("hi"));

        // uninstall -> endpoint disappears
        ObjectNode del = mapper.createObjectNode();
        del.put("id", "greet");
        assertFalse(callTool("protean.uninstall_module", del).path("result").path("isError").asBoolean(false));
        mockMvc.perform(get("/greet/hi")).andExpect(status().isNotFound());
    }

    @Test
    void deploy_via_manifest_serves() throws Exception {
        String manifest = "id: greetb\n"
                + "version: 1.0.0\n"
                + "controller: com.acme.Greet\n"
                + "sources:\n"
                + "  com.acme.Greet: |\n" + indent(SRC)
                + "tests:\n"
                + "  com.acme.GreetTest: |\n" + indent(TEST);
        ObjectNode args = mapper.createObjectNode();
        args.put("manifest", manifest);

        JsonNode result = callTool("protean.deploy_module", args).path("result");
        assertFalse(result.path("isError").asBoolean(false), "manifest deploy must succeed");
        mockMvc.perform(get("/greet/hi")).andExpect(status().isOk()).andExpect(content().string("hi"));
    }

    @Test
    void files_and_manifest_both_is_protocol_error() {
        ObjectNode args = filesArgs("greet", "1.0.0");
        args.put("manifest", "id: x");
        JsonNode resp = callTool("protean.deploy_module", args);
        assertEquals(McpException.INVALID_PARAMS, resp.path("error").path("code").asInt());
    }

    @Test
    void deploy_without_tests_is_tool_error_with_diagnostics() {
        ObjectNode args = filesArgs("greet", "1.0.0");
        // Remove the test file -> violates gate 1
        ((ArrayNode) args.get("files")).remove(1);
        JsonNode result = callTool("protean.deploy_module", args).path("result");
        assertTrue(result.path("isError").asBoolean(false), "a gate rejection is a tool result isError");
        // Gate failure is emitted structurally: code=GATE_FAILED + gate identifier.
        JsonNode problem = result.path("structuredContent");
        assertEquals("GATE_FAILED", problem.path("code").asText());
        assertEquals("tests", problem.path("gate").asText(), "the failed gate identifier");
        assertTrue(result.path("content").get(0).path("text").asText().contains("[GATE_FAILED]"),
                "code-prefixed text for lossy harnesses");
    }

    @Test
    void update_then_rollback() {
        callTool("protean.deploy_module", filesArgs("greet", "1.0.0"));
        JsonNode upd = callTool("protean.update_module", filesArgs("greet", "2.0.0")).path("result");
        assertFalse(upd.path("isError").asBoolean(false));
        assertEquals("2.0.0", upd.path("structuredContent").path("version").asText());

        ObjectNode rb = mapper.createObjectNode();
        rb.put("id", "greet");
        rb.put("version", "1.0.0");
        JsonNode rolled = callTool("protean.rollback_module", rb).path("result");
        assertFalse(rolled.path("isError").asBoolean(false));
        assertEquals("1.0.0", rolled.path("structuredContent").path("version").asText());
    }

    /** 4-space indentation for YAML block scalars. */
    private static String indent(String code) {
        StringBuilder sb = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            sb.append("    ").append(line).append("\n");
        }
        return sb.toString();
    }
}
