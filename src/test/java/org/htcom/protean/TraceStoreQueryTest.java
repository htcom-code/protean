/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.runtime.RequestTrace;
import org.htcom.protean.runtime.TraceQuery;
import org.htcom.protean.runtime.TraceStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link TraceStore} query filters (step B). Records synthetic traces directly so
 * status, latency, seq, and timestamps are deterministic (no real HTTP timing).
 */
class TraceStoreQueryTest {

    private TraceStore newStore() {
        return new TraceStore(new ProteanProperties());
    }

    /** seq is auto-assigned in insertion order: 1, 2, 3, ... */
    private TraceStore seeded() {
        TraceStore s = newStore();
        // seq 1: fast ok, module a
        s.record(1000, "GET", "/a", "/a", "mod-a", 200, 5, null, "t1");
        // seq 2: slow ok, module b
        s.record(2000, "GET", "/b", "/b", "mod-b", 200, 800, null, "t2");
        // seq 3: 404 unattributed
        s.record(3000, "GET", "/x", null, null, 404, 3, null, "t3");
        // seq 4: 500 error, module a
        s.record(4000, "POST", "/a", "/a", "mod-a", 500, 120, "java.lang.IllegalStateException", "t4");
        return s;
    }

    private List<Long> seqs(List<RequestTrace> traces) {
        return traces.stream().map(RequestTrace::seq).toList();
    }

    @Test
    void newest_first_and_limit() {
        List<RequestTrace> out = seeded().recent(new TraceQuery(2, null, false, null, null, null, null));
        assertThat(seqs(out)).containsExactly(4L, 3L);
    }

    @Test
    void module_filter() {
        List<RequestTrace> out = seeded().recent(new TraceQuery(50, "mod-a", false, null, null, null, null));
        assertThat(seqs(out)).containsExactly(4L, 1L);
    }

    @Test
    void errors_only_matches_5xx_or_thrown() {
        List<RequestTrace> out = seeded().recent(new TraceQuery(50, null, true, null, null, null, null));
        // seq 4 (500 + exception). 404 is not an error here; 200s are excluded.
        assertThat(seqs(out)).containsExactly(4L);
    }

    @Test
    void status_filter() {
        List<RequestTrace> out = seeded().recent(new TraceQuery(50, null, false, 404, null, null, null));
        assertThat(seqs(out)).containsExactly(3L);
    }

    @Test
    void min_latency_hunts_slow_requests() {
        List<RequestTrace> out = seeded().recent(new TraceQuery(50, null, false, null, 100L, null, null));
        // seq 2 (800ms) and seq 4 (120ms); fast ones (5ms, 3ms) excluded
        assertThat(seqs(out)).containsExactly(4L, 2L);
    }

    @Test
    void since_lower_bound() {
        List<RequestTrace> out = seeded().recent(new TraceQuery(50, null, false, null, null, 3000L, null));
        // epochMillis >= 3000 -> seq 3 and 4
        assertThat(seqs(out)).containsExactly(4L, 3L);
    }

    @Test
    void before_seq_cursor_pages_into_the_past() {
        List<RequestTrace> out = seeded().recent(new TraceQuery(50, null, false, null, null, null, 3L));
        // strictly older than seq 3 -> seq 2, 1
        assertThat(seqs(out)).containsExactly(2L, 1L);
    }

    @Test
    void filters_combine_with_and() {
        // module a AND errorsOnly -> only seq 4
        List<RequestTrace> out = seeded().recent(new TraceQuery(50, "mod-a", true, null, null, null, null));
        assertThat(seqs(out)).containsExactly(4L);
    }

    @Test
    void concurrent_records_do_not_disturb_queries() throws Exception {
        // Lock relief: recent() snapshots under the lock and filters outside it. Querying while several
        // threads record must never throw and must always return a consistent newest-first view.
        TraceStore s = newStore();
        int writers = 4;
        int perWriter = 2000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicBoolean failed = new AtomicBoolean(false);

        for (int w = 0; w < writers; w++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        s.record(1000 + i, "GET", "/c", "/c", "mod", 200, i, null, "t");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        Thread reader = new Thread(() -> {
            try {
                start.await();
                while (done.getCount() > 0) {
                    List<RequestTrace> page = s.recent(new TraceQuery(50, null, false, null, null, null, null));
                    // snapshot must be strictly newest-first by seq (no interleaving corruption)
                    for (int i = 1; i < page.size(); i++) {
                        if (page.get(i - 1).seq() <= page.get(i).seq()) {
                            failed.set(true);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                failed.set(true); // e.g. ConcurrentModificationException would fail the relief guarantee
            }
        });
        reader.setDaemon(true);
        reader.start();

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        reader.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(failed).isFalse();
        // capacity bound still holds after the storm
        assertThat(s.recent(new TraceQuery(10_000, null, false, null, null, null, null)))
                .hasSizeLessThanOrEqualTo(new ProteanProperties().getTrace().getCapacity());
    }

    @Test
    void after_returns_only_newer_entries_oldest_first() {
        // Forward tail cursor (for SSE streaming): unlike recent() (newest-first), after() is oldest-first.
        List<RequestTrace> out = seeded().after(2L);
        assertThat(seqs(out)).containsExactly(3L, 4L); // strictly seq > 2, ascending
    }

    @Test
    void after_zero_returns_everything_buffered_oldest_first() {
        assertThat(seqs(seeded().after(0L))).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void after_at_or_beyond_newest_seq_is_empty() {
        assertThat(seeded().after(4L)).isEmpty();
        assertThat(seeded().after(99L)).isEmpty();
    }

    @Test
    void clear_empties_buffer_but_seq_keeps_advancing() {
        TraceStore s = seeded();
        s.clear();
        assertThat(s.recent(50, null)).isEmpty();
        s.record(5000, "GET", "/c", "/c", "mod-c", 200, 1, null, "t5");
        // seq continues from 5 (not reset), proving monotonic ordering survives a clear
        assertThat(seqs(s.recent(50, null))).containsExactly(5L);
    }
}
