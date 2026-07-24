/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

import java.util.List;
import java.util.Optional;

/**
 * Durable registry of auto-provision DB scopes (tenants) — name + lifecycle state, surviving restart so reconcile can
 * re-attach modules to their scope. Host-side ({@code @Profile("!worker")}). Separate from {@code ModuleStore} (which
 * persists module descriptors) to keep that contract untouched, but backed by the same {@code module-store.backend}
 * (filesystem | jdbc) so no extra store configuration is needed.
 *
 * <p>Stores metadata only — never the scoped credentials (the password is regenerated on each provision).
 */
public interface ScopeStore {

    /** Upsert a scope record (create or state transition). */
    void save(ScopeRecord scope);

    Optional<ScopeRecord> load(String name);

    List<ScopeRecord> list();

    void remove(String name);
}
