/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.runtime.RequestTrace;
import org.htcom.protean.runtime.TraceMetrics;
import org.htcom.protean.runtime.TraceStore;
import org.htcom.protean.runtime.TraceSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Server-side push transport for the observability console (SSE). A single daemon ticker reads new traces
 * from the {@link TraceStore} via a forward seq cursor and pushes deltas to every connected client, so N
 * console clients collapse into one internal loop instead of N×3 polling GETs — and the console no longer
 * polls {@code /platform/modules}, which removes that self-observation trace pollution at the source (for
 * the console; other polling consumers are their own choice).
 *
 * <p>The recording hot path ({@code RequestTraceFilter.record()}) is untouched: this reads the store
 * out-of-band on a timer. One SSE connection multiplexes three named events — {@code trace} (incremental,
 * only when there is something new), {@code metrics} and {@code modules} (full snapshots each tick, which
 * also keeps the connection warm through proxies). A fresh connection receives an initial snapshot of all
 * three immediately so it is never blank until the first tick.
 *
 * <p>Same gating as the admin controllers ({@code @Profile("!worker")} + {@code protean.admin.enabled}), so
 * it is absent on workers and when the management surface is opted out.
 */
@Component
@Profile("!worker")
@ConditionalOnProperty(name = "protean.admin.enabled", havingValue = "true", matchIfMissing = true)
public class TraceStreamService {

    private static final Logger log = LoggerFactory.getLogger(TraceStreamService.class);
    private static final long TICK_MS = 1_000L;
    /** Size of the trace backlog replayed to a newly connected client. */
    private static final int INITIAL_TRACES = 200;

    private final TraceStore store;
    private final TraceMetrics metrics;
    private final ModulePlatform platform;
    private final ObjectMapper mapper;
    private final ProteanProperties props;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService ticker;
    /** Highest trace seq already pushed; advanced every tick so each delta carries only what is new. */
    private volatile long lastSeq = 0;

    public TraceStreamService(TraceStore store, TraceMetrics metrics, ModulePlatform platform, ObjectMapper mapper,
                              ProteanProperties props) {
        this.store = store;
        this.metrics = metrics;
        this.platform = platform;
        this.mapper = mapper;
        this.props = props;
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "protean-trace-stream");
            t.setDaemon(true);
            return t;
        });
        this.ticker.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Opens a live SSE stream. Timeout is disabled (0) because the stream is meant to stay open; if a proxy
     * drops it anyway, the browser {@code EventSource} reconnects and gets a fresh initial snapshot.
     */
    public SseEmitter open() {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(ex -> emitters.remove(emitter));
        try {
            List<ModuleStatus> mods = moduleStatuses();
            send(emitter, "trace", store.recent(INITIAL_TRACES, null));
            send(emitter, "metrics", metrics.snapshots());
            send(emitter, "modules", mods);
            send(emitter, "summary", summary(mods));
        } catch (IOException | RuntimeException ex) {
            emitter.completeWithError(ex);
            return emitter;
        }
        emitters.add(emitter);
        return emitter;
    }

    /** One push cycle: advance the cursor by the newest delta, then fan the deltas + fresh snapshots out. */
    private void tick() {
        try {
            List<RequestTrace> delta = store.after(lastSeq);
            if (!delta.isEmpty()) {
                lastSeq = delta.get(delta.size() - 1).seq(); // delta is oldest-first, so the last is newest
            }
            if (emitters.isEmpty()) {
                return; // cursor still advanced above, so a later client's first delta stays small
            }
            List<ModuleStatus> mods = moduleStatuses();
            if (!delta.isEmpty()) {
                broadcast("trace", delta);
            }
            broadcast("metrics", metrics.snapshots());
            broadcast("modules", mods);
            broadcast("summary", summary(mods));
        } catch (RuntimeException ex) {
            log.warn("trace stream tick failed", ex);
        }
    }

    private void broadcast(String event, Object payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("trace stream: failed to serialize {} event", event, e);
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(json, MediaType.APPLICATION_JSON));
            } catch (IOException | RuntimeException ex) {
                // client gone — drop it; EventSource will reconnect and re-snapshot
                emitters.remove(emitter);
                try {
                    emitter.completeWithError(ex);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void send(SseEmitter emitter, String event, Object payload) throws IOException {
        emitter.send(SseEmitter.event().name(event)
                .data(mapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
    }

    /**
     * Windowed request summary for the console header, computed out-of-band from the trace ring buffer plus a
     * point-in-time active-module-by-mode count derived from {@code modules} (reused so the two events stay
     * consistent). Window length is read live ({@code protean.trace.summary-window-ms}), floored to 1s.
     */
    private TraceSummary summary(List<ModuleStatus> modules) {
        Map<String, Long> byMode = modules.stream()
                .filter(m -> m.desiredState() == ModuleDescriptor.DesiredState.ACTIVE)
                .collect(Collectors.groupingBy(ModuleStatus::mode, TreeMap::new, Collectors.counting()));
        long active = byMode.values().stream().mapToLong(Long::longValue).sum();
        long windowMs = Math.max(1_000L, props.getTrace().getSummaryWindowMs());
        return TraceSummary.of(store.after(0), System.currentTimeMillis(), windowMs, active, byMode);
    }

    /** Current module statuses, mapped exactly as {@code ModuleAdminController.list()} does. */
    private List<ModuleStatus> moduleStatuses() {
        return platform.list().stream()
                .map(d -> ModuleStatus.from(d, platform.effectiveMode(d), platform.boundGeneration(d.id()),
                        platform.boundLibraryGenerations(d.id()), platform.libraryGeneration(d.id())))
                .toList();
    }

    @PreDestroy
    public void shutdown() {
        ticker.shutdownNow();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
            }
        }
        emitters.clear();
    }
}
