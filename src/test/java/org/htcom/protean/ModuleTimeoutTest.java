/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Module execution timeout verification (cooperative watchdog).
 * A blocking (sleep) handler is cut off by the timeout and returns 503; a fast handler returns 200.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModuleTimeoutTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-timeout-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.module.request-timeout-ms", () -> "300");  // enable timeout
    }

    @LocalServerPort int port;
    @Autowired ModulePlatform platform;

    static final String CTRL = "runtime.to.TimeoutController";
    static final String TEST = "runtime.to.TimeoutControllerTest";

    static final String CTRL_SRC = """
            package runtime.to;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class TimeoutController {
                @GetMapping("/to/fast")
                public String fast() { return "fast"; }
                @GetMapping("/to/slow")
                public String slow() throws InterruptedException { Thread.sleep(5000); return "slow"; }
            }
            """;
    static final String TEST_SRC = """
            package runtime.to;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class TimeoutControllerTest {
                @Test void fast_ok() { assertEquals("fast", new TimeoutController().fast()); }
            }
            """;

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall("to-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void slow_handler_times_out_fast_handler_ok() throws Exception {
        platform.install(ModuleDescriptor.builder()
                .id("to-mod").version("1.0.0")
                .controllerFqcn(CTRL).componentFqcns(List.of(CTRL))
                .sources(Map.of(CTRL, CTRL_SRC)).tests(Map.of(TEST, TEST_SRC))
                .build());

        HttpClient client = HttpClient.newHttpClient();

        // fast handler: OK
        assertEquals(200, get(client, "/to/fast").statusCode());

        // slow handler (5s sleep): interrupted at the 300ms timeout -> 503, and returns far sooner than 5s
        long start = System.nanoTime();
        HttpResponse<String> slow = get(client, "/to/slow");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(503, slow.statusCode(), "a timed-out module request must return 503");
        assertTrue(elapsedMs < 3000, "the timeout must terminate the 5s sleep early (actual " + elapsedMs + "ms)");
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
