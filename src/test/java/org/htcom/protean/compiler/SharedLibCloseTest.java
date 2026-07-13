/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leak-safe generation unload: {@link ModuleSharedLibs#closeUnreferenced()} closes only
 * non-current, non-pinned, zero-reference generations, and closing deregisters the JDBC drivers the generation's
 * ClassLoader loaded — the hard reference that originally forced the shared lib to be an application-lifetime
 * singleton. (In the {@code compiler} package to reach the package-private driver-deregister method: {@link
 * DriverManager}'s caller-classloader filter blocks observing a child-CL driver from a test's own classloader.)
 */
class SharedLibCloseTest {

    @Test
    void closeUnreferenced_unloads_only_free_non_current_non_pinned_generations() {
        ModuleSharedLibs reg = ModuleSharedLibs.standalone();   // gen0 (pinned, current)

        Generation g1 = reg.publishGeneration(List.of());       // now current
        reg.retain(g1.id(), "m1");
        Generation g2 = reg.publishGeneration(List.of());       // g1 no longer current

        // g1 is non-current but still referenced by m1 → not closed.
        assertEquals(0, reg.closeUnreferenced());
        assertTrue(reg.generation(g1.id()).isPresent());

        // Release m1 → g1 has zero references and is non-current → closed.
        reg.release(g1.id(), "m1");
        assertEquals(1, reg.closeUnreferenced());
        assertTrue(reg.generation(g1.id()).isEmpty(), "a free non-current generation is unloaded");

        // The current generation (g2, zero refs) and pinned gen0 are never closed.
        assertTrue(reg.generation(g2.id()).isPresent(), "the current generation is never closed even at zero refs");
        assertTrue(reg.generation(Generation.GEN0).isPresent(), "gen0 is pinned");
        assertTrue(reg.isPinned(Generation.GEN0));
    }

    @Test
    void deregister_drivers_removes_only_the_generations_own_drivers_via_caller_cl_trick() throws Exception {
        Path jar = fakeDriverJar();
        URLClassLoader genCl = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, ModuleSharedLibs.class.getClassLoader());
        try {
            // Classic leak: DriverManager now holds a hard reference to a class loaded by the generation CL.
            Driver driver = (Driver) Class.forName("ext.driver.FakeDriver", true, genCl)
                    .getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(driver);

            // The Tomcat-style helper (defined into a child of genCl) sees and deregisters exactly that driver.
            assertEquals(1, ModuleSharedLibs.deregisterDrivers(genCl),
                    "the generation's JDBC driver is deregistered (leak released)");
            assertEquals(0, ModuleSharedLibs.deregisterDrivers(genCl),
                    "idempotent — nothing left to deregister");
        } finally {
            ModuleSharedLibs.deregisterDrivers(genCl);   // best-effort: never leave a stray driver registered
            genCl.close();
        }
    }

    /** A jar with {@code ext.driver.FakeDriver implements java.sql.Driver} (all methods stubbed). */
    private static Path fakeDriverJar() throws Exception {
        Path base = Files.createTempDirectory("protean-driver");
        Path src = base.resolve("FakeDriver.java");
        Files.writeString(src, """
                package ext.driver;
                import java.sql.*;
                import java.util.Properties;
                import java.util.logging.Logger;
                public class FakeDriver implements Driver {
                    public Connection connect(String url, Properties info) { return null; }
                    public boolean acceptsURL(String url) { return false; }
                    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
                    public int getMajorVersion() { return 1; }
                    public int getMinorVersion() { return 0; }
                    public boolean jdbcCompliant() { return false; }
                    public Logger getParentLogger() { return Logger.getGlobal(); }
                }
                """);
        Path out = Files.createDirectories(base.resolve("classes"));
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(), src.toString()) != 0) {
            throw new IllegalStateException("fake driver compile failed");
        }
        Path jar = base.resolve("fake-driver.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("ext/driver/FakeDriver.class"));
            jos.write(Files.readAllBytes(out.resolve("ext/driver/FakeDriver.class")));
            jos.closeEntry();
        }
        return jar;
    }
}
