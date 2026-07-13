/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.runtime;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-module aggregated request metrics (opt-in via {@code protean.trace.metrics.enabled}).
 *
 * <p>The recording hot path ({@link #observe}) is lock-free for the common case (an already-tracked
 * module): a {@link ConcurrentHashMap} lookup plus {@link LongAdder}/CAS updates, with no shared lock and
 * separate from the {@link TraceStore} ring-buffer lock. A small synchronized section is taken only when a
 * <b>new</b> module is first seen (to create/evict under the {@code max-modules} bound), which is rare.
 *
 * <p>Metrics for an undeployed module are kept (for post-mortem analysis) and bounded by evicting the
 * least-recently-seen module once {@code max-modules} is reached.
 */
@Component
public class TraceMetrics {

    /** Bucket key for requests not attributed to any dynamic module (platform/static paths). */
    public static final String PLATFORM_KEY = "(platform)";

    private static final Logger log = LoggerFactory.getLogger(TraceMetrics.class);

    private final ProteanProperties props;
    private final ConcurrentHashMap<String, ModuleMetrics> byModule = new ConcurrentHashMap<>();
    private final Object creationLock = new Object();

    public TraceMetrics(ProteanProperties props) {
        this.props = props;
    }

    /** Read live so protean.trace(.metrics).enabled toggles take effect immediately (Tier 1). */
    public boolean enabled() {
        return props.getTrace().isEnabled() && props.getTrace().getMetrics().isEnabled();
    }

    /** Histogram bucket count, read live at module-first-seen (Tier 2: applies to modules tracked afterward). */
    private int latencyBuckets() {
        return Math.max(2, props.getTrace().getMetrics().getLatencyBuckets());
    }

    /** Max tracked modules, read live (Tier 2: the eviction bound applies from the next new module onward). */
    private int maxModules() {
        return Math.max(1, props.getTrace().getMetrics().getMaxModules());
    }

    /** Fold one request into its module's aggregates. No-op when metrics are disabled. */
    public void observe(String moduleId, int status, long latencyMs, String error, long epochMillis) {
        if (!enabled()) {
            return;
        }
        String key = moduleId == null ? PLATFORM_KEY : moduleId;
        ModuleMetrics m = byModule.get(key);
        if (m == null) {
            m = createTracked(key);
        }
        m.observe(status, latencyMs, error, epochMillis);
    }

    /** Create a metrics holder for a newly-seen module, evicting the least-recently-seen if at capacity. */
    private ModuleMetrics createTracked(String key) {
        synchronized (creationLock) {
            ModuleMetrics existing = byModule.get(key);
            if (existing != null) {
                return existing;
            }
            if (byModule.size() >= maxModules()) {
                evictOldestLocked();
            }
            ModuleMetrics created = new ModuleMetrics(latencyBuckets());
            byModule.put(key, created);
            return created;
        }
    }

    private void evictOldestLocked() {
        String oldestKey = null;
        long oldestSeen = Long.MAX_VALUE;
        for (Map.Entry<String, ModuleMetrics> e : byModule.entrySet()) {
            long seen = e.getValue().lastSeenEpochMillis();
            if (seen < oldestSeen) {
                oldestSeen = seen;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            byModule.remove(oldestKey);
            log.warn("trace metrics at capacity ({}); evicted least-recently-seen module '{}'",
                    maxModules(), oldestKey);
        }
    }

    /** Snapshot of one module's metrics, or empty if untracked/disabled. */
    public Optional<ModuleMetricsSnapshot> snapshot(String moduleId) {
        String key = moduleId == null ? PLATFORM_KEY : moduleId;
        ModuleMetrics m = byModule.get(key);
        return m == null ? Optional.empty() : Optional.of(m.snapshot(key));
    }

    /** Snapshots of every tracked module (unordered). Empty when disabled or nothing observed yet. */
    public List<ModuleMetricsSnapshot> snapshots() {
        List<ModuleMetricsSnapshot> out = new ArrayList<>();
        for (Map.Entry<String, ModuleMetrics> e : byModule.entrySet()) {
            out.add(e.getValue().snapshot(e.getKey()));
        }
        return out;
    }

    /** Discard all aggregates (used by tests; also a legitimate operational reset). */
    public void clear() {
        byModule.clear();
    }

    /** Mutable per-module aggregate. All updates are lock-free (LongAdder/CAS/volatile). */
    private static final class ModuleMetrics {
        private final LongAdder count = new LongAdder();
        private final LongAdder errorCount = new LongAdder();
        private final AtomicLong maxLatencyMs = new AtomicLong();
        private final LatencyHistogram latency;
        private volatile long lastSeenEpochMillis;

        ModuleMetrics(int latencyBuckets) {
            this.latency = new LatencyHistogram(latencyBuckets);
        }

        void observe(int status, long latencyMs, String error, long epochMillis) {
            count.increment();
            if (error != null || status >= 500) {
                errorCount.increment();
            }
            latency.record(latencyMs);
            long prev;
            while (latencyMs > (prev = maxLatencyMs.get()) && !maxLatencyMs.compareAndSet(prev, latencyMs)) {
                // retry until our value loses the race or wins the CAS
            }
            lastSeenEpochMillis = epochMillis;
        }

        long lastSeenEpochMillis() {
            return lastSeenEpochMillis;
        }

        ModuleMetricsSnapshot snapshot(String moduleId) {
            long c = count.sum();
            long errors = errorCount.sum();
            double rate = c == 0 ? 0.0 : (double) errors / c;
            return new ModuleMetricsSnapshot(moduleId, c, errors, rate,
                    latency.percentile(50), latency.percentile(95), latency.percentile(99),
                    maxLatencyMs.get(), lastSeenEpochMillis);
        }
    }
}
