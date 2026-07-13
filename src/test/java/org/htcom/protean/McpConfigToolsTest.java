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
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives {@link McpDispatcher} for the {@code config.list/get/set} tools (dispatch level, real context). */
@SpringBootTest(properties = "protean.mcp.enabled=true")
class McpConfigToolsTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir",
                () -> Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-config-test").toString());
    }

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;
    @Autowired ProteanProperties props;

    private JsonNode callTool(String tool, ObjectNode args) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/call");
        ObjectNode params = req.putObject("params");
        params.put("name", tool);
        params.set("arguments", args);
        return dispatcher.dispatch(req, McpCallContext.anonymous()).path("result");
    }

    @Test
    void listReturnsConfigs() {
        JsonNode result = callTool("config.list", mapper.createObjectNode());
        assertFalse(result.path("isError").asBoolean(false));
        JsonNode configs = result.path("structuredContent").path("configs");
        assertTrue(configs.isArray());
        assertTrue(configs.size() >= 50);
    }

    @Test
    void getReturnsSingleEntry() {
        ObjectNode args = mapper.createObjectNode();
        args.put("key", "trace.capacity");
        JsonNode result = callTool("config.get", args);
        assertFalse(result.path("isError").asBoolean(false));
        assertEquals("trace.capacity", result.path("structuredContent").path("key").asText());
        assertEquals("LIVE", result.path("structuredContent").path("tier").asText());
    }

    @Test
    void getUnknownKeyIsError() {
        ObjectNode args = mapper.createObjectNode();
        args.put("key", "no.such.key");
        JsonNode result = callTool("config.get", args);
        assertTrue(result.path("isError").asBoolean(false));
    }

    @Test
    void setAppliesLiveKey() {
        ObjectNode args = mapper.createObjectNode();
        args.putObject("changes").put("trace.capacity", 17);
        JsonNode result = callTool("config.set", args);
        assertFalse(result.path("isError").asBoolean(false));
        assertTrue(result.path("structuredContent").path("applied").asBoolean());
        assertEquals(17, props.getTrace().getCapacity());
    }

    @Test
    void setRestartKeyReportedNotApplied() {
        boolean before = props.getMcp().isEnabled();
        ObjectNode args = mapper.createObjectNode();
        args.putObject("changes").put("mcp.enabled", !before);
        JsonNode result = callTool("config.set", args);
        assertFalse(result.path("isError").asBoolean(false), "restart key alone is not a hard error");
        JsonNode outcomes = result.path("structuredContent").path("outcomes");
        assertEquals("REQUIRES_RESTART", outcomes.get(0).path("outcome").asText());
        assertEquals(before, props.getMcp().isEnabled());
    }

    @Test
    void setUnknownKeyIsErrorAndAppliesNothing() {
        int before = props.getTrace().getCapacity();
        ObjectNode args = mapper.createObjectNode();
        ObjectNode changes = args.putObject("changes");
        changes.put("trace.capacity", before + 5);
        changes.put("bogus.key", 1);
        JsonNode result = callTool("config.set", args);
        assertTrue(result.path("isError").asBoolean(false));
        assertEquals(before, props.getTrace().getCapacity(), "atomic abort — nothing applied");
    }
}
