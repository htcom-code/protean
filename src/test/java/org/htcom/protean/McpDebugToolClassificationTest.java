/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each debug tool advertises about itself has to keep matching what it can do.
 *
 * <p>It stopped matching once before, and quietly. {@code debug.evaluate} was classified when the evaluator
 * handled paths, getters and operators — assignment was still listed as unimplemented — so
 * {@code destructiveHint:false} was true at the time. Later work added assignment, arbitrary method calls,
 * lambdas and method references; the guides were updated to call it arbitrary code execution, but the two
 * surfaces compiled into the jar (the annotation and the description) kept the old answer. Nothing failed,
 * because nothing was checking.
 *
 * <p>So this pins the classification itself rather than one tool's flag. A new debug tool, or a tool moved
 * between buckets, has to come here and say which bucket it belongs in.
 */
@SpringBootTest(properties = {"protean.mcp.enabled=true", "protean.mcp.debug.enabled=true"})
class McpDebugToolClassificationTest {

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;

    /** Runs code supplied by the caller inside the debuggee — the effect is whatever that code does. */
    private static final Set<String> ARBITRARY_CODE = Set.of("debug.evaluate", "debug.redefine");

    /** Ends the session and the debug worker with it. */
    private static final Set<String> DESTROYS = Set.of("debug.terminate");

    /** Looks without changing anything. */
    private static final Set<String> OBSERVES =
            Set.of("debug.frames", "debug.get_variables", "debug.list_sessions", "debug.await_stop");

    /** Changes debugger state, bounded by the debugger protocol. */
    private static final Set<String> MUTATES =
            Set.of("debug.attach", "debug.launch", "debug.step", "debug.continue", "debug.set_breakpoint");

    @Test
    void every_debug_tool_is_classified_and_the_buckets_are_complete() {
        Map<String, JsonNode> debugTools = debugTools();

        Set<String> declared = new java.util.HashSet<>();
        declared.addAll(ARBITRARY_CODE);
        declared.addAll(DESTROYS);
        declared.addAll(OBSERVES);
        declared.addAll(MUTATES);

        List<String> unclassified = new ArrayList<>(debugTools.keySet());
        unclassified.removeAll(declared);
        assertTrue(unclassified.isEmpty(),
                "a debug tool exists that this test does not classify — decide its bucket here: " + unclassified);

        List<String> stale = new ArrayList<>(declared);
        stale.removeAll(debugTools.keySet());
        assertTrue(stale.isEmpty(), "this test classifies a tool that no longer exists: " + stale);
    }

    @Test
    void code_running_tools_admit_they_are_destructive() {
        Map<String, JsonNode> debugTools = debugTools();
        for (String name : ARBITRARY_CODE) {
            JsonNode hints = debugTools.get(name).path("annotations");
            assertFalse(hints.path("readOnlyHint").asBoolean(false), name + " is not read-only");
            // destructiveHint defaults to true in the spec, so false here would claim it is safer than an
            // unannotated tool. It runs caller-supplied code; it cannot promise additive-only updates.
            assertTrue(hints.path("destructiveHint").asBoolean(false),
                    name + " runs caller-supplied code and must not advertise destructiveHint:false");
        }
    }

    @Test
    void evaluate_describes_what_it_actually_runs() {
        // The description is the only prose an agent gets before calling. It has to name the capabilities
        // that make this destructive, or the annotation and the description disagree again.
        String description = debugTools().get("debug.evaluate").path("description").asText()
                .toLowerCase(java.util.Locale.ROOT);
        for (String capability : List.of("assignment", "method", "side effect")) {
            assertTrue(description.contains(capability),
                    "debug.evaluate's description must mention '" + capability + "': " + description);
        }
    }

    @Test
    void session_ending_tools_are_destructive_and_observers_are_read_only() {
        Map<String, JsonNode> debugTools = debugTools();
        for (String name : DESTROYS) {
            assertTrue(debugTools.get(name).path("annotations").path("destructiveHint").asBoolean(false),
                    name + " ends the session and must say so");
        }
        for (String name : OBSERVES) {
            assertTrue(debugTools.get(name).path("annotations").path("readOnlyHint").asBoolean(false),
                    name + " only looks and must say so");
        }
        for (String name : MUTATES) {
            JsonNode hints = debugTools.get(name).path("annotations");
            assertFalse(hints.path("readOnlyHint").asBoolean(false), name + " changes debugger state");
            assertFalse(hints.path("destructiveHint").asBoolean(true),
                    name + " is bounded by the debugger protocol; if that changed, move it to ARBITRARY_CODE");
        }
    }

    private Map<String, JsonNode> debugTools() {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/list");
        JsonNode tools = dispatcher.dispatch(req, McpCallContext.anonymous()).path("result").path("tools");

        Map<String, JsonNode> byName = new LinkedHashMap<>();
        tools.forEach(t -> {
            String name = t.path("name").asText();
            if (name.startsWith("debug.")) {
                byName.put(name, t);
            }
        });
        assertFalse(byName.isEmpty(), "the debug surface must be exposed when protean.mcp.enabled=true");
        return byName;
    }

    @Test
    void the_debug_surface_size_is_pinned() {
        // Catches a tool being added or dropped without anyone revisiting the buckets above.
        assertEquals(12, debugTools().size(), "debug tool count changed: " + debugTools().keySet());
    }
}
