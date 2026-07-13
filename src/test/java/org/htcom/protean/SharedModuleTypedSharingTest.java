/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.SharedModuleRegistry;
import org.htcom.protean.module.ModuleContainer;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleDescriptor.ModuleKind;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of shared-module <b>typed code sharing</b>: a {@code kind=LIBRARY}
 * module exposes a package ({@code geo}) on the parent tier, and a normal module that {@code uses} it compiles and runs
 * against the library's types with a <b>single type identity</b> — a library-produced {@code geo.Point} assigned to a
 * dependent-typed {@code geo.Point} local never throws {@code ClassCastException}. The live-swap leg re-publishes the
 * library and shows the dependent adopts the new generation on its next (re)deploy (the compile cache is keyed by the
 * parent tier, so a library republish forces a recompile).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SharedModuleTypedSharingTest {

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired ModuleContainer container;
    @Autowired SharedModuleRegistry sharedModules;

    static final String LIB = "lib-geometry";
    static final String CONSUMER = "mod-geo-consumer";

    /** Library v1: {@code geo.Points.origin()} = (0,0). */
    static Map<String, String> librarySources(int ox, int oy) {
        return Map.of(
                "geo.Point", """
                        package geo;
                        public class Point {
                            public final int x, y;
                            public Point(int x, int y) { this.x = x; this.y = y; }
                            public String describe() { return "point(" + x + "," + y + ")"; }
                        }
                        """,
                "geo.Points", """
                        package geo;
                        public class Points {
                            public static Point origin() { return new Point(%d, %d); }
                        }
                        """.formatted(ox, oy));
    }

    /** Version-independent library test (asserts the describe() format, not the origin value). */
    static final Map<String, String> LIBRARY_TESTS = Map.of(
            "geo.PointTest", """
                    package geo;
                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    class PointTest {
                        @Test void describes() { assertEquals("point(1,2)", new Point(1, 2).describe()); }
                    }
                    """);

    static final Map<String, String> CONSUMER_SOURCES = Map.of(
            "runtime.geo.PointFormatter", """
                    package runtime.geo;
                    import geo.Point;
                    import geo.Points;
                    public class PointFormatter {
                        // Cross-boundary: a library-produced Point assigned to a dependent-typed Point local. If the
                        // dependent's geo.Point differed in identity from the library's, this line would CCE at runtime.
                        public String origin() { Point p = Points.origin(); return "consumer:" + p.describe(); }
                        public String at(int x, int y) { return "consumer:" + new Point(x, y).describe(); }
                    }
                    """,
            "runtime.geo.GeoController", """
                    package runtime.geo;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RequestParam;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class GeoController {
                        private final PointFormatter fmt = new PointFormatter();
                        @GetMapping("/sharedmodule/origin") public String origin() { return fmt.origin(); }
                        @GetMapping("/sharedmodule/at")
                        public String at(@RequestParam("x") int x, @RequestParam("y") int y) { return fmt.at(x, y); }
                    }
                    """);

    static final Map<String, String> CONSUMER_TESTS = Map.of(
            "runtime.geo.PointFormatterTest", """
                    package runtime.geo;
                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    class PointFormatterTest {
                        @Test void formatsAcrossBoundary() { assertEquals("consumer:point(3,4)", new PointFormatter().at(3, 4)); }
                    }
                    """);

    static ModuleDescriptor library(int ox, int oy, String version) {
        return ModuleDescriptor.builder()
                .id(LIB).version(version).kind(ModuleKind.LIBRARY)
                .exports(List.of("geo"))
                .sources(librarySources(ox, oy)).tests(LIBRARY_TESTS)
                .build();
    }

    static ModuleDescriptor consumer(String version) {
        return ModuleDescriptor.builder()
                .id(CONSUMER).version(version).kind(ModuleKind.NORMAL)
                .uses(List.of(LIB))
                .controllerFqcn("runtime.geo.GeoController")
                .componentFqcns(List.of("runtime.geo.GeoController"))
                .sources(CONSUMER_SOURCES).tests(CONSUMER_TESTS)
                .build();
    }

    @AfterEach
    void cleanup() {
        for (String id : List.of(CONSUMER, LIB)) {
            try {
                if (platform.find(id).isPresent()) {
                    platform.uninstall(id);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Test
    void dependent_compiles_and_runs_against_library_types_with_single_identity() throws Exception {
        // 1. Publish the library — its activation is a parent-tier generation, not a route.
        platform.install(library(0, 0, "1.0.0"));
        assertTrue(sharedModules.currentGeneration(LIB).isPresent(), "library published a generation");

        // 2. Deploy the dependent that `uses` the library. Success requires geo.Point to resolve at BOTH compile
        //    (library temp jar on the dependent classpath) and runtime (multiplexer → library CL).
        platform.install(consumer("1.0.0"));
        mockMvc.perform(get("/sharedmodule/origin")).andExpect(status().isOk())
                .andExpect(content().string("consumer:point(0,0)"));
        mockMvc.perform(get("/sharedmodule/at").param("x", "3").param("y", "4")).andExpect(status().isOk())
                .andExpect(content().string("consumer:point(3,4)"));

        // 3. Single type identity: the geo.Point the dependent's ClassLoader resolves IS the one the library CL defines.
        ClassLoader depLoader = container.currentLoader(CONSUMER);
        ClassLoader libLoader = sharedModules.runtimeClassLoader(LIB);
        assertNotNull(depLoader);
        assertNotNull(libLoader);
        assertSame(libLoader.loadClass("geo.Point"), depLoader.loadClass("geo.Point"),
                "geo.Point must be a single Class across the library boundary (no ClassCastException)");

        // 4. Live-swap: re-publish the library with new behavior (origin → (9,9)), then redeploy the dependent
        //    (identical source). The compile cache is keyed by the parent tier, so the new library generation forces a
        //    recompile and the dependent adopts the new behavior — with identity still single.
        platform.update(library(9, 9, "2.0.0"));
        platform.update(consumer("2.0.0"));
        mockMvc.perform(get("/sharedmodule/origin")).andExpect(status().isOk())
                .andExpect(content().string("consumer:point(9,9)"));

        // 5. Teardown removes the routes.
        platform.uninstall(CONSUMER);
        mockMvc.perform(get("/sharedmodule/origin")).andExpect(status().isNotFound());
    }
}
