/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleDescriptor.ModuleKind;

import java.util.List;
import java.util.Map;

/**
 * Shared fixture for the shared-module route + reconcile matrix tests
 * ({@link InProcessSharedModuleRouteReconcileTest} / {@code Worker…} / {@code Container…}).
 *
 * <p>A {@code kind=LIBRARY} module (in-process — a library publishes exported types into the parent tier)
 * plus a NORMAL consumer whose route links against the library's shared types. The consumer's isolation mode
 * is parameterized so the same scenario runs in-process / worker / container. Route: {@code GET /geo-rt/translate}
 * → {@code at=point(x+10,y+10)}.
 */
final class SharedModuleRouteScenario {

    private SharedModuleRouteScenario() {}

    static final String LIB_ID = "geo-lib-rt";
    static final String CONSUMER_ID = "geo-consumer-rt";
    static final String ROUTE = "/geo-rt/translate";

    /** LIBRARY module — always in-process; exports package {@code geort} (Point/Points). */
    static ModuleDescriptor library() {
        Map<String, String> sources = Map.of(
                "geort.Point", """
                        package geort;
                        public record Point(int x, int y) {
                            public Point translate(int dx, int dy) { return new Point(x + dx, y + dy); }
                        }
                        """,
                "geort.Points", """
                        package geort;
                        public final class Points {
                            private Points() {}
                            public static String format(Point p) { return "point(" + p.x() + "," + p.y() + ")"; }
                        }
                        """);
        Map<String, String> tests = Map.of(
                "geort.PointTest", """
                        package geort;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertEquals;
                        public class PointTest {
                            @Test void t() { assertEquals("point(11,12)", Points.format(new Point(1, 2).translate(10, 10))); }
                        }
                        """);
        return ModuleDescriptor.builder()
                .id(LIB_ID).version("1")
                .kind(ModuleKind.LIBRARY).exports(List.of("geort"))
                .isolationMode("in-process")
                .sources(sources).tests(tests)
                .build();
    }

    /** NORMAL consumer — route uses the shared-module types; isolation mode parameterized. */
    static ModuleDescriptor consumer(String isolationMode) {
        String fqcn = "genrt.GeoRtController";
        Map<String, String> sources = Map.of(fqcn, """
                package genrt;
                import geort.Point;
                import geort.Points;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class GeoRtController {
                    @GetMapping("/geo-rt/translate")
                    public String translate(@RequestParam(defaultValue = "1") int x, @RequestParam(defaultValue = "2") int y) {
                        return "at=" + Points.format(new Point(x, y).translate(10, 10));
                    }
                }
                """);
        Map<String, String> tests = Map.of("genrt.GeoRtControllerTest", """
                package genrt;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                public class GeoRtControllerTest {
                    @Test void shape() { assertTrue(new GeoRtController().translate(1, 2).startsWith("at=point(")); }
                }
                """);
        ModuleDescriptor.Builder b = ModuleDescriptor.builder()
                .id(CONSUMER_ID).version("1")
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn))
                .uses(List.of(LIB_ID))
                .sources(sources).tests(tests);
        if (isolationMode != null) {
            b.isolationMode(isolationMode);
        }
        return b.build();
    }
}
