/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.runtime;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.config.ConfigChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded in-memory ring buffer of request traces (runtime trace PoC).
 * When capacity is exceeded, the oldest entries are dropped first. Queries return newest-first.
 *
 * <p>Toggle via {@code protean.trace.enabled} (default true); control retention via
 * {@code protean.trace.capacity} (default 200). Both are read live, so a runtime config change takes
 * effect on the next record; a capacity decrease also trims immediately on {@link ConfigChangedEvent}.
 * Persistence and external exposure are follow-up work — this is a minimal demonstration of
 * runtime observability.
 */
@Component
public class TraceStore {

    private final ProteanProperties props;
    private final Deque<RequestTrace> buffer = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong();

    public TraceStore(ProteanProperties props) {
        this.props = props;
    }

    public boolean enabled() {
        return props.getTrace().isEnabled();
    }

    private int capacity() {
        return Math.max(1, props.getTrace().getCapacity());
    }

    /** Record a single trace (seq auto-assigned). Entries over capacity are removed oldest-first. */
    public synchronized void record(long epochMillis, String method, String uri, String pattern,
                                    String moduleId, int status, long latencyMs, String error,
                                    String traceId) {
        buffer.addLast(new RequestTrace(seq.incrementAndGet(), epochMillis, method, uri,
                pattern, moduleId, status, latencyMs, error, traceId));
        trimToCapacity();
    }

    private synchronized void trimToCapacity() {
        int cap = capacity();
        while (buffer.size() > cap) {
            buffer.removeFirst();
        }
    }

    /** Trim immediately when {@code protean.trace.capacity} shrinks at runtime (Tier 1 live listener). */
    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        if (event.changed("trace.capacity")) {
            trimToCapacity();
        }
    }

    /** Discard all buffered traces (seq counter is preserved so ordering stays monotonic). */
    public synchronized void clear() {
        buffer.clear();
    }

    /** Up to {@code limit} entries, newest-first. If moduleId is given, only that module's entries. */
    public List<RequestTrace> recent(int limit, String moduleId) {
        return recent(new TraceQuery(limit, moduleId, false, null, null, null, null));
    }

    /**
     * Forward tail cursor: all buffered traces with {@code seq > afterSeq}, <b>oldest-first</b>. Complements
     * {@link TraceQuery#beforeSeq()} (which pages into the past) — this reads only what is new since the last
     * poll, for streaming deltas to SSE clients. Pass {@code 0} to get everything currently buffered.
     *
     * <p>Because the buffer is bounded (a ring), traces evicted between two calls are simply absent from the
     * next delta; a slow consumer that falls more than {@code capacity} entries behind loses the gap (acceptable
     * for the dev live-view tier). The lock is held only to snapshot the buffer; filtering runs lock-free.
     */
    public List<RequestTrace> after(long afterSeq) {
        RequestTrace[] snapshot;
        synchronized (this) {
            snapshot = buffer.toArray(new RequestTrace[0]); // oldest-first
        }
        List<RequestTrace> out = new ArrayList<>();
        for (RequestTrace t : snapshot) {
            if (t.seq() > afterSeq) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Up to {@code query.limit()} entries, newest-first, matching every set constraint in {@code query}
     * (see {@link TraceQuery}).
     *
     * <p>The lock is held only long enough to copy a snapshot of the buffer; filtering (which can be the
     * expensive part with several criteria) runs outside the lock. This keeps a burst of queries from
     * blocking the recording hot path. Traces are immutable records, so reading the snapshot lock-free is
     * safe. The snapshot is a point-in-time view; entries recorded during filtering are simply not included.
     */
    public List<RequestTrace> recent(TraceQuery query) {
        RequestTrace[] snapshot;
        synchronized (this) {
            snapshot = buffer.toArray(new RequestTrace[0]); // oldest-first
        }
        int limit = Math.max(1, query.limit());
        List<RequestTrace> out = new ArrayList<>();
        // Walk newest-first (end of the snapshot) and stop once the limit is reached.
        for (int i = snapshot.length - 1; i >= 0 && out.size() < limit; i--) {
            RequestTrace t = snapshot[i];
            if (query.matches(t)) {
                out.add(t);
            }
        }
        return out;
    }
}
