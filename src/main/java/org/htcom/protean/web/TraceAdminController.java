/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.web;

import org.htcom.protean.runtime.ModuleMetricsSnapshot;
import org.htcom.protean.runtime.RequestTrace;
import org.htcom.protean.runtime.TraceMetrics;
import org.htcom.protean.runtime.TraceQuery;
import org.htcom.protean.runtime.TraceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Runtime trace query API — exposes recent request execution records newest-first
 * (observability PoC).
 * Query with {@code GET /platform/traces} and any combination of filters:
 * {@code limit}, {@code moduleId}, {@code errorsOnly}, {@code status}, {@code minLatencyMs},
 * {@code since} (epoch-millis lower bound), and {@code beforeSeq} (cursor for paging into the past).
 * All filters combine with AND; results stay newest-first.
 *
 * <p>Same management surface as {@code ModuleAdminController}, so it can be opted out via the same
 * switch: {@code protean.admin.enabled=false} (default on).
 */
@RestController
@Profile("!worker")
@ConditionalOnProperty(name = "protean.admin.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/platform/traces")
public class TraceAdminController {

    private final TraceStore store;
    private final TraceMetrics metrics;
    private final TraceStreamService stream;

    public TraceAdminController(TraceStore store, TraceMetrics metrics, TraceStreamService stream) {
        this.store = store;
        this.metrics = metrics;
        this.stream = stream;
    }

    @GetMapping
    public List<RequestTrace> recent(@RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(required = false) String moduleId,
                                     @RequestParam(defaultValue = "false") boolean errorsOnly,
                                     @RequestParam(required = false) Integer status,
                                     @RequestParam(required = false) Long minLatencyMs,
                                     @RequestParam(required = false) Long since,
                                     @RequestParam(required = false) Long beforeSeq) {
        return store.recent(new TraceQuery(Math.max(1, limit), moduleId, errorsOnly,
                status, minLatencyMs, since, beforeSeq));
    }

    /**
     * Per-module aggregated metrics (opt-in; {@code protean.trace.metrics.enabled}). With {@code moduleId},
     * returns that module's metrics (empty list if untracked); without it, every tracked module.
     * Returns an empty list when metrics are disabled.
     */
    @GetMapping("/metrics")
    public List<ModuleMetricsSnapshot> metrics(@RequestParam(required = false) String moduleId) {
        if (moduleId != null) {
            return metrics.snapshot(moduleId).map(List::of).orElseGet(List::of);
        }
        return metrics.snapshots();
    }

    /**
     * Live push stream (SSE) that replaces the console's 5s polling. One connection multiplexes named
     * events {@code trace} / {@code metrics} / {@code modules}; a fresh connection gets an initial snapshot
     * of all three, then incremental pushes. Falls under the {@code /platform/traces} prefix, so the stream
     * connection itself is excluded from trace recording (no self-observation). See {@link TraceStreamService}.
     */
    @GetMapping("/stream")
    public SseEmitter stream() {
        return stream.open();
    }
}
