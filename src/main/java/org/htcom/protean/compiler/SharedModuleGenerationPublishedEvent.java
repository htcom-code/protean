/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

/**
 * Published when a library module publishes a new {@link LibraryGeneration} onto the parent tier (a
 * deploy/update/hot-swap of a {@code kind=LIBRARY} module changed its compiled {@code exports}). Carries both the
 * {@code previous} generation (null on the very first publish) and the {@code current} one so a listener can decide
 * how to move the dependents that were bound to the old generation onto the new one (Plan A1/A2/B).
 *
 * @param libraryId the id of the library module that published
 * @param previous  the generation that was current before this publish, or {@code null} if this is its first
 * @param current   the newly published, now-current generation
 */
public record SharedModuleGenerationPublishedEvent(String libraryId, LibraryGeneration previous,
                                                   LibraryGeneration current) {
}
