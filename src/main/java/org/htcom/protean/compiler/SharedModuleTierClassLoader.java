/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

import java.util.Map;

/**
 * Delegating multiplexer for the shared-module parent tier. A dependent
 * module's ClassLoader can have exactly one parent (a JVM constraint), yet a dependent may {@code use} several library
 * modules at once. This tier CL <b>is</b> that single parent: a class in an exported package is routed to the owning
 * library generation's ClassLoader — so the shared type has a <b>single identity</b> across the boundary (no
 * {@code ClassCastException}) — and everything else is delegated parent-first to this CL's own parent (the shared-lib
 * jar generation CL, then the app/platform CL).
 *
 * <p>Per-library independent generations (design decision Y — chosen over an aggregate single CL): each library keeps
 * its own generation lineage and leak-safe close. The delegation table is built per <b>dependent</b> from that
 * dependent's {@code uses} set, so a module that declares no dependency sees no shared package (opt-in isolation is
 * preserved — the default stays full isolation).
 *
 * <p>The library generation CLs behind the table are shared singletons per generation, so all dependents of a library
 * resolve its exported types through the same defining loader (single identity). This CL itself defines nothing.
 */
final class SharedModuleTierClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    /** Exported package name → the library generation ClassLoader that defines that package's classes. */
    private final Map<String, ClassLoader> byPackage;

    SharedModuleTierClassLoader(ClassLoader parent, Map<String, ClassLoader> byPackage) {
        super("protean-shared-module-tier", parent);
        this.byPackage = Map.copyOf(byPackage);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        ClassLoader lib = byPackage.get(packageOf(name));
        if (lib == null) {
            return super.loadClass(name, resolve);   // parent-first for everything not in an exported package
        }
        // Route to the owning library generation CL, which defines the class exactly once (single identity). Guard
        // per-name so concurrent dependents driving the same tier do not race; the library CL is itself
        // parallel-capable and caches its own defined classes.
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = lib.loadClass(name);
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }
}
