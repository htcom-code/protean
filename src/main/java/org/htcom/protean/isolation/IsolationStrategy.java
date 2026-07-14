/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import org.htcom.protean.module.ModuleDescriptor;

/**
 * SPI for module isolation/execution strategies. The library consumer selects the mode.
 *
 * Implementations:
 *  - InProcessIsolation  : same JVM (weak isolation, direct shared-bean access) — initial implementation
 *  - WorkerProcessIsolation : separate JVM (strong isolation)
 *
 * Core contract: each mode has different *capabilities*. Use {@link #supports(ModuleDescriptor)} to judge
 * compatibility up front so incompatible combinations fail fast at deploy time.
 */
public interface IsolationStrategy {

    /** Strategy identifier: "in-process" | "worker" | "container". */
    String mode();

    /** Whether this strategy can run the given module (capability compatibility). If not, deployment is rejected. */
    boolean supports(ModuleDescriptor descriptor);

    /** Deploys the module (compile, load, activate). */
    void deploy(ModuleDescriptor descriptor);

    /**
     * Optionally pre-warms a subsequent {@link #deploy} for this module without activating it (e.g. compile
     * into a shared cache). Called <b>concurrently for distinct modules</b> during startup reconcile so the
     * serial deploy phase can skip the expensive work. Best-effort: the caller swallows exceptions, and the
     * following {@code deploy} surfaces any real error through the normal path. Default: no-op.
     */
    default void prewarm(ModuleDescriptor descriptor) { }

    /** Replaces the same module with a new version with zero downtime (atomic swap). */
    void hotSwap(ModuleDescriptor descriptor);

    /** Takes the module down. */
    void undeploy(String moduleId);

    /**
     * Replaces resources in place (live-reload). Swaps only the resource bytes, with no recompile or context rebuild.
     * Returns true if handled; if this strategy does not support live-reload (e.g. a separate-JVM worker), returns
     * false → the caller falls back to a full update.
     *
     * @return true if a live-reload was performed
     */
    default boolean reloadResources(ModuleDescriptor descriptor) {
        return false;
    }

    /**
     * Retargets a module onto the current parent tier <b>without recompiling</b> and hot-swaps it (Plan A1):
     * used when a library the module {@code uses} republished a binary-compatible generation, so the
     * module's existing bytecode can simply be re-parented onto the new library CL. Returns true if handled; if this
     * strategy cannot retarget without a recompile (e.g. a separate-JVM worker, or a library dependent), returns false
     * → the caller falls back to a recompile (Plan A2).
     *
     * @return true if the retarget hot-swap was performed
     */
    default boolean retargetLibraries(ModuleDescriptor descriptor) {
        return false;
    }
}
