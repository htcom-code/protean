/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.module.ModuleContainer;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleResource;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Resource live-reload: swap only the resource in place without recompiling or rebuilding the context.
 * Proof = after reload (i) the new content is reflected (ii) the same ClassLoader instance is kept
 * (iii) zero recompilations.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "protean.gate.tests-enabled=false",
        "protean.gate.review-enabled=false"
})
class LiveReloadModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired ModuleContainer container;
    @Autowired RuntimeCompiler compiler;

    static final String CTRL = "runtime.lr.LrController";
    static final String SRC = """
            package runtime.lr;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            import java.io.InputStream;
            import java.nio.charset.StandardCharsets;
            @RestController
            public class LrController {
                @GetMapping("/lr/msg")
                public String msg() throws Exception {
                    // Read live on every request (not init-parse) -> subject to live-reload.
                    try (InputStream in = getClass().getClassLoader().getResourceAsStream("conf/live.txt")) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
            """;

    static ModuleDescriptor v1() {
        return ModuleDescriptor.builder()
                .id("lr-mod").version("1")
                .controllerFqcn(CTRL).componentFqcns(List.of(CTRL)).sources(Map.of(CTRL, SRC))
                .resources(Map.of("conf/live.txt", ModuleResource.text("v1")))
                .build();
    }

    @AfterEach
    void cleanup() {
        if (platform.find("lr-mod").isPresent()) {
            platform.uninstall("lr-mod");
        }
    }

    @Test
    void reload_resources_swaps_in_place_without_rebuild_or_recompile() throws Exception {
        platform.install(v1());
        mockMvc.perform(get("/lr/msg")).andExpect(status().isOk()).andExpect(content().string("v1"));

        ModuleClassLoader before = container.currentLoader("lr-mod");
        assertNotNull(before);
        long compilesBefore = compiler.compilationCount();

        // Swap only the resource in place (no compile, no context rebuild).
        mockMvc.perform(post("/platform/modules/lr-mod/reload-resources")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"files":[{"kind":"resource","filename":"conf/live.txt","content":"v2"}]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/lr/msg")).andExpect(status().isOk()).andExpect(content().string("v2"));

        ModuleClassLoader after = container.currentLoader("lr-mod");
        assertSame(before, after, "live-reload must not replace the ClassLoader (zero-reload)");
        assertEquals(compilesBefore, compiler.compilationCount(), "live-reload must not recompile");
    }
}
