/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.runtime;

/**
 * Filter criteria for querying buffered request traces (see {@link TraceStore#recent(TraceQuery)}).
 * All fields except {@code limit} are optional; {@code null} means "no constraint on this field".
 * Every set constraint is combined with AND. Results are always newest-first.
 *
 * @param limit        maximum number of entries to return (>= 1)
 * @param moduleId     only traces attributed to this dynamic module (null = any, including unattributed)
 * @param errorsOnly   when true, only traces that failed ({@code error != null} or {@code status >= 500})
 * @param status       only traces with this exact response status code (null = any)
 * @param minLatencyMs only traces whose latency is at least this many ms — for slow-request hunting (null = any)
 * @param since        only traces recorded at/after this epoch-millis lower bound (null = any)
 * @param beforeSeq    only traces older than this seq — cursor for paging into the past (null = newest page)
 */
public record TraceQuery(
        int limit,
        String moduleId,
        boolean errorsOnly,
        Integer status,
        Long minLatencyMs,
        Long since,
        Long beforeSeq
) {
    /** True if the given trace satisfies every set constraint (limit is applied by the caller). */
    boolean matches(RequestTrace t) {
        if (moduleId != null && !moduleId.equals(t.moduleId())) {
            return false;
        }
        if (errorsOnly && t.error() == null && t.status() < 500) {
            return false;
        }
        if (status != null && t.status() != status) {
            return false;
        }
        if (minLatencyMs != null && t.latencyMs() < minLatencyMs) {
            return false;
        }
        if (since != null && t.epochMillis() < since) {
            return false;
        }
        if (beforeSeq != null && t.seq() >= beforeSeq) {
            return false;
        }
        return true;
    }
}
