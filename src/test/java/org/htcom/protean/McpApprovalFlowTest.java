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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP deploy flow when the approval gate is on: deploy -> PENDING_APPROVAL (not served, next action
 * announced) -> approve_module -> ACTIVE (served). reject_module rejects and removes.
 */
@SpringBootTest(properties = {"protean.mcp.enabled=true", "protean.gate.approval.required=true"})
@AutoConfigureMockMvc
class McpApprovalFlowTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-approval-test");

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
        try {
            platform.uninstall("greet");
        } catch (RuntimeException ignored) {
        }
    }

    private ObjectNode deployArgs() {
        ObjectNode args = mapper.createObjectNode();
        args.put("id", "greet");
        args.put("version", "1.0.0");
        args.put("controller", "com.acme.Greet");
        ArrayNode files = args.putArray("files");
        files.addObject().put("kind", "source").put("filename", "Greet.java").put("content", SRC);
        files.addObject().put("kind", "test").put("filename", "GreetTest.java").put("content", TEST);
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
        return dispatcher.dispatch(req, McpCallContext.anonymous()).path("result");
    }

    @Test
    void deploy_pends_then_approve_activates_and_serves() throws Exception {
        JsonNode deploy = callTool("protean.deploy_module", deployArgs());
        assertFalse(deploy.path("isError").asBoolean(false));
        assertEquals("PENDING_APPROVAL", deploy.path("structuredContent").path("desiredState").asText());
        // PENDING is not served
        mockMvc.perform(get("/greet/hi")).andExpect(status().isNotFound());

        ObjectNode approveArgs = mapper.createObjectNode();
        approveArgs.put("id", "greet");
        approveArgs.put("approver", "alice");
        JsonNode approve = callTool("protean.approve_module", approveArgs);
        assertFalse(approve.path("isError").asBoolean(false));
        assertEquals("ACTIVE", approve.path("structuredContent").path("desiredState").asText());
        // Served after approval
        mockMvc.perform(get("/greet/hi")).andExpect(status().isOk()).andExpect(content().string("hi"));
    }

    @Test
    void deploy_pends_then_reject_removes() throws Exception {
        callTool("protean.deploy_module", deployArgs());

        ObjectNode rejectArgs = mapper.createObjectNode();
        rejectArgs.put("id", "greet");
        rejectArgs.put("approver", "bob");
        JsonNode reject = callTool("protean.reject_module", rejectArgs);
        assertFalse(reject.path("isError").asBoolean(false));

        // Not found when queried after rejection
        ObjectNode getArgs = mapper.createObjectNode();
        getArgs.put("id", "greet");
        JsonNode get = callTool("protean.get_module", getArgs);
        assertEquals(true, get.path("isError").asBoolean(false));
        mockMvc.perform(get("/greet/hi")).andExpect(status().isNotFound());
    }
}
