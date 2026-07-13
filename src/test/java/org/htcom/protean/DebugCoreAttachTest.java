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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link DebugCore} attaches a JDI socket to a real JVM launched with JDWP, queries the
 * VM, then cleans up the session. No Spring context and no Docker required (pure JDI plus a short-lived
 * child JVM). Drives the transport-independent core directly.
 */
class DebugCoreAttachTest {

    private static final Pattern LISTENING = Pattern.compile("address:\\s*(\\d+)");

    @Test
    void attaches_to_jdwp_jvm_and_terminates() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
                "-cp", System.getProperty("java.class.path"),
                "org.htcom.mcpext.DebugTarget");
        pb.redirectErrorStream(true);
        Process target = pb.start();
        try {
            int port = readListeningPort(target);
            assertTrue(port > 0, "must parse the JDWP listening port");

            DebugCore core = new DebugCore();
            DebugSession session = core.attach("127.0.0.1", port);
            try {
                assertNotNull(session.vmName(), "query VM name after attach");
                List<String> threads = session.threadNames();
                assertFalse(threads.isEmpty(), "target VM threads should be visible");
            } finally {
                core.terminate(session.id());
            }
            assertNull(core.session(session.id()), "session deregistered after terminate");
        } finally {
            target.destroyForcibly();
            target.waitFor();
        }
    }

    /** Parses the port from the child JVM's startup line "Listening for transport dt_socket at address: N". */
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

    private static void assertNull(Object o, String msg) {
        org.junit.jupiter.api.Assertions.assertNull(o, msg);
    }
}
