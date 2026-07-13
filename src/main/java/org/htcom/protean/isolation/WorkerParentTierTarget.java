/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.SharedLibStore;

import java.util.List;
import java.util.Set;

/**
 * A worker strategy whose running workers hold a live parent tier that must be kept in sync with the main.
 * Both the in-process {@link WorkerProcessIsolation} and the OS-isolated
 * {@link ContainerWorkerIsolation} implement this, so the main-side propagators fan a parent-tier change out to every
 * kind of worker uniformly (inject {@code List<WorkerParentTierTarget>}).
 */
public interface WorkerParentTierTarget {

    /**
     * Pushes the current live shared-lib generation to this strategy's running workers and rebinds each worker's
     * modules that use a changed jar (Plan A2 in-place recompile; Plan B sticky on failure). A push to a down/retiring
     * worker is skipped (log-and-continue). No-op when this strategy has no running workers.
     */
    void pushSharedLibGeneration(List<SharedLibStore.IncomingLib> bundle, Set<String> changedJars);

    /**
     * Propagates a library (typed-sharing) update to this strategy's running workers that host it: republishes the
     * library's new sources into each such worker and rebinds the co-located dependents that use it (Plan A2; Plan B
     * sticky on failure). No-op when no running worker hosts the library.
     */
    void propagateLibraryUpdate(ModuleDescriptor library);
}
