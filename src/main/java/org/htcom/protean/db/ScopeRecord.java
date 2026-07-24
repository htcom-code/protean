/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

/**
 * A registered auto-provision DB scope (tenant). Persisted metadata only — <b>no secrets</b>: the scoped user's
 * password is regenerated on each (re-)provision, never stored.
 *
 * @param name      stable scope identifier (also the sanitized DB/schema/user name)
 * @param state     lifecycle state — see {@link State}
 * @param dialectId provisioning dialect id at creation (e.g. {@code mysql}), for informational/status use
 */
public record ScopeRecord(String name, State state, String dialectId) {

    /**
     * ACTIVE — provisioned, accepts deploys. CLOSED — provisioned but no new deploys (soft-close). DETACHED — dormant:
     * the scoped user/role was dropped and workers retired, but the database/schema and its data are retained.
     */
    public enum State { ACTIVE, CLOSED, DETACHED }
}
