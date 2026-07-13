/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.gate.PromotionPipeline;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.ModuleStore;
import org.htcom.protean.module.VerificationPlan;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Canary verification: staged update + automatic rollback.
 * An update that passes verification switches to the new version; one that fails verification rolls
 * back to the previous version.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CanaryUpdateTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-canary-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @LocalServerPort int port;
    @Autowired ModulePlatform platform;
    @Autowired ModuleStore store;

    static final String CTRL = "runtime.canary.CanaryController";
    static final String TEST = "runtime.canary.CanaryControllerTest";

    static String ctrl(String version) {
        return """
                package runtime.canary;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class CanaryController {
                    @GetMapping("/canary/ping")
                    public String ping() { return "%s"; }
                }
                """.formatted(version);
    }

    static final String TEST_SRC = """
            package runtime.canary;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            public class CanaryControllerTest {
                @Test void instantiable() { assertNotNull(new CanaryController()); }
            }
            """;

    static ModuleDescriptor descriptor(String version, VerificationPlan plan) {
        return ModuleDescriptor.builder()
                .id("canary-mod").version("1.0.0")
                .controllerFqcn(CTRL).componentFqcns(List.of(CTRL))
                .sources(Map.of(CTRL, ctrl(version))).tests(Map.of(TEST, TEST_SRC))
                .verification(plan)
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall("canary-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void passing_update_switches_failing_update_rolls_back() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // install v1
        platform.install(descriptor("v1", null));
        assertEquals("v1", body(client));

        // update to v2 — verification passes (correct probe) -> switch
        VerificationPlan passing = new VerificationPlan(
                List.of(new VerificationPlan.Probe("GET", "/canary/ping", 200, "v2")),
                null, null, null, null, null);
        platform.update(descriptor("v2", passing));
        assertEquals("v2", body(client));

        // update to v3 — verification fails (expects 200 on a nonexistent path) -> auto rollback -> still v2
        VerificationPlan failing = new VerificationPlan(
                List.of(new VerificationPlan.Probe("GET", "/canary/does-not-exist", 200, null)),
                null, null, null, null, null);
        assertThrows(PromotionPipeline.GateFailedException.class,
                () -> platform.update(descriptor("v3", failing)));

        assertEquals("v2", body(client), "an update that fails verification should roll back to the previous version (v2)");
        assertEquals("v2", store.load("canary-mod").orElseThrow().sources().get(CTRL).contains("\"v2\"") ? "v2" : "?",
                "the store also keeps v2 (the failed version is not persisted)");
    }

    private String body(HttpClient client) throws Exception {
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/canary/ping")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return r.body();
    }
}
