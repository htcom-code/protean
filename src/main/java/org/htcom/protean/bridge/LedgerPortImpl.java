/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Main-side @Transactional shared bean. Because the bridge calls the {@code getBean(LedgerPort)} proxy,
 * each RPC call runs within main's transaction boundary — on an exception, that call's writes roll back.
 */
@Component
@Profile("!worker")
public class LedgerPortImpl implements LedgerPort {

    private final JdbcTemplate jdbc;

    public LedgerPortImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS ledger (name VARCHAR(255), amount INT)");
    }

    @Override
    @Transactional
    public void record(String name, int amount) {
        jdbc.update("INSERT INTO ledger(name, amount) VALUES (?, ?)", name, amount);
    }

    @Override
    @Transactional
    public void recordThenFail(String name, int amount) {
        jdbc.update("INSERT INTO ledger(name, amount) VALUES (?, ?)", name, amount);
        throw new IllegalStateException("intentional failure — this insert must roll back: " + name);
    }

    @Override
    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ledger", Integer.class);
        return n == null ? 0 : n;
    }
}
