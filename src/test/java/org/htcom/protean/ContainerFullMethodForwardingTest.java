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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Container-track counterpart of {@link WorkerFullMethodForwardingTest}: a {@code @PostMapping} module isolated in
 * a Docker container must be reachable with its real method + body through the {@link ContainerWorkerIsolation}
 * proxy path, and its listed routes must carry the real methods. Requires a Docker daemon and a bootJar
 * ({@code gradle bootJar}); skipped otherwise (Docker-gated, same as {@link OsIsolationTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ContainerFullMethodForwardingTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-container-full-method-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;

    static final String ID = "cfm-container";
    static final String FQCN = "gen.cfm.C";
    static final String SRC = """
            package gen.cfm;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class C {
                public static String tag(String s) { return "echo:" + s; }
                @PostMapping("/cfm/echo")
                public String echo(@RequestBody(required = false) String body) { return tag(body == null ? "<null>" : body); }
                @GetMapping("/cfm/ping")
                public String ping() { return "pong"; }
            }
            """;
    static final String TEST_SRC = """
            package gen.cfm;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class CTest { @Test void tag() { assertEquals("echo:x", C.tag("x")); } }
            """;

    @BeforeEach
    void preconditions() {
        assumeTrue(dockerAvailable(), "no Docker daemon — skip container forwarding test");
        assumeTrue(bootJarExists(), "no bootJar ('gradle bootJar' required) — skip");
        platform.install(ModuleDescriptor.builder()
                .id(ID).version("1")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN))
                .sources(Map.of(FQCN, SRC)).tests(Map.of("gen.cfm.CTest", TEST_SRC))
                .isolationMode("container")
                .build());
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall(ID);
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void container_post_with_body_is_forwarded() throws Exception {
        mockMvc.perform(post("/cfm/echo").contentType("text/plain").content("hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("echo:hello"));
        mockMvc.perform(get("/cfm/ping")).andExpect(status().isOk()).andExpect(content().string("pong"));
    }

    @Test
    void container_route_listing_reports_methods() throws Exception {
        mockMvc.perform(get("/platform/modules/" + ID + "/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.patterns[0] == '/cfm/echo')].methods[0]").value(containsInAnyOrder("POST")))
                .andExpect(jsonPath("$[?(@.patterns[0] == '/cfm/ping')].methods[0]").value(containsInAnyOrder("GET")));
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
