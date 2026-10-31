/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.mcp.session.McpSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Over {@code /platform/mcp}, a session created by one authenticated principal cannot be used, streamed or terminated
 * by another — it answers 404 exactly as an unknown session does.
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
@AutoConfigureMockMvc
class McpSessionOwnershipHttpTest {

    static final Path STORE = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-session-owner-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("protean.module-store.dir", STORE::toString);
    }

    static final Principal ALICE = () -> "alice";
    static final Principal BOB = () -> "bob";
    static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}";

    @Autowired MockMvc mvc;
    @Autowired McpSessionRegistry registry;

    @Test
    void another_principal_cannot_use_the_session() throws Exception {
        String sid = initializeAs(ALICE);

        mvc.perform(post("/platform/mcp").principal(BOB).header("Mcp-Session-Id", sid)
                        .contentType(MediaType.APPLICATION_JSON).content(PING))
                .andExpect(status().isNotFound());
        mvc.perform(get("/platform/mcp").principal(BOB).header("Mcp-Session-Id", sid))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/platform/mcp").principal(BOB).header("Mcp-Session-Id", sid))
                .andExpect(status().isNoContent());
        assertNotNull(registry.get(sid, "alice"), "another principal's DELETE must not end the session");

        mvc.perform(post("/platform/mcp").principal(ALICE).header("Mcp-Session-Id", sid)
                        .contentType(MediaType.APPLICATION_JSON).content(PING))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticated_request_cannot_use_an_owned_session() throws Exception {
        String sid = initializeAs(ALICE);
        mvc.perform(post("/platform/mcp").header("Mcp-Session-Id", sid)
                        .contentType(MediaType.APPLICATION_JSON).content(PING))
                .andExpect(status().isNotFound());
    }

    private String initializeAs(Principal principal) throws Exception {
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}}}";
        String sid = mvc.perform(post("/platform/mcp").principal(principal)
                        .contentType(MediaType.APPLICATION_JSON).content(init))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Mcp-Session-Id");
        assertNotNull(sid);
        return sid;
    }
}
