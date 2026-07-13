/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.isolation.ContainerWorkerIsolation;
import org.htcom.protean.module.ModuleDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared-lib support in <b>container mode</b> (Docker). Unlike the process worker (same host FS), the container has a
 * separate FS namespace, so {@code protean.module.shared-lib-dir} is bind-mounted read-only into the container and the
 * in-container path is passed to the worker. Verifies the containerized worker compiles and runs a class not on the
 * host classpath ({@code ext.Widget}) resolved solely via the mounted shared-lib. Requires a Docker daemon and a
 * bootJar (build/libs); skipped otherwise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ContainerSharedLibModuleTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        Path libDir = buildSharedLibJar();
        registry.add("protean.module.shared-lib-dir", libDir::toString);
    }

    /**
     * Builds the shared-lib jar under {@code build/} (an absolute path inside the workspace, which Docker Desktop shares
     * by default — a system temp dir under /var/folders may not be mountable on macOS).
     */
    private static Path buildSharedLibJar() throws Exception {
        Path base = Files.createDirectories(Path.of("build", "test-container-sharedlib").toAbsolutePath());
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
    @Autowired ContainerWorkerIsolation isolation;

    static final String FQCN = "runtime.csl.CslController";
    static final String SRC = """
            package runtime.csl;
            import ext.Widget;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class CslController {
                @GetMapping("/container-sl/hello")
                public String hello() { return new Widget().hello(); }
            }
            """;

    @BeforeEach
    void preconditions() {
        assumeTrue(dockerAvailable(), "no Docker daemon — skip container shared-lib test");
        assumeTrue(bootJarExists(), "no bootJar ('gradle bootJar' required) — skip");
    }

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("csl-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void container_compiles_and_runs_against_mounted_shared_lib() throws Exception {
        // ext.Widget is not on the host classpath -> succeeds only if the container resolves it via the mounted shared-lib.
        isolation.deploy(ModuleDescriptor.builder()
                .id("csl-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .isolationMode("container")
                .build());

        mockMvc.perform(get("/container-sl/hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("widget-from-shared-lib"));
    }

    static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version").redirectErrorStream(true).start();
            return p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean bootJarExists() {
        Path libs = Path.of("build", "libs");
        if (!Files.isDirectory(libs)) {
            return false;
        }
        try (Stream<Path> s = Files.list(libs)) {
            return s.anyMatch(p -> p.toString().endsWith("-boot.jar"));  // bootJar uses the '-boot' classifier
        } catch (Exception e) {
            return false;
        }
    }
}
