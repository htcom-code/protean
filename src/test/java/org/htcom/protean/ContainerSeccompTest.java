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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Custom seccomp profile <b>runtime enforcement demonstration</b> (Docker-gated).
 *
 * <p>Until now only the seccomp option plumbing ({@code --security-opt seccomp=}) was verified;
 * whether a custom profile actually blocks a syscall was not demonstrated. On a container with the
 * bundled default profile actually applied ({@code protean.worker.container.seccomp=bundled},
 * defaultAction=ALLOW with symlink-family calls denied via EPERM), this test verifies via the main
 * ReverseProxy that:
 * <ul>
 *   <li>a module action that triggers {@code symlinkat} (Files.createSymbolicLink) is <b>blocked</b>, and</li>
 *   <li>the allowed {@code mkdirat} (Files.createDirectories) <b>succeeds</b></li>
 * </ul>
 * thereby proving "selective enforcement" (the container works normally but only the designated
 * syscall is blocked).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "protean.worker.container.seccomp=bundled")
class ContainerSeccompTest {

    @LocalServerPort int port;
    @Autowired ContainerWorkerIsolation isolation;

    final HttpClient http = HttpClient.newHttpClient();

    static final String FQCN = "runtime.sc.ScController";

    static ModuleDescriptor descriptor() {
        String src = """
                package runtime.sc;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                import java.nio.file.Files;
                import java.nio.file.Path;
                @RestController
                public class ScController {
                    // symlinkat -> the bundled profile should deny this via EPERM.
                    @GetMapping("/sc/symlink")
                    public String symlink() {
                        try {
                            Path target = Files.createTempFile("t", ".txt");
                            Path link = Path.of("/tmp/sc-link-" + System.nanoTime());
                            Files.createSymbolicLink(link, target);
                            return "created";
                        } catch (Throwable t) {
                            return "blocked:" + t.getClass().getSimpleName();
                        }
                    }
                    // mkdirat -> the profile allows it (defaultAction=ALLOW), so it should succeed (proves the container works).
                    @GetMapping("/sc/mkdir")
                    public String mkdir() {
                        try {
                            Files.createDirectories(Path.of("/tmp/sc-dir-" + System.nanoTime()));
                            return "created";
                        } catch (Throwable t) {
                            return "blocked:" + t.getClass().getSimpleName();
                        }
                    }
                }
                """;
        return ModuleDescriptor.builder()
                .id("sc-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, src))
                .isolationMode("container")
                .build();
    }

    @BeforeEach
    void preconditions() {
        assumeTrue(OsIsolationTest.dockerAvailable(), "no Docker daemon — skip");
        assumeTrue(OsIsolationTest.bootJarExists(), "no bootJar ('gradle bootJar') — skip");
    }

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("sc-mod");
        } catch (RuntimeException ignored) {
        }
    }

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void bundled_seccomp_profile_blocks_symlink_but_allows_mkdir() throws Exception {
        isolation.deploy(descriptor());

        // Whether the seccomp profile was actually applied to the container (docker inspect).
        assertTrue(isolation.inspectSecurityOpt("sc-mod").contains("seccomp"),
                "the custom seccomp profile should be applied in security-opt: " + isolation.inspectSecurityOpt("sc-mod"));

        // The allowed syscall succeeds -> first confirm the container works (the test is not just broken).
        assertEquals("created", get("/sc/mkdir"), "mkdir (mkdirat) should succeed because the profile allows it");

        // The denied syscall is blocked with EPERM -> Java observes it as an exception.
        String symlink = get("/sc/symlink");
        assertTrue(symlink.startsWith("blocked:"),
                "symlink (symlinkat) should be denied by the bundled seccomp profile, got: " + symlink);
    }
}
