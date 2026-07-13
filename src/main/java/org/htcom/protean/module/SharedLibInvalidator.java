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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Precise invalidation hook. When a shared-lib generation is published, it diffs the
 * previous and current generations to find which jars actually changed (or were removed), asks the reverse index
 * {@link SharedLibUsageIndex#modulesUsing(String) who uses them}, and eagerly rebinds exactly those ACTIVE modules
 * onto the new generation — modules that use none of the changed jars are never touched (the point of the index).
 *
 * <p><b>Plan A</b> = {@link ModulePlatform#rebind}. <b>Plan B</b> (rebind failed): the module keeps serving on its
 * prior generation (sticky — for the dominant incompatible-jar case the failure is pre-swap, so it is truly
 * zero-downtime) and the failure is logged loudly — never a silent no-op. Eager propagation is the default
 * (zero-downtime principle) and can be turned off with {@code protean.module.shared-lib.eager-invalidation=false}.
 */
@Component
@Profile("!worker")
public class SharedLibInvalidator {

    private static final Logger log = LoggerFactory.getLogger(SharedLibInvalidator.class);

    private final ModulePlatform platform;
    private final SharedLibUsageIndex usageIndex;
    private final ProteanProperties props;

    public SharedLibInvalidator(ModulePlatform platform, SharedLibUsageIndex usageIndex, ProteanProperties props) {
        this.platform = platform;
        this.usageIndex = usageIndex;
        this.props = props;
    }

    @EventListener
    public void onGenerationPublished(SharedLibGenerationPublishedEvent event) {
        if (!props.getModule().isEagerSharedLibInvalidation()) {
            log.debug("eager shared-lib invalidation off → modules stay on their generation until they redeploy");
            return;
        }
        Set<String> changedJars = changedJarNames(event.previous(), event.current());
        if (changedJars.isEmpty()) {
            return;   // only new jars were added — no existing module uses them
        }
        Set<String> targets = new LinkedHashSet<>();
        for (String jar : changedJars) {
            targets.addAll(usageIndex.modulesUsing(jar));
        }
        if (targets.isEmpty()) {
            log.debug("shared-lib generation {} published: {} jar(s) changed, no ACTIVE module uses them",
                    event.current().id(), changedJars.size());
            return;
        }
        log.info("shared-lib generation {} published: rebinding {} module(s) using {} changed jar(s) {}",
                event.current().id(), targets.size(), changedJars.size(), changedJars);
        for (String moduleId : targets) {
            try {
                platform.rebind(moduleId);   // Plan A
            } catch (RuntimeException e) {
                // Plan B (sticky): the module keeps serving on its prior generation. Surface loudly — a hard
                // deactivate would violate zero-downtime, and a silent no-op would hide a stuck module.
                log.warn("shared-lib rebind failed for module '{}' — Plan B: it stays on its prior generation; "
                        + "fix and redeploy to move it forward. cause: {}", moduleId, e.toString());
            }
        }
        // Safe point: every rebind's container swap has committed, so generations the rebinds vacated can now be
        // unloaded (leak-safe close). A generation still referenced by a sticky Plan B module is left intact.
        platform.closeUnreferencedGenerations();
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
        return changed;   // names only in `after` are additions → no existing users, skipped
    }

    private static Map<String, String> shaByName(Generation gen) {
        Map<String, String> byName = new HashMap<>();
        gen.sharedLibIndex().values().forEach(lib -> byName.put(lib.name(), lib.sha256()));
        return byName;
    }
}
