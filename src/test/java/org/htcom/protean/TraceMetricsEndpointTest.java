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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for per-module metrics (step A) with {@code protean.trace.metrics.enabled=true}:
 * real requests are aggregated and served by GET /platform/traces/metrics.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TraceMetricsEndpointTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-trace-metrics-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.trace.metrics.enabled", () -> "true");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;

    static final String ID = "metrics-mod";
    static final String FQCN = "runtime.mt.MtController";
    static final String TEST_FQCN = "runtime.mt.MtControllerTest";

    static ModuleDescriptor descriptor() {
        String src = """
                package runtime.mt;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class MtController {
                    @GetMapping("/metrics-mod/ping")
                    public String ping() { return "pong"; }
                }
                """;
        String test = """
                package runtime.mt;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class MtControllerTest {
                    @Test void ping() { assertEquals("pong", new MtController().ping()); }
                }
                """;
        return ModuleDescriptor.builder()
                .id(ID).version("1.0.0")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN))
                .sources(Map.of(FQCN, src)).tests(Map.of(TEST_FQCN, test))
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall(ID);
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void aggregates_real_requests_per_module() throws Exception {
        platform.install(descriptor());

        mockMvc.perform(get("/metrics-mod/ping")).andExpect(status().isOk());
        mockMvc.perform(get("/metrics-mod/ping")).andExpect(status().isOk());
        mockMvc.perform(get("/metrics-mod/ping")).andExpect(status().isOk());

        mockMvc.perform(get("/platform/traces/metrics").param("moduleId", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].moduleId").value(ID))
                .andExpect(jsonPath("$[0].count").value(3))
                .andExpect(jsonPath("$[0].errorCount").value(0))
                .andExpect(jsonPath("$[0].errorRate").value(0.0));
    }
}
