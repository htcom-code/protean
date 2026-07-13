/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

/**
 * A shared-lib jar that a module's compilation actually referenced (observed while compiling). The key is
 * {@code name + sha256}: the file name alone is ambiguous when the same name carries different content across
 * generations, so the content hash is what lets a consumer decide "did this jar change / did this module use
 * this exact version" without false positives.
 *
 * <p>Produced and stored only. The precise
 * invalidation that consumes it — "jar X changed → rebind/deactivate only the modules that use X" — belongs to
 * a separate track, which builds the jar→module reverse index from these stable keys.
 *
 * @param name   the jar file name (e.g. {@code postgresql-42.7.3.jar})
 * @param sha256 lowercase hex SHA-256 of the jar file content
 */
public record UsedSharedLib(String name, String sha256) {
}
