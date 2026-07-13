/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.gate;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Runs JUnit tests loaded in a module-specific ClassLoader at runtime (promotion gate ①).
 * Uses the JUnit Platform Launcher and swaps the TCCL to the module loader so the engine and test classes
 * are resolved together.
 *
 * <p>Failure diagnostics: {@code failures} carries the exception's <b>full stack trace</b> so that an MCP agent
 * can see where and why a test broke and fix the source. When {@code captureOutput=true}, stdout/stderr produced
 * during the test run are also captured (opt-in because it intercepts global System.out;
 * {@code protean.mcp.capture-test-output}).
 */
public class ModuleTestRunner {

    public record Result(long succeeded, long failed, long aborted, List<String> failures, String output) {
        public boolean green() {
            return failed == 0 && succeeded > 0;
        }
    }

    public Result run(ClassLoader loader, List<String> testFqcns) {
        return run(loader, testFqcns, false);
    }

    public Result run(ClassLoader loader, List<String> testFqcns, boolean captureOutput) {
        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
        for (String fqcn : testFqcns) {
            try {
                builder.selectors(selectClass(Class.forName(fqcn, false, loader)));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("failed to load test class: " + fqcn, e);
            }
        }
        LauncherDiscoveryRequest request = builder.build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();

        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream captured = captureOutput ? new ByteArrayOutputStream() : null;
        current.setContextClassLoader(loader);
        try {
            if (captured != null) {
                PrintStream ps = new PrintStream(captured, true, StandardCharsets.UTF_8);
                System.setOut(ps);
                System.setErr(ps);
            }
            launcher.execute(request, listener);
        } finally {
            current.setContextClassLoader(previous);
            if (captured != null) {
                System.setOut(origOut);
                System.setErr(origErr);
            }
        }

        TestExecutionSummary summary = listener.getSummary();
        List<String> failures = new ArrayList<>();
        for (TestExecutionSummary.Failure f : summary.getFailures()) {
            failures.add(f.getTestIdentifier().getDisplayName() + ": " + stackTrace(f.getException()));
        }
        String output = captured == null ? "" : captured.toString(StandardCharsets.UTF_8);
        return new Result(summary.getTestsSucceededCount(), summary.getTestsFailedCount(),
                summary.getTestsAbortedCount(), failures, output);
    }

    private static String stackTrace(Throwable t) {
        if (t == null) {
            return "(no exception)";
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
