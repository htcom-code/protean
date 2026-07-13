/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.runtime;

/**
 * A single execution trace for one request (runtime trace PoC).
 *
 * @param seq         monotonically increasing sequence (for ordering/paging)
 * @param epochMillis request completion time (epoch ms)
 * @param method      HTTP method
 * @param uri         request URI
 * @param pattern     matched handler pattern (e.g. "/foo/{id}", null if unmatched)
 * @param moduleId    id of the dynamic module that registered the pattern (null if unattributed = platform/static path)
 * @param status      response status code
 * @param latencyMs   elapsed time from filter entry to response (ms)
 * @param error       FQCN of the exception class thrown by the handler (null if none)
 * @param traceId     correlation id ({@code X-Request-Id} / MDC {@code traceId}) shared with the log
 *                    lines and the RFC 9457 error envelope for the same request (null if unavailable)
 */
public record RequestTrace(
        long seq,
        long epochMillis,
        String method,
        String uri,
        String pattern,
        String moduleId,
        int status,
        long latencyMs,
        String error,
        String traceId
) {}
