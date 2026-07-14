/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.compiler.Generation;
import org.htcom.protean.isolation.WorkerParentTierTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Worker-mode sibling of {@link SharedLibInvalidator}. When the store publishes a new
 * shared-lib generation, the invalidator rebinds the in-process modules that use a changed jar; this propagator fans
 * the same generation out to the worker JVMs so their parent tier converges too. It pushes the full live store bundle
 * to every worker (so even a pure addition reaches workers that will host a future module using it) and reports which
 * jars changed, so each worker can rebind exactly its affected modules (Plan A2 in-place recompile).
 *
 * <p>Gated by the same {@code protean.module.eager-shared-lib-invalidation} flag. Fresh workers are seeded separately
 * at spawn ({@code WorkerProcessIsolation.seedParentTier}), so this handles the already-running workers.
 */
@Component
@Profile("!worker")
public class WorkerSharedLibPropagator {

    private static final Logger log = LoggerFactory.getLogger(WorkerSharedLibPropagator.class);

    private final List<WorkerParentTierTarget> targets;
    private final SharedLibStore store;
    private final ProteanProperties props;

    public WorkerSharedLibPropagator(List<WorkerParentTierTarget> targets, SharedLibStore store,
                                     ProteanProperties props) {
        this.targets = targets;
        this.store = store;
        this.props = props;
    }

    @EventListener
    public void onGenerationPublished(SharedLibGenerationPublishedEvent event) {
        if (!props.getModule().isEagerSharedLibInvalidation()) {
            log.debug("eager shared-lib invalidation off → workers stay on their generation until they redeploy");
            return;
        }
        Set<String> changedJars = changedJarNames(event.previous(), event.current());
        List<SharedLibStore.IncomingLib> bundle = store.pushBundle();
        for (WorkerParentTierTarget target : targets) {   // fan out to every worker strategy (process + container)
            target.pushSharedLibGeneration(bundle, changedJars);
        }
    }

    /** Jar file names whose content hash changed between the two generations, or that the new one dropped. */
    private static Set<String> changedJarNames(Generation previous, Generation current) {
        Map<String, String> before = shaByName(previous);
        Map<String, String> after = shaByName(current);
        Set<String> changed = new HashSet<>();
        before.forEach((name, sha) -> {
            if (!sha.equals(after.get(name))) {   // different sha (replaced) or absent (removed)
                changed.add(name);
            }
        });
        return changed;   // names only in `after` are additions → no existing worker module uses them
    }

    private static Map<String, String> shaByName(Generation gen) {
        Map<String, String> byName = new HashMap<>();
        gen.sharedLibIndex().values().forEach(lib -> byName.put(lib.name(), lib.sha256()));
        return byName;
    }
}
