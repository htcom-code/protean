/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Control-plane REST for runtime config: list, single get, and atomic patch semantics over HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
class ConfigAdminControllerTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir",
                () -> Path.of(System.getProperty("java.io.tmpdir"), "protean-config-rest-test").toString());
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProteanProperties props;

    @Test
    void listReturnsEntries() throws Exception {
        mockMvc.perform(get("/platform/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").exists())
                .andExpect(jsonPath("$[0].tier").exists());
    }

    @Test
    void getSingleKey() throws Exception {
        mockMvc.perform(get("/platform/config/trace.capacity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("trace.capacity"))
                .andExpect(jsonPath("$.tier").value("LIVE"));
    }

    @Test
    void getUnknownKeyIs400() throws Exception {
        mockMvc.perform(get("/platform/config/no.such.key"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchAppliesLiveKey() throws Exception {
        mockMvc.perform(patch("/platform/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trace.capacity\": 42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.outcomes[0].outcome").value("APPLIED_LIVE"));
        assertEquals(42, props.getTrace().getCapacity());
    }

    @Test
    void patchInvalidBatchIs400AndAppliesNothing() throws Exception {
        int before = props.getTrace().getCapacity();
        mockMvc.perform(patch("/platform/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trace.capacity\": " + (before + 7) + ", \"bogus\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.applied").value(false));
        assertEquals(before, props.getTrace().getCapacity());
    }

    // The trace-summary window and worker shutdown-grace knobs are registered LIVE, so they are exposed on the config
    // plane (REST here; MCP shares the same ProteanConfigService) and mutable at runtime — not boot-only.
    @Test
    void summaryWindowMsIsLiveConfigurable() throws Exception {
        mockMvc.perform(get("/platform/config/trace.summary-window-ms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("LIVE"));
        mockMvc.perform(patch("/platform/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trace.summary-window-ms\": 30000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].outcome").value("APPLIED_LIVE"));
        assertEquals(30000L, props.getTrace().getSummaryWindowMs());
    }

    @Test
    void shutdownGraceMsIsLiveConfigurable() throws Exception {
        mockMvc.perform(get("/platform/config/worker.shutdown-grace-ms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("LIVE"));
        mockMvc.perform(patch("/platform/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worker.shutdown-grace-ms\": 2000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].outcome").value("APPLIED_LIVE"));
        assertEquals(2000L, props.getWorker().getShutdownGraceMs());
    }

    @Test
    void shutdownGraceMsRejectsNegativeAtThePlane() throws Exception {
        long before = props.getWorker().getShutdownGraceMs();
        mockMvc.perform(patch("/platform/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worker.shutdown-grace-ms\": -1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.applied").value(false));
        assertEquals(before, props.getWorker().getShutdownGraceMs());
    }
}
