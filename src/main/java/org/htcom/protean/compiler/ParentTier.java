/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete parent tier a module is compiled and loaded against: the shared-lib jar {@link Generation} plus the
 * {@link LibraryGeneration library generations} the module {@code uses}. The jar generation supplies
 * drivers/third-party jars; the library generations supply typed shared code. A module
 * with no {@code uses} has an empty library set and behaves exactly as before (the jar generation alone).
 *
 * <p>This bundles the three things the compiler needs so both are threaded together and stay consistent:
 * <ul>
 *   <li>{@link #compileClasspathSuffix()} — jar suffix + each library's temp jar (so javac sees the library API).</li>
 *   <li>{@link #moduleParent()} — the ClassLoader parent for the module: the jar generation CL when there are no
 *       libraries, otherwise a {@link SharedModuleTierClassLoader delegating multiplexer} routing exported packages
 *       to the owning library CLs (single type identity) and everything else parent-first to the jar generation.</li>
 *   <li>{@link #bindingKey()} — the compile-cache key: recompilation is required when the jar generation <i>or</i> any
 *       used library generation advances.</li>
 * </ul>
 */
public final class ParentTier {

    private final Generation jarGen;
    /** Library generations this tier binds to, sorted by library id for a deterministic {@link #bindingKey()}. */
    private final List<LibraryGeneration> libraries;

    ParentTier(Generation jarGen, List<LibraryGeneration> libraries) {
        this.jarGen = jarGen;
        this.libraries = libraries.stream()
                .sorted((a, b) -> a.libraryId().compareTo(b.libraryId()))
                .toList();
    }

    /** A tier with no library dependencies — the plain shared-lib jar generation (backward-compatible path). */
    public static ParentTier of(Generation jarGen) {
        return new ParentTier(jarGen, List.of());
    }

    Generation jarGeneration() {
        return jarGen;
    }

    List<LibraryGeneration> libraries() {
        return libraries;
    }

    boolean hasLibraries() {
        return !libraries.isEmpty();
    }

    /** Suffix appended to a module compile {@code -classpath}: the jar generation suffix plus each library temp jar. */
    String compileClasspathSuffix() {
        String suffix = jarGen.compileClasspathSuffix();
        if (libraries.isEmpty()) {
            return suffix;
        }
        StringBuilder sb = new StringBuilder(suffix);
        for (LibraryGeneration lib : libraries) {
            sb.append(File.pathSeparator).append(lib.jar());
        }
        return sb.toString();
    }

    /**
     * The parent ClassLoader for a module bound to this tier. With no libraries it is the jar generation's own parent
     * (unchanged). With libraries it is a fresh {@link SharedModuleTierClassLoader} whose parent is that same jar
     * generation CL and whose delegation table maps each library's exported packages to its runtime CL — so exported
     * types resolve to a single defining loader and everything else stays parent-first.
     */
    ClassLoader moduleParent() {
        if (libraries.isEmpty()) {
            return jarGen.moduleParent();
        }
        Map<String, ClassLoader> byPackage = new HashMap<>();
        for (LibraryGeneration lib : libraries) {
            for (String pkg : lib.exports()) {
                byPackage.put(pkg, lib.classLoader());
            }
        }
        return new SharedModuleTierClassLoader(jarGen.moduleParent(), byPackage);
    }

    /**
     * The compile-cache key for a module bound to this tier: the jar generation id, plus each used library id and its
     * generation id. Bytecode compiled under one key must not be reused under another — a jar-generation change or any
     * used-library republish yields a different key, forcing a recompile against the new parent tier (the conservative
     * always-recompile behavior; the retarget-without-recompile fast-path is the invalidation-milestone follow-up).
     */
    String bindingKey() {
        if (libraries.isEmpty()) {
            return Long.toString(jarGen.id());
        }
        StringBuilder sb = new StringBuilder(Long.toString(jarGen.id()));
        for (LibraryGeneration lib : libraries) {
            sb.append('|').append(lib.libraryId()).append(':').append(lib.id());
        }
        return sb.toString();
    }
}
