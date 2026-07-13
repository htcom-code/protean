/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import org.htcom.protean.compiler.Generation;

/**
 * Published when the live shared-lib store advances the parent tier to a new {@link Generation} (a put-jar
 * deploy/remove changed the active jar set). Carries both the {@code previous} and the {@code current} generation so
 * a listener can diff them to find which jars changed and drive precise invalidation.
 *
 * @param previous the generation that was current before the publish (may be gen0)
 * @param current  the newly published, now-current generation
 */
public record SharedLibGenerationPublishedEvent(Generation previous, Generation current) {
}
