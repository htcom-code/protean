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
import org.htcom.protean.mcp.session.McpSession;
import org.htcom.protean.mcp.session.McpSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client response to a server-initiated request (elicitation, sampling, roots) is accepted only from the session
 * the request was sent to. Another session, or a request with no session, must not be able to complete it — even
 * when it knows the request id. Request ids must not be predictable either. Uses real HTTP (RANDOM_PORT).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"protean.mcp.enabled=true"})
class McpClientResponseBindingTest {

    static final Path STORE = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-response-binding-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("protean.module-store.dir", STORE::toString);
    }

    @LocalServerPort int port;
    @Autowired McpSessionRegistry registry;
    @Autowired ObjectMapper mapper;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void response_from_the_target_session_completes_the_request() throws Exception {
        McpSession target = registry.create();
        CompletableFuture<JsonNode> pending = registry.requestClient(target.id(), "roots/list", null, 10_000);
        String id = deliveredRequestId(target);

        assertEquals(202, postResponse(id, target.id()).statusCode());
        assertTrue(pending.get(5, TimeUnit.SECONDS).path("roots").isArray());
    }

    @Test
    void response_from_another_session_does_not_complete_the_request() throws Exception {
        McpSession target = registry.create();
        McpSession other = registry.create();
        CompletableFuture<JsonNode> pending = registry.requestClient(target.id(), "elicitation/create", null, 10_000);
        String id = deliveredRequestId(target);

        postResponse(id, other.id());
        assertFalse(pending.isDone(), "another session must not complete this session's request");

        // the genuine response still lands afterwards
        postResponse(id, target.id());
        assertTrue(pending.get(5, TimeUnit.SECONDS).path("roots").isArray());
    }

    @Test
    void response_without_a_session_does_not_complete_the_request() throws Exception {
        McpSession target = registry.create();
        CompletableFuture<JsonNode> pending = registry.requestClient(target.id(), "sampling/createMessage", null, 10_000);
        String id = deliveredRequestId(target);

        postResponse(id, null);
        assertFalse(pending.isDone(), "a session-less response must not complete a session's request");
        pending.cancel(true);
    }

    @Test
    void request_ids_are_not_sequential() throws Exception {
        McpSession s = registry.create();
        CompletableFuture<JsonNode> first = registry.requestClient(s.id(), "roots/list", null, 10_000);
        CompletableFuture<JsonNode> second = registry.requestClient(s.id(), "roots/list", null, 10_000);
        String a = requestIdAt(s, 1);
        String b = requestIdAt(s, 2);
        assertNotEquals(a, b);
        assertFalse(a.matches("srv:\\d+") || b.matches("srv:\\d+"), "ids must not be a counter: " + a + ", " + b);
        first.cancel(true);
        second.cancel(true);
    }

    // --- helpers ---

    private HttpResponse<String> postResponse(String id, String sessionId) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + mapper.writeValueAsString(id) + ",\"result\":{\"roots\":[]}}";
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(mcpUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (sessionId != null) {
            b.header("Mcp-Session-Id", sessionId);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String deliveredRequestId(McpSession s) throws Exception {
        return requestIdAt(s, 1);
    }

    /** Reads the {@code n}-th (1-based) event of the session's buffered stream and returns its request id. */
    private String requestIdAt(McpSession s, int n) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(mcpUrl()))
                .header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", s.id())
                .header("Last-Event-ID", Integer.toString(n - 1))
                .GET().build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        return line.substring("data:".length()).trim();
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        });
        String data;
        try {
            data = first.get(10, TimeUnit.SECONDS);
        } finally {
            resp.body().close();
        }
        assertNotNull(data, "the server request must be delivered on the session stream");
        return mapper.readTree(data).path("id").asText();
    }

    private String mcpUrl() {
        return "http://localhost:" + port + "/platform/mcp";
    }
}
