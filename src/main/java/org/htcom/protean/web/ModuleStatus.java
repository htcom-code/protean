/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.web;

import org.htcom.protean.module.ModuleDescriptor;

import java.util.List;

/**
 * Module status view for control-plane responses.
 * Omits heavy (or sensitive) descriptor fields such as sources/tests/validation plan, exposing only
 * the metadata an operator needs to identify and manage the module plus the effectively applied
 * isolation mode.
 */
public record ModuleStatus(
        String id,
        String version,
        ModuleDescriptor.TrustTier trustTier,
        ModuleDescriptor.DesiredState desiredState,
        String controllerFqcn,
        String mode,
        boolean needsSharedBeans,
        List<String> bridgedInterfaces,
        Long boundGeneration,
        ModuleDescriptor.ModuleKind kind,
        List<String> exports,
        List<String> uses,
        List<Long> boundLibraryGenerations,
        Long libraryGeneration
) {
    /** Status without any runtime generation binding (jar/library generations null/empty). */
    public static ModuleStatus from(ModuleDescriptor d, String effectiveMode) {
        return from(d, effectiveMode, null, List.of(), null);
    }

    /**
     * Status including the jar parent-tier generation the module's live ClassLoader is bound to; the library fields
     * default to unbound. Kept for callers that only carry the jar generation.
     */
    public static ModuleStatus from(ModuleDescriptor d, String effectiveMode, Long boundGeneration) {
        return from(d, effectiveMode, boundGeneration, List.of(), null);
    }

    /**
     * Full status including both generation bindings (observability): {@code boundGeneration} = the shared-lib jar
     * generation; {@code boundLibraryGenerations} = the library generations a dependent is bound to via {@code uses};
     * {@code libraryGeneration} = a LIBRARY module's own currently published generation. Runtime fields are null/empty
     * when the module is not loaded (INACTIVE/PENDING) or uses/exposes nothing. {@code kind}/{@code exports}/{@code
     * uses} come straight from the descriptor.
     */
    public static ModuleStatus from(ModuleDescriptor d, String effectiveMode, Long boundGeneration,
                                    List<Long> boundLibraryGenerations, Long libraryGeneration) {
        return new ModuleStatus(
                d.id(), d.version(), d.trustTier(), d.desiredState(),
                d.controllerFqcn(), effectiveMode, d.needsSharedBeans(), d.bridgedInterfaces(), boundGeneration,
                d.kind(), d.exports(), d.uses(), boundLibraryGenerations, libraryGeneration);
    }
}
