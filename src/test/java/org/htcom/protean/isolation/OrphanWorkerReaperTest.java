/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the unclean-exit reaper: a worker JVM orphaned by a prior run (a live JVM carrying the
 * {@code -Dprotean.worker.id=<uuid>} marker plus a leftover marker file) is identified by uuid and force-killed on the
 * next startup; a marker with no matching live process is cleaned up as stale.
 */
class OrphanWorkerReaperTest {

    /** Spawns a real, idle JVM carrying the given worker-id marker on its command line (single-file source launcher). */
    private Process spawnMarkedJvm(Path tmp, UUID id) throws Exception {
        Path src = tmp.resolve("Sleeper.java");
        Files.writeString(src, "public class Sleeper { public static void main(String[] a) throws Exception "
                + "{ Thread.sleep(600000); } }");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(javaBin, OrphanWorkerReaper.markerArg(id), src.toString())
                .redirectErrorStream(true).start();
        // The marker is on the launched java command line immediately; wait only for the process to be observable.
        for (int i = 0; i < 50 && !p.isAlive(); i++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertTrue(p.isAlive(), "marked JVM should be running");
        return p;
    }

    @Test
    void reaps_live_orphan_by_uuid(@TempDir Path dir) throws Exception {
        OrphanWorkerReaper reaper = new OrphanWorkerReaper(dir);
        UUID id = UUID.randomUUID();
        Process orphan = spawnMarkedJvm(dir, id);
        try {
            reaper.record(id, orphan.pid());
            assertTrue(Files.exists(dir.resolve(id.toString())), "marker file recorded");

            int reaped = reaper.reapOrphans();

            assertEquals(1, reaped, "the live orphan is reaped");
            assertTrue(orphan.waitFor(10, TimeUnit.SECONDS), "orphan JVM is killed");
            assertFalse(orphan.isAlive(), "orphan JVM no longer alive");
            assertFalse(Files.exists(dir.resolve(id.toString())), "marker file removed after reap");
        } finally {
            orphan.destroyForcibly();
        }
    }

    @Test
    void cleans_stale_marker_with_no_matching_process(@TempDir Path dir) throws Exception {
        OrphanWorkerReaper reaper = new OrphanWorkerReaper(dir);
        UUID id = UUID.randomUUID();
        // A marker whose recorded PID is dead and whose uuid is on no live command line.
        Files.writeString(dir.resolve(id.toString()), "999999999");

        int reaped = reaper.reapOrphans();

        assertEquals(0, reaped, "nothing live to reap");
        assertFalse(Files.exists(dir.resolve(id.toString())), "stale marker cleaned up");
    }

    @Test
    void forget_removes_marker(@TempDir Path dir) {
        OrphanWorkerReaper reaper = new OrphanWorkerReaper(dir);
        UUID id = UUID.randomUUID();
        reaper.record(id, 12345);
        assertTrue(Files.exists(dir.resolve(id.toString())));

        reaper.forget(id);

        assertFalse(Files.exists(dir.resolve(id.toString())), "forget removes the marker");
    }

    @Test
    void reap_on_empty_or_missing_dir_is_noop(@TempDir Path dir) {
        assertEquals(0, new OrphanWorkerReaper(dir).reapOrphans(), "empty dir → nothing reaped");
        assertEquals(0, new OrphanWorkerReaper(dir.resolve("does-not-exist")).reapOrphans(), "missing dir → nothing reaped");
    }
}
