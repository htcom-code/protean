/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.module.JdbcModuleStore;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.ModuleStore;
import org.htcom.protean.module.ModuleVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ModuleStore DB backend: when protean.module-store.backend=jdbc, JdbcModuleStore is selected, its
 * interface (save/load/listActive/history/loadVersion/remove) works, and the ModulePlatform lifecycle runs
 * end-to-end on top of the JDBC backend.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JdbcModuleStoreTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.backend", () -> "jdbc");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModuleStore store;
    @Autowired ModulePlatform platform;

    @AfterEach
    void cleanup() {
        for (String id : new String[]{"js-mod", "jsp-mod"}) {
            try {
                store.remove(id);
            } catch (RuntimeException ignored) {
            }
            try {
                platform.uninstall(id);
            } catch (RuntimeException ignored) {
            }
        }
    }

    static ModuleDescriptor descriptor(String id, String version) {
        return ModuleDescriptor.builder()
                .id(id).version(version)
                .controllerFqcn("x.Y").componentFqcns(List.of("x.Y"))
                .sources(Map.of("x.Y", "src")).tests(Map.of("x.YTest", "t"))
                .build();
    }

    @Test
    void jdbc_backend_selected_and_store_interface_roundtrips() {
        assertInstanceOf(JdbcModuleStore.class, store, "with backend=jdbc, JdbcModuleStore must be injected");

        store.save(descriptor("js-mod", "1.0.0"));
        assertTrue(store.load("js-mod").isPresent());
        assertEquals("1.0.0", store.load("js-mod").get().version());
        assertTrue(store.listActive().stream().anyMatch(d -> d.id().equals("js-mod")));
        assertEquals(1, store.history("js-mod").size());

        // Second save -> history accumulates (newest first), current state is 2.0.0
        store.save(descriptor("js-mod", "2.0.0"));
        List<ModuleVersion> history = store.history("js-mod");
        assertEquals(2, history.size());
        assertEquals("2.0.0", history.get(0).version(), "newest first");
        assertEquals("2.0.0", store.load("js-mod").get().version());

        // Query a past version
        assertTrue(store.loadVersion("js-mod", "1.0.0").isPresent());
        assertEquals("1.0.0", store.loadVersion("js-mod", "1.0.0").get().version());

        // Remove -> both current and history disappear
        store.remove("js-mod");
        assertTrue(store.load("js-mod").isEmpty());
        assertTrue(store.history("js-mod").isEmpty());
        assertFalse(store.listActive().stream().anyMatch(d -> d.id().equals("js-mod")));
    }

    @Test
    void platform_lifecycle_runs_on_jdbc_backend() throws Exception {
        String fqcn = "runtime.js.JsController";
        String testFqcn = "runtime.js.JsControllerTest";
        String src = """
                package runtime.js;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class JsController {
                    @GetMapping("/js/ping")
                    public String ping() { return "js"; }
                }
                """;
        String test = """
                package runtime.js;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class JsControllerTest {
                    @Test void ping() { assertEquals("js", new JsController().ping()); }
                }
                """;
        platform.install(ModuleDescriptor.builder()
                .id("jsp-mod").version("1.0.0")
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn))
                .sources(Map.of(fqcn, src)).tests(Map.of(testFqcn, test))
                .build());

        // Persisted to the JDBC store and actually serving
        assertTrue(store.load("jsp-mod").isPresent(), "descriptor persisted to the JDBC store");
        mockMvc.perform(get("/js/ping")).andExpect(status().isOk()).andExpect(content().string("js"));

        platform.uninstall("jsp-mod");
        assertTrue(store.load("jsp-mod").isEmpty(), "removed from the store after uninstall");
    }
}
