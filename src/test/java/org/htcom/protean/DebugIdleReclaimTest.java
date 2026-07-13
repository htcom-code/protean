/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.mcp.debug.DebugCore;
import org.htcom.protean.mcp.debug.DebugSession;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Idle auto-reclaim (leak-guard): verifies that the {@link DebugCore} sweeper automatically
 * {@code terminate}s a session that exceeds the idle threshold, running its dispose hooks (e.g. killing a
 * debug-launch worker). Attaches to a real target JVM launched with JDWP, then leaves it untouched with a
 * short idle-timeout to confirm reclamation. No Docker required.
 */
class DebugIdleReclaimTest {

    private static final Pattern LISTENING = Pattern.compile("address:\\s*(\\d+)");

    @Test
    void idle_session_is_auto_reclaimed_and_runs_dispose_hook() throws Exception {
        Process target = spawnJdwpTarget();
        try {
            int port = readListeningPort(target);
            assertTrue(port > 0, "parse the JDWP listening port");

            DebugCore core = new DebugCore(500);   // 500ms idle -> sweeper reclaims soon
            try {
                DebugSession session = core.attach("127.0.0.1", port);
                String id = session.id();
                CountDownLatch reclaimed = new CountDownLatch(1);
                session.onDispose(reclaimed::countDown);   // stands in for the debug-launch worker cleanup hook

                // Leave untouched -> the sweeper reclaims it and runs the dispose hook
                assertTrue(reclaimed.await(10, TimeUnit.SECONDS),
                        "an idle session should be auto-reclaimed and run its dispose hook");
                assertNull(core.session(id), "session deregistered after reclaim");
            } finally {
                core.shutdown();
            }
        } finally {
            target.destroyForcibly();
            target.waitFor();
        }
    }

    @Test
    void touched_session_survives_within_window() throws Exception {
        Process target = spawnJdwpTarget();
        try {
            int port = readListeningPort(target);
            DebugCore core = new DebugCore(1500);   // 1.5s idle, sweep ~1s
            try {
                DebugSession session = core.attach("127.0.0.1", port);
                AtomicBoolean disposed = new AtomicBoolean(false);
                session.onDispose(() -> disposed.set(true));

                // Touch every 1s for ~3s -> the idle timer keeps resetting, so it is not reclaimed
                for (int i = 0; i < 3; i++) {
                    Thread.sleep(1000);
                    core.session(session.id());   // touch
                }
                assertFalse(disposed.get(), "a periodically touched session should not be reclaimed");
                assertTrue(core.session(session.id()) != null, "session retained");
            } finally {
                core.shutdown();
            }
        } finally {
            target.destroyForcibly();
            target.waitFor();
        }
    }

    private Process spawnJdwpTarget() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
                "-cp", System.getProperty("java.class.path"),
                "org.htcom.mcpext.DebugLoopTarget");
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private int readListeningPort(Process target) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(target.getInputStream(), StandardCharsets.UTF_8));
        String line;
        for (int i = 0; i < 50 && (line = reader.readLine()) != null; i++) {
            Matcher m = LISTENING.matcher(line);
            if (line.contains("Listening for transport") && m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return -1;
    }
}
