/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.compiler.SharedModuleGenerationPublishedEvent;
import org.htcom.protean.compiler.SharedModuleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Eager propagation for shared-module typed sharing. When a library module republishes its
 * generation, this rebinds exactly the ACTIVE dependents that {@code use} it — found via the reverse index
 * {@link SharedModuleUsageIndex}, never a full scan — onto the new generation. Dependents of an unrelated library are
 * untouched (the point of the index). Mirror of {@link SharedLibInvalidator} for the jar track.
 *
 * <p>Per dependent, one of three plans:
 * <ul>
 *   <li><b>Plan A1</b> ({@link ModulePlatform#rebindFast}) — the library change is binary-compatible
 *       ({@link SharedModuleRegistry#isApiSuperset}), so the dependent is retargeted onto the new library CL
 *       <b>without recompiling</b> (delegation retarget only; the cheapest path).</li>
 *   <li><b>Plan A2</b> ({@link ModulePlatform#rebind}) — an exported API changed (or A1 does not apply), so the
 *       dependent is recompiled against the new generation and hot-swapped.</li>
 *   <li><b>Plan B</b> — A2 recompile/verify failed: the dependent keeps serving on its prior generation (sticky) and
 *       the failure is logged loudly. A silent no-op or a hard deactivate would violate the zero-downtime principle.</li>
 * </ul>
 *
 * <p>Eager propagation is the default and can be turned off with
 * {@code protean.module.shared-lib.eager-shared-module-invalidation=false} (dependents then adopt the new generation
 * only when they next redeploy).
 */
@Component
@Profile("!worker")
public class SharedModuleInvalidator {

    private static final Logger log = LoggerFactory.getLogger(SharedModuleInvalidator.class);

    private final ModulePlatform platform;
    private final SharedModuleUsageIndex usageIndex;
    private final SharedModuleRegistry registry;
    private final ProteanProperties props;

    // Propagation-plan counters (observability): how many dependents each plan handled.
    private final LongAdder planA1 = new LongAdder();
    private final LongAdder planA2 = new LongAdder();
    private final LongAdder planB = new LongAdder();

    public SharedModuleInvalidator(ModulePlatform platform, SharedModuleUsageIndex usageIndex,
                                   SharedModuleRegistry registry, ProteanProperties props) {
        this.platform = platform;
        this.usageIndex = usageIndex;
        this.registry = registry;
        this.props = props;
    }

    @EventListener
    public void onGenerationPublished(SharedModuleGenerationPublishedEvent event) {
        if (!props.getModule().isEagerSharedModuleInvalidation()) {
            log.debug("eager shared-module invalidation off → dependents stay on their generation until they redeploy");
            return;
        }
        if (event.previous() == null) {
            return;   // first publish of this library — no dependent was bound to an older generation
        }
        Set<String> targets = usageIndex.dependentsOf(event.libraryId());
        if (targets.isEmpty()) {
            return;
        }
        boolean compatible = registry.isApiSuperset(event.previous(), event.current());
        log.info("library '{}' generation {} published: propagating to {} dependent(s) (binary-compatible={})",
                event.libraryId(), event.current().id(), targets.size(), compatible);
        for (String moduleId : targets) {
            try {
                if (compatible && platform.rebindFast(moduleId)) {
                    planA1.increment();   // Plan A1: retargeted without recompiling
                    continue;
                }
                platform.rebind(moduleId);   // Plan A2: recompile against the new generation
                planA2.increment();
            } catch (RuntimeException e) {
                // Plan B (sticky): the dependent keeps serving on its prior generation. Surface loudly — a hard
                // deactivate would violate zero-downtime, and a silent no-op would hide a stuck dependent.
                planB.increment();
                log.warn("shared-module propagation failed for '{}' — Plan B: it stays on its prior library "
                        + "generation; fix and redeploy to move it forward. cause: {}", moduleId, e.toString());
            }
        }
        // Safe point: every rebind's swap has committed, so library generations the rebinds vacated can be unloaded.
        platform.closeUnreferencedGenerations();
    }

    /** Count of dependents propagated via Plan A1 (retarget, no recompile) since startup (observability/tests). */
    public long planA1Count() {
        return planA1.sum();
    }

    /** Count of dependents propagated via Plan A2 (recompile) since startup (observability/tests). */
    public long planA2Count() {
        return planA2.sum();
    }

    /** Count of dependents that fell to Plan B (sticky — propagation failed) since startup (observability/tests). */
    public long planBCount() {
        return planB.sum();
    }
}
