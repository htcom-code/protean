/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.runtime.RequestTrace;
import org.htcom.protean.runtime.TraceSummary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the pure windowing/trend math of {@link TraceSummary#of}. A fixed {@code now} anchors the two
 * windows so the partitioning is deterministic (no wall-clock dependency).
 */
class TraceSummaryTest {

    private static final long NOW = 1_000_000_000_000L;
    private static final long WINDOW = 60_000L; // 60s

    /** A trace at {@code ageMs} before NOW with the given latency/status/error. */
    private static RequestTrace at(long ageMs, long latencyMs, int status, String error) {
        return new RequestTrace(0, NOW - ageMs, "GET", "/x", "/x", "m", status, latencyMs, error, "tid");
    }

    private static TraceSummary summarize(List<RequestTrace> traces) {
        return TraceSummary.of(traces, NOW, WINDOW, 0, Map.of());
    }

    @Test
    void current_window_aggregates_count_error_and_percentiles() {
        List<RequestTrace> traces = new ArrayList<>();
        // 10 requests within the current window (ages 1s..10s), latencies 1..10ms, one 500 error.
        for (int i = 1; i <= 10; i++) {
            traces.add(at(i * 1000L, i, i == 10 ? 500 : 200, null));
        }
        TraceSummary s = summarize(traces);

        assertEquals(10, s.count());
        assertEquals(1, s.errorCount(), "the status-500 request counts as an error");
        assertEquals(0.1, s.errorRate(), 1e-9);
        assertEquals(10, s.maxLatencyMs());
        // nearest-rank: p95 index = floor(10*0.95)=9 → 10th value (10ms); p50 index = floor(10*0.5)=5 → 6ms
        assertEquals(10, s.p95LatencyMs());
        assertEquals(6, s.p50LatencyMs());
    }

    @Test
    void error_from_thrown_exception_counts_even_when_status_below_500() {
        // status 200 but an exception FQCN present → still an error.
        TraceSummary s = summarize(List.of(at(1000, 5, 200, "java.lang.IllegalStateException")));
        assertEquals(1, s.count());
        assertEquals(1, s.errorCount());
    }

    @Test
    void trend_is_computed_against_the_previous_window() {
        List<RequestTrace> traces = new ArrayList<>();
        // previous window (ages 61s..80s): 2 requests, both fast (10ms), no errors
        traces.add(at(61_000, 10, 200, null));
        traces.add(at(70_000, 10, 200, null));
        // current window (ages 1s..30s): 4 requests, slower (20ms), one error
        traces.add(at(1_000, 20, 200, null));
        traces.add(at(10_000, 20, 200, null));
        traces.add(at(20_000, 20, 500, null));
        traces.add(at(30_000, 20, 200, null));

        TraceSummary s = summarize(traces);

        assertEquals(4, s.count());
        // requests: (4-2)/2 = +1.0 (=+100%)
        assertEquals(1.0, s.requestsDeltaPct(), 1e-9);
        // error rate: current 1/4=0.25, previous 0 → +25pp
        assertEquals(25.0, s.errorRateDeltaPp(), 1e-9);
        // p95: current 20ms - previous 10ms = +10ms
        assertEquals(10L, s.p95DeltaMs());
    }

    @Test
    void trend_is_null_when_previous_window_is_empty() {
        // only current-window traffic; no prior baseline
        TraceSummary s = summarize(List.of(at(1000, 5, 200, null), at(2000, 7, 200, null)));
        assertEquals(2, s.count());
        assertNull(s.requestsDeltaPct(), "no previous window → no fabricated delta");
        assertNull(s.errorRateDeltaPp());
        assertNull(s.p95DeltaMs());
    }

    @Test
    void traces_older_than_two_windows_are_ignored() {
        List<RequestTrace> traces = new ArrayList<>();
        traces.add(at(1000, 5, 200, null));      // current
        traces.add(at(200_000, 5, 200, null));   // ~3.3 windows old → dropped from both windows
        TraceSummary s = summarize(traces);
        assertEquals(1, s.count());
        assertNull(s.requestsDeltaPct(), "the stale trace does not populate the previous window");
    }

    @Test
    void empty_buffer_yields_zeros_and_null_trend() {
        TraceSummary s = summarize(List.of());
        assertEquals(0, s.count());
        assertEquals(0.0, s.errorRate(), 1e-9);
        assertEquals(0, s.p95LatencyMs());
        assertNull(s.requestsDeltaPct());
    }

    @Test
    void mode_snapshot_is_passed_through() {
        TraceSummary s = TraceSummary.of(List.of(), NOW, WINDOW, 3, Map.of("in-process", 1L, "worker", 2L));
        assertEquals(3, s.activeModules());
        assertEquals(Map.of("in-process", 1L, "worker", 2L), s.modulesByMode());
    }
}
