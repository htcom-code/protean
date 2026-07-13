/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.debug;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lambda-injection <b>feasibility PoC</b> — can JDI inject a synthesized class into the target VM?
 * Hypothesis: JDI {@code invokeMethod} bypasses access control, so it can call the protected
 * {@code ClassLoader.defineClass} directly to inject bytecode (no {@code --add-opens} needed, and it
 * works via external attach too).
 * Compile a {@code Supplier} implementation with RuntimeCompiler -> mirror the byte[] into the target ->
 * defineClass on the system class loader -> instantiate -> the mechanism is verified once get() returns
 * the expected value.
 */
class LambdaInjectionPocTest {

    private static final Pattern LISTENING = Pattern.compile("address:\\s*(\\d+)");
    private static final String TARGET = "org.htcom.mcpext.DebugEvalTarget";
    private static final int RETURN_LINE = 47;

    @Test
    void jdi_can_inject_and_invoke_synthesized_class() throws Exception {
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
                session.setBreakpoint(TARGET, RETURN_LINE);
                assertNotNull(session.awaitStop(30000), "breakpoint stop");
                VirtualMachine vm = session.vm();
                ThreadReference t = session.pausedThread();

                // 1) compile the Supplier implementation -> bytecode
                String fqcn = "poc.InjectedLambda";
                String src = """
                        package poc;
                        import java.util.function.Supplier;
                        public class InjectedLambda implements Supplier<Object> {
                            public Object get() { return "lambda-works"; }
                        }
                        """;
                byte[] bytes = new RuntimeCompiler().compileAll(Map.of(fqcn, src)).bytecode().get(fqcn);
                assertNotNull(bytes, "compiled bytecode");

                // 2) mirror the byte[] into the target VM
                ArrayType byteArrayType = (ArrayType) vm.classesByName("byte[]").get(0);
                ArrayReference arr = byteArrayType.newInstance(bytes.length);
                List<Value> vals = new ArrayList<>(bytes.length);
                for (byte b : bytes) {
                    vals.add(vm.mirrorOf(b));
                }
                arr.setValues(vals);

                // 3) obtain the system class loader
                ClassType clazzLoader = (ClassType) vm.classesByName("java.lang.ClassLoader").get(0);
                Method getSys = clazzLoader.methodsByName("getSystemClassLoader").get(0);
                ObjectReference loader = (ObjectReference) clazzLoader.invokeMethod(
                        t, getSys, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);

                // 4) call protected defineClass(String, byte[], int, int) directly via JDI (access-control bypass hypothesis)
                Method define = null;
                for (Method m : loader.referenceType().methodsByName("defineClass")) {
                    if (m.argumentTypeNames().equals(
                            List.of("java.lang.String", "byte[]", "int", "int"))) {
                        define = m;
                        break;
                    }
                }
                assertNotNull(define, "found defineClass(String,byte[],int,int)");
                Value classObj = loader.invokeMethod(t, define,
                        List.of(vm.mirrorOf(fqcn), arr, vm.mirrorOf(0), vm.mirrorOf(bytes.length)),
                        ObjectReference.INVOKE_SINGLE_THREADED);
                assertTrue(classObj instanceof ClassObjectReference, "defineClass returns a Class");
                ClassObjectReference classRef = (ClassObjectReference) classObj;

                // 5) instantiate — defineClass only defines (does not prepare), so its reflectedType methods
                //    cannot be read directly. Calling java.lang.Class.newInstance() (already prepared)
                //    triggers preparation and initialization.
                Method classNewInstance = classRef.referenceType().methodsByName("newInstance").get(0);
                ObjectReference instance = (ObjectReference) classRef.invokeMethod(
                        t, classNewInstance, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
                Method get = instance.referenceType().methodsByName("get").get(0);
                Value r = instance.invokeMethod(t, get, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);

                assertEquals("lambda-works", ((StringReference) r).value(),
                        "the injected synthesized class's method must run in the target VM");

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
