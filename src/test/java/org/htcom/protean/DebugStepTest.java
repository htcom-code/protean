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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies step over: after stopping at {@code int doubled = i * 2;} (line 20), a step_over advances to
 * the next line {@code return doubled;} (line 21).
 */
class DebugStepTest {

    private static final Pattern LISTENING = Pattern.compile("address:\\s*(\\d+)");
    private static final String TARGET = "org.htcom.mcpext.DebugLoopTarget";

    @Test
    void step_over_advances_to_next_line() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
                "-cp", System.getProperty("java.class.path"),
                TARGET);
        pb.redirectErrorStream(true);
        Process target = pb.start();
        try {
            int port = readListeningPort(target);
            assertTrue(port > 0);

            DebugCore core = new DebugCore();
            DebugSession session = core.attach("127.0.0.1", port);
            try {
                session.setBreakpoint(TARGET, 20); // int doubled = i * 2;
                DebugSession.Stop hit = session.awaitStop(30000);
                assertNotNull(hit, "breakpoint hit");
                assertEquals(20, hit.line());

                session.step(DebugSession.StepDepth.OVER);
                DebugSession.Stop stepped = session.awaitStop(30000);
                assertNotNull(stepped, "stopped after step");
                assertEquals(21, stepped.line(), "step_over advances to next line (return doubled)");
                assertEquals("compute", stepped.method());

                session.resume();
            } finally {
                core.terminate(session.id());
            }
        } finally {
            target.destroyForcibly();
            target.waitFor();
        }
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
