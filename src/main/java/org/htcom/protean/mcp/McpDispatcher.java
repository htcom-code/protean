/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProblemDetail;
import org.htcom.protean.error.ProteanException;
import org.htcom.protean.mcp.debug.DebugSurfaceState;
import org.htcom.protean.mcp.session.McpClientChannel;
import org.htcom.protean.mcp.session.McpServerNotifier;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Transport-agnostic JSON-RPC 2.0 dispatcher — the heart of the MCP protocol (zero-dep core, central to the
 * verification strategy). Streamable HTTP ({@code McpHttpController}) and stdio share this single instance, and
 * tests drive it directly.
 *
 * <p>Supported methods: {@code initialize} (version negotiation) · {@code ping} · {@code tools/list} ·
 * {@code tools/call} · {@code notifications/*} (no-op).
 */
public class McpDispatcher {

    /** Target protocol version (single pin). */
    public static final String PROTOCOL_VERSION = "2025-11-25";

    /** Used to validate the {@code MCP-Protocol-Version} header. null (unspecified) is tolerated for backward compatibility. */
    public boolean isSupportedProtocol(String version) {
        return version == null || SUPPORTED.contains(version);
    }
    /** Set of supported versions — add a single line here when it needs to be widened (the surface is version-stable). */
    private static final Set<String> SUPPORTED = Set.of(PROTOCOL_VERSION);
    private static final String SERVER_NAME = "protean";
    private static final String SERVER_VERSION = "0.1.0";
    /** Default list page size. Even as consumer tools grow, the rest is handed off via a cursor. */
    static final int DEFAULT_PAGE_SIZE = 100;

    private final ObjectMapper mapper;
    private final Map<String, McpTool> tools = new ConcurrentHashMap<>();
    private final ModuleActionAuthorizer authorizer;
    private final McpResources resources;
    private final McpPrompts prompts;
    /** Server→client notification channel. null = session surface off → no tool-change push (registration itself still works). */
    private final McpServerNotifier notifier;
    /** Debug execution gate (Model Y). null = debug not wired → all DEBUG actions are rejected outright (safe default). */
    private final DebugSurfaceState debugState;
    /** Live source of the strict-schema flag (Spring bean path). null in test constructors → {@link #strictSchemaFixed}. */
    private final ProteanProperties properties;
    /** Fixed strict-schema flag for the non-Spring (test) constructors. Ignored when {@link #properties} is present. */
    private final boolean strictSchemaFixed;
    /** Full JSON-Schema validator backing strict mode; unavailable() when the validator jar is absent. */
    private final SchemaValidator schemaValidator;
    /** Set of subscribed resource URIs. resources/updated only notifies for URIs in this set. Server-global (honest). */
    private final Set<String> resourceSubscriptions = ConcurrentHashMap.newKeySet();
    /** In-flight request cancellation registry: session-scoped request key → cancellation token + worker thread. */
    private final Map<String, Inflight> inflight = new ConcurrentHashMap<>();
    /** List page size (runtime-adjustable). */
    private volatile int pageSize = DEFAULT_PAGE_SIZE;
    /** Server-global log threshold. Defaults to INFO before setLevel. Log records below this level are not emitted. */
    private volatile McpLogLevel logLevel = McpLogLevel.INFO;

    public McpDispatcher(ObjectMapper mapper, List<McpTool> toolBeans, ModuleActionAuthorizer authorizer,
                         McpResources resources, McpPrompts prompts,
                         McpServerNotifier notifier, DebugSurfaceState debugState) {
        this(mapper, toolBeans, authorizer, resources, prompts, notifier, debugState, false, SchemaValidator.unavailable());
    }

    public McpDispatcher(ObjectMapper mapper, List<McpTool> toolBeans, ModuleActionAuthorizer authorizer,
                         McpResources resources, McpPrompts prompts,
                         McpServerNotifier notifier, DebugSurfaceState debugState,
                         boolean strictSchema, SchemaValidator schemaValidator) {
        this(mapper, toolBeans, authorizer, resources, prompts, notifier, debugState,
                null, strictSchema, schemaValidator);
    }

    /**
     * Spring-bean constructor: reads {@code protean.mcp.strict-schema} live from {@code properties} so a runtime
     * toggle takes effect at the next dispatch (the validator instance is classpath-bound and fixed).
     */
    public McpDispatcher(ObjectMapper mapper, List<McpTool> toolBeans, ModuleActionAuthorizer authorizer,
                         McpResources resources, McpPrompts prompts,
                         McpServerNotifier notifier, DebugSurfaceState debugState,
                         ProteanProperties properties, SchemaValidator schemaValidator) {
        this(mapper, toolBeans, authorizer, resources, prompts, notifier, debugState,
                properties, false, schemaValidator);
    }

    private McpDispatcher(ObjectMapper mapper, List<McpTool> toolBeans, ModuleActionAuthorizer authorizer,
                          McpResources resources, McpPrompts prompts,
                          McpServerNotifier notifier, DebugSurfaceState debugState,
                          ProteanProperties properties, boolean strictSchemaFixed, SchemaValidator schemaValidator) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.resources = resources;
        this.prompts = prompts;
        this.notifier = notifier;
        this.debugState = debugState;
        this.properties = properties;
        this.strictSchemaFixed = strictSchemaFixed;
        this.schemaValidator = schemaValidator == null ? SchemaValidator.unavailable() : schemaValidator;
        for (McpTool t : toolBeans) {
            tools.put(t.name(), t);
        }
    }

    /** Strict full-schema validation of tool I/O (opt-in via {@code protean.mcp.strict-schema}); read live when Spring-wired. */
    private boolean strictSchema() {
        return properties != null ? properties.getMcp().isStrictSchema() : strictSchemaFixed;
    }

    /**
     * Registers/replaces a tool at runtime (open core). Consumers can add their own tools without a restart, and on
     * change a {@code notifications/tools/list_changed} is pushed over the standing stream so clients refresh without
     * reconnecting.
     */
    public void registerTool(McpTool tool) {
        tools.put(tool.name(), tool);
        notifyToolsChanged();
    }

    /** Unregisters a tool at runtime. Does not push if the name was not present. */
    public void unregisterTool(String name) {
        if (tools.remove(name) != null) {
            notifyToolsChanged();
        }
    }

    private void notifyToolsChanged() {
        if (notifier != null) {
            ObjectNode notif = mapper.createObjectNode();
            notif.put("jsonrpc", "2.0");
            notif.put("method", "notifications/tools/list_changed");
            notifier.broadcast(notif);
        }
    }

    /**
     * {@code notifications/cancelled} — cancels the in-flight request for the given requestId. Sets the cancellation
     * token and interrupts the worker thread (releasing any blocking wait). A cooperative tool stops at its next
     * progress step. An already-finished or unknown id is ignored (idempotent, allowed by the spec). No return value
     * since this is a notification.
     */
    private JsonNode cancelInflight(JsonNode params, McpCallContext ctx) {
        if (params != null && params.hasNonNull("requestId")) {
            String reason = params.path("reason").asText(null);
            Inflight reg = inflight.get(key(ctx.sessionId(), params.get("requestId").asText()));
            if (reg != null) {
                reg.token().cancel(reason == null ? "client cancelled" : reason);
                reg.thread().interrupt();
            }
        }
        return null;
    }

    /** {@code logging/setLevel} — sets the server-global log threshold. An unknown level yields invalidParams. */
    private JsonNode setLogLevel(JsonNode params) {
        String level = params == null ? null : params.path("level").asText(null);
        McpLogLevel parsed = McpLogLevel.fromWire(level);
        if (parsed == null) {
            throw McpException.invalidParams("unknown log level: " + level);
        }
        this.logLevel = parsed;
        return mapper.createObjectNode();
    }

    /** The current server-global log threshold. */
    public McpLogLevel logLevel() {
        return logLevel;
    }

    /**
     * {@code completion/complete} — argument autocompletion for prompts/resource templates. Depending on the ref
     * kind, delegates to {@link McpPrompts#complete}/{@link McpResources#complete} (empty candidates if neither is
     * present). Spec result: {@code {completion:{values,total,hasMore}}}, with at most 100 values.
     */
    private JsonNode complete(JsonNode params) {
        if (params == null) {
            throw McpException.invalidParams("completion/complete: params required");
        }
        JsonNode ref = params.path("ref");
        String refType = ref.path("type").asText(null);
        JsonNode argument = params.path("argument");
        String argName = argument.path("name").asText(null);
        String value = argument.path("value").asText("");
        List<String> values;
        if ("ref/prompt".equals(refType)) {
            values = prompts != null ? prompts.complete(ref.path("name").asText(null), argName, value) : List.of();
        } else if ("ref/resource".equals(refType)) {
            values = resources != null ? resources.complete(ref.path("uri").asText(null), argName, value) : List.of();
        } else {
            throw McpException.invalidParams("unknown completion ref type: " + refType);
        }
        ObjectNode res = mapper.createObjectNode();
        ObjectNode completion = res.putObject("completion");
        ArrayNode arr = completion.putArray("values");
        for (String v : values) {
            arr.add(v);
        }
        completion.put("total", values.size());
        completion.put("hasMore", false);
        return res;
    }

    /**
     * Server→client log emission ({@code notifications/message}). A library primitive — invoked by consumer
     * tools/platforms. Sends over the standing stream only for levels at or above the current threshold, and only
     * when the session surface is on (notifier != null). The threshold is a <b>server-global</b> value adjusted via
     * {@code logging/setLevel} (shared by all connected clients).
     *
     * @param level  severity
     * @param logger log source name (optional, omitted if null)
     * @param data   structured payload (arbitrary JSON)
     */
    public void emitLog(McpLogLevel level, String logger, JsonNode data) {
        if (level == null || notifier == null || !level.atLeast(logLevel)) {
            return;
        }
        ObjectNode notif = mapper.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", "notifications/message");
        ObjectNode p = notif.putObject("params");
        p.put("level", level.wire());
        if (logger != null) {
            p.put("logger", logger);
        }
        p.set("data", data == null ? mapper.nullNode() : data);
        notifier.broadcast(notif);
    }

    /** {@code resources/subscribe} — subscribe to change notifications for a URI. Idempotent. */
    private JsonNode subscribe(JsonNode params) {
        resourceSubscriptions.add(requireUri(params, "resources/subscribe"));
        return mapper.createObjectNode();
    }

    /** {@code resources/unsubscribe}. Idempotent. */
    private JsonNode unsubscribe(JsonNode params) {
        resourceSubscriptions.remove(requireUri(params, "resources/unsubscribe"));
        return mapper.createObjectNode();
    }

    private String requireUri(JsonNode params, String ctx) {
        if (params == null || !params.hasNonNull("uri")) {
            throw McpException.invalidParams(ctx + ": uri required");
        }
        return params.get("uri").asText();
    }

    /**
     * Resource-content change notification ({@code notifications/resources/updated}). A library primitive —
     * invoked by the platform/bridge when a resource changes. Broadcasts over the standing stream only for a
     * <b>subscribed uri</b> and only when the session surface is on (notifier != null). Subscriptions are
     * server-global (shared by all connected clients).
     */
    public void notifyResourceUpdated(String uri) {
        if (uri == null || notifier == null || !resourceSubscriptions.contains(uri)) {
            return;
        }
        ObjectNode notif = mapper.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", "notifications/resources/updated");
        notif.putObject("params").put("uri", uri);
        notifier.broadcast(notif);
    }

    // ── Client-capability requests — server→client requests. Library primitives (invoked by consumer tools/platform).
    //    Only possible when the session surface is on (notifier is an McpClientChannel) and the client has declared the
    //    relevant capability. ──

    private McpClientChannel channel() {
        return notifier instanceof McpClientChannel c ? c : null;
    }

    /**
     * {@code sampling/createMessage} — asks the client's LLM to generate a message (blocking, with timeout).
     * {@code params} follows the spec SamplingMessage shape. Throws if the client has not declared {@code sampling}.
     */
    public JsonNode createMessage(String sessionId, JsonNode params, long timeoutMillis) {
        return clientRequest(sessionId, "sampling/createMessage", "sampling", params, timeoutMillis);
    }

    /** {@code roots/list} — requests the list of roots (workspaces, etc.) exposed by the client. */
    public JsonNode listRoots(String sessionId, long timeoutMillis) {
        return clientRequest(sessionId, "roots/list", "roots", null, timeoutMillis);
    }

    /** {@code elicitation/create} — requests user input (schema-based) from the client. */
    public JsonNode elicit(String sessionId, JsonNode params, long timeoutMillis) {
        return clientRequest(sessionId, "elicitation/create", "elicitation", params, timeoutMillis);
    }

    private JsonNode clientRequest(String sessionId, String method, String requiredCap,
                                   JsonNode params, long timeoutMillis) {
        McpClientChannel ch = channel();
        if (ch == null) {
            throw new IllegalStateException("no server→client request channel (session surface off): " + method);
        }
        if (sessionId == null) {
            throw new IllegalStateException(method + " not possible in a session-less context (stateless/stdio)");
        }
        JsonNode caps = ch.clientCapabilities(sessionId);
        if (caps == null || !caps.has(requiredCap)) {
            throw new IllegalStateException("client did not declare the " + requiredCap + " capability");
        }
        try {
            return ch.requestClient(sessionId, method, params, timeoutMillis)
                    .get(timeoutMillis + 1000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for " + method, e);
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(method + " failed: " + cause.getMessage(), e);
        }
    }

    /**
     * Handles a single JSON-RPC message. Returns the response node, or {@code null} for a notification (no id).
     */
    public JsonNode dispatch(JsonNode request, McpCallContext ctx) {
        JsonNode id = request.get("id");
        boolean notification = id == null || id.isNull();
        JsonNode params = request.get("params");
        JsonNode meta = params == null ? null : params.get("_meta");   // pass through the request _meta
        String inflightKey = null;
        try {
            String method = request.path("method").asText(null);
            if (method == null) {
                throw new McpException(ErrorCode.MALFORMED_REQUEST,
                        ErrorCode.MALFORMED_REQUEST.format("missing method"));
            }
            // A request with an id registers a cancellation token and runs with the progress sink wrapped
            // (cooperative cancellation).
            McpCallContext callCtx;
            if (!notification) {
                McpCancellation token = new McpCancellation();
                McpCallContext.ProgressSink base = ctx.progress();
                McpCallContext.ProgressSink guarded = (c, t, m) -> {
                    token.throwIfCancelled();
                    base.report(c, t, m);
                };
                callCtx = new McpCallContext(ctx.caller(), guarded, ctx.sessionId(), token, meta);
                inflightKey = key(ctx.sessionId(), id.asText());
                inflight.put(inflightKey, new Inflight(token, Thread.currentThread()));
            } else {
                callCtx = new McpCallContext(ctx.caller(), ctx.progress(), ctx.sessionId(), McpCancellation.NONE, meta);
            }
            try {
                JsonNode result = handle(method, params, callCtx);
                return notification ? null : success(id, result);
            } finally {
                if (inflightKey != null) {
                    inflight.remove(inflightKey);
                }
            }
        } catch (McpException e) {
            return notification ? null : error(id, e);
        } catch (RuntimeException e) {
            return notification ? null
                    : error(id, new McpException(ErrorCode.INTERNAL_ERROR, String.valueOf(e.getMessage())));
        }
    }

    /** In-flight cancellation correlation key — session-scoped (global if there is no session). */
    private static String key(String sessionId, String requestId) {
        return sessionId + "|" + requestId;
    }

    private record Inflight(McpCancellation token, Thread thread) {
    }

    /** Sets the page size (adjusted at wiring time). Values of 0 or less are ignored. */
    public void setPageSize(int size) {
        if (size > 0) {
            this.pageSize = size;
        }
    }

    private JsonNode handle(String method, JsonNode params, McpCallContext ctx) {
        return switch (method) {
            case "initialize" -> initialize(params);
            case "ping" -> mapper.createObjectNode();
            case "tools/list" -> paginate((ObjectNode) listTools(), "tools", params);
            case "tools/call" -> callTool(params, ctx);
            case "resources/list" -> paginate((ObjectNode) resources.list(), "resources", params);
            case "resources/templates/list" -> paginate((ObjectNode) resources.listTemplates(), "resourceTemplates", params);
            case "resources/read" -> resources.read(params);
            case "prompts/list" -> paginate((ObjectNode) prompts.list(), "prompts", params);
            case "prompts/get" -> prompts.get(params);
            case "logging/setLevel" -> setLogLevel(params);
            case "completion/complete" -> complete(params);
            case "resources/subscribe" -> subscribe(params);
            case "resources/unsubscribe" -> unsubscribe(params);
            case "notifications/cancelled" -> cancelInflight(params, ctx);   // actual cancellation
            case "notifications/initialized",
                 "notifications/roots/list_changed" -> null; // no-op notif (roots changes are queried on demand, so there is no cache)
            default -> throw McpException.methodNotFound(method);
        };
    }

    /**
     * Cursor-based pagination of the list result's {@code arrayField}. Includes {@code pageSize} entries starting
     * after {@code params.cursor}, and if more remain, appends a {@code nextCursor} (an opaque token). The cursor is
     * a Base64-encoded offset — a malformed cursor yields {@code invalidParams} (spec: an invalid cursor is -32602).
     */
    private JsonNode paginate(ObjectNode result, String arrayField, JsonNode params) {
        JsonNode arrNode = result.get(arrayField);
        if (!(arrNode instanceof ArrayNode all)) {
            return result;
        }
        int offset = decodeCursor(params);
        if (offset > all.size()) {
            offset = all.size();
        }
        int end = Math.min(offset + pageSize, all.size());
        if (offset > 0 || end < all.size()) {
            ArrayNode page = mapper.createArrayNode();
            for (int i = offset; i < end; i++) {
                page.add(all.get(i));
            }
            result.set(arrayField, page);
        }
        if (end < all.size()) {
            result.put("nextCursor", encodeCursor(end));
        }
        return result;
    }

    private int decodeCursor(JsonNode params) {
        if (params == null || !params.hasNonNull("cursor")) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(params.get("cursor").asText()),
                    StandardCharsets.UTF_8);
            int offset = Integer.parseInt(decoded);
            if (offset < 0) {
                throw new NumberFormatException("negative offset");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw McpException.invalidParams("invalid cursor");
        }
    }

    private String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    /** Version negotiation: echoes the requested version if it is in the supported set, otherwise responds with the latest. Only implemented capabilities are advertised. */
    private JsonNode initialize(JsonNode params) {
        String requested = params != null ? params.path("protocolVersion").asText(PROTOCOL_VERSION) : PROTOCOL_VERSION;
        String negotiated = SUPPORTED.contains(requested) ? requested : PROTOCOL_VERSION;

        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", negotiated);
        ObjectNode caps = result.putObject("capabilities"); // advertise only what is implemented, honestly
        caps.putObject("tools").put("listChanged", true);   // runtime tool register/unregister → list_changed push
        // resources: the list itself is fixed, so listChanged is not advertised (honest); content changes are notified via subscribe.
        caps.putObject("resources").put("subscribe", true);
        caps.putObject("prompts");
        caps.putObject("logging");     // logging/setLevel + notifications/message
        caps.putObject("completions"); // completion/complete
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", SERVER_NAME);
        info.put("version", SERVER_VERSION);
        return result;
    }

    private JsonNode listTools() {
        ObjectNode res = mapper.createObjectNode();
        ArrayNode arr = res.putArray("tools");
        for (McpTool t : tools.values()) {
            ObjectNode node = arr.addObject();
            node.put("name", t.name());
            if (t.title() != null) {                       // display name (optional)
                node.put("title", t.title());
            }
            node.put("description", t.description());
            node.set("inputSchema", t.inputSchema());
            ObjectNode out = t.outputSchema();             // output schema (optional)
            if (out != null) {
                node.set("outputSchema", out);
            }
            McpToolAnnotations ann = t.annotations();      // behavior hints (optional)
            if (ann != null) {
                ObjectNode a = ann.toJson(mapper);
                if (a != null) {
                    node.set("annotations", a);
                }
            }
        }
        return res;
    }

    private JsonNode callTool(JsonNode params, McpCallContext ctx) {
        if (params == null || !params.hasNonNull("name")) {
            throw McpException.invalidParams("tools/call: name required");
        }
        String name = params.get("name").asText();
        McpTool tool = tools.get(name);
        if (tool == null) {
            throw McpException.unknownTool(name);
        }
        JsonNode args = params.get("arguments");
        if (args == null || args.isNull()) {
            args = mapper.createObjectNode();
        }

        // Debug execution gate (Model Y) — debug tools are always exposed, but their execution is gated by debugState.
        // When off, reject with isError immediately before tool.call() (zero side effects such as spawning a JDWP worker). Production posture.
        if (tool.action() == ModuleActionAuthorizer.ModuleAction.DEBUG
                && (debugState == null || !debugState.isEnabled())) {
            return toolResultNode(McpToolResult.error(ErrorCode.DEBUG_DISABLED,
                    ErrorCode.DEBUG_DISABLED.format("protean.mcp.debug.enabled=false")));
        }

        // Strict input validation (opt-in): full inputSchema conformance of the arguments. Most valuable for
        // consumer custom tools, which the library's own tests cannot cover. Degrades to no-op when the validator
        // jar is absent (schemaValidator.available() == false) → the tool's own imperative checks still apply.
        if (strictSchema() && schemaValidator.available() && tool.inputSchema() != null) {
            List<String> violations = schemaValidator.validate(tool.inputSchema(), args);
            if (!violations.isEmpty()) {
                return toolResultNode(McpToolResult.error(ErrorCode.INVALID_ARGUMENT,
                        "arguments violate inputSchema: " + String.join("; ", violations)));
            }
        }

        // Authorization choke point — shared by core tools and consumer custom tools.
        ModuleActionAuthorizer.Decision decision =
                authorizer.authorize(ctx.caller(), tool.action(), tool.targetModuleId(args));
        McpToolResult result;
        if (!decision.allowed()) {
            // Include the reason plus action/tool as structured remediation data so the agent clearly recognizes "permission denied".
            String reason = decision.reason() == null ? "authorizer denied" : decision.reason();
            result = McpToolResult.error(ErrorCode.PERMISSION_DENIED, reason)
                    .with("action", tool.action().name())
                    .with("tool", name);
        } else {
            try {
                result = tool.call(args, ctx);
            } catch (McpException e) {
                throw e; // protocol-level errors surface as a JSON-RPC error
            } catch (ProteanException e) {
                // Stable-code domain failure (gate/compile, etc.) → tool result isError + problem-detail shape
                result = McpToolResult.error(e.code(), e.getMessage());
                for (Map.Entry<String, Object> ext : e.extensions().entrySet()) {
                    result = result.with(ext.getKey(), ext.getValue());
                }
            } catch (RuntimeException e) {
                // Not-yet-migrated domain failures surface as a plain isError with no code (to be promoted to ProteanException later)
                result = McpToolResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
        // Minimal conformance check of a successful result for tools that declare an outputSchema. A violation is a
        // server-side bug → surface it as isError instead of silent corruption (blocks the structuredContent bug class).
        if (!result.isError() && tool.outputSchema() != null) {
            String violation;
            if (strictSchema() && schemaValidator.available()) {
                // Full nested/type conformance (opt-in). Same null/non-object precheck as the top-level guard.
                JsonNode structured = result.structured();
                if (structured == null || !structured.isObject()) {
                    violation = "structuredContent missing or not an object (outputSchema declared)";
                } else {
                    List<String> errs = schemaValidator.validate(tool.outputSchema(), structured);
                    violation = errs.isEmpty() ? null : String.join("; ", errs);
                }
            } else {
                violation = outputSchemaViolation(tool.outputSchema(), result.structured());
            }
            if (violation != null) {
                result = McpToolResult.error(ErrorCode.OUTPUT_SCHEMA_VIOLATION,
                        ErrorCode.OUTPUT_SCHEMA_VIOLATION.format(violation));
            }
        }
        return toolResultNode(result);
    }

    /**
     * Zero-dep minimal conformance check. Verifies only the top level of whether a successful result satisfies the
     * outputSchema: that structuredContent is an object and that all schema {@code required} fields are present. Full
     * (nested/type) validation is the client's responsibility (per spec). Returns a violation-reason string, or
     * {@code null} if valid.
     */
    private String outputSchemaViolation(ObjectNode schema, JsonNode structured) {
        if (structured == null || !structured.isObject()) {
            return "structuredContent missing or not an object (outputSchema declared)";
        }
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode r : required) {
                if (!structured.has(r.asText())) {
                    return "missing required field: " + r.asText();
                }
            }
        }
        return null;
    }

    private JsonNode toolResultNode(McpToolResult r) {
        ObjectNode res = mapper.createObjectNode();
        ObjectNode text = res.putArray("content").addObject();
        text.put("type", "text");
        text.put("text", r.text() == null ? "" : r.text());
        if (r.isError()) {
            res.put("isError", true);
        }
        // Success: the tool payload. Error (a problem is attached): the RFC 9457 problem-detail shape as structuredContent.
        JsonNode structured = r.structured();
        if (structured == null && r.problem() != null) {
            structured = r.problem().toJson(mapper);
        }
        if (structured != null) {
            res.set("structuredContent", structured);
        }
        if (r.meta() != null) {
            res.set("_meta", r.meta());   // additional metadata attached by the tool
        }
        return res;
    }

    private JsonNode success(JsonNode id, JsonNode result) {
        ObjectNode res = mapper.createObjectNode();
        res.put("jsonrpc", "2.0");
        res.set("id", id);
        res.set("result", result == null ? mapper.createObjectNode() : result);
        return res;
    }

    /**
     * JSON-RPC error envelope. Keeps the envelope's {@code error.{code,message}}, but for an exception carrying a
     * stable code, assembles the RFC 9457 problem-detail shape into {@code error.data} and prefixes the human-facing
     * {@code message} with {@code [CODE]} (as a guard against lossy harnesses). status is not included (the protocol
     * code lives in the envelope / data.code).
     */
    private JsonNode error(JsonNode id, McpException e) {
        ObjectNode res = mapper.createObjectNode();
        res.put("jsonrpc", "2.0");
        res.set("id", id == null ? NullNode.getInstance() : id);
        ObjectNode err = res.putObject("error");
        err.put("code", e.code());
        ErrorCode ec = e.errorCode();
        if (ec != null) {
            String detail = e.getMessage() == null ? ec.title() : e.getMessage();
            err.put("message", ec.prefixed(detail));
            ProblemDetail pd = ProblemDetail.of(ec).detail(detail);
            for (Map.Entry<String, Object> ext : e.extensions().entrySet()) {
                pd.ext(ext.getKey(), ext.getValue());
            }
            err.set("data", pd.toJson(mapper));
        } else {
            err.put("message", e.getMessage() == null ? "" : e.getMessage());
        }
        return res;
    }
}
