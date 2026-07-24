/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

/**
 * An isolation strategy that provisions per-scope workers and can reclaim a scope's runtime footprint. Implemented by
 * the worker/container strategies so the scope-admin path ({@link org.htcom.protean.db package}) can release a scope
 * without depending on the isolation package (one-way: isolation → db).
 */
public interface ScopeReclaimable {

    /**
     * Forgets a scope's cached provisioning so a later deploy re-provisions it from scratch. Modules of the scope must
     * already be undeployed (their empty workers retire on their own); this just drops the strategy's cached
     * {@link DbScope} for the name. No-op if the scope is unknown to this strategy.
     */
    void forgetScope(String scopeName);
}
