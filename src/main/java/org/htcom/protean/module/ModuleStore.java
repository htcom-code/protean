/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import java.util.List;
import java.util.Optional;

/**
 * Durable store for module descriptors (including sources and desired-state).
 * After a hard reboot, reconcile reads the ACTIVE modules from here to recompile and redeploy them.
 */
public interface ModuleStore {

    /** write-ahead: called before returning a successful deploy response, persisting durably. */
    void save(ModuleDescriptor descriptor);

    Optional<ModuleDescriptor> load(String moduleId);

    /** All modules with desiredState == ACTIVE (targets of startup reconcile). */
    List<ModuleDescriptor> listActive();

    void remove(String moduleId);

    /** The module's version history (newest first). One entry is appended per save() call. */
    List<ModuleVersion> history(String moduleId);

    /** Loads the descriptor for the given version from history (the most recent one if the version appears multiple times). */
    Optional<ModuleDescriptor> loadVersion(String moduleId, String version);
}
