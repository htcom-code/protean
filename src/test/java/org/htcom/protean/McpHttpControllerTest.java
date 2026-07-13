/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Thin smoke test for the Streamable HTTP transport. The dispatcher logic is covered by
 * {@link McpDispatcherTest}; here we only confirm the wiring that {@code POST /platform/mcp} accepts
 * JSON-RPC and forwards it to the dispatcher.
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
@AutoConfigureMockMvc
class McpHttpControllerTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-http-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    @Test
    void initialize_over_http() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-11-25\"}}";
        mockMvc.perform(post("/platform/mcp").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"))
                .andExpect(jsonPath("$.result.serverInfo.name").value("protean"));
    }

    @Test
    void tools_call_list_modules_over_http() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"protean.list_modules\",\"arguments\":{}}}";
        mockMvc.perform(post("/platform/mcp").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].type").value("text"));
    }

    @Test
    void notification_gets_202_no_body() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        mockMvc.perform(post("/platform/mcp").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
    }
}
