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
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolResult;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streamable HTTP session, standing stream and resumability regression.
 * Uses real HTTP (RANDOM_PORT) to verify session issuance/validation, stateless backward compatibility,
 * and GET standing-stream replay.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"protean.mcp.enabled=true"})
class McpStreamableSessionTest {

    static final Path STORE = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-session-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("protean.module-store.dir", STORE::toString);
    }

    @LocalServerPort int port;
    @Autowired McpSessionRegistry registry;
    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private String mcpUrl() {
        return "http://localhost:" + port + "/platform/mcp";
    }

    private HttpResponse<String> post(String body, String sessionId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(mcpUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (sessionId != null) {
            b.header("Mcp-Session-Id", sessionId);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void initialize_returns_session_header() throws Exception {
        HttpResponse<String> resp = post(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}}}",
                null);
        assertEquals(200, resp.statusCode());
        String sid = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertNotNull(sid, "the initialize response must carry an Mcp-Session-Id header");
        assertFalse(sid.isBlank());
        // tools.listChanged capability advertised
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.path("result").path("capabilities").path("tools").path("listChanged").asBoolean(false),
                "capabilities.tools.listChanged must be true: " + resp.body());
    }

    @Test
    void runtime_tool_registration_pushes_list_changed_and_reflects_in_list() throws Exception {
        McpSession s = registry.create();
        McpTool echo = new McpTool() {
            @Override public String name() { return "test.echo"; }
            @Override public String description() { return "test tool"; }
            @Override public ObjectNode inputSchema() {
                ObjectNode s = mapper.createObjectNode();
                s.put("type", "object");
                return s;
            }
            @Override public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
                return McpToolResult.ok("ok");
            }
        };
        try {
            // Runtime registration broadcasts notifications/tools/list_changed to all sessions (buffered on s)
            dispatcher.registerTool(echo);
            // Connect the standing stream without Last-Event-ID: receive the buffered list_changed replay
            // (demonstrating refresh without reconnection)
            List<String> data = readSse(s.id(), null, 1, Duration.ofSeconds(10));
            assertEquals(1, data.size(), "1 list_changed notification: " + data);
            assertTrue(data.get(0).contains("notifications/tools/list_changed"), data.get(0));
            // the new tool is reflected in tools/list
            JsonNode list = mapper.readTree(post(
                    "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}", null).body());
            boolean found = false;
            for (JsonNode t : list.path("result").path("tools")) {
                if ("test.echo".equals(t.path("name").asText())) {
                    found = true;
                }
            }
            assertTrue(found, "the runtime-registered tool must appear in tools/list");
        } finally {
            dispatcher.unregisterTool("test.echo");
        }
    }

    @Test
    void stateless_post_without_session_still_works() throws Exception {
        HttpResponse<String> resp = post(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", null);
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.path("result").path("tools").isArray(), "stateless tools/list works: " + resp.body());
    }

    @Test
    void unknown_session_id_returns_404() throws Exception {
        // Real-JVM e2e: 404 status + real application/problem+json + type/status (not an envelope -32600).
        HttpResponse<String> resp = post(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}", "bogus-session-xyz");
        assertEquals(404, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("application/problem+json"),
                "must be a real problem+json media type: " + resp.headers().map());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("urn:protean:error:unknown-session", body.path("type").asText(), resp.body());
        assertEquals(404, body.path("status").asInt(), resp.body());
        assertEquals("UNKNOWN_SESSION", body.path("code").asText(), resp.body());
    }

    @Test
    void unsupported_protocol_version_returns_400() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(mcpUrl()))
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "1999-01-01")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}"))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("application/problem+json"),
                "must be a real problem+json media type: " + resp.headers().map());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("urn:protean:error:unsupported-protocol-version", body.path("type").asText(), resp.body());
        assertEquals(400, body.path("status").asInt(), resp.body());
    }

    @Test
    void get_stream_replays_buffered_events_after_last_event_id() throws Exception {
        // With the stream not connected, send 3 notifications to the session -> buffered (eventId 1,2,3)
        McpSession s = registry.create();
        for (int i = 1; i <= 3; i++) {
            registry.notifySession(s.id(),
                    mapper.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{\"seq\":" + i + "}}"));
        }
        // Connect the standing stream with Last-Event-ID=1: only 2,3 must be replayed
        List<String> dataLines = readSse(s.id(), "1", 2, Duration.ofSeconds(10));
        assertEquals(2, dataLines.size(), "2 events after eventId 1 must be replayed: " + dataLines);
        assertTrue(dataLines.get(0).contains("\"seq\":2"), dataLines.get(0));
        assertTrue(dataLines.get(1).contains("\"seq\":3"), dataLines.get(1));
    }

    @Test
    void get_stream_without_session_400_and_unknown_session_404() throws Exception {
        HttpRequest noSid = HttpRequest.newBuilder(URI.create(mcpUrl()))
                .header("Accept", "text/event-stream").GET().build();
        assertEquals(400, client.send(noSid, HttpResponse.BodyHandlers.ofString()).statusCode(),
                "a GET without a session header must be 400 (not 500)");
        HttpRequest bogus = HttpRequest.newBuilder(URI.create(mcpUrl()))
                .header("Accept", "text/event-stream").header("Mcp-Session-Id", "nope").GET().build();
        assertEquals(404, client.send(bogus, HttpResponse.BodyHandlers.ofString()).statusCode(),
                "a GET for an unknown session is 404");
    }

    /** Opens the GET SSE stream and returns once {@code expected} data lines are collected (or however many by the deadline). */
    private List<String> readSse(String sessionId, String lastEventId, int expected, Duration deadline)
            throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(mcpUrl()))
                .header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .GET();
        if (lastEventId != null) {
            rb.header("Last-Event-ID", lastEventId);
        }
        HttpRequest req = rb.build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());
        List<String> data = new ArrayList<>();
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while (data.size() < expected && (line = br.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        data.add(line.substring("data:".length()).trim());
                    }
                }
            } catch (Exception ignored) {
            }
        });
        try {
            reader.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception timeout) {
            reader.cancel(true);
        }
        resp.body().close();   // close the standing stream
        return data;
    }
}
