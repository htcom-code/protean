/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.db.DbDialect;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Contract test for the {@link DbDialect} default methods added for scope lifecycle: {@code destroyScope} must
 * delegate to a legacy dialect's {@code dropScope} (backward compatible), and {@code detachScope} must throw by default
 * (a custom dialect never silently falls through to a data-destroying drop).
 */
class DbDialectContractTest {

    /** Minimal dialect implementing only the pre-existing methods (no detach/destroy overrides). */
    static final class LegacyDialect implements DbDialect {
        String dropped;

        @Override
        public String id() {
            return "legacy";
        }

        @Override
        public int maxNameLength() {
            return 63;
        }

        @Override
        public void createScope(JdbcTemplate admin, String name, String password) {
        }

        @Override
        public void dropScope(JdbcTemplate admin, String name) {
            this.dropped = name;
        }

        @Override
        public String scopedUrl(String adminUrl, String name) {
            return adminUrl;
        }
    }

    @Test
    void destroyScope_defaults_to_dropScope() {
        LegacyDialect d = new LegacyDialect();
        d.destroyScope(null, "scope-1");
        assertEquals("scope-1", d.dropped, "destroy must delegate to the legacy full drop");
    }

    @Test
    void detachScope_throws_by_default() {
        LegacyDialect d = new LegacyDialect();
        assertThrows(UnsupportedOperationException.class, () -> d.detachScope(null, "scope-1"),
                "a dialect that does not implement detach must not silently drop data");
    }
}
