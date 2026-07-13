/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.isolation.ContainerWorkerIsolation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit check of the container track's JDBC URL host rewrite (localhost -> host) parsing (no Docker). */
class RewriteHostTest {

    @Test
    void rewrites_only_localhost_preserving_port_and_query() {
        // localhost -> rewritten, port/DB/query preserved
        assertEquals("jdbc:postgresql://host.docker.internal:5433/db?currentSchema=mod_a",
                ContainerWorkerIsolation.rewriteHost(
                        "jdbc:postgresql://localhost:5433/db?currentSchema=mod_a", "host.docker.internal"));
        // 127.0.0.1 is rewritten too
        assertEquals("jdbc:mysql://host.docker.internal:3306/mod_b",
                ContainerWorkerIsolation.rewriteHost("jdbc:mysql://127.0.0.1:3306/mod_b", "host.docker.internal"));
        // a real remote host is left unchanged
        assertEquals("jdbc:postgresql://db.internal:5432/db",
                ContainerWorkerIsolation.rewriteHost("jdbc:postgresql://db.internal:5432/db", "host.docker.internal"));
        // when newHost is empty, left unchanged
        assertEquals("jdbc:mysql://localhost:3306/x",
                ContainerWorkerIsolation.rewriteHost("jdbc:mysql://localhost:3306/x", ""));
    }
}
