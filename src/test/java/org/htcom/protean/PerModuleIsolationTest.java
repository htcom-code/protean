/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.isolation.WorkerProcessIsolation;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-module isolation mode verification: an in-process module and a worker module coexist on one server.
 * The global default is in-process. The descriptor's isolationMode routes the strategy per module.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class PerModuleIsolationTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-permod-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        // global mode unset -> default in-process
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired WorkerProcessIsolation workerIsolation;

    static final String TEST_TMPL = """
            package runtime.%s;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            public class %sControllerTest {
                @Test void ok() { assertNotNull(new %sController()); }
            }
            """;

    static ModuleDescriptor module(String pkg, String cls, String path, String body, String isolationMode) {
        String ctrlFqcn = "runtime." + pkg + "." + cls + "Controller";
        String testFqcn = "runtime." + pkg + "." + cls + "ControllerTest";
        String ctrl = """
                package runtime.%s;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class %sController {
                    @GetMapping("%s")
                    public String ping() { return "%s"; }
                }
                """.formatted(pkg, cls, path, body);
        String test = TEST_TMPL.formatted(pkg, cls, cls);
        return ModuleDescriptor.builder()
                .id("permod-" + pkg).version("1.0.0")
                .controllerFqcn(ctrlFqcn).componentFqcns(List.of(ctrlFqcn))
                .sources(Map.of(ctrlFqcn, ctrl)).tests(Map.of(testFqcn, test))
                .isolationMode(isolationMode)
                .build();
    }

    @AfterEach
    void cleanup() {
        for (String id : List.of("permod-ip", "permod-wk")) {
            try {
                platform.uninstall(id);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Test
    void in_process_and_worker_modules_coexist() throws Exception {
        // A: in-process (isolationMode=null -> global default in-process)
        platform.install(module("ip", "Ip", "/perm/ip", "in-process", null));
        // B: worker (isolationMode="worker")
        platform.install(module("wk", "Wk", "/perm/wk", "worker", "worker"));

        mockMvc.perform(get("/perm/ip")).andExpect(status().isOk()).andExpect(content().string("in-process"));
        mockMvc.perform(get("/perm/wk")).andExpect(status().isOk()).andExpect(content().string("worker"));

        // only the worker module gets a separate JVM -> 1 worker. the in-process module uses no worker.
        assertEquals(1, workerIsolation.workerCount(), "only worker-mode modules use a worker JVM");
    }
}
