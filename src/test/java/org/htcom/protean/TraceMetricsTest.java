/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.runtime.ModuleMetricsSnapshot;
import org.htcom.protean.runtime.TraceMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit test for {@link TraceMetrics} aggregation (step A): counts, error rate, latency percentiles,
 * the opt-in gate, unattributed-request bucketing, and max-modules eviction.
 */
class TraceMetricsTest {

    private TraceMetrics enabledMetrics(int maxModules) {
        ProteanProperties props = new ProteanProperties();
        props.getTrace().getMetrics().setEnabled(true);
        props.getTrace().getMetrics().setMaxModules(maxModules);
        return new TraceMetrics(props);
    }

    @Test
    void disabled_by_default_is_a_noop() {
        TraceMetrics m = new TraceMetrics(new ProteanProperties());
        assertThat(m.enabled()).isFalse();
        m.observe("mod-a", 200, 5, null, 1000);
        assertThat(m.snapshots()).isEmpty();
        assertThat(m.snapshot("mod-a")).isEmpty();
    }

    @Test
    void counts_requests_and_errors() {
        TraceMetrics m = enabledMetrics(512);
        m.observe("mod-a", 200, 5, null, 1000);
        m.observe("mod-a", 200, 7, null, 1001);
        m.observe("mod-a", 500, 9, null, 1002);                                  // 5xx -> error
        m.observe("mod-a", 200, 9, "java.lang.IllegalStateException", 1003);     // thrown -> error

        ModuleMetricsSnapshot s = m.snapshot("mod-a").orElseThrow();
        assertThat(s.count()).isEqualTo(4);
        assertThat(s.errorCount()).isEqualTo(2);
        assertThat(s.errorRate()).isCloseTo(0.5, within(1e-9));
        assertThat(s.lastSeenEpochMillis()).isEqualTo(1003);
    }

    @Test
    void latency_percentiles_and_max_are_approximated() {
        TraceMetrics m = enabledMetrics(512);
        // 90 fast requests at 1ms + 10 slow requests at 1000ms (so the slow tail is above p90)
        for (int i = 0; i < 90; i++) {
            m.observe("mod-a", 200, 1, null, 1000 + i);
        }
        for (int i = 0; i < 10; i++) {
            m.observe("mod-a", 200, 1000, null, 2000 + i);
        }

        ModuleMetricsSnapshot s = m.snapshot("mod-a").orElseThrow();
        assertThat(s.count()).isEqualTo(100);
        assertThat(s.maxLatencyMs()).isEqualTo(1000);
        // p50 sits in the ~1ms bucket (upper bound 2); the slow tail does not drag the median up
        assertThat(s.p50LatencyMs()).isLessThanOrEqualTo(2);
        // p95 falls into the slow tail's bucket, well above the median
        assertThat(s.p95LatencyMs()).isGreaterThanOrEqualTo(1000);
        assertThat(s.p95LatencyMs()).isGreaterThan(s.p50LatencyMs());
    }

    @Test
    void unattributed_requests_bucket_under_platform_key() {
        TraceMetrics m = enabledMetrics(512);
        m.observe(null, 404, 2, null, 1000);
        assertThat(m.snapshot(TraceMetrics.PLATFORM_KEY)).isPresent();
        assertThat(m.snapshot(null).orElseThrow().moduleId()).isEqualTo(TraceMetrics.PLATFORM_KEY);
    }

    @Test
    void evicts_least_recently_seen_module_past_max_modules() {
        TraceMetrics m = enabledMetrics(2);
        m.observe("mod-a", 200, 1, null, 1000);   // oldest
        m.observe("mod-b", 200, 1, null, 2000);
        m.observe("mod-c", 200, 1, null, 3000);   // triggers eviction of mod-a (lowest lastSeen)

        List<String> tracked = m.snapshots().stream().map(ModuleMetricsSnapshot::moduleId).toList();
        assertThat(tracked).containsExactlyInAnyOrder("mod-b", "mod-c");
        assertThat(m.snapshot("mod-a")).isEmpty();
    }
}
