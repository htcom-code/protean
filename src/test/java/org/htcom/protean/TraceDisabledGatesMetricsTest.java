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
import org.htcom.protean.runtime.TraceMetrics;
import org.htcom.protean.runtime.TraceStore;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code protean.trace.enabled} gates the whole recording path in {@code RequestTraceFilter}, ahead of
 * per-module aggregation, so metrics accrue only while <b>both</b> it and {@code protean.trace.metrics.enabled}
 * are true. {@link TraceMetrics#enabled()} already folds the two together, and the SSE {@code ready} ack must
 * report <em>that</em> rather than the raw switch — otherwise the ack disagrees with the MCP metrics tool, which
 * answers the same question from the same accessor, and a client acting on the ack alone would promise rows that
 * can never arrive.
 *
 * <p>This runs in the configuration where the two readings diverge — the switch says on, the effective answer is
 * off — so anything that goes back to reading the raw switch fails here rather than silently shipping.
 */
@SpringBootTest(properties = {
        "protean.trace.enabled=false",           // the outer gate
        "protean.trace.metrics.enabled=true"     // says "on", but nothing can reach it
})
@AutoConfigureMockMvc
class TraceDisabledGatesMetricsTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-trace-gate-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired TraceMetrics metrics;
    @Autowired TraceStore store;
    @Autowired org.htcom.protean.autoconfigure.ProteanProperties props;

    static final String ID = "gate-mod";
    static final String FQCN = "runtime.gate.GateController";

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall(ID);
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void metrics_report_off_and_never_accrue_while_trace_recording_is_off() throws Exception {
        platform.install(descriptor());

        // The switch is on, but the effective answer is off — and it is the effective answer the platform gives.
        assertThat(props.getTrace().getMetrics().isEnabled()).isTrue();
        assertThat(metrics.enabled()).isFalse();

        mockMvc.perform(get("/gate/ping")).andExpect(status().isOk());
        mockMvc.perform(get("/gate/ping")).andExpect(status().isOk());

        // Requests are served, yet nothing is aggregated: the filter returns early on !trace.enabled, before the
        // aggregation call is reached. Reporting the switch here would tell a client to expect these rows.
        assertThat(metrics.snapshots()).isEmpty();
        assertThat(metrics.snapshot(ID)).isEmpty();
        // Same gate, so the ring is empty too — `buffered` in the ack is therefore 0, not a stale count.
        assertThat(store.recent(200, null)).isEmpty();
    }

    @Test
    void the_ready_ack_reports_the_effective_value_not_the_switch() throws Exception {
        String body = mockMvc.perform(get("/platform/traces/stream")
                        .header("Accept", "text/event-stream"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String ready = body.lines().dropWhile(l -> !l.startsWith("event:ready"))
                .filter(l -> l.startsWith("data:")).findFirst().orElseThrow();
        // Both false, although protean.trace.metrics.enabled is true: the ack answers "are metrics accruing",
        // which is the same question the MCP tool answers, not "is the switch set".
        assertThat(ready).contains("\"tracesEnabled\":false");
        assertThat(ready).contains("\"metricsEnabled\":false");
    }

    static ModuleDescriptor descriptor() {
        String src = """
                package runtime.gate;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class GateController {
                    @GetMapping("/gate/ping")
                    public String ping() { return "pong"; }
                }
                """;
        String test = """
                package runtime.gate;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class GateControllerTest {
                    @Test void ping() { assertEquals("pong", new GateController().ping()); }
                }
                """;
        return ModuleDescriptor.builder()
                .id(ID).version("1.0.0")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN))
                .sources(Map.of(FQCN, src)).tests(Map.of("runtime.gate.GateControllerTest", test))
                .build();
    }
}
