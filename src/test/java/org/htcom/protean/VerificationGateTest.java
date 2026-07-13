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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Promotion gate #3 — verification of the deployed live endpoint (integration/multi-request/latency/memory).
 * Requires a real server, hence RANDOM_PORT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VerificationGateTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-verify-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired ModulePlatform platform;
    @Autowired ModuleStore store;

    static final String CTRL = "runtime.verify.VerifyController";
    static final String TEST = "runtime.verify.VerifyControllerTest";

    static final String CTRL_SRC = """
            package runtime.verify;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class VerifyController {
                @GetMapping("/verify/ping")
                public String ping() { return "vok"; }
            }
            """;
    static final String TEST_SRC = """
            package runtime.verify;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class VerifyControllerTest {
                @Test void ping_ok() { assertEquals("vok", new VerifyController().ping()); }
            }
            """;

    static ModuleDescriptor descriptor(VerificationPlan plan) {
        return ModuleDescriptor.builder()
                .id("verify-mod").version("1.0.0")
                .controllerFqcn(CTRL).componentFqcns(List.of(CTRL))
                .sources(Map.of(CTRL, CTRL_SRC)).tests(Map.of(TEST, TEST_SRC))
                .verification(plan)
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall("verify-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void passing_verification_plan_promotes() {
        VerificationPlan plan = new VerificationPlan(
                List.of(new VerificationPlan.Probe("GET", "/verify/ping", 200, "vok")),
                "/verify/ping", 8, 20, 2000L, 200_000_000L);

        platform.install(descriptor(plan));  // success requires passing gates #1, #2, and #3
        assertTrue(store.load("verify-mod").isPresent(), "a module that passes verification must be stored");
    }

    @Test
    void failing_integration_probe_rolls_back() {
        // wrong expected status (expecting 200 for a nonexistent path that returns 404) -> gate #3 fails -> rollback
        VerificationPlan plan = new VerificationPlan(
                List.of(new VerificationPlan.Probe("GET", "/verify/does-not-exist", 200, null)),
                null, null, null, null, null);

        assertThrows(PromotionPipeline.GateFailedException.class,
                () -> platform.install(descriptor(plan)));

        // rollback: store removed + torn down
        assertFalse(store.load("verify-mod").isPresent(), "a module that fails gate #3 must be rolled back with no stored copy");
    }
}
