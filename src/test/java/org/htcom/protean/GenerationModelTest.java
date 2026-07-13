/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.Generation;
import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.ModuleSharedLibs;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parent-tier generation model: the {@link ModuleSharedLibs} registry, the
 * current-generation pointer, per-module {@link RuntimeCompiler#boundGeneration binding}, and reference counting for
 * leak-safe close. Uses a {@link ModuleSharedLibs#standalone() standalone} registry (empty gen0, parent = platform
 * CL); the shared-lib-dir gen0 path is exercised for backward compatibility by {@code SharedLib*Test}.
 */
class GenerationModelTest {

    private static final String SRC = """
            package gen.probe;
            public class Probe { public int v() { return 42; } }
            """;

    @Test
    void standalone_registry_has_an_empty_gen0_as_the_current_generation() {
        ModuleSharedLibs registry = ModuleSharedLibs.standalone();

        Generation gen0 = registry.currentGeneration();
        assertEquals(Generation.GEN0, gen0.id());
        assertEquals("", gen0.compileClasspathSuffix(), "empty gen0 adds nothing to the compile classpath");
        assertTrue(gen0.sharedLibIndex().isEmpty(), "empty gen0 carries no shared-lib jars");
        assertSame(ModuleSharedLibs.class.getClassLoader(), gen0.moduleParent(),
                "with no shared lib the module parent is the platform CL");
        assertSame(gen0, registry.generation(Generation.GEN0).orElseThrow(), "gen0 is registered and looked up by id");
        assertTrue(registry.generation(999L).isEmpty(), "an unknown generation id resolves to empty");
    }

    @Test
    void compiling_binds_the_module_to_the_current_generation_and_counts_the_reference() {
        ModuleSharedLibs registry = ModuleSharedLibs.standalone();
        RuntimeCompiler compiler = new RuntimeCompiler(registry);

        ModuleClassLoader loader = compiler.compileAll(Map.of("gen.probe.Probe", SRC), Map.of(), "mod-a");
        assertSame(registry.currentGeneration().moduleParent(), loader.getParent(),
                "the loaded module's parent is the bound generation's CL");
        assertEquals(Generation.GEN0, compiler.boundGeneration("mod-a").orElseThrow());
        assertEquals(1, registry.referenceCount(Generation.GEN0), "one module bound → reference count 1");

        // A second module bound to the same generation adds a reference; recompiling the first is idempotent.
        compiler.compileAll(Map.of("gen.probe.Probe", SRC), Map.of(), "mod-b");
        assertEquals(2, registry.referenceCount(Generation.GEN0));
        compiler.compileAll(Map.of("gen.probe.Probe", SRC), Map.of(), "mod-a");
        assertEquals(2, registry.referenceCount(Generation.GEN0), "rebinding a module to its own generation does not double-count");
    }

    @Test
    void evicting_releases_the_reference_but_gen0_stays_registered_because_it_is_pinned() {
        ModuleSharedLibs registry = ModuleSharedLibs.standalone();
        RuntimeCompiler compiler = new RuntimeCompiler(registry);
        compiler.compileAll(Map.of("gen.probe.Probe", SRC), Map.of(), "mod-a");
        compiler.compileAll(Map.of("gen.probe.Probe", SRC), Map.of(), "mod-b");

        compiler.evict("mod-a");
        assertTrue(compiler.boundGeneration("mod-a").isEmpty(), "an evicted module has no bound generation");
        assertEquals(1, registry.referenceCount(Generation.GEN0), "evict releases exactly that module's reference");

        compiler.evict("mod-b");
        assertEquals(0, registry.referenceCount(Generation.GEN0), "the last module releases the generation");
        assertTrue(registry.generation(Generation.GEN0).isPresent(),
                "gen0 is pinned (app-lifetime) and stays registered even at zero references");
    }

    @Test
    void the_generation_explicit_overload_binds_to_the_given_generation() throws Exception {
        ModuleSharedLibs registry = ModuleSharedLibs.standalone();
        RuntimeCompiler compiler = new RuntimeCompiler(registry);
        Generation current = registry.currentGeneration();

        ModuleClassLoader loader = compiler.compileAll(Map.of("gen.probe.Probe", SRC), Map.of(), "mod-x", current);
        assertEquals(current.id(), compiler.boundGeneration("mod-x").orElseThrow());

        Class<?> probe = loader.loadClass("gen.probe.Probe");
        Object v = probe.getMethod("v").invoke(probe.getDeclaredConstructor().newInstance());
        assertEquals(42, v, "the module compiled against the explicit generation loads and runs");
    }

    @Test
    void an_unloaded_module_has_no_bound_generation() {
        RuntimeCompiler compiler = new RuntimeCompiler(ModuleSharedLibs.standalone());
        assertFalse(compiler.boundGeneration("never-compiled").isPresent());
    }
}
