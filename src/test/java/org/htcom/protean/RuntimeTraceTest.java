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
import org.htcom.protean.proxy.ReverseProxy;
import org.htcom.protean.runtime.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Priority 2 check: runtime trace PoC.
 * Verifies that a dynamic module request's latency, status, and module attribution are recorded as a trace
 * and can be queried newest-first via GET /platform/traces.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RuntimeTraceTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-trace-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired TraceStore traceStore;
    @Autowired ReverseProxy proxy;

    @BeforeEach
    void resetTraces() {
        traceStore.clear();
    }

    static final String ID = "trace-mod";
    static final String FQCN = "runtime.tr.TrController";
    static final String TEST_FQCN = "runtime.tr.TrControllerTest";

    static ModuleDescriptor descriptor() {
        String src = """
                package runtime.tr;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class TrController {
                    @GetMapping("/trace/ping")
                    public String ping() { return "pong"; }
                }
                """;
        String test = """
                package runtime.tr;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class TrControllerTest {
                    @Test void ping() { assertEquals("pong", new TrController().ping()); }
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
    void records_module_requests_with_latency_and_attribution() throws Exception {
        platform.install(descriptor());

        // 2 module requests (200) + 1 unmatched (404)
        mockMvc.perform(get("/trace/ping")).andExpect(status().isOk());
        mockMvc.perform(get("/trace/ping")).andExpect(status().isOk());
        mockMvc.perform(get("/trace/missing")).andExpect(status().isNotFound());

        // all traces: exactly 3, newest-first (the most recent is /trace/missing 404), the trace query itself is not recorded
        mockMvc.perform(get("/platform/traces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].uri").value("/trace/missing"))
                .andExpect(jsonPath("$[0].status").value(404))
                // module requests are attributed to trace-mod + pattern/latency recorded
                .andExpect(jsonPath("$[?(@.uri=='/trace/ping')].moduleId").value(hasItem(ID)))
                .andExpect(jsonPath("$[?(@.uri=='/trace/ping')].pattern").value(hasItem("/trace/ping")))
                .andExpect(jsonPath("$[?(@.uri=='/trace/ping')].latencyMs").exists())
                // self-noise prevention: /platform/traces is not recorded
                .andExpect(jsonPath("$[?(@.uri=='/platform/traces')]").isEmpty());

        // module filter: only trace-mod's (2), excluding the 404 (no attribution)
        mockMvc.perform(get("/platform/traces").param("moduleId", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].moduleId").value(everyItem(is(ID))));
    }

    @Test
    void proxied_worker_container_route_is_attributed_to_its_module() throws Exception {
        // Worker/container modules register their route through ReverseProxy, not the in-process
        // registrar. Attribution must still resolve via the proxy's path->moduleId map. Register a
        // route to a dead port (no worker) so the forward yields 502, but the trace is still recorded.
        String proxiedPath = "/proxied/ping";
        String proxyModuleId = "proxy-mod";
        proxy.register(proxiedPath, 59999, proxyModuleId);
        try {
            mockMvc.perform(get(proxiedPath)).andExpect(status().isBadGateway());

            mockMvc.perform(get("/platform/traces").param("moduleId", proxyModuleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].uri").value(proxiedPath))
                    .andExpect(jsonPath("$[0].pattern").value(proxiedPath))
                    .andExpect(jsonPath("$[0].moduleId").value(proxyModuleId));
        } finally {
            proxy.unregister(proxiedPath);
        }
    }

    @Test
    void trace_carries_client_supplied_correlation_id() throws Exception {
        platform.install(descriptor());

        // client-supplied X-Request-Id is carried through into the trace record (log <-> trace <-> error correlation)
        String requestId = "test-correlation-123";
        mockMvc.perform(get("/trace/ping").header("X-Request-Id", requestId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", requestId));

        mockMvc.perform(get("/platform/traces").param("moduleId", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].traceId").value(requestId));
    }

    @Test
    void trace_gets_generated_correlation_id_when_client_supplies_none() throws Exception {
        platform.install(descriptor());

        // no X-Request-Id supplied: CorrelationIdFilter generates a UUID, which the trace still records
        mockMvc.perform(get("/trace/ping")).andExpect(status().isOk());

        mockMvc.perform(get("/platform/traces").param("moduleId", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].traceId").isNotEmpty());
    }
}
