/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.gate.PromotionPipeline;
import org.htcom.protean.gate.rules.CodeRule;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.ModuleStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Promotion gate #2 — bytecode guardrails plus the rule system.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewGateTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-review-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    /** Optional-rule skeleton demo: a custom rule that forbids any class carrying the "PWNED" string constant. */
    @TestConfiguration
    static class CustomRuleConfig {
        @Bean
        CodeRule noPwnedConstantRule() {
            return new CodeRule() {
                public String name() { return "no-pwned-constant"; }
                public List<String> check(String className, byte[] bytecode) {
                    String s = new String(bytecode, java.nio.charset.StandardCharsets.ISO_8859_1);
                    return s.contains("PWNED") ? List.of(className + " forbidden constant PWNED") : List.of();
                }
            };
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired ModuleStore store;

    static final String CTRL = "runtime.review.ReviewController";
    static final String TEST = "runtime.review.ReviewControllerTest";

    static final String PASSING_TEST = """
            package runtime.review;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            public class ReviewControllerTest {
                @Test void instantiable() { assertNotNull(new ReviewController()); }
            }
            """;

    static ModuleDescriptor descriptor(String controllerSrc) {
        return ModuleDescriptor.builder()
                .id("review-mod").version("1.0.0")
                .controllerFqcn(CTRL).componentFqcns(List.of(CTRL))
                .sources(Map.of(CTRL, controllerSrc)).tests(Map.of(TEST, PASSING_TEST))
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall("review-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void clean_module_passes_review_gate() throws Exception {
        String clean = """
                package runtime.review;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class ReviewController {
                    @GetMapping("/review/ping")
                    public String ping() { return "ok"; }
                }
                """;
        platform.install(descriptor(clean));
        mockMvc.perform(get("/review/ping")).andExpect(status().isOk());
    }

    @Test
    void system_exit_is_blocked_by_guardrail() throws Exception {
        String dangerous = """
                package runtime.review;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class ReviewController {
                    @GetMapping("/review/ping")
                    public String ping() { System.exit(1); return "never"; }
                }
                """;
        assertThrows(PromotionPipeline.GateFailedException.class,
                () -> platform.install(descriptor(dangerous)));

        mockMvc.perform(get("/review/ping")).andExpect(status().isNotFound());
        assertFalse(store.load("review-mod").isPresent(), "a module that violates gate #2 must not be stored");
    }

    @Test
    void custom_rule_is_enforced() {
        // A module carrying the "PWNED" constant forbidden by the custom rule -> rejected
        String withMarker = """
                package runtime.review;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class ReviewController {
                    @GetMapping("/review/ping")
                    public String ping() { return "PWNED"; }
                }
                """;
        assertThrows(PromotionPipeline.GateFailedException.class,
                () -> platform.install(descriptor(withMarker)));
    }
}
