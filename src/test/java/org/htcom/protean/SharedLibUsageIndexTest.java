/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.UsedSharedLib;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.SharedLibUsageIndex;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reverse index. The forward mapping (module → used shared-lib jars) is inverted so a jar
 * change can find its users. Verified through the real platform: install adds the module under the jars it uses,
 * uninstall removes it, and a module that touches no shared lib never appears.
 */
@SpringBootTest
class SharedLibUsageIndexTest {

    @Autowired ModulePlatform platform;
    @Autowired SharedLibUsageIndex index;

    static Path JAR;

    @DynamicPropertySource
    static void sharedLibDir(DynamicPropertyRegistry registry) throws Exception {
        registry.add("protean.module.shared-lib-dir", buildSharedLibJar()::toString);
    }

    private static Path buildSharedLibJar() throws Exception {
        Path base = Files.createTempDirectory("protean-usage-index-test");
        Path classes = Files.createDirectories(base.resolve("classes"));
        Path src = base.resolve("Cog.java");
        Files.writeString(src, "package ext.idx; public class Cog { public int n() { return 3; } }");
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.toString()) != 0) {
            throw new IllegalStateException("shared lib fixture compilation failed");
        }
        Path libDir = Files.createDirectories(base.resolve("lib"));
        JAR = libDir.resolve("ext-cog.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(JAR))) {
            jos.putNextEntry(new JarEntry("ext/idx/Cog.class"));
            jos.write(Files.readAllBytes(classes.resolve("ext/idx/Cog.class")));
            jos.closeEntry();
        }
        return libDir;
    }

    @AfterEach
    void cleanup() {
        for (String id : List.of("cog-user", "plain")) {
            try {
                platform.uninstall(id);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static ModuleDescriptor usingCog(String id) {
        return ModuleDescriptor.builder()
                .id(id).version("1.0.0")
                .controllerFqcn("runtime.idx." + id.replace("-", "") + ".C")
                .componentFqcns(List.of("runtime.idx." + id.replace("-", "") + ".C"))
                .sources(Map.of("runtime.idx." + id.replace("-", "") + ".C", """
                        package runtime.idx.%s;
                        import ext.idx.Cog;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController public class C {
                            @GetMapping("/idx/%s") public int c() { return new Cog().n(); }
                        }
                        """.formatted(id.replace("-", ""), id)))
                .tests(Map.of("runtime.idx." + id.replace("-", "") + ".CT", """
                        package runtime.idx.%s;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertEquals;
                        public class CT { @Test void n() { assertEquals(3, new ext.idx.Cog().n()); } }
                        """.formatted(id.replace("-", ""))))
                .build();
    }

    @Test
    void install_indexes_and_uninstall_removes() throws Exception {
        UsedSharedLib key = new UsedSharedLib("ext-cog.jar", sha256(JAR));

        platform.install(usingCog("cog-user"));

        assertTrue(index.modulesUsing(key).contains("cog-user"),
                "exact-key lookup (name+sha256) must find the installed user");
        assertTrue(index.modulesUsing("ext-cog.jar").contains("cog-user"),
                "by-name lookup must find the installed user");

        platform.uninstall("cog-user");
        assertFalse(index.modulesUsing(key).contains("cog-user"), "uninstall must drop the module from the index");
        assertTrue(index.modulesUsing("ext-cog.jar").isEmpty(), "no users left → jar key removed");
    }

    @Test
    void module_not_touching_shared_lib_is_absent() throws Exception {
        ModuleDescriptor plain = ModuleDescriptor.builder()
                .id("plain").version("1.0.0")
                .controllerFqcn("runtime.idx.plain.C")
                .componentFqcns(List.of("runtime.idx.plain.C"))
                .sources(Map.of("runtime.idx.plain.C", """
                        package runtime.idx.plain;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController public class C { @GetMapping("/idx/plain") public int c() { return 0; } }
                        """))
                .tests(Map.of("runtime.idx.plain.CT", """
                        package runtime.idx.plain;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertEquals;
                        public class CT { @Test void c() { assertEquals(0, 0); } }
                        """))
                .build();

        platform.install(plain);

        boolean anywhere = index.snapshot().values().stream().anyMatch(s -> s.contains("plain"));
        assertFalse(anywhere, "a module that references no shared-lib class must not appear in the reverse index");
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
