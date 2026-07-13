/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.runtime;

import java.util.concurrent.atomic.LongAdder;

/**
 * A zero-dependency, fixed-memory latency histogram with log-linear (power-of-two) buckets, used to
 * approximate p50/p95/p99 without sorting the samples (which would break the constant-memory guarantee).
 *
 * <p>Buckets (in ms): bucket 0 = {@code [0,1)}, bucket i (i&gt;=1) = {@code [2^(i-1), 2^i)}, and the last
 * bucket is the open-ended overflow {@code [2^(n-2), +inf)}. Recording is O(1) and lock-free (each bucket
 * is a {@link LongAdder}); a percentile read is O(buckets).
 *
 * <p>The returned percentile is the <b>upper</b> boundary of the bucket the target rank falls into — a
 * conservative estimate ("p95 is at most X ms"). Good enough for operational signals; if finer resolution
 * is needed, raise {@code protean.trace.metrics.latency-buckets}.
 */
final class LatencyHistogram {

    private final LongAdder[] buckets;

    LatencyHistogram(int bucketCount) {
        int n = Math.max(2, bucketCount);
        this.buckets = new LongAdder[n];
        for (int i = 0; i < n; i++) {
            buckets[i] = new LongAdder();
        }
    }

    /** Index of the bucket a latency falls into (O(1), no branches beyond the guards). */
    private int indexOf(long latencyMs) {
        if (latencyMs < 1) {
            return 0;
        }
        // floor(log2(latencyMs)) + 1 : latency 1 -> 1, 2..3 -> 2, 4..7 -> 3, ...
        int idx = Long.SIZE - Long.numberOfLeadingZeros(latencyMs);
        return Math.min(idx, buckets.length - 1);
    }

    /** Upper boundary (ms) of a bucket; the overflow bucket reports its lower boundary as a floor. */
    private long upperBoundOf(int idx) {
        if (idx == 0) {
            return 0L;
        }
        if (idx == buckets.length - 1) {
            return 1L << (idx - 1); // overflow bucket: report its lower bound (open-ended above)
        }
        return 1L << idx;
    }

    void record(long latencyMs) {
        buckets[indexOf(latencyMs)].increment();
    }

    /**
     * Approximate percentile latency in ms for {@code p} in (0,100]. Returns 0 if no samples.
     * The result is the upper boundary of the bucket containing the target rank.
     */
    long percentile(double p) {
        long total = 0;
        for (LongAdder b : buckets) {
            total += b.sum();
        }
        if (total == 0) {
            return 0L;
        }
        long target = (long) Math.ceil(p / 100.0 * total);
        long cumulative = 0;
        for (int i = 0; i < buckets.length; i++) {
            cumulative += buckets[i].sum();
            if (cumulative >= target) {
                return upperBoundOf(i);
            }
        }
        return upperBoundOf(buckets.length - 1);
    }
}
