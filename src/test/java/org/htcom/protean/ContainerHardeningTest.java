/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.isolation.ContainerWorkerIsolation;
import org.htcom.protean.module.ModuleDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OS-isolation container-track production hardening verification (Docker-gated):
 * security options (no-new-privileges, pids-limit, memory) + atomic zero-downtime hot-swap
 * (zero non-200 responses during the swap).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContainerHardeningTest {

    @LocalServerPort int port;
    @Autowired ContainerWorkerIsolation isolation;

    final HttpClient http = HttpClient.newHttpClient();

    static final String FQCN = "runtime.cs.CsController";

    static ModuleDescriptor descriptor(String reply) {
        String src = """
                package runtime.cs;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class CsController {
                    @GetMapping("/cs/ping")
                    public String ping() { return "%s"; }
                }
                """.formatted(reply);
        return ModuleDescriptor.builder()
                .id("cs-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, src))
                .isolationMode("container")
                .build();
    }

    @BeforeEach
    void preconditions() {
        assumeTrue(OsIsolationTest.dockerAvailable(), "no Docker daemon — skip");
        assumeTrue(OsIsolationTest.bootJarExists(), "no bootJar ('gradle bootJar') — skip");
    }

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("cs-mod");
        } catch (RuntimeException ignored) {
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void applies_security_hardening_flags() throws Exception {
        isolation.deploy(descriptor("v1"));

        assertEquals("v1", get("/cs/ping").body());
        assertTrue(isolation.inspectSecurityOpt("cs-mod").contains("no-new-privileges"),
                "no-new-privileges should be applied: " + isolation.inspectSecurityOpt("cs-mod"));
        assertEquals(1024L, isolation.inspectPidsLimit("cs-mod"), "pids-limit (fork-bomb defense) applied");
        assertEquals(512L * 1024 * 1024, isolation.inspectMemoryLimit("cs-mod"), "cgroup memory limit applied");
    }

    @Test
    void hot_swap_is_zero_downtime() throws Exception {
        isolation.deploy(descriptor("v1"));
        assertEquals("v1", get("/cs/ping").body());

        List<Integer> nonOk = Collections.synchronizedList(new java.util.ArrayList<>());
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread hammer = new Thread(() -> {
            while (!stop.get()) {
                try {
                    int sc = get("/cs/ping").statusCode();
                    if (sc != 200) {
                        nonOk.add(sc);
                    }
                } catch (Exception e) {
                    nonOk.add(-1);
                }
            }
        }, "hammer");
        hammer.start();
        Thread.sleep(500);              // confirm v1 is served during warm-up

        isolation.hotSwap(descriptor("v2"));   // atomic repoint after the new container is fully up

        Thread.sleep(500);
        stop.set(true);
        hammer.join();

        assertTrue(nonOk.isEmpty(), "should be zero-downtime — observed non-200: " + nonOk);
        assertEquals("v2", get("/cs/ping").body(), "new version served after swap");
    }
}
