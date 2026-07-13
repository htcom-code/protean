/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.isolation.WorkerProcessIsolation;
import org.htcom.protean.module.ModuleDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zero-downtime worker hot-swap: swapping a worker from v1 to v2 under concurrent load
 * must produce no dropped requests (non-200). The new worker is spawned and made ready
 * first, then only the proxy port is switched atomically, keeping routes intact (no 404/502).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerHotSwapTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-wswap-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
    }

    @LocalServerPort int port;
    @Autowired WorkerProcessIsolation isolation;

    static final String FQCN = "runtime.ws.WsController";

    static String ctrl(String version) {
        return """
                package runtime.ws;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class WsController {
                    @GetMapping("/ws/ping")
                    public String ping() { return "%s"; }
                }
                """.formatted(version);
    }

    static ModuleDescriptor descriptor(String version) {
        return ModuleDescriptor.builder()
                .id("ws-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, ctrl(version)))
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("ws-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void worker_hot_swap_is_zero_downtime_under_load() throws Exception {
        isolation.deploy(descriptor("v1"));

        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/ws/ping");

        int threads = 12;
        AtomicBoolean running = new AtomicBoolean(true);
        LongAdder total = new LongAdder();
        LongAdder ok = new LongAdder();
        Map<String, LongAdder> byBody = new ConcurrentHashMap<>();
        LongAdder errors = new LongAdder();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch started = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                started.countDown();
                while (running.get()) {
                    total.increment();
                    try {
                        HttpResponse<String> r = client.send(
                                HttpRequest.newBuilder(uri).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        if (r.statusCode() == 200) {
                            ok.increment();
                            byBody.computeIfAbsent(r.body(), k -> new LongAdder()).increment();
                        }
                    } catch (Exception e) {
                        errors.increment();
                    }
                }
            });
        }
        started.await();

        Thread.sleep(300);
        isolation.hotSwap(descriptor("v2"));   // prepare v2 worker, then switch atomically (blocking: v1 keeps serving during spawn)
        Thread.sleep(1500);                    // traffic on v2 after the switch

        running.set(false);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        long non200 = total.sum() - ok.sum();
        System.out.println("[worker-hotswap] total=" + total.sum() + " ok=" + ok.sum()
                + " errors=" + errors.sum() + " byBody={v1=" + count(byBody, "v1")
                + " v2=" + count(byBody, "v2") + "}");

        assertEquals(0, non200, "zero-downtime worker hot-swap must produce no dropped requests (non-200)");
        assertTrue(count(byBody, "v1") > 0 && count(byBody, "v2") > 0,
                "both v1 and v2 responses must be observed (proves the swap actually happened)");
    }

    private static long count(Map<String, LongAdder> m, String k) {
        LongAdder a = m.get(k);
        return a == null ? 0 : a.sum();
    }
}
