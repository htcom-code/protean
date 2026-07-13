/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.runtime;

/**
 * An immutable point-in-time view of one module's aggregated request metrics (see {@link TraceMetrics}).
 *
 * @param moduleId            module id, or {@code "(platform)"} for unattributed platform/static paths
 * @param count               total requests observed
 * @param errorCount          requests that failed (thrown exception or status &gt;= 500)
 * @param errorRate           {@code errorCount / count} (0.0 when count == 0)
 * @param p50LatencyMs        approximate median latency (ms)
 * @param p95LatencyMs        approximate 95th percentile latency (ms)
 * @param p99LatencyMs        approximate 99th percentile latency (ms)
 * @param maxLatencyMs        worst observed latency (ms)
 * @param lastSeenEpochMillis time of the most recent request (epoch ms)
 */
public record ModuleMetricsSnapshot(
        String moduleId,
        long count,
        long errorCount,
        double errorRate,
        long p50LatencyMs,
        long p95LatencyMs,
        long p99LatencyMs,
        long maxLatencyMs,
        long lastSeenEpochMillis
) {}
