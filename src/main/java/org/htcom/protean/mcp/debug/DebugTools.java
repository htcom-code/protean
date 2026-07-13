/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpException;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolAnnotations;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.isolation.WorkerProcessIsolation;
import org.htcom.protean.isolation.WorkerProcessIsolation.DebugWorkerHandle;
import org.htcom.protean.mcp.ModuleInputNormalizer;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleManifestLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP debug tool bundle — a thin session adapter over {@link DebugCore}/{@link DebugSession}.
 * Every tool's action = {@link ModuleActionAuthorizer.ModuleAction#DEBUG}, so the authorizer judges debugging separately.
 * Session flow: {@code debug.attach} → set_breakpoint → await_stop → frames/get_variables → step/continue → terminate.
 */
public final class DebugTools {

    private DebugTools() {
    }

    /** Common DEBUG action + sessionId resolution. */
    abstract static class Base implements McpTool {
        final ObjectMapper mapper;
        final DebugCore core;

        Base(ObjectMapper mapper, DebugCore core) {
            this.mapper = mapper;
            this.core = core;
        }

        // Shared behavior hints — debug tools deal only with the debuggee (a closed domain), so openWorld=false.
        /** Observe-only (state-unchanging). */
        static final McpToolAnnotations OBSERVE = McpToolAnnotations.builder()
                .readOnly(true).idempotent(true).openWorld(false).build();
        /** State-changing, non-idempotent action (attach/launch/step/continue/evaluate/redefine). */
        static final McpToolAnnotations MUTATE = McpToolAnnotations.builder()
                .readOnly(false).destructive(false).idempotent(false).openWorld(false).build();
        /** Action whose result is the same when repeated (set_breakpoint). */
        static final McpToolAnnotations MUTATE_IDEMPOTENT = McpToolAnnotations.builder()
                .readOnly(false).destructive(false).idempotent(true).openWorld(false).build();
        /** Action that destroys the session/worker (terminate). */
        static final McpToolAnnotations DESTROY = McpToolAnnotations.builder()
                .readOnly(false).destructive(true).idempotent(true).openWorld(false).build();

        @Override
        public ModuleActionAuthorizer.ModuleAction action() {
            return ModuleActionAuthorizer.ModuleAction.DEBUG;
        }

        @Override
        public String targetModuleId(JsonNode arguments) {
            return null; // debugging is keyed by sessionId, not a module id
        }

        DebugSession require(JsonNode args) {
            String id = str(args, "sessionId");
            DebugSession s = core.session(id);
            if (s == null) {
                throw new IllegalStateException("no debug session: " + id);
            }
            return s;
        }

        ObjectNode obj() {
            return mapper.createObjectNode();
        }

        static String str(JsonNode args, String key) {
            if (args == null || !args.hasNonNull(key)) {
                throw McpException.invalidParams("missing required argument: " + key);
            }
            return args.get(key).asText();
        }

        ObjectNode sessionSchema() {
            ObjectNode schema = obj();
            schema.put("type", "object");
            schema.putObject("properties").putObject("sessionId").put("type", "string");
            schema.putArray("required").add("sessionId");
            return schema;
        }

        /** Requester identity to record as the session owner (nullable — unauthenticated/stdio). Basis for future per-user lookups. */
        static String ownerOf(McpCallContext ctx) {
            return ctx != null && ctx.caller() != null ? ctx.caller().getName() : null;
        }
    }

    /**
     * debug.list_sessions — list of active debug sessions (for reconnect/rediscovery). A session is identified only by
     * sessionId and is independent of the client connection, so knowing the id lets you reconnect — but if the id was
     * lost there was no way to rediscover it; this tool fills that gap.
     * The first phase is global (all sessions); a later phase scopes per-user via {@code ownerOf(ctx)}.
     */
    public static final class ListSessions extends Base {
        public ListSessions(ObjectMapper mapper, DebugCore core) {
            super(mapper, core);
        }

        @Override public String name() { return "debug.list_sessions"; }
        @Override public String title() { return "List Debug Sessions"; }
        @Override public McpToolAnnotations annotations() { return OBSERVE; }
        @Override public String description() {
            return "List of active debug sessions (sessionId, vmName, owner, idle time, paused state, last stop location). For rediscovery/reconnect by a client that lost its id.";
        }
        @Override public ObjectNode inputSchema() {
            ObjectNode s = obj();
            s.put("type", "object");
            s.putObject("properties");
            return s;
        }
        @Override public ObjectNode outputSchema() { return DebugToolSchemas.sessionList(mapper); }

        @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
            long now = System.nanoTime();
            com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
            // First phase: global (all sessions). Later phase: add an ownerOf(ctx) filter for per-user lookups.
            for (DebugSession s : core.sessions()) {
                ObjectNode o = obj();
                o.put("sessionId", s.id());
                o.put("vmName", s.vmName());
                if (s.owner() != null) {
                    o.put("owner", s.owner());
                }
                o.put("idleMs", (now - s.lastActivityNanos()) / 1_000_000L);
                o.put("paused", s.paused());
                DebugSession.Stop last = s.lastStop();
                if (last != null) {
                    o.put("lastStop", last.className() + "." + last.method() + ":" + last.line());
                }
                arr.add(o);
            }
            // structuredContent must be an object (arrays not allowed) → wrap under a sessions key.
            ObjectNode out = obj();
            out.set("sessions", arr);
            return McpToolResult.ok(arr.size() + " debug session(s)", out);
        }
    }

    /** debug.attach — attaches to a JDWP target (host:port) and opens a session. */
    public static final class Attach extends Base {
        public Attach(ObjectMapper mapper, DebugCore core) {
            super(mapper, core);
        }

        @Override public String name() { return "debug.attach"; }
        @Override public String title() { return "Attach Debugger"; }
        @Override public McpToolAnnotations annotations() { return MUTATE; }
        @Override public String description() { return "Attaches to a target JVM running JDWP (host:port) and opens a debug session."; }
        @Override public ObjectNode inputSchema() {
            ObjectNode s = obj();
            s.put("type", "object");
            ObjectNode p = s.putObject("properties");
            p.putObject("host").put("type", "string");
            p.putObject("port").put("type", "integer");
            s.putArray("required").add("host").add("port");
            return s;
        }
        @Override public ObjectNode outputSchema() { return DebugToolSchemas.sessionRef(mapper); }

        @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
            String host = str(args, "host");
            if (!args.hasNonNull("port")) {
                throw McpException.invalidParams("missing required argument: port");
            }
            int port = args.get("port").asInt();
            try {
                DebugSession s = core.attach(host, port, ownerOf(ctx));
                ObjectNode out = obj();
                out.put("sessionId", s.id());
                out.put("vmName", s.vmName());
                return McpToolResult.ok("attached: " + s.id(), out);
            } catch (java.io.IOException e) {
                return McpToolResult.error(ErrorCode.INTERNAL_ERROR, "attach failed: " + e.getMessage());
            }
        }
    }

    /**
     * debug.launch — (re)deploys a module to a dedicated debug worker with JDWP enabled and auto-attaches to open a session.
     * Input is identical to {@code protean.deploy_module} (files[]/manifest) — reuses {@link ModuleInputNormalizer}.
     * On session termination (debug.terminate), the worker is killed and routes are restored (wired via a dispose hook).
     */
    public static final class Launch extends Base {
        private final ModuleInputNormalizer normalizer;
        private final WorkerProcessIsolation isolation;

        public Launch(ObjectMapper mapper, DebugCore core,
                      ModuleInputNormalizer normalizer, WorkerProcessIsolation isolation) {
            super(mapper, core);
            this.normalizer = normalizer;
            this.isolation = isolation;
        }

        @Override public String name() { return "debug.launch"; }
        @Override public String title() { return "Launch Debug Worker"; }
        @Override public McpToolAnnotations annotations() { return MUTATE; }
        @Override public String description() {
            return "(Re)deploys a module to a dedicated debug worker with JDWP enabled and auto-attaches to open a session (on termination, reverts to normal deployment).";
        }

        @Override
        public String targetModuleId(JsonNode arguments) {
            // For files[] input, scope authz by id. For manifest input, id is inside the yaml so return null (default debug policy).
            return arguments != null && arguments.hasNonNull("id") ? arguments.get("id").asText() : null;
        }

        @Override public ObjectNode inputSchema() {
            ObjectNode s = obj();
            s.put("type", "object");
            ObjectNode p = s.putObject("properties");
            p.putObject("id").put("type", "string");
            p.putObject("version").put("type", "string");
            p.putObject("controller").put("type", "string");
            p.putObject("isolationMode").put("type", "string");
            p.putObject("files").put("type", "array")
                    .put("description", "Source files ({kind:source|test, filename, content}) — alternative to manifest");
            p.putObject("manifest").put("type", "string")
                    .put("description", "YAML manifest — alternative to files");
            return s;
        }
        @Override public ObjectNode outputSchema() { return DebugToolSchemas.launchResult(mapper); }

        @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
            ModuleDescriptor descriptor = normalizer.normalize(args);
            DebugWorkerHandle worker = isolation.launchDebugWorker(descriptor);
            try {
                DebugSession session = core.attach("127.0.0.1", worker.jdwpPort(), ownerOf(ctx));
                // tie worker kill + route restore to the session lifecycle on termination.
                session.onDispose(() -> isolation.terminateDebugWorker(worker));
                ObjectNode out = obj();
                out.put("sessionId", session.id());
                out.put("vmName", session.vmName());
                out.put("moduleId", descriptor.id());
                out.put("workerPort", worker.workerPort());
                out.put("jdwpPort", worker.jdwpPort());
                out.set("paths", mapper.valueToTree(worker.paths()));
                return McpToolResult.ok("debug-launch: " + descriptor.id() + " → session " + session.id(), out);
            } catch (java.io.IOException | RuntimeException e) {
                isolation.terminateDebugWorker(worker);  // prevent worker leak if attach fails
                return McpToolResult.error(ErrorCode.INTERNAL_ERROR,
                        "debug.launch attach failed (worker cleaned up): " + e.getMessage());
            }
        }
    }

    /** debug.set_breakpoint — {sessionId, className, line}. */
    public static final class SetBreakpoint extends Base {
        public SetBreakpoint(ObjectMapper mapper, DebugCore core) {
            super(mapper, core);
        }

        @Override public String name() { return "debug.set_breakpoint"; }
        @Override public String title() { return "Set Breakpoint"; }
        @Override public McpToolAnnotations annotations() { return MUTATE_IDEMPOTENT; }
        @Override public String description() { return "Sets a breakpoint at className:line."; }
        @Override public ObjectNode inputSchema() {
            ObjectNode s = sessionSchema();
            ObjectNode p = (ObjectNode) s.get("properties");
            p.putObject("className").put("type", "string");
            p.putObject("line").put("type", "integer");
            ((com.fasterxml.jackson.databind.node.ArrayNode) s.get("required")).add("className").add("line");
            return s;
        }

        @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
            DebugSession s = require(args);
            if (!args.hasNonNull("line")) {
                throw McpException.invalidParams("missing required argument: line");
            }
            s.setBreakpoint(str(args, "className"), args.get("line").asInt());
            return McpToolResult.ok("Breakpoint set");
        }
    }

    /** debug.continue — resumes the stopped thread. */
    public static final class Continue extends Base {
        public Continue(ObjectMapper mapper, DebugCore core) {
            super(mapper, core);
        }

        @Override public String name() { return "debug.continue"; }
        @Override public String title() { return "Continue Execution"; }
        @Override public McpToolAnnotations annotations() { return MUTATE; }
        @Override public String description() { return "Resumes the stopped thread."; }
        @Override public ObjectNode inputSchema() { return sessionSchema(); }

        @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
            require(args).resume();
            return McpToolResult.ok("Resumed");
        }
    }

    /** debug.step — {sessionId, depth: over|into|out}. */
    public static final class Step extends Base {
        public Step(ObjectMapper mapper, DebugCore core) {
            super(mapper, core);
        }

        @Override public String name() { return "debug.step"; }
        @Override public String title() { return "Step"; }
        @Override public McpToolAnnotations annotations() { return MUTATE; }
        @Override public String description() { return "Executes one step (depth: over|into|out) and stops at the next line."; }
        @Override public ObjectNode inputSchema() {
            ObjectNode s = sessionSchema();
            ((ObjectNode) s.get("properties")).putObject("depth").put("type", "string")
                    .put("description", "over|into|out");
            return s;
        }
        @Override public ObjectNode outputSchema() { return DebugToolSchemas.stepResult(mapper); }

        @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
            DebugSession s = require(args);
            String depth = args.path("depth").asText("over");
            DebugSession.StepDepth d;
            try {
                d = DebugSession.StepDepth.valueOf(depth.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw McpException.invalidParams("depth must be over|into|out: " + depth);
            }
            s.step(d);
            ObjectNode out = obj();
            out.put("depth", d.name().toLowerCase(java.util.Locale.ROOT));
            return McpToolResult.ok("step " + depth, out);
        }
    }

    /** debug.await_stop — {sessionId, timeoutMs?} waits for the next stop and returns its location. */
    public static final class AwaitStop extends Base {
        public AwaitStop(ObjectMapper mapper, DebugCore core) {
            super(mapper, core);
        }

        @Override public String name() { return "debug.await_stop"; }
        @Override public String title() { return "Await Stop"; }
        @Override public McpToolAnnotations annotations() { return OBSERVE; }
        @Override public String description() { return "Waits for the next stop (breakpoint/step) and returns the stop location."; }
        @Override public ObjectNode inputSchema() {
            ObjectNode s = sessionSchema();
            ((ObjectNode) s.get("properties")).putObject("timeoutMs").put("type", "integer");
            return s;
        }
        @Override public ObjectNode outputSchema() { return DebugToolSchemas.stopLocation(mapper); }

        @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
            DebugSession s = require(args);
            long timeout = args.path("timeoutMs").asLong(10_000);
            DebugSession.Stop stop = s.awaitStop(timeout);
            ObjectNode out = obj();
            if (stop == null) {
                out.put("stopped", false);
                return McpToolResult.ok("No stop (timeout)", out);
            }
            out.put("stopped", true);
            out.put("className", stop.className());
            out.put("method", stop.method());
            out.put("line", stop.line());
            return McpToolResult.ok(stop.className() + "." + stop.method() + ":" + stop.line(), out);
        }
    }

    /** debug.frames — stack at the stop point. */
    public static final class Frames extends Base {
        public Frames(ObjectMapper mapper, DebugCore core) {
            super(mapper, core);
        }

        @Override public String name() { return "debug.frames"; }
        @Override public String title() { return "Get Frames"; }
        @Override public McpToolAnnotations annotations() { return OBSERVE; }
        @Override public String description() { return "Returns the stack frames of the stopped thread."; }
        @Override public ObjectNode inputSchema() { return sessionSchema(); }
        @Override public ObjectNode outputSchema() { return DebugToolSchemas.frameList(mapper); }

        @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
            List<DebugSession.Frame> frames = require(args).frames();
            // Per the MCP spec, structuredContent must be an object (arrays not allowed) → wrap under a frames key.
            ObjectNode out = obj();
            out.set("frames", mapper.valueToTree(frames));
            return McpToolResult.ok(frames.size() + " frame(s)", out);
        }
    }

    /** debug.get_variables — {sessionId, frame?} local variables of the frame. */
    public static final class Variables extends Base {
        public Variables(ObjectMapper mapper, DebugCore core) {
            super(mapper, core);
        }

        @Override public String name() { return "debug.get_variables"; }
        @Override public String title() { return "Get Variables"; }
        @Override public McpToolAnnotations annotations() { return OBSERVE; }
        @Override public String description() { return "Returns the frame's local variables (name→value) (requires -g compilation)."; }
        @Override public ObjectNode inputSchema() {
            ObjectNode s = sessionSchema();
            ((ObjectNode) s.get("properties")).putObject("frame").put("type", "integer")
                    .put("description", "Frame index (default 0)");
            return s;
        }
        @Override public ObjectNode outputSchema() { return DebugToolSchemas.variableMap(mapper); }

        @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
            DebugSession s = require(args);
            int frame = args.path("frame").asInt(0);
            Map<String, String> vars = s.variables(frame);
            return McpToolResult.ok(vars.size() + " variable(s)", mapper.valueToTree(vars));
        }
    }

    /** debug.evaluate — {sessionId, expr, frame?} evaluates an expression in the stopped frame (full Java syntax + lambdas). */
    public static final class Evaluate extends Base {
        private final RuntimeCompiler compiler;

        public Evaluate(ObjectMapper mapper, DebugCore core, RuntimeCompiler compiler) {
            super(mapper, core);
            this.compiler = compiler;
        }

        @Override public String name() { return "debug.evaluate"; }
        @Override public String title() { return "Evaluate Expression"; }
        @Override public McpToolAnnotations annotations() { return MUTATE; }
        @Override public String description() {
            return "Evaluates an expression in the stopped frame (paths, getters, indexing, literals + arithmetic/comparison/logical operators, unary, string concatenation, primitive casts, new, FQCN static).";
        }
        @Override public ObjectNode inputSchema() {
            ObjectNode s = sessionSchema();
            ObjectNode p = (ObjectNode) s.get("properties");
            p.putObject("expr").put("type", "string").put("description", "e.g. user.getName(), order.items[0], list.size()");
            p.putObject("frame").put("type", "integer").put("description", "Frame index (default 0)");
            ((com.fasterxml.jackson.databind.node.ArrayNode) s.get("required")).add("expr");
            return s;
        }
        @Override public ObjectNode outputSchema() { return DebugToolSchemas.evalResult(mapper); }

        @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
            DebugSession s = require(args);
            String expr = str(args, "expr");
            int frame = args.path("frame").asInt(0);
            DebugSession.Eval e = s.evaluate(frame, expr, compiler);   // evaluation errors throw → dispatcher turns into isError
            ObjectNode out = obj();
            out.put("value", e.value());
            out.put("type", e.type());
            return McpToolResult.ok(expr + " = " + e.value() + " (" + e.type() + ")", out);
        }
    }

    /** debug.redefine — fix-and-continue. Recompiles edited source and replaces the loaded class in place. */
    public static final class Redefine extends Base {
        private final RuntimeCompiler compiler;

        public Redefine(ObjectMapper mapper, DebugCore core, RuntimeCompiler compiler) {
            super(mapper, core);
            this.compiler = compiler;
        }

        @Override public String name() { return "debug.redefine"; }
        @Override public String title() { return "Redefine Class"; }
        @Override public McpToolAnnotations annotations() { return MUTATE; }
        @Override public String description() {
            return "Recompiles edited source and replaces the running class in place (method bodies only). Keeps the session and stop state.";
        }
        @Override public ObjectNode inputSchema() {
            ObjectNode s = sessionSchema();
            ((ObjectNode) s.get("properties")).putObject("files").put("type", "array")
                    .put("description", "Source files to recompile ({filename, content})");
            ((com.fasterxml.jackson.databind.node.ArrayNode) s.get("required")).add("files");
            return s;
        }
        @Override public ObjectNode outputSchema() { return DebugToolSchemas.redefineResult(mapper); }

        @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
            DebugSession session = require(args);
            if (!args.hasNonNull("files") || !args.get("files").isArray()) {
                throw McpException.invalidParams("files[] required");
            }
            Map<String, String> sources = new LinkedHashMap<>();
            for (JsonNode f : args.get("files")) {
                String filename = str(f, "filename");
                String content = str(f, "content");
                String stem = filename.endsWith(".java") ? filename.substring(0, filename.length() - 5) : filename;
                sources.put(ModuleManifestLoader.deriveFqcn(stem, content), content);
            }
            ModuleClassLoader compiled = compiler.compileAll(sources);
            List<String> redefined = new ArrayList<>();
            compiled.bytecode().forEach((fqcn, bytes) -> {
                session.redefine(fqcn, bytes); // throws if not loaded/unsupported → dispatcher turns into isError
                redefined.add(fqcn);
            });
            ObjectNode out = obj();
            out.set("redefined", mapper.valueToTree(redefined));
            return McpToolResult.ok("redefine complete: " + redefined, out);
        }
    }

    /** debug.terminate — terminates the session. */
    public static final class Terminate extends Base {
        public Terminate(ObjectMapper mapper, DebugCore core) {
            super(mapper, core);
        }

        @Override public String name() { return "debug.terminate"; }
        @Override public String title() { return "Terminate Debug Session"; }
        @Override public McpToolAnnotations annotations() { return DESTROY; }
        @Override public String description() { return "Terminates the debug session (detaches from the target VM)."; }
        @Override public ObjectNode inputSchema() { return sessionSchema(); }

        @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
            core.terminate(str(args, "sessionId"));
            return McpToolResult.ok("Session terminated");
        }
    }
}
