/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.gate;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.VerificationPlan;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Promotion gate ③ (verification) — runs integration/multi-request/latency/memory checks against the deployed live
 * endpoints. Throws {@link PromotionPipeline.GateFailedException} on failure. No-op when there is no plan.
 *
 * Note: the memory check assumes a lenient threshold due to JVM GC noise (it is not a precise measurement).
 */
@Component
public class VerificationGate {

    private final ServerPortHolder portHolder;
    private final HttpClient client;

    public VerificationGate(HttpClient client, ServerPortHolder portHolder) {
        this.client = client;
        this.portHolder = portHolder;
    }

    public void verify(ModuleDescriptor descriptor) {
        VerificationPlan plan = descriptor.verification();
        if (plan == null) {
            return;
        }
        int port = portHolder.port();
        if (port <= 0) {
            throw new PromotionPipeline.GateFailedException("verification",
                    "server port not resolved (verification needs a live server environment)");
        }
        String base = "http://localhost:" + port;

        runIntegration(client, base, plan);
        runLoad(client, base, plan);
    }

    private void runIntegration(HttpClient client, String base, VerificationPlan plan) {
        if (plan.integration() == null) {
            return;
        }
        for (VerificationPlan.Probe p : plan.integration()) {
            HttpResponse<String> resp = send(client, base, p.method(), p.path());
            if (resp.statusCode() != p.expectedStatus()) {
                throw new PromotionPipeline.GateFailedException("verification", "integration failed: " + p.method()
                        + " " + p.path() + " expected " + p.expectedStatus() + " actual " + resp.statusCode());
            }
            if (p.bodyContains() != null && !resp.body().contains(p.bodyContains())) {
                throw new PromotionPipeline.GateFailedException("verification", "integration failed: " + p.path()
                        + " body does not contain '" + p.bodyContains() + "'");
            }
        }
    }

    private void runLoad(HttpClient client, String base, VerificationPlan plan) {
        if (plan.concurrency() == null || plan.loadPath() == null) {
            return;
        }
        int threads = plan.concurrency();
        int perThread = plan.requestsPerThread() != null ? plan.requestsPerThread() : 10;
        URI uri = URI.create(base + plan.loadPath());

        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heapBefore = rt.totalMemory() - rt.freeMemory();

        LongAdder errors = new LongAdder();
        LongAdder totalLatencyMs = new LongAdder();
        LongAdder count = new LongAdder();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        long start = System.nanoTime();
                        try {
                            HttpResponse<String> r = client.send(
                                    HttpRequest.newBuilder(uri).GET().build(),
                                    HttpResponse.BodyHandlers.ofString());
                            if (r.statusCode() / 100 != 2) {
                                errors.increment();
                            }
                        } catch (Exception e) {
                            errors.increment();
                        }
                        totalLatencyMs.add((System.nanoTime() - start) / 1_000_000);
                        count.increment();
                    }
                });
            }
            pool.shutdown();
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                throw new PromotionPipeline.GateFailedException("verification", "multi-request load: timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PromotionPipeline.GateFailedException("verification", "multi-request load: interrupted");
        }

        // multi-request: no errors
        if (errors.sum() > 0) {
            throw new PromotionPipeline.GateFailedException("verification",
                    "multi-request load failed: non-2xx responses " + errors.sum() + "/" + count.sum());
        }
        // latency
        if (plan.maxAvgLatencyMs() != null) {
            long avg = count.sum() == 0 ? 0 : totalLatencyMs.sum() / count.sum();
            if (avg > plan.maxAvgLatencyMs()) {
                throw new PromotionPipeline.GateFailedException("verification", "latency failed: avg " + avg
                        + "ms > " + plan.maxAvgLatencyMs() + "ms");
            }
        }
        // memory (lenient threshold)
        if (plan.maxHeapGrowthBytes() != null) {
            System.gc();
            long heapAfter = rt.totalMemory() - rt.freeMemory();
            long growth = heapAfter - heapBefore;
            if (growth > plan.maxHeapGrowthBytes()) {
                throw new PromotionPipeline.GateFailedException("verification", "memory failed: heap growth "
                        + growth + "B > " + plan.maxHeapGrowthBytes() + "B");
            }
        }
    }

    private HttpResponse<String> send(HttpClient client, String base, String method, String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                    .method(method == null ? "GET" : method, HttpRequest.BodyPublishers.noBody())
                    .build();
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new PromotionPipeline.GateFailedException("verification",
                    "request failed: " + path + " - " + e.getMessage());
        }
    }
}
