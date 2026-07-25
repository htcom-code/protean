/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import java.util.List;

/**
 * The parent-tier generations a module's live ClassLoader is bound to, as observed by the JVM that loaded it.
 *
 * <p>For an in-process module the main JVM is that JVM, so the main's compiler already knows this. A worker- or
 * container-isolated module instead compiles and links inside its own JVM: the main can see which generation it
 * <i>pushed</i>, but not which one the module ended up on — and those differ exactly when it matters, because a
 * dependent whose test gate fails on rebind stays on its prior generation (Plan B). So the hosting runtime reports
 * this back over the control plane and the main serves it on the admin surface.
 *
 * @param boundGeneration          the shared-lib (jar) parent-tier generation id, or null when the module is not loaded
 * @param boundLibraryGenerations  the library generations bound via {@code uses}, empty when it uses none
 */
public record ModuleBindings(Long boundGeneration, List<Long> boundLibraryGenerations) {

    public ModuleBindings {
        boundLibraryGenerations = boundLibraryGenerations == null ? List.of() : List.copyOf(boundLibraryGenerations);
    }
}
