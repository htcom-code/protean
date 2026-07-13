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
import org.htcom.protean.module.SharedLibStore;
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
import java.io.ByteArrayOutputStream;
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
 * Live shared-lib propagation to <b>container</b> workers. A container is a full protean
 * worker app in a Docker container whose {@code /shared-lib} mount only carries the read-only boot seed; this pushes
 * the runtime put-jar generation over the container's {@code /__admin/*} plane instead. A fresh container is seeded
 * with the current generation at spawn, and a running container adopts a live put-jar update in place (no restart).
 * Requires a Docker daemon and a bootJar (build/libs); skipped otherwise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ContainerSharedLibPropagationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        registry.add("protean.isolation.mode", () -> "container");
        // No seed dir: the container must get the jar via live push / spawn seeding only. Store dir under build/ so the
        // main can read it (it is never mounted into the container — bytes travel over HTTP).
        Path store = Files.createDirectories(Path.of("build", "test-container-prop-store").toAbsolutePath());
        registry.add("protean.module.shared-lib-store-dir", store::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ContainerWorkerIsolation isolation;
    @Autowired SharedLibStore store;

    static byte[] widgetJar(String helloReturn) throws Exception {
        Path base = Files.createTempDirectory("protean-cprop-widget");
        Path classes = Files.createDirectories(base.resolve("classes"));
        Path src = base.resolve("Widget.java");
        Files.writeString(src, "package ext; public class Widget { public String hello() { return \""
                + helloReturn + "\"; } }");
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.toString()) != 0) {
            throw new IllegalStateException("widget fixture compilation failed");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(bos)) {
            jos.putNextEntry(new JarEntry("ext/Widget.class"));
            jos.write(Files.readAllBytes(classes.resolve("ext/Widget.class")));
            jos.closeEntry();
        }
        return bos.toByteArray();
    }

    static final String FQCN = "runtime.cprop.Ctrl";
    static final String SRC = """
            package runtime.cprop;
            import ext.Widget;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class Ctrl {
                @GetMapping("/cprop/hello") public String hello() { return new Widget().hello(); }
            }
            """;

    static final String TEST_FQCN = "runtime.cprop.CtrlTest";
    // Version-agnostic: passes against both widget-v1 and widget-v2, so a binary-compatible live shared-lib update
    // clears the rebind test gate (enforceTestGate on /__admin/redeploy). A module with no bundled test is rejected
    // by that gate on rebind → Plan B (sticky), so the container would never adopt the update.
    static final String TEST_SRC = """
            package runtime.cprop;
            import ext.Widget;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            public class CtrlTest {
                @Test void widget_greets() { assertTrue(new Widget().hello().startsWith("widget")); }
            }
            """;

    @BeforeEach
    void preconditions() {
        assumeTrue(dockerAvailable(), "no Docker daemon — skip container propagation test");
        assumeTrue(bootJarExists(), "no bootJar ('gradle bootJar' required) — skip");
    }

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("cprop-mod");
        } catch (RuntimeException ignored) {
        }
        for (SharedLibStore.StoredLib lib : store.list()) {
            try {
                store.remove(lib.name());
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Test
    void running_container_adopts_a_live_shared_lib_update() throws Exception {
        store.deploy(List.of(new SharedLibStore.IncomingLib("ext-widget", "1.0.0", widgetJar("widget-v1"), null, null)));
        // The container is spawned + seeded with v1 → compiles ext.Widget v1 (the /shared-lib mount is empty here).
        isolation.deploy(ModuleDescriptor.builder()
                .id("cprop-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .tests(Map.of(TEST_FQCN, TEST_SRC))
                .isolationMode("container")
                .build());
        mockMvc.perform(get("/cprop/hello")).andExpect(status().isOk()).andExpect(content().string("widget-v1"));

        // Upload v2 → the generation event pushes it to the running container, which republishes its parent tier and
        // rebinds the module in place — no container restart, no module redeploy by the test.
        store.deploy(List.of(new SharedLibStore.IncomingLib("ext-widget", "2.0.0", widgetJar("widget-v2"), null, null)));
        awaitContent("/cprop/hello", "widget-v2");
    }

    /**
     * Polls the endpoint until the asynchronous live shared-lib push has rebound the running container to serve
     * {@code expected} (the container republishes its parent tier out of process, so the change is not visible the
     * instant {@code store.deploy} returns), then asserts. Fails with the usual content mismatch if it never adopts.
     */
    private void awaitContent(String path, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString();
            if (expected.equals(body)) {
                break;
            }
            Thread.sleep(100);
        }
        mockMvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string(expected));
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
            return s.anyMatch(p -> p.toString().endsWith("-boot.jar"));
        } catch (Exception e) {
            return false;
        }
    }
}
