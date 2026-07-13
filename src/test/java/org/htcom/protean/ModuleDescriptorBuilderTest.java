/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.module.ModuleDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * record + builder PoC — locks in that the builder produces a descriptor equal to the canonical
 * constructor, that optional-field defaults and compact-constructor normalization
 * (resources null -> empty map), and {@code toBuilder()} functional updates behave as expected.
 */
class ModuleDescriptorBuilderTest {

    @Test
    void builder_matches_canonical_constructor() {
        ModuleDescriptor viaCtor = new ModuleDescriptor(
                "m", "1", ModuleDescriptor.TrustTier.TRUSTED, ModuleDescriptor.DesiredState.ACTIVE,
                "gen.C", List.of("gen.C"), Map.of("gen.C", "src"), Map.of("gen.CT", "test"),
                false, null, "in-process", null, null, null, Map.of(), List.of(),
                ModuleDescriptor.ModuleKind.NORMAL, List.of(), List.of());

        ModuleDescriptor viaBuilder = ModuleDescriptor.builder()
                .id("m").version("1")
                .controllerFqcn("gen.C").componentFqcns(List.of("gen.C"))
                .sources(Map.of("gen.C", "src")).tests(Map.of("gen.CT", "test"))
                .isolationMode("in-process")
                .build();

        // trustTier=TRUSTED, desiredState=ACTIVE, needsSharedBeans=false are builder defaults, so omitting them is equivalent
        assertEquals(viaCtor, viaBuilder);
    }

    @Test
    void builder_defaults_are_idiomatic() {
        ModuleDescriptor d = ModuleDescriptor.builder()
                .id("m").version("1").controllerFqcn("gen.C")
                .build();

        assertEquals(ModuleDescriptor.TrustTier.TRUSTED, d.trustTier());
        assertEquals(ModuleDescriptor.DesiredState.ACTIVE, d.desiredState());
        assertEquals(List.of(), d.componentFqcns());
        assertEquals(Map.of(), d.sources());
        assertEquals(Map.of(), d.tests());
        assertTrue(d.resources().isEmpty(), "resources normalized to an empty map by the compact constructor");
        assertTrue(d.usedSharedLibs().isEmpty(), "usedSharedLibs normalized to an empty list by the compact constructor");
        assertEquals(ModuleDescriptor.ModuleKind.NORMAL, d.kind(), "kind defaults to NORMAL");
        assertTrue(d.exports().isEmpty(), "exports normalized to an empty list");
        assertTrue(d.uses().isEmpty(), "uses normalized to an empty list");
        assertNull(d.isolationMode());
        assertNull(d.bridgedInterfaces());
        assertNull(d.verification());
    }

    @Test
    void toBuilder_roundtrips_and_supports_functional_update() {
        ModuleDescriptor base = ModuleDescriptor.builder()
                .id("m").version("1").controllerFqcn("gen.C")
                .sources(Map.of("gen.C", "src")).tests(Map.of("gen.CT", "t"))
                .isolationMode("worker")
                .build();

        // unchanged round-trip -> equal
        assertEquals(base, base.toBuilder().build());

        // derivative with a single field changed = same semantics as with* (immutability preserved)
        ModuleDescriptor pending = base.toBuilder()
                .desiredState(ModuleDescriptor.DesiredState.PENDING_APPROVAL)
                .build();
        assertEquals(ModuleDescriptor.DesiredState.PENDING_APPROVAL, pending.desiredState());
        assertEquals(base.withDesiredState(ModuleDescriptor.DesiredState.PENDING_APPROVAL), pending);
        // original unchanged
        assertEquals(ModuleDescriptor.DesiredState.ACTIVE, base.desiredState());
    }
}
