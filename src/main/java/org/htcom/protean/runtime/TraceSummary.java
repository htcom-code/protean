/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Windowed request-metrics summary for the observability console header (the KPI row), pushed on the SSE
 * {@code summary} event so the console renders the header from one payload instead of fabricating trends or
 * re-aggregating client-side.
 *
 * <p>Aggregates the current rolling window {@code (now-windowMs, now]} and a <b>trend</b> against the previous equal
 * window {@code (now-2*windowMs, now-windowMs]}. The three trend fields are {@code null} when the previous window has
 * no samples — no baseline, no fabricated delta.
 *
 * <p>Computed out-of-band from the {@link TraceStore} ring buffer (the recording hot path is untouched), so window
 * accuracy is bounded by {@code protean.trace.capacity}: a window holding more than the ring capacity undercounts —
 * the accepted limit of the dev/single-node live-view tier (raise the capacity to widen it).
 *
 * <p>{@code activeModules}/{@code modulesByMode} are a point-in-time module-lifecycle snapshot (active modules grouped
 * by isolation mode), supplied by the caller — not derived from traces.
 *
 * @param windowMs          rolling window length used (ms)
 * @param count             requests in the current window
 * @param errorCount        errored requests in the current window (thrown exception or status &gt;= 500)
 * @param errorRate         {@code errorCount / count} for the current window (0.0 when count == 0), a fraction
 * @param p50LatencyMs      current-window median latency (ms)
 * @param p95LatencyMs      current-window 95th percentile latency (ms)
 * @param p99LatencyMs      current-window 99th percentile latency (ms)
 * @param maxLatencyMs      current-window worst latency (ms)
 * @param requestsDeltaPct  fractional change in request count vs the previous window ({@code (cur-prev)/prev}, e.g.
 *                          {@code 0.12} = +12%); {@code null} when the previous window is empty
 * @param errorRateDeltaPp  error-rate change vs the previous window in <b>percentage points</b>
 *                          ({@code (cur-prev)*100}); {@code null} when the previous window is empty
 * @param p95DeltaMs        p95 change vs the previous window (ms); {@code null} when the previous window is empty
 * @param activeModules     number of ACTIVE modules right now
 * @param modulesByMode     active-module counts grouped by isolation mode (e.g. {@code {"in-process":1,"worker":2}})
 */
public record TraceSummary(
        long windowMs,
        long count,
        long errorCount,
        double errorRate,
        long p50LatencyMs,
        long p95LatencyMs,
        long p99LatencyMs,
        long maxLatencyMs,
        Double requestsDeltaPct,
        Double errorRateDeltaPp,
        Long p95DeltaMs,
        long activeModules,
        Map<String, Long> modulesByMode
) {

    /**
     * Aggregates a ring-buffer snapshot into the current and previous windows anchored at {@code nowMs}, computing the
     * trend between them. {@code modulesByMode}/{@code activeModules} are passed through unchanged (a caller-supplied
     * point-in-time snapshot). The buffer may be in any order; entries older than {@code now-2*windowMs} are ignored.
     */
    public static TraceSummary of(List<RequestTrace> buffer, long nowMs, long windowMs,
                                  long activeModules, Map<String, Long> modulesByMode) {
        long curFrom = nowMs - windowMs;
        long prevFrom = nowMs - 2 * windowMs;
        List<RequestTrace> cur = new ArrayList<>();
        List<RequestTrace> prev = new ArrayList<>();
        for (RequestTrace t : buffer) {
            long ts = t.epochMillis();
            if (ts > curFrom) {
                cur.add(t);
            } else if (ts > prevFrom) {
                prev.add(t);
            }
        }
        Window c = Window.of(cur);
        Window p = Window.of(prev);
        Double requestsDeltaPct = p.count == 0 ? null : (double) (c.count - p.count) / p.count;
        Double errorRateDeltaPp = p.count == 0 ? null : (c.errorRate - p.errorRate) * 100.0;
        Long p95DeltaMs = p.count == 0 ? null : c.p95 - p.p95;
        return new TraceSummary(windowMs, c.count, c.errorCount, c.errorRate,
                c.p50, c.p95, c.p99, c.max, requestsDeltaPct, errorRateDeltaPp, p95DeltaMs,
                activeModules, modulesByMode);
    }

    /** One window's aggregate over a trace list. */
    private record Window(long count, long errorCount, double errorRate,
                          long p50, long p95, long p99, long max) {
        static Window of(List<RequestTrace> traces) {
            if (traces.isEmpty()) {
                return new Window(0, 0, 0.0, 0, 0, 0, 0);
            }
            long errors = 0;
            long max = 0;
            long[] latencies = new long[traces.size()];
            for (int i = 0; i < traces.size(); i++) {
                RequestTrace t = traces.get(i);
                if (t.error() != null || t.status() >= 500) {
                    errors++;
                }
                latencies[i] = t.latencyMs();
                if (t.latencyMs() > max) {
                    max = t.latencyMs();
                }
            }
            Arrays.sort(latencies);
            long n = traces.size();
            return new Window(n, errors, (double) errors / n,
                    percentile(latencies, 50), percentile(latencies, 95), percentile(latencies, 99), max);
        }

        /** Nearest-rank percentile over a sorted array (matches the frontend's prior client-side derivation). */
        private static long percentile(long[] sorted, int q) {
            int idx = (int) Math.floor(sorted.length * (q / 100.0));
            if (idx >= sorted.length) {
                idx = sorted.length - 1;
            }
            return sorted[idx];
        }
    }
}
