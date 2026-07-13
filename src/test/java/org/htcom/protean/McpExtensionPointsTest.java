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
import org.htcom.mcpext.McpExtBeans;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extension-point demonstration:
 * <ul>
 *   <li><b>Authorization SPI</b>: a consumer {@link ModuleActionAuthorizer} bean replaces the permissive
 *       default and acts as the common choke point for tool calls (DEPLOY denied -> tool result isError).</li>
 *   <li><b>Open core</b>: a consumer's custom {@link McpTool} bean is listed in tools/list and callable,
 *       passing through the same authorizer gate.</li>
 * </ul>
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
@Import(McpExtBeans.class)
class McpExtensionPointsTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-ext-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;
    @Autowired ModuleActionAuthorizer authorizer;

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

    private JsonNode callTool(String tool, ObjectNode args) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", tool);
        params.set("arguments", args);
        return call("tools/call", params).path("result");
    }

    @Test
    void custom_authorizer_replaces_permissive_default() {
        // @ConditionalOnMissingBean must kick in so the consumer bean is the only authorizer
        assertFalse(authorizer instanceof org.htcom.protean.mcp.PermissiveModuleActionAuthorizer);
    }

    @Test
    void deploy_is_denied_by_custom_authorizer() {
        ObjectNode args = mapper.createObjectNode();
        args.put("id", "x");
        args.put("version", "1.0.0");
        args.put("controller", "com.x.C");
        args.putArray("files");
        JsonNode result = callTool("protean.deploy_module", args);
        assertTrue(result.path("isError").asBoolean(false));
        // RFC 9457 - [CODE]-prefixed text plus structuredContent problem-detail (code/tool/action).
        String text = result.path("content").get(0).path("text").asText();
        assertTrue(text.contains("[PERMISSION_DENIED]"), text);
        JsonNode problem = result.path("structuredContent");
        assertEquals("PERMISSION_DENIED", problem.path("code").asText());
        assertEquals("protean.deploy_module", problem.path("tool").asText());
    }

    @Test
    void read_is_allowed_by_custom_authorizer() {
        JsonNode result = callTool("protean.list_modules", mapper.createObjectNode());
        assertFalse(result.path("isError").asBoolean(false));
    }

    @Test
    void custom_tool_is_listed_and_callable() {
        List<String> names = new ArrayList<>();
        call("tools/list", null).path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        assertTrue(names.contains("consumer.echo"), "the custom tool must be listed in tools/list");

        ObjectNode args = mapper.createObjectNode();
        args.put("text", "hello");
        JsonNode result = callTool("consumer.echo", args);
        assertFalse(result.path("isError").asBoolean(false));
        assertEquals("echo: hello", result.path("content").get(0).path("text").asText());
    }
}
