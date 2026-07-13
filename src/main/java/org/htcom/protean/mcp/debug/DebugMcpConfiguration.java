/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.isolation.WorkerProcessIsolation;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.ModuleInputNormalizer;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Level 3 debug surface wiring. Active when {@code protean.mcp.enabled} and {@code !worker} —
 * the debug beans/tools/DebugCore are <b>always registered and exposed</b>, and only actual <b>execution</b> is gated by
 * {@link DebugSurfaceState} (initial value {@code protean.mcp.debug.enabled}, default false). When off, debug calls are
 * immediately rejected at the dispatcher choke point with {@code isError}("debug surface disabled") and have no side
 * effects. Can be flipped at runtime (no reconnect required).
 *
 * <p>Because the debug.* tools are {@link McpTool} beans, {@code McpDispatcher} collects them too (open core), and every
 * call passes through the authorizer choke point via {@code ModuleAction.DEBUG} plus this execution gate — a double
 * layer of defense.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!worker")
@ConditionalOnProperty(name = "protean.mcp.enabled", havingValue = "true")
public class DebugMcpConfiguration {

    /** Debug execution gate. Initial value {@code protean.mcp.debug.enabled} (default false), runtime-mutable. */
    @Bean
    DebugSurfaceState debugSurfaceState(@Value("${protean.mcp.debug.enabled:false}") boolean initiallyEnabled) {
        return new DebugSurfaceState(initiallyEnabled);
    }

    /**
     * Debug session store. On shutdown, stops the sweeper and cleans up all sessions (leak prevention).
     * Sessions idle longer than {@code protean.mcp.debug.session-idle-timeout} (default 30m; disabled if 0/negative)
     * are automatically reclaimed by the sweeper, preventing leaks of abandoned debug-launch worker JVMs.
     */
    @Bean(destroyMethod = "shutdown")
    DebugCore debugCore(@Value("${protean.mcp.debug.session-idle-timeout:30m}") Duration idleTimeout) {
        return new DebugCore(idleTimeout.toMillis());
    }

    @Bean McpTool debugAttachTool(ObjectMapper m, DebugCore c) { return new DebugTools.Attach(m, c); }
    @Bean McpTool debugLaunchTool(ObjectMapper m, DebugCore c, ModuleInputNormalizer n, WorkerProcessIsolation w) {
        return new DebugTools.Launch(m, c, n, w);
    }
    @Bean McpTool debugSetBreakpointTool(ObjectMapper m, DebugCore c) { return new DebugTools.SetBreakpoint(m, c); }
    @Bean McpTool debugContinueTool(ObjectMapper m, DebugCore c) { return new DebugTools.Continue(m, c); }
    @Bean McpTool debugStepTool(ObjectMapper m, DebugCore c) { return new DebugTools.Step(m, c); }
    @Bean McpTool debugAwaitStopTool(ObjectMapper m, DebugCore c) { return new DebugTools.AwaitStop(m, c); }
    @Bean McpTool debugFramesTool(ObjectMapper m, DebugCore c) { return new DebugTools.Frames(m, c); }
    @Bean McpTool debugVariablesTool(ObjectMapper m, DebugCore c) { return new DebugTools.Variables(m, c); }
    @Bean McpTool debugEvaluateTool(ObjectMapper m, DebugCore c, RuntimeCompiler rc) { return new DebugTools.Evaluate(m, c, rc); }
    @Bean McpTool debugTerminateTool(ObjectMapper m, DebugCore c) { return new DebugTools.Terminate(m, c); }
    @Bean McpTool debugListSessionsTool(ObjectMapper m, DebugCore c) { return new DebugTools.ListSessions(m, c); }
    @Bean McpTool debugRedefineTool(ObjectMapper m, DebugCore c, RuntimeCompiler rc) { return new DebugTools.Redefine(m, c, rc); }
}
