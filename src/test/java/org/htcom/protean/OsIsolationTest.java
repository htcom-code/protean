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
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OS isolation PoC (Docker). Launches a worker as a container and verifies that a cgroup memory cap +
 * read-only FS are enforced. Requires a Docker daemon and a bootJar (build/libs); skipped otherwise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class OsIsolationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ContainerWorkerIsolation isolation;

    static final String FQCN = "runtime.os.OsController";
    static final String SRC = """
            package runtime.os;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class OsController {
                @GetMapping("/os/ok")
                public String ok() { return "ok"; }
                @GetMapping("/os/fswrite")
                public String fswrite() {
                    try {
                        java.nio.file.Files.writeString(java.nio.file.Path.of("/blocked.txt"), "x");
                        return "write_blocked=false";
                    } catch (Exception e) {
                        return "write_blocked=true";   // read-only rootfs -> write fails
                    }
                }
            }
            """;

    @BeforeEach
    void preconditions() {
        assumeTrue(dockerAvailable(), "no Docker daemon — skip OS isolation test");
        assumeTrue(bootJarExists(), "no bootJar ('gradle bootJar' required) — skip");
    }

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("os-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void container_enforces_memory_cap_and_readonly_fs() throws Exception {
        isolation.deploy(ModuleDescriptor.builder()
                .id("os-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .isolationMode("container")
                .build());

        // normal operation
        mockMvc.perform(get("/os/ok")).andExpect(status().isOk()).andExpect(content().string("ok"));

        // read-only FS: writes to the host root are blocked
        mockMvc.perform(get("/os/fswrite"))
                .andExpect(status().isOk())
                .andExpect(content().string("write_blocked=true"));

        // memory cap (cgroup): the 256m limit is actually applied to the container
        assertEquals(256L * 1024 * 1024, isolation.inspectMemoryLimit("os-mod"),
                "a cgroup memory limit must be applied to the container");
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
