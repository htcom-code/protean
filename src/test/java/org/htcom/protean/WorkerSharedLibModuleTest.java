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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared-lib support in <b>worker mode</b>: when the worker (a separate JVM = the protean app) is passed
 * {@code --protean.module.shared-lib-dir}, the worker's ModuleSharedLibs reads the same directory
 * (shared host FS) to <b>compile and run</b> a class not on the host classpath ({@code ext.Widget}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerSharedLibModuleTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        Path libDir = buildSharedLibJar();
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.module.shared-lib-dir", libDir::toString);
    }

    private static Path buildSharedLibJar() throws Exception {
        Path base = Files.createTempDirectory("protean-worker-sharedlib");
        Path classes = Files.createDirectories(base.resolve("classes"));
        Path src = base.resolve("Widget.java");
        Files.writeString(src, "package ext; public class Widget { "
                + "public String hello() { return \"widget-from-shared-lib\"; } }");
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.toString()) != 0) {
            throw new IllegalStateException("shared-lib fixture compilation failed");
        }
        Path libDir = Files.createDirectories(base.resolve("lib"));
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(libDir.resolve("ext-widget.jar")))) {
            jos.putNextEntry(new JarEntry("ext/Widget.class"));
            jos.write(Files.readAllBytes(classes.resolve("ext/Widget.class")));
            jos.closeEntry();
        }
        return libDir;
    }

    @Autowired MockMvc mockMvc;
    @Autowired WorkerProcessIsolation isolation;

    static final String FQCN = "runtime.wsl.WslController";
    static final String SRC = """
            package runtime.wsl;
            import ext.Widget;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class WslController {
                @GetMapping("/worker-sl/hello")
                public String hello() { return new Widget().hello(); }
            }
            """;

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("wsl-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void worker_compiles_and_runs_against_shared_lib_via_forwarded_property() throws Exception {
        // ext.Widget is not on the host classpath -> succeeds only if the worker resolves it via shared-lib-dir alone.
        isolation.deploy(ModuleDescriptor.builder()
                .id("wsl-mod").version("1.0.0")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .isolationMode("worker")
                .build());

        mockMvc.perform(get("/worker-sl/hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("widget-from-shared-lib"));
    }
}
