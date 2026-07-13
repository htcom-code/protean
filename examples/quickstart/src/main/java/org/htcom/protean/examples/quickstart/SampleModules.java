/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.examples.quickstart;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleDescriptor.DesiredState;
import org.htcom.protean.module.ModuleDescriptor.TrustTier;

import java.util.List;
import java.util.Map;

/**
 * <b>Source definitions</b> of the sample modules this example deploys at runtime.
 *
 * <p>Protean modules are source-only: controllers/services/tests are shipped as <b>Java source strings</b>
 * that the server compiles, loads, and registers at runtime. Promotion gate 1 (tests) is enforced by
 * default, so each module bundles a pure unit test (the gate runs it standalone under JUnit — no Spring
 * context required).
 */
final class SampleModules {

    private SampleModules() {}

    // ── Data-access module ───────────────────────────────────────────
    // The host (example app) bundles an H2 DataSource; the parent context's JdbcTemplate is injected parent-first.

    private static final String ITEM_SERVICE = "sample.items.ItemService";
    private static final String ITEM_CONTROLLER = "sample.items.ItemController";
    private static final String ITEM_TEST = "sample.items.ItemTest";

    static ModuleDescriptor dataAccessModule() {
        Map<String, String> sources = Map.of(
                ITEM_SERVICE, """
                        package sample.items;
                        import org.springframework.jdbc.core.JdbcTemplate;
                        import org.springframework.stereotype.Service;
                        @Service
                        public class ItemService {
                            private final JdbcTemplate jdbc;
                            public ItemService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
                            public int addAndCount(String name) {
                                jdbc.execute("create table if not exists sample_item(name varchar(100))");
                                jdbc.update("insert into sample_item(name) values (?)", name);
                                return jdbc.queryForObject("select count(*) from sample_item", Integer.class);
                            }
                            /** Pure function — target of gate 1 unit test. */
                            public static String label(int n) { return "items=" + n; }
                        }
                        """,
                ITEM_CONTROLLER, """
                        package sample.items;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RequestParam;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class ItemController {
                            private final ItemService service;
                            public ItemController(ItemService service) { this.service = service; }
                            @GetMapping("/items/add")
                            public String add(@RequestParam(defaultValue = "widget") String name) {
                                return ItemService.label(service.addAndCount(name));
                            }
                        }
                        """);

        Map<String, String> tests = Map.of(
                ITEM_TEST, """
                        package sample.items;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertEquals;
                        class ItemTest {
                            @Test void label_formats() { assertEquals("items=3", ItemService.label(3)); }
                        }
                        """);

        // builder: isolationMode = global default (unspecified), verification omitted (gate 3 skipped).
        return ModuleDescriptor.builder()
                .id("sample-data-access").version("1")
                .trustTier(TrustTier.TRUSTED).desiredState(DesiredState.ACTIVE)
                .controllerFqcn(ITEM_CONTROLLER)
                .componentFqcns(List.of(ITEM_SERVICE, ITEM_CONTROLLER))
                .sources(sources).tests(tests)
                .needsSharedBeans(false)   // no dependency on shared in-process beans
                .build();
    }

    // ── Compute module (worker demo) ──────────────────────────────────
    // No DB or shared-bean dependency, so it can serve independently in a separate JVM worker.

    private static final String COMPUTE_CONTROLLER = "sample.compute.ComputeController";
    private static final String COMPUTE_TEST = "sample.compute.ComputeTest";

    static ModuleDescriptor computeModule() {
        Map<String, String> sources = Map.of(
                COMPUTE_CONTROLLER, """
                        package sample.compute;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RequestParam;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class ComputeController {
                            /** Pure function — target of gate 1 unit test. */
                            public static int sq(int n) { return n * n; }
                            @GetMapping("/compute/square")
                            public String square(@RequestParam(defaultValue = "7") int n) {
                                return "square=" + sq(n);
                            }
                        }
                        """);

        Map<String, String> tests = Map.of(
                COMPUTE_TEST, """
                        package sample.compute;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertEquals;
                        class ComputeTest {
                            @Test void square_of_seven() { assertEquals(49, ComputeController.sq(7)); }
                        }
                        """);

        // isolationMode unspecified → follows the global protean.isolation.mode (worker under the worker profile).
        return ModuleDescriptor.builder()
                .id("sample-compute").version("1")
                .trustTier(TrustTier.TRUSTED).desiredState(DesiredState.ACTIVE)
                .controllerFqcn(COMPUTE_CONTROLLER)
                .componentFqcns(List.of(COMPUTE_CONTROLLER))
                .sources(sources).tests(tests)
                .needsSharedBeans(false)
                .build();
    }
}
