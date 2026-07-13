/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.session;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP Streamable HTTP session. Holds the state for the lifetime of a single client connection:
 * the persistent server-to-client SSE stream (GET) handle, the replay buffer (resumability), and the
 * last-activity timestamp used for idle detection.
 *
 * <p>Replay: each message going out on the GET stream is assigned a session-scoped monotonically increasing
 * {@code eventId}, and the most recent {@code bufferCap} entries are kept in a ring buffer. When the client
 * reconnects with {@code Last-Event-ID}, events after that id are re-streamed (anything evicted from the buffer
 * is re-synced by the client via a re-list).
 *
 * <p>Thread safety: buffer/emitter access is synchronized on {@code this}; the activity timestamp and eventSeq are
 * volatile/atomic.
 */
public final class McpSession {

    /** Replay buffer entry (eventId + serialized JSON payload). */
    public record BufferedEvent(long eventId, String data) {
    }

    private final String id;
    private final int bufferCap;
    private final AtomicLong eventSeq = new AtomicLong();
    private final Deque<BufferedEvent> buffer = new ArrayDeque<>();  // guarded by this
    private volatile long lastActivityNanos = System.nanoTime();
    private volatile SseEmitter stream;   // persistent GET stream (null if none)
    private volatile com.fasterxml.jackson.databind.JsonNode clientCapabilities; // reported by the client at initialize

    McpSession(String id, int bufferCap) {
        this.id = id;
        this.bufferCap = Math.max(1, bufferCap);
    }

    public String id() {
        return id;
    }

    /** Capabilities the client reported at initialize (null if none). Used to gate server-to-client requests. */
    public com.fasterxml.jackson.databind.JsonNode clientCapabilities() {
        return clientCapabilities;
    }

    public void setClientCapabilities(com.fasterxml.jackson.databind.JsonNode caps) {
        this.clientCapabilities = caps;
    }

    /** Updates the activity timestamp (resets the idle auto-reclaim timer). Called on every session-bound request. */
    public void touch() {
        lastActivityNanos = System.nanoTime();
    }

    public long lastActivityNanos() {
        return lastActivityNanos;
    }

    long nextEventId() {
        return eventSeq.incrementAndGet();
    }

    /** Records an outgoing event in the buffer and evicts the oldest entries beyond capacity. */
    synchronized void record(long eventId, String data) {
        buffer.addLast(new BufferedEvent(eventId, data));
        while (buffer.size() > bufferCap) {
            buffer.removeFirst();
        }
    }

    /** Snapshot of buffered events after (greater than) {@code lastEventId}, for replay. */
    synchronized List<BufferedEvent> replayAfter(long lastEventId) {
        List<BufferedEvent> out = new ArrayList<>();
        for (BufferedEvent e : buffer) {
            if (e.eventId() > lastEventId) {
                out.add(e);
            }
        }
        return out;
    }

    synchronized void attachStream(SseEmitter emitter) {
        this.stream = emitter;
    }

    synchronized void detachStream(SseEmitter emitter) {
        if (this.stream == emitter) {
            this.stream = null;
        }
    }

    /** The currently connected persistent stream (null if none). */
    SseEmitter stream() {
        return stream;
    }
}
