/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleDescriptor.ModuleKind;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.SharedModuleInvalidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Eager propagation for shared-module typed sharing. When a library republishes, the ACTIVE
 * dependents that {@code use} it move onto the new generation automatically — with no manual redeploy — via
 * <b>Plan A1</b> (binary-compatible → retarget without recompiling), <b>Plan A2</b> (API changed → recompile), or
 * <b>Plan B</b> (recompile/verify failed → the dependent stays on its prior generation, sticky, zero-downtime).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SharedModuleInvalidationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired SharedModuleInvalidator invalidator;
    @Autowired ProteanProperties props;

    static final String LIB = "lib-calc";
    static final String CONSUMER = "mod-calc-consumer";

    /** A library exposing geo.Calc with the given class body. Its test only checks instantiation (version-stable). */
    static Map<String, String> lib(String calcBody) {
        return Map.of("geo.Calc", "package geo;\npublic class Calc {\n" + calcBody + "\n}\n");
    }

    static final Map<String, String> LIB_TEST = Map.of("geo.CalcTest", """
            package geo;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            class CalcTest { @Test void instantiable() { assertNotNull(new Calc()); } }
            """);

    static ModuleDescriptor library(String calcBody, String version) {
        return ModuleDescriptor.builder()
                .id(LIB).version(version).kind(ModuleKind.LIBRARY).exports(List.of("geo"))
                .sources(lib(calcBody)).tests(LIB_TEST)
                .build();
    }

    // Consumer uses only Calc.base(); its test asserts a version-stable property (value is always a multiple of 10),
    // so an A2 recompile against a new library value still passes the gate.
    static final Map<String, String> CONSUMER_SOURCES = Map.of(
            "runtime.calc.CalcConsumer", """
                    package runtime.calc;
                    import geo.Calc;
                    public class CalcConsumer { public int value() { return new Calc().base() * 10; } }
                    """,
            "runtime.calc.CalcController", """
                    package runtime.calc;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class CalcController {
                        @GetMapping("/calc/value") public String value() { return String.valueOf(new CalcConsumer().value()); }
                    }
                    """);

    static final Map<String, String> CONSUMER_TESTS = Map.of("runtime.calc.CalcConsumerTest", """
            package runtime.calc;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class CalcConsumerTest { @Test void multipleOfTen() { assertEquals(0, new CalcConsumer().value() % 10); } }
            """);

    static ModuleDescriptor consumer(String version) {
        return ModuleDescriptor.builder()
                .id(CONSUMER).version(version).kind(ModuleKind.NORMAL).uses(List.of(LIB))
                .controllerFqcn("runtime.calc.CalcController")
                .componentFqcns(List.of("runtime.calc.CalcController"))
                .sources(CONSUMER_SOURCES).tests(CONSUMER_TESTS)
                .build();
    }

    // STRICT gate: asserts the exact value (10 = base()1 * 10), so a value-changing library impl breaks it.
    static final Map<String, String> STRICT_TESTS = Map.of("runtime.calc.CalcConsumerTest", """
            package runtime.calc;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class CalcConsumerTest { @Test void exactlyTen() { assertEquals(10, new CalcConsumer().value()); } }
            """);

    static ModuleDescriptor strictConsumer(String version) {
        return ModuleDescriptor.builder()
                .id(CONSUMER).version(version).kind(ModuleKind.NORMAL).uses(List.of(LIB))
                .controllerFqcn("runtime.calc.CalcController")
                .componentFqcns(List.of("runtime.calc.CalcController"))
                .sources(CONSUMER_SOURCES).tests(STRICT_TESTS)
                .build();
    }

    @AfterEach
    void cleanup() {
        props.getModule().setEagerSharedModuleInvalidation(true);
        for (String id : List.of(CONSUMER, LIB)) {
            try {
                if (platform.find(id).isPresent()) {
                    platform.uninstall(id);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Plan A1: an implementation-only library change (identical public API) auto-propagates without recompiling. */
    @Test
    void binary_compatible_change_propagates_via_planA1() throws Exception {
        platform.install(library("public int base() { return 1; }", "1.0.0"));
        platform.install(consumer("1.0.0"));
        mockMvc.perform(get("/calc/value")).andExpect(status().isOk()).andExpect(content().string("10"));

        long a1Before = invalidator.planA1Count();
        long a2Before = invalidator.planA2Count();
        // Same API (base()), different implementation → binary-compatible → A1 (retarget, no dependent recompile).
        platform.update(library("public int base() { return 2; }", "2.0.0"));

        assertEquals(a1Before + 1, invalidator.planA1Count(), "dependent propagated via Plan A1");
        assertEquals(a2Before, invalidator.planA2Count(), "no recompile happened");
        // The dependent reflects the new library behavior with NO manual redeploy of the dependent.
        mockMvc.perform(get("/calc/value")).andExpect(status().isOk()).andExpect(content().string("20"));
    }

    /** Plan A2: an API change the dependent still compiles against auto-propagates with a recompile. */
    @Test
    void api_change_propagates_via_planA2_recompile() throws Exception {
        // v1 has an extra() public method the dependent does not use.
        platform.install(library("public int base() { return 1; } public int extra() { return 9; }", "1.0.0"));
        platform.install(consumer("1.0.0"));
        mockMvc.perform(get("/calc/value")).andExpect(status().isOk()).andExpect(content().string("10"));

        long a2Before = invalidator.planA2Count();
        // v2 removes extra() (public API no longer a superset) → A2 recompile. base=3 → dependent recompiles fine.
        platform.update(library("public int base() { return 3; }", "2.0.0"));

        assertEquals(a2Before + 1, invalidator.planA2Count(), "dependent propagated via Plan A2 (recompile)");
        mockMvc.perform(get("/calc/value")).andExpect(status().isOk()).andExpect(content().string("30"));
    }

    /** Plan B: an incompatible change that breaks the dependent's source leaves it serving on its prior generation. */
    @Test
    void incompatible_change_leaves_dependent_sticky_planB() throws Exception {
        platform.install(library("public int base() { return 1; }", "1.0.0"));
        platform.install(consumer("1.0.0"));
        mockMvc.perform(get("/calc/value")).andExpect(status().isOk()).andExpect(content().string("10"));

        long bBefore = invalidator.planBCount();
        // v2 renames base() → compute(): not a superset (A2), and the dependent's source references base() so its
        // recompile fails → Plan B. The library update itself still succeeds (the library is self-consistent).
        platform.update(library("public int compute() { return 5; }", "2.0.0"));

        assertEquals(bBefore + 1, invalidator.planBCount(), "dependent fell to Plan B (sticky)");
        // Zero-downtime: the dependent keeps serving on its prior (v1) generation.
        mockMvc.perform(get("/calc/value")).andExpect(status().isOk()).andExpect(content().string("10"));
    }

    /** With eager invalidation off, a library republish does not touch dependents until they redeploy. */
    @Test
    void eager_off_leaves_dependent_on_prior_generation() throws Exception {
        props.getModule().setEagerSharedModuleInvalidation(false);
        platform.install(library("public int base() { return 1; }", "1.0.0"));
        platform.install(consumer("1.0.0"));
        mockMvc.perform(get("/calc/value")).andExpect(status().isOk()).andExpect(content().string("10"));

        long a1Before = invalidator.planA1Count();
        platform.update(library("public int base() { return 2; }", "2.0.0"));

        assertEquals(a1Before, invalidator.planA1Count(), "eager off → no propagation");
        mockMvc.perform(get("/calc/value")).andExpect(status().isOk()).andExpect(content().string("10"));

        // A manual redeploy of the dependent adopts the new generation.
        platform.update(consumer("1.0.1"));
        mockMvc.perform(get("/calc/value")).andExpect(status().isOk()).andExpect(content().string("20"));
    }

    /**
     * PoC: a binary-compatible library impl change that would
     * break a dependent's STRICT gate is caught — Plan A1 now re-runs the dependent's test gate against the new
     * library generation, so it falls to Plan B (sticky) instead of propagating the value-changing impl unverified.
     */
    @Test
    void value_changing_impl_that_breaks_a_strict_dependent_gate_is_sticky_planB() throws Exception {
        platform.install(library("public int base() { return 1; }", "1.0.0"));
        platform.install(strictConsumer("1.0.0"));   // gate asserts value()==10 (base 1 * 10)
        mockMvc.perform(get("/calc/value")).andExpect(status().isOk()).andExpect(content().string("10"));

        long a1Before = invalidator.planA1Count();
        long bBefore = invalidator.planBCount();
        // Same public API (base()), binary-compatible, but base()=2 flips the dependent's value 10 -> 20; its strict
        // gate asserts 10. A1 re-runs that gate against the new library, it fails, so the dependent stays sticky (B).
        platform.update(library("public int base() { return 2; }", "2.0.0"));

        assertEquals(bBefore + 1, invalidator.planBCount(),
                "a value-changing impl that breaks the dependent's gate falls to Plan B (not unverified A1)");
        assertEquals(a1Before, invalidator.planA1Count(), "it did NOT propagate via A1");
        // Zero-downtime: the dependent keeps serving its prior (base=1) value.
        mockMvc.perform(get("/calc/value")).andExpect(status().isOk()).andExpect(content().string("10"));
    }
}
