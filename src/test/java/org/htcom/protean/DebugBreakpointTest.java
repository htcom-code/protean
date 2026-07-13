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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Breakpoint verification — breakpoint -> stop event -> frames/variables (read). Attaches to a real
 * target JVM launched under JDWP, sets a breakpoint on {@code return doubled;} (line 21) in
 * {@code DebugLoopTarget.compute}, and verifies the stack and local variables when it is hit. No
 * Spring or Docker required.
 */
class DebugBreakpointTest {

    private static final Pattern LISTENING = Pattern.compile("address:\\s*(\\d+)");
    private static final String TARGET = "org.htcom.mcpext.DebugLoopTarget";
    private static final int RETURN_LINE = 21;

    @Test
    void breakpoint_hit_exposes_frames_and_variables() throws Exception {
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
            assertTrue(port > 0, "parse JDWP listening port");

            DebugCore core = new DebugCore();
            DebugSession session = core.attach("127.0.0.1", port);
            try {
                session.setBreakpoint(TARGET, RETURN_LINE);

                DebugSession.Stop stop = session.awaitStop(30000);
                assertNotNull(stop, "the breakpoint should be hit and suspend execution");
                assertEquals(TARGET, stop.className());
                assertEquals("compute", stop.method());
                assertEquals(RETURN_LINE, stop.line());

                // Stack: compute on top, main below
                List<DebugSession.Frame> frames = session.frames();
                assertTrue(frames.size() >= 2, "compute + main frames");
                assertEquals("compute", frames.get(0).method());
                assertEquals("main", frames.get(1).method());

                // Local variables (-g): i and doubled are visible and doubled == i*2
                Map<String, String> vars = session.variables(0);
                assertTrue(vars.containsKey("i"), "variable i exposed: " + vars);
                assertTrue(vars.containsKey("doubled"), "variable doubled exposed: " + vars);
                int i = Integer.parseInt(vars.get("i"));
                int doubled = Integer.parseInt(vars.get("doubled"));
                assertEquals(i * 2, doubled, "doubled == i*2");

                session.resume(); // resume (avoid deadlock)
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
