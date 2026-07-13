/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

import java.net.URLClassLoader;
import java.util.Map;

/**
 * An immutable snapshot of the module <b>parent tier</b> — the classloader and compile classpath a module binds to
 * when it is (re)compiled. A generation is created from a fixed set of shared-lib jars and never mutated; a change to
 * the jar set produces a <i>new</i> generation, because the JVM cannot re-parent an already-created ClassLoader.
 * Modules keep serving on their bound generation until they redeploy
 * onto a newer one — that is what makes a live shared-lib upgrade zero-downtime.
 *
 * <p>{@code gen0} is the boot-time snapshot of {@code protean.module.shared-lib-dir} (backward compatible); later
 * generations accumulate as the runtime lib-store changes (the put-jar surface, a follow-up step). The standalone
 * generation ({@link #standalone()}) carries no jars — parent = platform CL — for tests and the no-shared-lib case.
 *
 * @see ModuleSharedLibs the registry that holds generations and the current pointer
 */
public final class Generation {

    /** The id of the boot-time generation (the {@code shared-lib-dir} scan, or empty when unconfigured). */
    public static final long GEN0 = 0L;

    private final long id;
    private final ClassLoader moduleParent;
    private final String compileClasspathSuffix;
    private final Map<String, UsedSharedLib> sharedLibIndex;
    /** The URLClassLoader backing this generation, or {@code null} when it carries no jars (standalone / empty gen0). */
    private final URLClassLoader ownClassLoader;

    Generation(long id, ClassLoader moduleParent, String compileClasspathSuffix,
               Map<String, UsedSharedLib> sharedLibIndex, URLClassLoader ownClassLoader) {
        this.id = id;
        this.moduleParent = moduleParent;
        this.compileClasspathSuffix = compileClasspathSuffix;
        this.sharedLibIndex = Map.copyOf(sharedLibIndex);
        this.ownClassLoader = ownClassLoader;
    }

    /** A generation with no shared-lib jars: parent = platform CL, empty suffix/index, no own ClassLoader to close. */
    static Generation standalone() {
        return new Generation(GEN0, Generation.class.getClassLoader(), "", Map.of(), null);
    }

    /** This generation's id ({@link #GEN0} for the boot snapshot; monotonically increasing for later ones). */
    public long id() {
        return id;
    }

    /** Parent of a module ClassLoader bound to this generation (the shared lib CL, or the platform CL when empty). */
    public ClassLoader moduleParent() {
        return moduleParent;
    }

    /** Suffix to append to a module compile {@code -classpath} (leading separator included, "" when no jars). */
    public String compileClasspathSuffix() {
        return compileClasspathSuffix;
    }

    /**
     * Index of this generation's shared-lib jars: normalized absolute path → {@link UsedSharedLib}(name, sha256).
     * Empty when the generation carries no jars. The compiler matches the jars a compile opens against these keys
     * (and skips observation entirely when this is empty).
     */
    public Map<String, UsedSharedLib> sharedLibIndex() {
        return sharedLibIndex;
    }

    /** The URLClassLoader owned by this generation, or {@code null} when it carries no jars. Package-private (close). */
    URLClassLoader ownClassLoader() {
        return ownClassLoader;
    }

    @Override
    public String toString() {
        return "gen" + id + "(" + sharedLibIndex.size() + " jars)";
    }
}
