/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProblemDetail;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
import org.htcom.protean.mcp.session.McpSession;
import org.htcom.protean.mcp.session.McpSessionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.security.Principal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP Streamable HTTP transport — {@code /platform/mcp}.
 *
 * <p><b>POST</b>: delegates the JSON-RPC message to {@link McpDispatcher} (transport-agnostic). For
 * {@code initialize}, a session is issued and returned in the {@code Mcp-Session-Id} response header.
 * A {@code tools/call} carrying {@code params._meta.progressToken} streams progress notifications plus
 * the result over SSE. Everything else returns a single JSON response.
 *
 * <p><b>GET</b> (text/event-stream): the session's <b>persistent server-to-client stream</b>.
 * Out-of-call notifications (such as {@code tools/list_changed}) are pushed here. Reconnecting with
 * {@code Last-Event-ID} replays events emitted after that id.
 *
 * <p><b>DELETE</b>: terminates the session.
 *
 * <p>Backward compatibility: a request without a session id is handled <b>statelessly</b> (current
 * behavior, no push). An unknown or expired session id returns 404 (prompting re-initialization).
 * When {@code protean.mcp.session.enabled=false} there is no session registry at all, so the transport
 * is purely stateless. Authentication is not implemented here — the injected {@link Principal} (from the
 * consumer's Security setup) is propagated as the authorization context.
 */
@RestController
@Profile("!worker")
@ConditionalOnProperty(name = "protean.mcp.enabled", havingValue = "true")
@RequestMapping("/platform/mcp")
public class McpHttpController {

    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String PROTOCOL_HEADER = "MCP-Protocol-Version";

    private final McpDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final McpSessionRegistry sessions;   // null = session surface disabled
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcp-sse");
        t.setDaemon(true);
        return t;
    });

    public McpHttpController(McpDispatcher dispatcher, ObjectMapper mapper,
                            ObjectProvider<McpSessionRegistry> sessionRegistry) {
        this.dispatcher = dispatcher;
        this.mapper = mapper;
        this.sessions = sessionRegistry.getIfAvailable();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object handle(@RequestBody JsonNode message,
                        @RequestHeader(value = SESSION_HEADER, required = false) String sessionId,
                        @RequestHeader(value = PROTOCOL_HEADER, required = false) String protocolVersion,
                        Principal principal) {
        if (!dispatcher.isSupportedProtocol(protocolVersion)) {
            return problem(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                    ErrorCode.UNSUPPORTED_PROTOCOL_VERSION.format(protocolVersion), null);
        }

        // If this is a response to a server-to-client request (sampling/roots/elicitation) — i.e. it has
        // result/error but no method — don't route it to the dispatcher; complete the waiting future
        // instead. If it matches nothing, ignore it (a late or duplicate response).
        if (!message.hasNonNull("method") && (message.has("result") || message.has("error"))) {
            if (sessions != null) {
                sessions.completeFromClient(message);
            }
            return ResponseEntity.accepted().build();
        }

        String method = message.path("method").asText();

        // Session validation: if a session id is present it must be known (unknown/expired -> 404,
        // prompting re-initialization). Without a session id, proceed statelessly (backward compatibility).
        if (sessions != null && sessionId != null && !sessionId.isBlank()
                && !"initialize".equals(method) && sessions.get(sessionId) == null) {
            // Return a genuine problem+json with type/status 404, instead of a 404 status paired with a
            // mismatched -32600 envelope code.
            return problem(ErrorCode.UNKNOWN_SESSION,
                    ErrorCode.UNKNOWN_SESSION.format(sessionId), sessionId);
        }

        JsonNode progressToken = message.path("params").path("_meta").get("progressToken");
        boolean stream = progressToken != null && !progressToken.isNull() && "tools/call".equals(method);
        if (stream) {
            return stream(message, principal, progressToken, sessionId);
        }

        McpCallContext ctx = new McpCallContext(principal, McpCallContext.NOOP, sessionId);
        JsonNode response = dispatcher.dispatch(message, ctx);
        if (response == null) {
            return ResponseEntity.accepted().build(); // notification — no response
        }
        // On successful initialize, issue a session and return it in the Mcp-Session-Id header, recording
        // the client's capabilities for later gating of server-to-client requests.
        if (sessions != null && "initialize".equals(method) && response.path("result").has("protocolVersion")) {
            McpSession s = sessions.create();
            s.setClientCapabilities(message.path("params").get("capabilities"));
            return ResponseEntity.ok().header(SESSION_HEADER, s.id()).body(response);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Persistent server-to-client SSE stream (GET). Requires a session. Replays via Last-Event-ID.
     * {@code produces} is intentionally left unset — on success the {@link SseEmitter} sets
     * text/event-stream itself, while on error a JSON {@code ResponseEntity} must be returned (pinning
     * the type would break error-response conversion and yield a 500).
     */
    @GetMapping
    public Object openStream(@RequestHeader(value = SESSION_HEADER, required = false) String sessionId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        // GET errors return a status code only, with no body — the client's Accept is text/event-stream,
        // so a JSON body would produce a 406 (Not Acceptable). The status code is the signal (405/400/404).
        if (sessions == null) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        }
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        McpSession s = sessions.get(sessionId);
        if (s == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        long after = parseLastEventId(lastEventId);
        return sessions.openStream(s, after);
    }

    /** Terminates the session. */
    @DeleteMapping
    public ResponseEntity<Void> terminate(@RequestHeader(value = SESSION_HEADER, required = false) String sessionId) {
        if (sessions != null && sessionId != null && !sessionId.isBlank()) {
            sessions.terminate(sessionId);
        }
        return ResponseEntity.noContent().build();
    }

    private static long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return 0L;   // unparseable -> replay from the beginning (the entire buffer)
        }
    }

    /** Per-call SSE stream that emits progress notifications and then finishes with the result. */
    private SseEmitter stream(JsonNode message, Principal principal, JsonNode progressToken, String sessionId) {
        SseEmitter emitter = new SseEmitter(120_000L);
        executor.execute(() -> {
            McpCallContext.ProgressSink sink = (current, total, msg) -> {
                ObjectNode notif = mapper.createObjectNode();
                notif.put("jsonrpc", "2.0");
                notif.put("method", "notifications/progress");
                ObjectNode params = notif.putObject("params");
                params.set("progressToken", progressToken);
                params.put("progress", current);
                if (total > 0) {
                    params.put("total", total);
                }
                if (msg != null) {
                    params.put("message", msg);
                }
                trySend(emitter, notif);
            };
            try {
                JsonNode response = dispatcher.dispatch(message, new McpCallContext(principal, sink, sessionId));
                trySend(emitter, response);
                emitter.complete();
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void trySend(SseEmitter emitter, JsonNode payload) {
        try {
            emitter.send(SseEmitter.event().data(payload.toString(), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    /**
     * Pure HTTP transport errors (before dispatch) — returns a genuine {@code application/problem+json}
     * body with the correct status. Unlike the JSON-RPC and tool error paths, this is the only place that
     * uses the real problem+json media type.
     */
    private ResponseEntity<JsonNode> problem(ErrorCode code, String detail, String instance) {
        ProblemDetail pd = ProblemDetail.of(code).detail(detail).status(code.httpStatus());
        if (instance != null) {
            pd.instance(instance);
        }
        return ResponseEntity.status(code.httpStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd.toJson(mapper));
    }
}
