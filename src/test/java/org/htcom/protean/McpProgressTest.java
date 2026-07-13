/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that stage progress during deploy flows through {@link McpCallContext.ProgressSink}.
 * SSE transport is thin wiring, so here we stay transport-independent by passing the dispatcher a
 * capturing sink and confirming the stages are reached.
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
class McpProgressTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-progress-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;
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

    @Test
    void deploy_emits_stage_progress() {
        ObjectNode args = mapper.createObjectNode();
        args.put("id", "greet");
        args.put("version", "1.0.0");
        args.put("controller", "com.acme.Greet");
        ArrayNode files = args.putArray("files");
        files.addObject().put("kind", "source").put("filename", "Greet.java").put("content", SRC);
        files.addObject().put("kind", "test").put("filename", "GreetTest.java").put("content", TEST);

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "protean.deploy_module");
        params.set("arguments", args);
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/call");
        req.set("params", params);

        List<String> stages = new ArrayList<>();
        McpCallContext ctx = new McpCallContext(null, (current, total, message) -> stages.add(message));
        dispatcher.dispatch(req, ctx);

        assertTrue(stages.size() >= 3, "multiple stages must be reported: " + stages);
        assertTrue(stages.stream().anyMatch(s -> s.contains("active")), "active stage reached: " + stages);
    }
}
