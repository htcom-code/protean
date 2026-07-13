/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.isolation.InProcessIsolation;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Diff-based incremental update: a resource-only update skips javac (fast-path), while a source change
 * recompiles. Observes whether a real compile happened via RuntimeCompiler.compilationCount.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiffUpdateModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired InProcessIsolation isolation;
    @Autowired RuntimeCompiler compiler;

    static final String MODULE_ID = "diff-mod";
    static final String CONTROLLER = "runtime.diff.DiffController";

    /** Controller that reads and returns the resource live on each request (to confirm resource replacement takes effect). */
    static String controllerSrc(String prefix) {
        return """
                package runtime.diff;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                import java.io.InputStream;
                import java.nio.charset.StandardCharsets;
                @RestController
                public class DiffController {
                    @GetMapping("/diff/msg")
                    public String msg() throws Exception {
                        try (InputStream in = getClass().getClassLoader().getResourceAsStream("conf/msg.txt")) {
                            return "%s" + new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }
                }
                """.formatted(prefix);
    }

    static ModuleDescriptor desc(String source, String msg) {
        return ModuleDescriptor.builder()
                .id(MODULE_ID).version("1.0")
                .controllerFqcn(CONTROLLER).componentFqcns(List.of(CONTROLLER)).sources(Map.of(CONTROLLER, source))
                .resources(Map.of("conf/msg.txt", ModuleResource.text(msg)))
                .build();
    }

    @AfterEach
    void cleanup() {
        if (isDeployed()) {
            isolation.undeploy(MODULE_ID);
        }
    }

    private boolean isDeployed() {
        try {
            return mockMvc.perform(get("/diff/msg")).andReturn().getResponse().getStatus() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void resource_only_update_skips_recompile_but_source_change_recompiles() throws Exception {
        // Initial deploy -> 1 compile.
        isolation.deploy(desc(controllerSrc(""), "v1"));
        long afterDeploy = compiler.compilationCount();
        mockMvc.perform(get("/diff/msg")).andExpect(status().isOk()).andExpect(content().string("v1"));

        // Replace only the resource (same source) -> fast-path: new resource takes effect without recompiling.
        isolation.hotSwap(desc(controllerSrc(""), "v2"));
        assertEquals(afterDeploy, compiler.compilationCount(),
                "a resource-only update must skip javac (source unchanged)");
        mockMvc.perform(get("/diff/msg")).andExpect(status().isOk()).andExpect(content().string("v2"));

        // Source change -> recompile (compile count +1), new source behavior takes effect.
        isolation.hotSwap(desc(controllerSrc("CHANGED:"), "v2"));
        assertEquals(afterDeploy + 1, compiler.compilationCount(),
                "a source-change update must recompile");
        mockMvc.perform(get("/diff/msg")).andExpect(status().isOk()).andExpect(content().string("CHANGED:v2"));
    }

    @Test
    void undeploy_evicts_compile_cache_so_redeploy_recompiles() throws Exception {
        isolation.deploy(desc(controllerSrc(""), "a"));
        long afterDeploy = compiler.compilationCount();

        isolation.undeploy(MODULE_ID);   // evict cache
        mockMvc.perform(get("/diff/msg")).andExpect(status().isNotFound());

        // Redeploying with the same source must still recompile because the cache is empty (+1).
        isolation.deploy(desc(controllerSrc(""), "a"));
        assertEquals(afterDeploy + 1, compiler.compilationCount(),
                "undeploy empties the cache, so redeploy must recompile");
        mockMvc.perform(get("/diff/msg")).andExpect(status().isOk()).andExpect(content().string("a"));
    }
}
