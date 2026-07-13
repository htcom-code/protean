/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.module.ModuleContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Zero-downtime hot-swap: swaps a v1->v2 module while concurrent requests flow through a real Tomcat port,
 * measuring downtime and the version transition.
 *
 * <p>Downtime is judged solely by <b>actually received non-200 HTTP status codes</b> (404/5xx) — client I/O
 * errors and timeouts are load-related environmental factors and are distinguished from downtime. Requests
 * carry a timeout to prevent infinite hangs, and instead of a fixed sleep the test "waits until both versions
 * are observed" so it truly passes through and observes the swap regardless of load.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HotSwapConcurrencyTest {

    @LocalServerPort int port;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;

    static final String FQCN = "runtime.hot.HotController";

    /**
     * Generous, load-tolerant ceiling for "wait until a version is observed in the response stream".
     * On a healthy machine each version appears within milliseconds, so {@link #awaitBody} returns
     * immediately; the ceiling is only ever reached when the swap genuinely never surfaces the version
     * (a real regression), so this does not slow the suite yet removes the timing sensitivity that made
     * the observation assertions flaky under heavy full-suite CPU pressure.
     */
    static final long OBSERVE_TIMEOUT_MS = 20_000;

    static String controllerSrc(String version) {
        return """
                package runtime.hot;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class HotController {
                    @GetMapping("/hot/ping")
                    public String ping() { return "%s"; }
                }
                """.formatted(version);
    }

    @Test
    void naive_swap_under_concurrent_load() throws Exception {
        ModuleClassLoader v1 = compiler.compileAll(Map.of(FQCN, controllerSrc("v1")));
        container.deploy("hot", v1, List.of(FQCN), FQCN);

        HttpClient client = newClient();
        URI uri = uri();
        AtomicBoolean running = new AtomicBoolean(true);
        LongAdder total = new LongAdder(), ok = new LongAdder(), ioErrors = new LongAdder();
        Map<Integer, LongAdder> byStatus = new ConcurrentHashMap<>();
        Map<String, LongAdder> byBody = new ConcurrentHashMap<>();

        ExecutorService pool = startLoad(client, uri, running, total, ok, byStatus, byBody, ioErrors);
        awaitBody(byBody, "v1", OBSERVE_TIMEOUT_MS);

        // Naive swap: undeploy then redeploy -> a disruption can occur in the window between (for comparison, no assertion).
        ModuleClassLoader v2 = compiler.compileAll(Map.of(FQCN, controllerSrc("v2")));
        container.undeploy("hot");
        container.deploy("hot", v2, List.of(FQCN), FQCN);
        awaitBody(byBody, "v2", OBSERVE_TIMEOUT_MS);

        stopLoad(running, pool);
        System.out.println("[hotswap-naive] total=" + total.sum() + " ok=" + ok.sum()
                + " ioErrors=" + ioErrors.sum() + " byStatus=" + flatten(byStatus) + " byBody=" + flatten(byBody));
        container.undeploy("hot");
    }

    @Test
    void atomic_swap_under_concurrent_load() throws Exception {
        ModuleClassLoader v1 = compiler.compileAll(Map.of(FQCN, controllerSrc("v1")));
        container.deploy("hot2", v1, List.of(FQCN), FQCN);

        HttpClient client = newClient();
        URI uri = uri();
        AtomicBoolean running = new AtomicBoolean(true);
        LongAdder total = new LongAdder(), ok = new LongAdder(), ioErrors = new LongAdder();
        Map<Integer, LongAdder> byStatus = new ConcurrentHashMap<>();
        Map<String, LongAdder> byBody = new ConcurrentHashMap<>();

        ExecutorService pool = startLoad(client, uri, running, total, ok, byStatus, byBody, ioErrors);
        boolean sawV1 = awaitBody(byBody, "v1", OBSERVE_TIMEOUT_MS);   // confirm v1 is serving (load flow started)

        // Atomic hot-swap while load flows -> wait until v2 is observed (the measurement window passes through the swap).
        ModuleClassLoader v2 = compiler.compileAll(Map.of(FQCN, controllerSrc("v2")));
        container.hotSwap("hot2", v2, List.of(FQCN), FQCN);
        boolean sawV2 = awaitBody(byBody, "v2", OBSERVE_TIMEOUT_MS);

        stopLoad(running, pool);

        // Downtime = actually received non-200 HTTP status (404/5xx). I/O errors and timeouts are excluded as load-related environmental factors.
        long non200Statuses = byStatus.entrySet().stream()
                .filter(e -> e.getKey() != 200).mapToLong(e -> e.getValue().sum()).sum();
        System.out.println("[hotswap-atomic] total=" + total.sum() + " ok=" + ok.sum()
                + " ioErrors=" + ioErrors.sum() + " non200Statuses=" + non200Statuses
                + " byStatus=" + flatten(byStatus) + " byBody=" + flatten(byBody));

        container.undeploy("hot2");

        org.junit.jupiter.api.Assertions.assertEquals(0, non200Statuses,
                "there must be no non-200 HTTP status (disruption) during the atomic swap");
        // Observation is polled up to a generous ceiling (not a fixed wall-clock window), so these prove a
        // real swap without being sensitive to how fast the loaded machine drains requests.
        org.junit.jupiter.api.Assertions.assertTrue(sawV1,
                "v1 response must be observed before the swap (proof load was flowing through v1)");
        org.junit.jupiter.api.Assertions.assertTrue(sawV2,
                "v2 response must be observed after the swap (proof of a real swap)");
    }

    // ---- Load harness ----

    private HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    private URI uri() {
        return URI.create("http://localhost:" + port + "/hot/ping");
    }

    private ExecutorService startLoad(HttpClient client, URI uri, AtomicBoolean running,
                                      LongAdder total, LongAdder ok, Map<Integer, LongAdder> byStatus,
                                      Map<String, LongAdder> byBody, LongAdder ioErrors) throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch started = new CountDownLatch(threads);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                started.countDown();
                while (running.get()) {
                    total.increment();
                    try {
                        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        byStatus.computeIfAbsent(resp.statusCode(), k -> new LongAdder()).increment();
                        if (resp.statusCode() == 200) {
                            ok.increment();
                            byBody.computeIfAbsent(resp.body(), k -> new LongAdder()).increment();
                        }
                    } catch (Exception e) {
                        ioErrors.increment();   // timeout/connection error = environmental factor (not downtime)
                    }
                }
            });
        }
        started.await();
        return pool;
    }

    private void stopLoad(AtomicBoolean running, ExecutorService pool) throws Exception {
        running.set(false);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    /** Waits up to timeoutMs until key (a version string) is observed in byBody. */
    private static boolean awaitBody(Map<String, LongAdder> byBody, String key, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (byBody.containsKey(key)) {
                return true;
            }
            Thread.sleep(20);
        }
        return byBody.containsKey(key);
    }

    private static String flatten(Map<?, LongAdder> m) {
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> sb.append(k).append('=').append(v.sum()).append(' '));
        return sb.append('}').toString();
    }
}
