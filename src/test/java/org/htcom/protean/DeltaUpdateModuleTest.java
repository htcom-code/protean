/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePatch;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.ModuleResource;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Delta/patch update: send only the changed files, overlay them onto the current descriptor, then run a
 * canary update. Gates are off in this test context to focus on the merge semantics (the update pipeline
 * itself is covered by CanaryUpdateTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "protean.gate.tests-enabled=false",
        "protean.gate.review-enabled=false"
})
class DeltaUpdateModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;

    static final String CTRL = "runtime.dl.DlController";

    static String ctrlSrc(String prefix) {
        return """
                package runtime.dl;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                import java.io.InputStream;
                import java.nio.charset.StandardCharsets;
                @RestController
                public class DlController {
                    @GetMapping("/dl/msg")
                    public String msg() throws Exception {
                        try (InputStream in = getClass().getClassLoader().getResourceAsStream("conf/msg.txt")) {
                            return "%s" + new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }
                }
                """.formatted(prefix);
    }

    static ModuleDescriptor v1() {
        return ModuleDescriptor.builder()
                .id("dl-mod").version("1")
                .controllerFqcn(CTRL).componentFqcns(List.of(CTRL)).sources(Map.of(CTRL, ctrlSrc("")))
                .resources(Map.of("conf/msg.txt", ModuleResource.text("v1")))
                .build();
    }

    @AfterEach
    void cleanup() {
        if (platform.find("dl-mod").isPresent()) {
            platform.uninstall("dl-mod");
        }
    }

    @Test
    void patch_overlays_resource_then_source_keeping_unpatched() throws Exception {
        platform.install(v1());
        mockMvc.perform(get("/dl/msg")).andExpect(status().isOk()).andExpect(content().string("v1"));

        // Replace only the resource (keep the source) -> v2.
        mockMvc.perform(patch("/platform/modules/dl-mod").contentType(APPLICATION_JSON).content("""
                {"version":"2","files":[{"kind":"resource","filename":"conf/msg.txt","content":"v2"}]}
                """)).andExpect(status().isOk());
        mockMvc.perform(get("/dl/msg")).andExpect(status().isOk()).andExpect(content().string("v2"));

        // Replace only the source (confirm the resource stays v2) -> prefix "X:" is added.
        mockMvc.perform(patch("/platform/modules/dl-mod").contentType(APPLICATION_JSON).content("""
                {"version":"3","files":[{"kind":"source","filename":"DlController.java","content":%s}]}
                """.formatted(quote(ctrlSrc("X:"))))).andExpect(status().isOk());
        mockMvc.perform(get("/dl/msg")).andExpect(status().isOk()).andExpect(content().string("X:v2"));
    }

    /** Escape as a JSON string literal. */
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void module_patch_merge_semantics() {
        ModuleDescriptor current = ModuleDescriptor.builder()
                .id("m").version("1")
                .controllerFqcn("p.A").componentFqcns(List.of("p.A")).sources(Map.of("p.A", "srcA"))
                .resources(Map.of("r/one.txt", ModuleResource.text("one")))
                .build();

        ModuleDescriptor merged = ModulePatch.apply(current, "2",
                List.of(
                        new ModulePatch.FileSpec("source", "A.java", "package p; class A{}", false), // replace (p.A)
                        new ModulePatch.FileSpec("resource", "r/two.txt", "two", false)),            // add
                List.of());
        assertEquals("2", merged.version());
        assertEquals("package p; class A{}", merged.sources().get("p.A"));      // replaced
        assertTrue(merged.resources().containsKey("r/one.txt"));                 // existing kept
        assertTrue(merged.resources().containsKey("r/two.txt"));                 // added
        assertEquals("p.A", merged.controllerFqcn());                            // non-patched fields preserved

        ModuleDescriptor removed = ModulePatch.apply(merged, "3", List.of(), List.of("r/one.txt"));
        assertFalse(removed.resources().containsKey("r/one.txt"));               // removed
        assertTrue(removed.resources().containsKey("r/two.txt"));
        assertEquals("1", ModulePatch.apply(current, null, List.of(), List.of()).version()); // version unspecified = current value
    }
}
