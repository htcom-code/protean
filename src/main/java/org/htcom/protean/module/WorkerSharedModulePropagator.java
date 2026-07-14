/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.isolation.WorkerParentTierTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Propagates a library (typed-sharing) update to worker JVMs. The in-process eager
 * rebind ({@link SharedModuleInvalidator}) reacts to the generation-publish <i>event</i>, which fires <b>before</b> the
 * new descriptor is saved; a worker instead needs the library's new <b>sources</b>, so this reacts to the module-change
 * notification, which fires <b>after</b> the store commit — {@link ModuleStore#load loading} the library then yields
 * the new sources to push. For each library change it asks {@link WorkerParentTierTarget#propagateLibraryUpdate} to
 * republish that library into every worker hosting it and rebind the co-located dependents; a no-op unless a worker
 * actually hosts the library (an initial install, or an in-process-only library with no worker dependents, touches no
 * worker). Gated by the same {@code protean.module.eager-shared-module-invalidation} flag.
 */
@Component
@Profile("!worker")
public class WorkerSharedModulePropagator {

    private static final Logger log = LoggerFactory.getLogger(WorkerSharedModulePropagator.class);

    private final List<WorkerParentTierTarget> targets;
    private final ModuleStore store;
    private final ProteanProperties props;

    public WorkerSharedModulePropagator(ModulePlatform platform, List<WorkerParentTierTarget> targets,
                                        ModuleStore store, ProteanProperties props) {
        this.targets = targets;
        this.store = store;
        this.props = props;
        platform.addChangeListener(this::onModuleChanged);
    }

    /** Post-commit module-change hook: propagate a LIBRARY module's new sources to the workers that host it. */
    private void onModuleChanged(String moduleId) {
        if (!props.getModule().isEagerSharedModuleInvalidation()) {
            return;
        }
        ModuleDescriptor descriptor = store.load(moduleId).orElse(null);
        if (descriptor == null || !descriptor.isLibrary()) {
            return;
        }
        for (WorkerParentTierTarget target : targets) {   // fan out to every worker strategy (process + container)
            try {
                target.propagateLibraryUpdate(descriptor);
            } catch (RuntimeException e) {
                log.warn("worker library propagation failed for '{}' (ignored): {}", moduleId, e.toString());
            }
        }
    }
}
