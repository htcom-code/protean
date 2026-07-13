/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

/** Demo interface for RPC bridge transaction boundaries — the worker calls a main-side @Transactional bean. */
public interface LedgerPort {

    /** Inserts one row (normal commit). */
    void record(String name, int amount);

    /** Inserts one row then throws — since it runs in the same transaction, it rolls back and must leave no trace. */
    void recordThenFail(String name, int amount);

    int count();
}
