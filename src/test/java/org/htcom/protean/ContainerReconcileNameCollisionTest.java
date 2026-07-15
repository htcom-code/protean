/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression for the container reconcile name-collision bug: after an unclean main exit a detached container
 * keeps holding the deterministic name (`protean-worker-&lt;id&gt;-&lt;seq&gt;`), and because {@code seq} resets on
 * restart the next deploy re-derives that exact name. A blind {@code docker run --name} then fails with a 125
 * conflict and the module is skipped (route 404). {@code startContainer} now removes a stale same-name container
 * first. This test plants such an orphan and asserts the deploy still succeeds. Docker + bootJar gated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ContainerReconcileNameCollisionTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-container-collision-test");
    static final String ID = "collide";
    // The first container deploy on a fresh isolation instance uses seq=1, so this is the name it will pick.
    static final String COLLIDING_NAME = "protean-worker-" + ID + "-1";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;

    static final String FQCN = "gen.col.C";
    static final String SRC = """
            package gen.col;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class C {
                public static String ok() { return "pong"; }
                @GetMapping("/collide/ping")
                public String ping() { return ok(); }
            }
            """;
    static final String TEST_SRC = """
            package gen.col;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class CTest { @Test void ok() { assertEquals("pong", C.ok()); } }
            """;

    @BeforeEach
    void preconditions() {
        assumeTrue(dockerAvailable(), "no Docker daemon — skip");
        assumeTrue(bootJarExists(), "no bootJar ('gradle bootJar' required) — skip");
        dockerRm(COLLIDING_NAME);
        // Plant an orphan holding the exact name the next deploy will pick (simulates a prior main's leftover).
        docker(List.of("docker", "run", "-d", "--rm", "--name", COLLIDING_NAME,
                "eclipse-temurin:21-jdk", "sleep", "120"));
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall(ID);
        } catch (RuntimeException ignored) {
        }
        dockerRm(COLLIDING_NAME);
    }

    @Test
    void deploy_clears_stale_same_name_container_and_serves() throws Exception {
        // Before the fix this threw (docker 125: name already in use) and the module never came up.
        platform.install(ModuleDescriptor.builder()
                .id(ID).version("1")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN))
                .sources(Map.of(FQCN, SRC)).tests(Map.of("gen.col.CTest", TEST_SRC))
                .isolationMode("container")
                .build());

        mockMvc.perform(get("/collide/ping")).andExpect(status().isOk()).andExpect(content().string("pong"));
    }

    static void dockerRm(String name) {
        docker(List.of("docker", "rm", "-f", name));
    }

    static void docker(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version").redirectErrorStream(true).start();
            return p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
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
