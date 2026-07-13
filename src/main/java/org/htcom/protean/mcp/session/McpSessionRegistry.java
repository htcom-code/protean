/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP Streamable HTTP session registry and server-to-client notification channel.
 *
 * <p>On {@code initialize} it issues a session ({@code Mcp-Session-Id}) and opens a per-session persistent GET SSE
 * stream so notifications can be pushed outside of a call ({@link McpServerNotifier} implementation). Each
 * notification is assigned a session-scoped eventId and recorded in the replay buffer for re-sync on
 * {@code Last-Event-ID} reconnect.
 *
 * <p>A daemon scheduler handles both (1) persistent-stream heartbeats (keeping proxy/idle connections alive) and
 * (2) automatic reclaim of idle sessions. This bean is not created when the session surface is disabled
 * ({@code protean.mcp.session.enabled=false}).
 */
public class McpSessionRegistry implements McpServerNotifier, McpClientChannel {

    private static final Logger log = LoggerFactory.getLogger(McpSessionRegistry.class);
    private static final long HEARTBEAT_MS = 15_000L;

    private final ObjectMapper mapper;
    private final long idleTimeoutMillis;   // <=0 = idle reclaim disabled
    private final int bufferCap;
    private final long streamTimeoutMillis;
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;
    /** Server-to-client request correlation: server request id -> future awaiting the response. */
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong serverReqSeq = new AtomicLong();

    public McpSessionRegistry(ObjectMapper mapper, long idleTimeoutMillis, int bufferCap, long streamTimeoutMillis) {
        this.mapper = mapper;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.bufferCap = bufferCap;
        this.streamTimeoutMillis = streamTimeoutMillis;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-session-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweeper.scheduleWithFixedDelay(this::sweep, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    /** Issues a new session (initialize). */
    public McpSession create() {
        McpSession s = new McpSession(UUID.randomUUID().toString(), bufferCap);
        sessions.put(s.id(), s);
        return s;
    }

    /** Looks up a session (null if none). The lookup itself counts as activity and resets the idle timer. */
    public McpSession get(String id) {
        if (id == null) {
            return null;
        }
        McpSession s = sessions.get(id);
        if (s != null) {
            s.touch();
        }
        return s;
    }

    /** Terminates a session — closes the persistent stream and removes it. Idempotent. */
    public void terminate(String id) {
        McpSession s = sessions.remove(id);
        if (s == null) {
            return;
        }
        SseEmitter e = s.stream();
        if (e != null) {
            synchronized (s) {
                s.detachStream(e);
            }
            try {
                e.complete();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Opens the session's persistent GET SSE stream. If {@code lastEventId >= 0}, buffered events after it are
     * replayed first. Attach and replay are performed atomically under the session lock so that concurrently
     * arriving notifications cannot be interleaved out of order.
     */
    public SseEmitter openStream(McpSession s, long lastEventId) {
        SseEmitter emitter = new SseEmitter(streamTimeoutMillis);
        emitter.onCompletion(() -> s.detachStream(emitter));
        emitter.onTimeout(() -> {
            s.detachStream(emitter);
            emitter.complete();
        });
        emitter.onError(ex -> s.detachStream(emitter));
        synchronized (s) {
            s.attachStream(emitter);
            for (McpSession.BufferedEvent be : s.replayAfter(lastEventId)) {
                try {
                    emitter.send(SseEmitter.event().id(Long.toString(be.eventId()))
                            .data(be.data(), MediaType.APPLICATION_JSON));
                } catch (IOException | RuntimeException ex) {
                    s.detachStream(emitter);
                    emitter.completeWithError(ex);
                    return emitter;
                }
            }
        }
        return emitter;
    }

    @Override
    public CompletableFuture<JsonNode> requestClient(String sessionId, String method, JsonNode params, long timeoutMillis) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        McpSession s = get(sessionId);
        if (s == null) {
            future.completeExceptionally(new IllegalStateException("No MCP session (cannot send request): " + sessionId));
            return future;
        }
        String reqId = "srv:" + serverReqSeq.incrementAndGet();
        pending.put(reqId, future);
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", reqId);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        deliver(s, req.toString());   // deliver the request over the persistent stream (+ record in replay buffer)
        sweeper.schedule(() -> {
            CompletableFuture<JsonNode> f = pending.remove(reqId);
            if (f != null) {
                f.completeExceptionally(new TimeoutException("Client response timed out: " + method));
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        return future;
    }

    @Override
    public JsonNode clientCapabilities(String sessionId) {
        McpSession s = sessions.get(sessionId);
        return s == null ? null : s.clientCapabilities();
    }

    /**
     * If the client-POSTed message is a <b>response to a server-to-client request</b> (its textual id is in
     * {@code pending}), matches it, completes the future, and returns {@code true}. Otherwise (a new
     * request/notification) returns {@code false} — the controller hands it to the dispatcher.
     */
    public boolean completeFromClient(JsonNode message) {
        JsonNode idNode = message == null ? null : message.get("id");
        if (idNode == null || !idNode.isTextual()) {
            return false;
        }
        CompletableFuture<JsonNode> future = pending.remove(idNode.asText());
        if (future == null) {
            return false;
        }
        if (message.has("error")) {
            future.completeExceptionally(new IllegalStateException(
                    "Client error: " + message.path("error").path("message").asText()));
        } else {
            future.complete(message.path("result"));
        }
        return true;
    }

    @Override
    public void broadcast(JsonNode notification) {
        String json = notification.toString();
        for (McpSession s : sessions.values()) {
            deliver(s, json);
        }
    }

    @Override
    public void notifySession(String sessionId, JsonNode notification) {
        McpSession s = sessions.get(sessionId);
        if (s != null) {
            deliver(s, notification.toString());
        }
    }

    /** Assign eventId -> record in buffer -> send if connected. All done under the session lock to guarantee ordering. */
    private void deliver(McpSession s, String json) {
        synchronized (s) {
            long id = s.nextEventId();
            s.record(id, json);
            SseEmitter emitter = s.stream();
            if (emitter != null) {
                try {
                    emitter.send(SseEmitter.event().id(Long.toString(id))
                            .data(json, MediaType.APPLICATION_JSON));
                } catch (IOException | RuntimeException ex) {
                    // Send failed (client disconnected) — detach only the stream and keep the buffer (replayed via Last-Event-ID on reconnect).
                    s.detachStream(emitter);
                    try {
                        emitter.completeWithError(ex);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
    }

    /** Heartbeat (keeps the persistent stream alive) + automatic reclaim of idle sessions. */
    private void sweep() {
        long now = System.nanoTime();
        for (McpSession s : sessions.values()) {
            if (idleTimeoutMillis > 0
                    && (now - s.lastActivityNanos()) / 1_000_000L > idleTimeoutMillis) {
                log.info("auto-reclaiming idle MCP session: {}", s.id());
                terminate(s.id());
                continue;
            }
            SseEmitter emitter = s.stream();
            if (emitter != null) {
                synchronized (s) {
                    SseEmitter e = s.stream();
                    if (e != null) {
                        try {
                            e.send(SseEmitter.event().comment("hb"));
                        } catch (IOException | RuntimeException ex) {
                            s.detachStream(e);
                        }
                    }
                }
            }
        }
    }

    /** On bean destruction, stops the scheduler and cleans up all streams. */
    public void shutdown() {
        sweeper.shutdownNow();
        for (String id : sessions.keySet()) {
            terminate(id);
        }
        pending.forEach((id, f) -> f.completeExceptionally(new IllegalStateException("MCP server shutting down")));
        pending.clear();
    }
}
