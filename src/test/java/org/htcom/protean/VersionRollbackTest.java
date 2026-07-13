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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Advanced check: version history + explicit rollback.
 * Queries the history built up by install->update via GET /versions, then uses POST /rollback?version= to
 * restore a prior version on the canary path and confirms the actual serving changes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VersionRollbackTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-version-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired ModulePlatform platform;

    static final String ID = "ver-mod";
    static final String FQCN = "runtime.ver.VerController";
    static final String TEST_FQCN = "runtime.ver.VerControllerTest";

    static ModuleDescriptor descriptor(String version, String reply) {
        String src = """
                package runtime.ver;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class VerController {
                    @GetMapping("/ver/ping")
                    public String ping() { return "%s"; }
                }
                """.formatted(reply);
        String test = """
                package runtime.ver;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class VerControllerTest {
                    @Test void ping() { assertEquals("%s", new VerController().ping()); }
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
    void history_accumulates_and_explicit_rollback_restores_prior_version() throws Exception {
        // deploy v1 -> update to v2
        platform.install(descriptor("1.0.0", "v1"));
        mockMvc.perform(get("/ver/ping")).andExpect(content().string("v1"));
        mockMvc.perform(put("/platform/modules/" + ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(descriptor("2.0.0", "v2"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/ver/ping")).andExpect(content().string("v2"));

        // history: 2 entries, newest-first (2.0.0 first)
        mockMvc.perform(get("/platform/modules/" + ID + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value("2.0.0"))
                .andExpect(jsonPath("$[1].version").value("1.0.0"));

        // explicit rollback -> 1.0.0
        mockMvc.perform(post("/platform/modules/" + ID + "/rollback").param("version", "1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.0"));

        // actual serving reverts to v1
        mockMvc.perform(get("/ver/ping")).andExpect(content().string("v1"));

        // the rollback is also recorded as a new history entry -> 3 entries, newest is 1.0.0
        mockMvc.perform(get("/platform/modules/" + ID + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].version").value("1.0.0"));
    }

    @Test
    void rollback_to_unknown_version_is_409() throws Exception {
        platform.install(descriptor("1.0.0", "v1"));
        mockMvc.perform(post("/platform/modules/" + ID + "/rollback").param("version", "9.9.9"))
                .andExpect(status().isConflict())
                // RFC 9457 problem+json — 409 state conflict.
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.type").value("urn:protean:error:state-conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void versions_of_unknown_module_is_404() throws Exception {
        mockMvc.perform(get("/platform/modules/no-such/versions"))
                .andExpect(status().isNotFound());
    }
}
