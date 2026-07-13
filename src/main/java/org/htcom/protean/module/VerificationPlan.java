/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import java.util.List;

/**
 * Promotion gate 3 (verification) plan. Verifies against the deployed, live endpoints.
 * Each field is skipped when null (the module author declares only the items they need).
 *
 * @param integration       list of integration probes (HTTP checks)
 * @param loadPath          target path for the multi-request/throughput/memory load test
 * @param concurrency       number of concurrent threads (null = skip the multi-request/throughput/memory test)
 * @param requestsPerThread requests per thread
 * @param maxAvgLatencyMs   maximum average latency (ms, null = skip)
 * @param maxHeapGrowthBytes maximum heap growth across the load (bytes, null = skip; noisy, so a generous value is recommended)
 */
public record VerificationPlan(
        List<Probe> integration,
        String loadPath,
        Integer concurrency,
        Integer requestsPerThread,
        Long maxAvgLatencyMs,
        Long maxHeapGrowthBytes
) {
    /** A single HTTP integration check. */
    public record Probe(String method, String path, int expectedStatus, String bodyContains) {}
}
