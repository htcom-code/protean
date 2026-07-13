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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worker supervision / auto-restart: worker crash -> automatic restart -> endpoint recovers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerSupervisionTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-sup-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.auto-restart", () -> "true");
    }

    @LocalServerPort int port;
    @Autowired WorkerProcessIsolation isolation;

    static final String FQCN = "runtime.sup.SupController";
    static final String SRC = """
            package runtime.sup;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class SupController {
                @GetMapping("/sup/ping")
                public String ping() { return "alive"; }
            }
            """;

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("sup-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void worker_crash_auto_restarts_and_recovers() throws Exception {
        isolation.deploy(ModuleDescriptor.builder()
                .id("sup-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .build());

        HttpClient client = HttpClient.newHttpClient();
        assertEquals(200, get(client, "/sup/ping").statusCode());

        // worker crash
        isolation.simulateCrash("sup-mod");

        // the supervisor spawns a new worker and redeploys -> poll until recovered (up to ~20s)
        int status = -1;
        String body = null;
        for (int i = 0; i < 100; i++) {
            HttpResponse<String> r = get(client, "/sup/ping");
            status = r.statusCode();
            body = r.body();
            if (status == 200) {
                break;
            }
            Thread.sleep(200);
        }

        assertEquals(200, status, "must recover via automatic restart after a worker crash");
        assertEquals("alive", body);
        assertTrue(isolation.workerCount() >= 1, "a worker must be alive after recovery");
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
