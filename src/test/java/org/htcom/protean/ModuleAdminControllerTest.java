/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module lifecycle over the control-plane REST API.
 * Covers the full flow over HTTP: deploy (POST) -> serve -> status query (GET) -> canary update (PUT) ->
 * uninstall (DELETE), plus the gate rejection (422), id mismatch (400) and absence (404) mappings.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModuleAdminControllerTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-cp-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired ModulePlatform platform;

    static final String ID = "cp-mod";
    static final String FQCN = "runtime.cp.CpController";
    static final String TEST_FQCN = "runtime.cp.CpControllerTest";

    /** Descriptor bundling a controller + test with the given reply value (v1/v2). */
    static ModuleDescriptor descriptor(String version, String reply) {
        String src = """
                package runtime.cp;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class CpController {
                    @GetMapping("/cp/ping")
                    public String ping() { return "%s"; }
                }
                """.formatted(reply);
        String test = """
                package runtime.cp;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class CpControllerTest {
                    @Test void ping_reply() { assertEquals("%s", new CpController().ping()); }
                }
                """.formatted(reply);
        return ModuleDescriptor.builder()
                .id(ID).version(version)
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
    void full_lifecycle_deploy_serve_status_update_delete() throws Exception {
        // Before deploy: no endpoint + empty list
        mockMvc.perform(get("/cp/ping")).andExpect(status().isNotFound());
        mockMvc.perform(get("/platform/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + ID + "')]").isEmpty());

        // POST deploy -> 201 + Location + status body
        mockMvc.perform(post("/platform/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(descriptor("1.0.0", "v1"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/platform/modules/" + ID))
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.mode").value("in-process"))
                .andExpect(jsonPath("$.desiredState").value("ACTIVE"));

        // Verify serving (v1)
        mockMvc.perform(get("/cp/ping")).andExpect(status().isOk()).andExpect(content().string("v1"));

        // List / single-item status query
        mockMvc.perform(get("/platform/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + ID + "')]").exists());
        mockMvc.perform(get("/platform/modules/" + ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.0"));

        // PUT canary update -> 200, version bumped
        mockMvc.perform(put("/platform/modules/" + ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(descriptor("2.0.0", "v2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2.0.0"));

        // The update is reflected in real serving (v2)
        mockMvc.perform(get("/cp/ping")).andExpect(status().isOk()).andExpect(content().string("v2"));

        // DELETE uninstall -> 204, after which both the endpoint and the status disappear
        mockMvc.perform(delete("/platform/modules/" + ID)).andExpect(status().isNoContent());
        mockMvc.perform(get("/cp/ping")).andExpect(status().isNotFound());
        mockMvc.perform(get("/platform/modules/" + ID)).andExpect(status().isNotFound());
        // Idempotency: uninstalling an already-absent module is 404
        mockMvc.perform(delete("/platform/modules/" + ID)).andExpect(status().isNotFound());
    }

    @Test
    void deploy_without_tests_is_rejected_by_gate_422() throws Exception {
        // Violates gate 1 (test bundling required): empty tests -> 422
        ModuleDescriptor noTests = ModuleDescriptor.builder()
                .id(ID).version("1.0.0")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN))
                .sources(Map.of(FQCN, descriptor("1.0.0", "v1").sources().get(FQCN)))
                .build();

        mockMvc.perform(post("/platform/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(noTests)))
                .andExpect(status().isUnprocessableEntity())
                // RFC 9457 problem+json shape, replacing an ad-hoc {error}.
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:protean:error:gate-failed"))
                .andExpect(jsonPath("$.code").value("GATE_FAILED"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").exists())
                // Structured failed-gate identifier (gate) + correlation id (traceId).
                .andExpect(jsonPath("$.gate").value("tests"))
                .andExpect(jsonPath("$.traceId").exists());

        // Since it was rejected, there is neither serving nor a listing
        mockMvc.perform(get("/cp/ping")).andExpect(status().isNotFound());
        mockMvc.perform(get("/platform/modules/" + ID)).andExpect(status().isNotFound());
    }

    @Test
    void routes_reflects_live_in_process_registration_and_404_when_absent() throws Exception {
        // Absent module -> 404 (matches the frontend's "unavailable/pending" mapping)
        mockMvc.perform(get("/platform/modules/" + ID + "/routes")).andExpect(status().isNotFound());

        // Deploy in-process, then the endpoint surfaces the live mapping (method + pattern)
        mockMvc.perform(post("/platform/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(descriptor("1.0.0", "v1"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/platform/modules/" + ID + "/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].patterns[0]").value("/cp/ping"))
                .andExpect(jsonPath("$[0].methods[0]").value("GET"));
    }

    @Test
    void update_with_path_body_id_mismatch_is_400() throws Exception {
        mockMvc.perform(put("/platform/modules/other-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(descriptor("1.0.0", "v1"))))
                .andExpect(status().isBadRequest());
    }
}
