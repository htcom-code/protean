/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.compiler.UsedSharedLib;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.ModuleStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §B — module → used shared-lib jar mapping. When a module's compile actually opens a class from a shared-lib jar,
 * {@link RuntimeCompiler#usedSharedLibs(String)} records that jar keyed by {@code name + sha256}. A module that does
 * not reference the shared lib records nothing. The mapping is produced/stored only (no consumer acts on it yet).
 */
@SpringBootTest
class SharedLibUsageMappingTest {

    @Autowired RuntimeCompiler compiler;
    @Autowired ModulePlatform platform;
    @Autowired ModuleStore store;

    static Path JAR;   // the shared-lib jar file, for hashing in-test

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall("gadget-user");
        } catch (RuntimeException ignored) {
        }
    }

    @DynamicPropertySource
    static void sharedLibDir(DynamicPropertyRegistry registry) throws Exception {
        Path libDir = buildSharedLibJar();
        registry.add("protean.module.shared-lib-dir", libDir::toString);
    }

    private static Path buildSharedLibJar() throws Exception {
        Path base = Files.createTempDirectory("protean-sharedlib-usage-test");
        Path classes = Files.createDirectories(base.resolve("classes"));
        Path src = base.resolve("Gadget.java");
        Files.writeString(src, "package ext.usage; public class Gadget { public int v() { return 7; } }");
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.toString());
        if (rc != 0) {
            throw new IllegalStateException("shared lib fixture compilation failed");
        }
        Path libDir = Files.createDirectories(base.resolve("lib"));
        JAR = libDir.resolve("ext-gadget.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(JAR))) {
            jos.putNextEntry(new JarEntry("ext/usage/Gadget.class"));
            jos.write(Files.readAllBytes(classes.resolve("ext/usage/Gadget.class")));
            jos.closeEntry();
        }
        return libDir;
    }

    @Test
    void records_the_shared_lib_jar_a_module_actually_references() throws Exception {
        String fqcn = "runtime.usage.Uses";
        compiler.compileAll(Map.of(fqcn, """
                package runtime.usage;
                import ext.usage.Gadget;
                public class Uses { public int go() { return new Gadget().v(); } }
                """), Map.of(), "uses-mod");

        List<UsedSharedLib> used = compiler.usedSharedLibs("uses-mod");
        assertEquals(1, used.size(), "should record exactly the one shared-lib jar the module references");
        assertEquals("ext-gadget.jar", used.get(0).name());
        assertEquals(sha256(JAR), used.get(0).sha256(), "sha256 must be the content hash of the actual jar");
    }

    @Test
    void records_nothing_when_the_module_does_not_touch_the_shared_lib() {
        String fqcn = "runtime.usage.Plain";
        compiler.compileAll(Map.of(fqcn, """
                package runtime.usage;
                public class Plain { public int go() { return 1; } }
                """), Map.of(), "plain-mod");

        assertTrue(compiler.usedSharedLibs("plain-mod").isEmpty(),
                "a module that references no shared-lib class records no usage");
    }

    @Test
    void install_persists_used_shared_libs_and_survives_reconcile() throws Exception {
        // A full install through the platform: gate ② compiles the controller under the real id (observing the
        // shared-lib usage), ModulePlatform enriches the descriptor, and the store persists it. Both the
        // controller and its bundled unit test reference the shared-lib class ext.usage.Gadget.
        ModuleDescriptor d = ModuleDescriptor.builder()
                .id("gadget-user").version("1.0.0")
                .controllerFqcn("runtime.e2e.GadgetController")
                .componentFqcns(List.of("runtime.e2e.GadgetController"))
                .sources(Map.of("runtime.e2e.GadgetController", """
                        package runtime.e2e;
                        import ext.usage.Gadget;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class GadgetController {
                            @GetMapping("/e2e/gadget") public int g() { return new Gadget().v(); }
                        }
                        """))
                .tests(Map.of("runtime.e2e.GadgetControllerTest", """
                        package runtime.e2e;
                        import ext.usage.Gadget;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertEquals;
                        public class GadgetControllerTest {
                            @Test void v() { assertEquals(7, new Gadget().v()); }
                        }
                        """))
                .build();

        platform.install(d);

        ModuleDescriptor stored = store.load("gadget-user").orElseThrow();
        assertEquals(List.of(new UsedSharedLib("ext-gadget.jar", sha256(JAR))), stored.usedSharedLibs(),
                "the persisted descriptor must record the shared-lib jar the module used (name + sha256)");

        // Durability: the mapping must survive a reconcile (recompile + redeploy from the stored descriptor).
        platform.reconcile();
        assertEquals(List.of(new UsedSharedLib("ext-gadget.jar", sha256(JAR))),
                store.load("gadget-user").orElseThrow().usedSharedLibs(),
                "usedSharedLibs must persist across reconcile");
    }

    private static String sha256(Path p) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
