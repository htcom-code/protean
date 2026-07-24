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
import org.htcom.protean.module.ModuleDescriptor.ModuleKind;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 * Empirical check that typed sharing works under auto-provision + a named scope in <b>worker</b> mode, exercising the
 * scope-packing propagation path the pool rewrite introduced (Testcontainers Postgres, Docker-gated):
 *
 * <ol>
 *   <li><b>shared-module</b>: an in-process LIBRARY plus two consumers that {@code uses} it, both bound to the same
 *       scope — they pack into a single worker, both compile/link against the library, and a live library update
 *       propagates to <i>both</i> co-located dependents in place (the multi-module-in-one-worker rebind path).</li>
 *   <li><b>shared-lib</b>: a consumer that compiles against a class present only in the shared-lib dir ({@code ext.Widget})
 *       runs in a scoped worker.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class WorkerScopedTypedSharingTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-scoped-typed-sharing");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.module.shared-lib-dir", buildSharedLibJar()::toString);
        registry.add("protean.worker.db.auto-provision", () -> "true");
        registry.add("protean.worker.db.dialect", () -> "postgresql");
        registry.add("protean.worker.db.scopes", () -> "sharedscope");
        registry.add("protean.worker.db.admin-url", pg::getJdbcUrl);
        registry.add("protean.worker.db.admin-username", pg::getUsername);
        registry.add("protean.worker.db.admin-password", pg::getPassword);
    }

    private static Path buildSharedLibJar() throws Exception {
        Path base = Files.createTempDirectory("protean-scoped-sharedlib");
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
    @Autowired ModulePlatform platform;
    @Autowired WorkerProcessIsolation isolation;

    // --- shared-module (typed-sharing LIBRARY) fixtures ---

    static final String LIB = "sslib-geo";

    static ModuleDescriptor library(String label, String version) {
        return ModuleDescriptor.builder().id(LIB).version(version).kind(ModuleKind.LIBRARY)
                .exports(List.of("geo")).isolationMode("in-process")
                .sources(Map.of("geo.Point", """
                        package geo;
                        public class Point { public String label() { return "%s"; } }
                        """.formatted(label)))
                .tests(Map.of("geo.PointTest", """
                        package geo;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertNotNull;
                        class PointTest { @Test void ok() { assertNotNull(new Point().label()); } }
                        """))
                .build();
    }

    /** A worker consumer of the library, bound to the shared scope. {@code n} distinguishes id/route/class. */
    static ModuleDescriptor consumer(int n, String version) {
        String cls = "runtime.ss.Consumer" + n;
        return ModuleDescriptor.builder().id("ss-consumer-" + n).version(version).kind(ModuleKind.NORMAL)
                .uses(List.of(LIB)).isolationMode("worker").scope("sharedscope")
                .controllerFqcn(cls).componentFqcns(List.of(cls))
                .sources(Map.of(cls, """
                        package runtime.ss;
                        import geo.Point;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class Consumer%1$d {
                            @GetMapping("/ss/%1$d/label") public String label() { return "c%1$d:" + new Point().label(); }
                        }
                        """.formatted(n)))
                .tests(Map.of(cls + "Test", """
                        package runtime.ss;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertTrue;
                        class Consumer%1$dTest { @Test void ok() { assertTrue(new Consumer%1$d().label().startsWith("c%1$d:")); } }
                        """.formatted(n)))
                .build();
    }

    // --- shared-lib (external jar) fixture ---

    static final String SL_FQCN = "runtime.ssl.SslController";
    static final String SL_SRC = """
            package runtime.ssl;
            import ext.Widget;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class SslController {
                @GetMapping("/ssl/hello") public String hello() { return new Widget().hello(); }
            }
            """;

    @AfterEach
    void cleanup() {
        for (String id : List.of("ss-consumer-1", "ss-consumer-2", LIB)) {
            try {
                if (platform.find(id).isPresent()) {
                    platform.uninstall(id);
                }
            } catch (RuntimeException ignored) {
            }
        }
        try {
            isolation.undeploy("ssl-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void shared_module_consumers_pack_into_one_scoped_worker_and_both_adopt_a_live_library_update() throws Exception {
        platform.install(library("v1", "1.0.0"));
        platform.install(consumer(1, "1.0.0"));
        platform.install(consumer(2, "1.0.0"));

        // Same scope → both consumers pack into one worker JVM; both compile/link against the in-process library.
        org.junit.jupiter.api.Assertions.assertEquals(1, isolation.workerCount(),
                "same-scope consumers must share one worker");
        mockMvc.perform(get("/ss/1/label")).andExpect(status().isOk()).andExpect(content().string("c1:v1"));
        mockMvc.perform(get("/ss/2/label")).andExpect(status().isOk()).andExpect(content().string("c2:v1"));

        // A live library update propagates to BOTH co-located dependents in the packed worker — no restart, no manual redeploy.
        platform.update(library("v2", "2.0.0"));
        mockMvc.perform(get("/ss/1/label")).andExpect(status().isOk()).andExpect(content().string("c1:v2"));
        mockMvc.perform(get("/ss/2/label")).andExpect(status().isOk()).andExpect(content().string("c2:v2"));
    }

    @Test
    void shared_lib_consumer_runs_in_a_scoped_worker() throws Exception {
        // ext.Widget is not on the host classpath — this succeeds only if the scoped worker resolves it via shared-lib-dir.
        isolation.deploy(ModuleDescriptor.builder()
                .id("ssl-mod").version("1.0.0")
                .controllerFqcn(SL_FQCN).componentFqcns(List.of(SL_FQCN)).sources(Map.of(SL_FQCN, SL_SRC))
                .isolationMode("worker").scope("sharedscope")
                .build());

        mockMvc.perform(get("/ssl/hello")).andExpect(status().isOk()).andExpect(content().string("widget-from-shared-lib"));
    }
}
