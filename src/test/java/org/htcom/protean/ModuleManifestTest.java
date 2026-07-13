/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleManifestLoader;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deep verification: the module.yaml declaration format.
 * (1) the loader converts a filesystem bundle (module.yaml + sourceDir/testDir) into a descriptor,
 * then deploys and serves it, (2) an inline manifest is deployed over HTTP (POST /from-manifest),
 * (3) a missing required field yields 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModuleManifestTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-manifest-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired ModuleManifestLoader loader;

    @AfterEach
    void cleanup() {
        for (String id : new String[]{"mani-mod", "mani-inline"}) {
            try {
                platform.uninstall(id);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Test
    void filesystem_bundle_loads_and_serves(@org.junit.jupiter.api.io.TempDir Path bundle) throws Exception {
        Files.writeString(bundle.resolve("module.yaml"), """
                id: mani-mod
                version: 1.0.0
                controller: runtime.mani.ManiController
                sourceDir: src
                testDir: test
                """);
        Path src = Files.createDirectories(bundle.resolve("src"));
        Files.writeString(src.resolve("ManiController.java"), """
                package runtime.mani;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class ManiController {
                    @GetMapping("/mani/ping")
                    public String ping() { return "mani"; }
                }
                """);
        Path test = Files.createDirectories(bundle.resolve("test"));
        Files.writeString(test.resolve("ManiControllerTest.java"), """
                package runtime.mani;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class ManiControllerTest {
                    @Test void ping() { assertEquals("mani", new ManiController().ping()); }
                }
                """);

        // The loader converts the manifest into a descriptor — the FQCN is derived from package + file name
        ModuleDescriptor d = loader.load(bundle.resolve("module.yaml"));
        assertEquals("mani-mod", d.id());
        assertTrue(d.sources().containsKey("runtime.mani.ManiController"), "source registered under FQCN key");
        assertTrue(d.tests().containsKey("runtime.mani.ManiControllerTest"), "test registered under FQCN key");

        platform.install(d);
        mockMvc.perform(get("/mani/ping")).andExpect(status().isOk()).andExpect(content().string("mani"));
    }

    @Test
    void inline_manifest_via_rest_deploys() throws Exception {
        String yaml = """
                id: mani-inline
                version: 1.0.0
                controller: runtime.mi.MiController
                sources:
                  runtime.mi.MiController: |
                    package runtime.mi;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class MiController {
                        @GetMapping("/mi/ping")
                        public String ping() { return "inline"; }
                    }
                tests:
                  runtime.mi.MiControllerTest: |
                    package runtime.mi;
                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    public class MiControllerTest {
                        @Test void ping() { assertEquals("inline", new MiController().ping()); }
                    }
                """;
        mockMvc.perform(post("/platform/modules/from-manifest")
                        .contentType("application/yaml")
                        .content(yaml))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("mani-inline"))
                .andExpect(jsonPath("$.version").value("1.0.0"));

        mockMvc.perform(get("/mi/ping")).andExpect(status().isOk()).andExpect(content().string("inline"));
    }

    @Test
    void manifest_missing_required_field_is_400() throws Exception {
        String yaml = """
                id: bad-mod
                version: 1.0.0
                """;  // controller missing
        mockMvc.perform(post("/platform/modules/from-manifest")
                        .contentType("application/yaml")
                        .content(yaml))
                .andExpect(status().isBadRequest())
                // RFC 9457 problem+json.
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.type").value("urn:protean:error:invalid-argument"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists())
                // The missing field is surfaced as a structured extension member (self-correction).
                .andExpect(jsonPath("$.missingFields[0]").value("controller"));
    }
}
