/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * An immutable snapshot of one <b>library module</b>'s published parent-tier contribution. A library module is
 * source-compiled like any other module, but its
 * activation is not route registration — it is publishing its compiled {@code exports} packages onto the parent tier
 * so dependents ({@code uses}) link against them with a single type identity.
 *
 * <p>Each generation carries two views of the same compiled bytes:
 * <ul>
 *   <li>{@link #jar()} — a temp jar of the compiled classes, appended to a dependent's compile {@code -classpath} so
 *       javac sees the library API (reuses the shared-lib jar classpath machinery).</li>
 *   <li>{@link #classLoader()} — the runtime {@code URLClassLoader} over that jar (parent = the shared-lib jar
 *       generation CL) that <b>defines</b> the exported classes exactly once. Shared across all dependents of this
 *       generation, which is what gives the single type identity.</li>
 * </ul>
 *
 * <p>Per-library independent generations (design decision Y): a change to this library publishes a <i>new</i>
 * generation (the JVM cannot re-parent a live CL), and dependents move onto it by recompiling. The old generation is
 * closed — CL released and temp jar deleted — once no dependent references it (leak-safe close, mirrors
 * {@link ModuleSharedLibs}).
 */
public final class LibraryGeneration {

    private final long id;
    private final String libraryId;
    private final Path jar;
    private final URLClassLoader classLoader;
    private final List<String> exports;
    private final List<String> exportedClasses;
    private final String sha256;

    LibraryGeneration(long id, String libraryId, Path jar, URLClassLoader classLoader,
                      List<String> exports, List<String> exportedClasses, String sha256) {
        this.id = id;
        this.libraryId = libraryId;
        this.jar = jar;
        this.classLoader = classLoader;
        this.exports = List.copyOf(exports);
        this.exportedClasses = List.copyOf(exportedClasses);
        this.sha256 = sha256;
    }

    /** This library generation's id (a monotonic id in the shared-module namespace, distinct from jar generations). */
    public long id() {
        return id;
    }

    /** The id of the library module that published this generation. */
    public String libraryId() {
        return libraryId;
    }

    /** Temp jar of the compiled classes — appended to a dependent's compile classpath. Package-private (lifecycle). */
    Path jar() {
        return jar;
    }

    /** The runtime CL that defines this generation's exported classes (shared across dependents → single identity). */
    ClassLoader classLoader() {
        return classLoader;
    }

    /** The {@code URLClassLoader} backing this generation — for leak-safe close. Package-private. */
    URLClassLoader ownClassLoader() {
        return classLoader;
    }

    /** The packages this library exposes as shared types (from the descriptor's {@code exports}). */
    public List<String> exports() {
        return exports;
    }

    /** FQCNs of the compiled classes that fall in an exported package — the API surface used for A1/A2 compatibility. */
    List<String> exportedClasses() {
        return exportedClasses;
    }

    /** Lowercase-hex SHA-256 of the compiled bytes (identity/idempotency + change detection for invalidation). */
    public String sha256() {
        return sha256;
    }

    @Override
    public String toString() {
        return "libgen" + id + "(" + libraryId + ", exports=" + exports + ")";
    }
}
