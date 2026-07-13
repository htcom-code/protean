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
 * Shared lib directory: if a jar containing a class <b>not</b> on the host classpath ({@code ext.Widget}) is placed in
 * {@code protean.module.shared-lib-dir}, a module can <b>compile against and run</b> that class
 * (module CL parent = shared lib CL, also reflected on the compile classpath). Consumers can drop in drivers/libraries
 * without rebuilding the app.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SharedLibModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;

    /** Before the context starts, build the ext.Widget jar in the shared lib directory and point the property at it. */
    @DynamicPropertySource
    static void sharedLibDir(DynamicPropertyRegistry registry) throws Exception {
        Path libDir = buildSharedLibJar();
        registry.add("protean.module.shared-lib-dir", libDir::toString);
    }

    private static Path buildSharedLibJar() throws Exception {
        Path base = Files.createTempDirectory("protean-sharedlib-test");
        Path classes = Files.createDirectories(base.resolve("classes"));
        Path src = base.resolve("Widget.java");
        Files.writeString(src, "package ext; public class Widget { "
                + "public String hello() { return \"widget-from-shared-lib\"; } }");
        int rc = ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", classes.toString(), src.toString());
        if (rc != 0) {
            throw new IllegalStateException("shared lib fixture compilation failed");
        }
        Path libDir = Files.createDirectories(base.resolve("lib"));
        Path jar = libDir.resolve("ext-widget.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("ext/Widget.class"));
            jos.write(Files.readAllBytes(classes.resolve("ext/Widget.class")));
            jos.closeEntry();
        }
        return libDir;
    }

    static final String CONTROLLER = "runtime.sl.SlController";
    static final Map<String, String> SOURCES = Map.of(
            CONTROLLER, """
                    package runtime.sl;
                    import ext.Widget;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class SlController {
                        @GetMapping("/sharedlib/hello")
                        public String hello() { return new Widget().hello(); }
                    }
                    """
    );

    @AfterEach
    void cleanup() {
        if (container.isDeployed("sl-mod")) {
            container.undeploy("sl-mod");
        }
    }

    @Test
    void module_compiles_and_runs_against_shared_lib_class() throws Exception {
        // ext.Widget is not on the host classpath and exists only in the shared lib jar -> success requires it to resolve via the shared lib at both compile and runtime.
        ModuleClassLoader loader = compiler.compileAll(SOURCES);
        container.deploy("sl-mod", loader, List.of(CONTROLLER), CONTROLLER);

        mockMvc.perform(get("/sharedlib/hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("widget-from-shared-lib"));

        container.undeploy("sl-mod");
        mockMvc.perform(get("/sharedlib/hello")).andExpect(status().isNotFound());
    }
}
