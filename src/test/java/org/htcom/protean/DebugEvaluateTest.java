/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.RuntimeCompiler;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code debug.evaluate} expression evaluation (paths and getters) in a suspended frame. Sets a
 * breakpoint at {@code return marker;} (line 46) in {@code DebugEvalTarget.run} launched with JDWP and
 * evaluates locals, fields, getters, indexing, and literals. No Spring or Docker required.
 */
class DebugEvaluateTest {

    private static final Pattern LISTENING = Pattern.compile("address:\\s*(\\d+)");
    private static final String TARGET = "org.htcom.mcpext.DebugEvalTarget";
    private static final int RETURN_LINE = 47;

    @Test
    void evaluate_full_expression_grammar() throws Exception {
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
            assertTrue(port > 0, "parse the JDWP listening port");

            DebugCore core = new DebugCore();
            DebugSession session = core.attach("127.0.0.1", port);
            try {
                session.setBreakpoint(TARGET, RETURN_LINE);
                DebugSession.Stop stop = session.awaitStop(8000);
                assertNotNull(stop, "breakpoint hit");
                assertEquals("run", stop.method());

                // Local String plus a getter on it
                assertEval(session, "name", "protean", "java.lang.String");
                assertEval(session, "name.length()", "7", "int");

                // Object local: getter call plus direct private-field read
                assertEval(session, "user.getName()", "neo", "java.lang.String");
                assertEval(session, "user.getAge()", "29", "int");
                assertEval(session, "user.age", "29", "int");     // private fields are readable over JDI too

                // Array indexing
                assertEval(session, "nums[1]", "20", "int");

                // List getter
                assertEval(session, "items.size()", "2", "int");

                // this-field (bare identifier) plus explicit this
                assertEval(session, "tag", "eval-tag", "java.lang.String");
                assertEval(session, "this.tag", "eval-tag", "java.lang.String");

                // Literal root plus method
                assertEval(session, "\"hi\".length()", "2", "int");

                // Error: missing method -> exception (the path the dispatcher converts to isError)
                assertThrows(RuntimeException.class, () -> session.evaluate(0, "user.nope()"),
                        "a nonexistent method should be an evaluation error");

                // --- Operators, casts, new, static ---
                // Arithmetic plus precedence plus parentheses
                assertEval(session, "1 + 2 * 3", "7", "int");
                assertEval(session, "(1 + 2) * 3", "9", "int");
                assertEval(session, "10 / 3", "3", "int");            // int division
                assertEval(session, "10 % 3", "1", "int");
                assertEval(session, "10.0 / 4", "2.5", "double");      // floating-point promotion
                assertEval(session, "nums[0] + nums[2]", "40", "int"); // combining locals
                assertEval(session, "user.getAge() * 2", "58", "int"); // getter plus arithmetic
                // Comparison plus logical (short-circuit) — the x != null && x.m() idiom
                assertEval(session, "user.getAge() > 18", "true", "boolean");
                assertEval(session, "user != null && user.getAge() == 29", "true", "boolean");
                assertEval(session, "items == null || items.size() == 2", "true", "boolean");
                // Unary
                assertEval(session, "-nums[0]", "-10", "int");
                assertEval(session, "!(1 > 2)", "true", "boolean");
                // String concatenation
                assertEval(session, "name + \"!\"", "protean!", "java.lang.String");
                assertEval(session, "\"age=\" + user.getAge()", "age=29", "java.lang.String");
                // Primitive cast (binds tighter than unary)
                assertEval(session, "(int) 3.9", "3", "int");
                assertEval(session, "(double) 10 / 4", "2.5", "double");
                assertEval(session, "(long) nums[0]", "10", "long");
                // FQCN static reference (field/method) plus new (no-arg constructor — avoids overload ambiguity)
                assertEval(session, "java.lang.Integer.MAX_VALUE", "2147483647", "int");
                assertEval(session, "java.lang.Integer.parseInt(\"42\")", "42", "int");
                assertEval(session, "new java.lang.String()", "", "java.lang.String");

                // --- Completeness: bitwise/shift ---
                assertEval(session, "6 & 3", "2", "int");
                assertEval(session, "6 | 1", "7", "int");
                assertEval(session, "6 ^ 3", "5", "int");
                assertEval(session, "~0", "-1", "int");
                assertEval(session, "1 << 4", "16", "int");
                assertEval(session, "256 >> 2", "64", "int");
                assertEval(session, "-1 >>> 28", "15", "int");
                // Ternary
                assertEval(session, "user.getAge() > 18 ? \"adult\" : \"minor\"", "adult", "java.lang.String");
                // instanceof
                assertEval(session, "user instanceof java.lang.Object", "true", "boolean");
                assertEval(session, "name instanceof java.lang.String", "true", "boolean");
                assertEval(session, "user instanceof java.lang.String", "false", "boolean");
                // Reference cast: identity (upcast to interface then method) plus invalid-cast error
                assertEval(session, "((java.lang.CharSequence) name).length()", "7", "int");
                assertThrows(RuntimeException.class, () -> session.evaluate(0, "(java.lang.Integer) name"),
                        "an invalid reference cast is a ClassCastException-style error");
                // Overload type resolution: selects the 1-arg String(String) constructor (previously failed when matching arg count only)
                assertEval(session, "new java.lang.String(\"hey\")", "hey", "java.lang.String");
                // Assignment: local (simple plus compound)
                assertEval(session, "marker = 99", "99", "int");
                assertEval(session, "marker", "99", "int");
                assertEval(session, "marker += 1", "100", "int");
                // Assignment: this-field (non-final)
                assertEval(session, "counter = 42", "42", "int");
                assertEval(session, "this.counter", "42", "int");
                // Assignment: array element
                assertEval(session, "nums[0] = 55", "55", "int");
                assertEval(session, "nums[0]", "55", "int");

                // --- Lambdas: inject a synthetic class -> pass it to a stream in the target VM ---
                RuntimeCompiler compiler = new RuntimeCompiler();
                // Predicate + count(long)
                DebugSession.Eval f1 = session.evaluate(0,
                        "items.stream().filter((java.lang.String s) -> s.length() > 0).count()", compiler);
                assertEquals("2", f1.value(), "filter+count");
                assertEquals("long", f1.type());
                // Function (autoboxed return) + count
                assertEquals("2", session.evaluate(0,
                        "items.stream().map((java.lang.String s) -> s.length()).count()", compiler).value(), "map+count");
                // Predicate anyMatch(boolean)
                DebugSession.Eval f3 = session.evaluate(0,
                        "items.stream().anyMatch((java.lang.String s) -> s.equals(\"a\"))", compiler);
                assertEquals("true", f3.value(), "anyMatch");
                assertEquals("boolean", f3.type());
                // Capture variable (name) injected as a field
                assertEquals("2", session.evaluate(0,
                        "items.stream().filter((java.lang.String s) -> name.length() > 0).count()", compiler).value(),
                        "capture-variable lambda");

                // --- Method references ---
                // unbound instance: String::length (Function)
                assertEquals("2", session.evaluate(0,
                        "items.stream().map(java.lang.String::length).count()", compiler).value(), "unbound ref");
                // bound instance: name::equals (Predicate) — name="protean" does not match "a"/"b" -> 0
                assertEquals("0", session.evaluate(0,
                        "items.stream().filter(name::equals).count()", compiler).value(), "bound ref");
                // static: Boolean::parseBoolean (Function, single overload)
                assertEquals("2", session.evaluate(0,
                        "items.stream().map(java.lang.Boolean::parseBoolean).count()", compiler).value(), "static ref");
                // constructor: Object::new (Supplier)
                assertEquals("2", session.evaluate(0,
                        "java.util.stream.Stream.generate(java.lang.Object::new).limit(2).count()", compiler).value(),
                        "constructor ref");

                session.resume();
            } finally {
                core.terminate(session.id());
            }
        } finally {
            target.destroyForcibly();
            target.waitFor();
        }
    }

    private static void assertEval(DebugSession s, String expr, String value, String type) {
        DebugSession.Eval e = s.evaluate(0, expr);
        assertEquals(value, e.value(), "value: " + expr);
        assertEquals(type, e.type(), "type: " + expr);
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
