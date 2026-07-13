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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exposure and retrieval of resources (module list and traces) plus prompts (create-module), and whether
 * initialize honestly advertises the resources/prompts capabilities.
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
class McpResourcesPromptsTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-res-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;

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
    void initialize_advertises_resources_and_prompts() {
        JsonNode caps = call("initialize", null).path("result").path("capabilities");
        assertTrue(caps.has("tools"));
        assertTrue(caps.has("resources"));
        assertTrue(caps.has("prompts"));
    }

    @Test
    void resources_list_exposes_modules_and_traces() {
        List<String> uris = new ArrayList<>();
        call("resources/list", null).path("result").path("resources")
                .forEach(r -> uris.add(r.path("uri").asText()));
        assertTrue(uris.contains("protean://modules"));
        assertTrue(uris.contains("protean://traces"));
    }

    @Test
    void resources_read_modules_returns_json_snapshot() {
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", "protean://modules");
        JsonNode contents = call("resources/read", params).path("result").path("contents").get(0);
        assertEquals("application/json", contents.path("mimeType").asText());
        assertTrue(contents.path("text").isTextual());
    }

    @Test
    void resources_read_unknown_uri_is_invalid_params() {
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", "protean://nope");
        JsonNode resp = call("resources/read", params);
        assertEquals(-32602, resp.path("error").path("code").asInt());
    }

    @Test
    void prompts_list_and_get_create_module() {
        List<String> names = new ArrayList<>();
        call("prompts/list", null).path("result").path("prompts").forEach(p -> names.add(p.path("name").asText()));
        assertTrue(names.contains("create-module"));

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "create-module");
        params.putObject("arguments").put("purpose", "Greeting API");
        JsonNode result = call("prompts/get", params).path("result");
        String text = result.path("messages").get(0).path("content").path("text").asText();
        assertTrue(text.contains("Greeting API"));
        assertTrue(text.contains("deploy_module"));
    }
}
