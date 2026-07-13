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
import org.htcom.protean.mcp.McpDispatcher;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.htcom.protean.mcp.session.McpClientChannel;
import org.htcom.protean.mcp.session.McpServerNotifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the dispatcher behavior of the server-to-client request primitives
 * (sampling/roots/elicitation) using a fake channel: capability gating, method routing, result return,
 * and rejection when the session/channel is absent.
 */
class McpClientFeaturesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleActionAuthorizer allowAll =
            (caller, action, moduleId) -> ModuleActionAuthorizer.Decision.allow();

    /** A fake implementing both McpServerNotifier and McpClientChannel - records requests and returns a preset result. */
    static final class FakeChannel implements McpServerNotifier, McpClientChannel {
        JsonNode caps;
        String lastSession;
        String lastMethod;
        JsonNode lastParams;
        JsonNode reply;

        @Override public void broadcast(JsonNode n) { }
        @Override public void notifySession(String id, JsonNode n) { }

        @Override
        public CompletableFuture<JsonNode> requestClient(String sessionId, String method, JsonNode params, long t) {
            this.lastSession = sessionId;
            this.lastMethod = method;
            this.lastParams = params;
            return CompletableFuture.completedFuture(reply);
        }

        @Override public JsonNode clientCapabilities(String sessionId) { return caps; }
    }

    private McpDispatcher dispatcher(FakeChannel ch) {
        return new McpDispatcher(mapper, List.of(), allowAll, null, null, ch, null);
    }

    @Test
    void create_message_routes_to_client_when_sampling_declared() {
        FakeChannel ch = new FakeChannel();
        ch.caps = mapper.createObjectNode().set("sampling", mapper.createObjectNode());
        ch.reply = mapper.createObjectNode().put("role", "assistant");
        McpDispatcher d = dispatcher(ch);

        ObjectNode params = mapper.createObjectNode();
        params.putArray("messages");
        JsonNode result = d.createMessage("sess-1", params, 1000);

        assertEquals("sampling/createMessage", ch.lastMethod);
        assertEquals("sess-1", ch.lastSession);
        assertNotNull(ch.lastParams);
        assertEquals("assistant", result.path("role").asText());
    }

    @Test
    void list_roots_and_elicit_route_to_their_methods() {
        FakeChannel ch = new FakeChannel();
        ObjectNode caps = mapper.createObjectNode();
        caps.set("roots", mapper.createObjectNode());
        caps.set("elicitation", mapper.createObjectNode());
        ch.caps = caps;
        ch.reply = mapper.createObjectNode();
        McpDispatcher d = dispatcher(ch);

        d.listRoots("s", 1000);
        assertEquals("roots/list", ch.lastMethod);

        d.elicit("s", mapper.createObjectNode(), 1000);
        assertEquals("elicitation/create", ch.lastMethod);
    }

    @Test
    void request_rejected_when_capability_not_declared() {
        FakeChannel ch = new FakeChannel();
        ch.caps = mapper.createObjectNode(); // sampling not declared
        McpDispatcher d = dispatcher(ch);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> d.createMessage("s", mapper.createObjectNode(), 1000));
        assertTrue(ex.getMessage().contains("sampling"), ex.getMessage());
    }

    @Test
    void request_rejected_without_session() {
        FakeChannel ch = new FakeChannel();
        ch.caps = mapper.createObjectNode().set("sampling", mapper.createObjectNode());
        McpDispatcher d = dispatcher(ch);
        assertThrows(IllegalStateException.class,
                () -> d.createMessage(null, mapper.createObjectNode(), 1000));
    }

    @Test
    void request_rejected_when_no_client_channel() {
        // If the notifier is not an McpClientChannel (equivalent to the session surface being off), requests are impossible.
        McpServerNotifier plain = new McpServerNotifier() {
            @Override public void broadcast(JsonNode n) { }
            @Override public void notifySession(String id, JsonNode n) { }
        };
        McpDispatcher d = new McpDispatcher(mapper, List.of(), allowAll, null, null, plain, null);
        assertThrows(IllegalStateException.class,
                () -> d.listRoots("s", 1000));
    }
}
