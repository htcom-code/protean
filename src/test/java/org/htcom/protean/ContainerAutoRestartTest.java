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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Container-track supervision / auto-restart verification (Docker-gated, opt-in).
 * When a container is force-killed, the watcher detects the crash, redeploys a new container, and
 * repoints the proxy so that serving is restored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContainerAutoRestartTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.worker.container.auto-restart", () -> "true");
    }

    @LocalServerPort int port;
    @Autowired ContainerWorkerIsolation isolation;

    final HttpClient http = HttpClient.newHttpClient();

    static final String FQCN = "runtime.cr.CrController";

    static ModuleDescriptor descriptor() {
        String src = """
                package runtime.cr;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class CrController {
                    @GetMapping("/cr/ping")
                    public String ping() { return "alive"; }
                }
                """;
        return ModuleDescriptor.builder()
                .id("cr-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
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
            isolation.undeploy("cr-mod");
        } catch (RuntimeException ignored) {
        }
    }

    private int status(String path) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    @Test
    void crashed_container_is_auto_restarted() throws Exception {
        isolation.deploy(descriptor());
        assertTrue(status("/cr/ping") == 200, "initial serving");

        String name = isolation.containerName("cr-mod");
        assertNotNull(name);

        // Simulate a crash: force-kill the container (removed because of --rm) -> the watcher detects it
        new ProcessBuilder("docker", "kill", name).redirectErrorStream(true).start().waitFor();

        // Wait for the watcher to redeploy a new container + repoint -> serving restored (up to ~120s)
        boolean recovered = false;
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (status("/cr/ping") == 200 && !name.equals(isolation.containerName("cr-mod"))) {
                recovered = true;
                break;
            }
            Thread.sleep(500);
        }
        assertTrue(recovered, "a crashed container should auto-recover into a new container");
    }
}
