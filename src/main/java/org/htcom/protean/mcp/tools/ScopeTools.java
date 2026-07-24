/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.db.ScopeAdminService;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolAnnotations;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.springframework.beans.factory.ObjectProvider;

/**
 * MCP {@code protean.scope_*} tool family — parity with the REST scope-admin surface ({@code /platform/scopes}). An
 * agent can list/inspect scopes and, as an operator, create/open/close/detach/destroy them.
 *
 * <p><b>Exposure follows the {@code debug.*} convention</b>: the tools are always listed (registered under
 * {@code protean.mcp.enabled}) so an agent can discover them, but they are <b>gated at call time</b> on
 * auto-provision. When {@code worker.db.auto-provision} is off the backing {@link ScopeAdminService} bean is absent, so
 * every call returns a clear {@code isError} telling the caller to enable it — rather than the tools silently not
 * existing. All mutations classify as {@link ModuleActionAuthorizer.ModuleAction#CUSTOM}; {@code destroy} carries the
 * extra {@code allow-destroy + confirm} gate inside {@link ScopeAdminService}.
 */
public final class ScopeTools {

    private ScopeTools() {
    }

    // --- output schemas (contract completeness: every tool that emits structuredContent declares one) ---

    /** A single scope view: {name, state, dialectId?, modules}. */
    static ObjectNode scopeViewSchema(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("name").put("type", "string").put("description", "Scope name.");
        ObjectNode state = p.putObject("state");
        state.put("type", "string").put("description", "Lifecycle state.");
        state.putArray("enum").add("ACTIVE").add("CLOSED").add("DETACHED");
        p.putObject("dialectId").putArray("type").add("string").add("null");  // nullable until provisioned
        p.putObject("modules").put("type", "integer").put("description", "ACTIVE modules bound to the scope.");
        s.putArray("required").add("name").add("state").add("modules");
        s.put("additionalProperties", false);
        return s;
    }

    /** The list result wrapper: {scopes: [scopeView, ...]} (structuredContent must be an object, not a bare array). */
    static ObjectNode scopeListSchema(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode scopes = s.putObject("properties").putObject("scopes");
        scopes.put("type", "array");
        scopes.set("items", scopeViewSchema(m));
        s.putArray("required").add("scopes");
        s.put("additionalProperties", false);
        return s;
    }

    /** The destroy acknowledgement: {name, destroyed}. */
    static ObjectNode scopeDestroySchema(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("name").put("type", "string");
        p.putObject("destroyed").put("type", "boolean");
        s.putArray("required").add("name").add("destroyed");
        s.put("additionalProperties", false);
        return s;
    }

    abstract static class Base implements McpTool {
        final ObjectMapper mapper;
        private final ObjectProvider<ScopeAdminService> svcProvider;

        Base(ObjectMapper mapper, ObjectProvider<ScopeAdminService> svcProvider) {
            this.mapper = mapper;
            this.svcProvider = svcProvider;
        }

        /** Input schema with a required {@code name} (plus {@code confirm} when {@code withConfirm}). */
        ObjectNode nameSchema(boolean withConfirm) {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            props.putObject("name").put("type", "string").put("description", "Scope name.");
            ArrayNode required = schema.putArray("required");
            required.add("name");
            if (withConfirm) {
                props.putObject("confirm").put("type", "string")
                        .put("description", "Must equal 'name' to acknowledge irreversible deletion.");
                required.add("confirm");
            }
            schema.put("additionalProperties", false);
            return schema;
        }

        @Override
        public ObjectNode inputSchema() {
            return nameSchema(false);
        }

        String requiredName(JsonNode args) {
            if (args == null || !args.hasNonNull("name") || args.get("name").asText().isBlank()) {
                throw new IllegalArgumentException("'name' is required");
            }
            return args.get("name").asText();
        }

        JsonNode tree(ScopeAdminService.ScopeView view) {
            return mapper.valueToTree(view);
        }

        @Override
        public ModuleActionAuthorizer.ModuleAction action() {
            return ModuleActionAuthorizer.ModuleAction.CUSTOM;
        }

        @Override
        public String targetModuleId(JsonNode arguments) {
            return null;   // scope tools target a scope name, not a module id
        }

        /**
         * Two-stage gate. (1) <b>Argument contract first</b>: a missing declared-required field is
         * {@code INVALID_ARGUMENT} regardless of whether auto-provision is on — otherwise the capability gate below
         * would mask it and {@code McpInputContractTest}'s "omitting a required field fails" guarantee would pass for
         * the wrong reason (the call fails on the gate, not the missing field). (2) <b>Capability gate</b> (mirrors the
         * {@code debug.*} surface gate): resolve the {@link ScopeAdminService} — absent when auto-provision is off — and
         * short-circuit with a clear {@code isError} rather than executing.
         */
        @Override
        public final McpToolResult call(JsonNode arguments, McpCallContext ctx) {
            for (JsonNode req : inputSchema().path("required")) {
                String field = req.asText();
                boolean missing = arguments == null || !arguments.hasNonNull(field)
                        || (arguments.get(field).isTextual() && arguments.get(field).asText().isBlank());
                if (missing) {
                    return McpToolResult.error(ErrorCode.INVALID_ARGUMENT, "'" + field + "' is required");
                }
            }
            ScopeAdminService svc = svcProvider.getIfAvailable();
            if (svc == null) {
                return McpToolResult.error(ErrorCode.STATE_CONFLICT,
                        "scope tools require worker.db.auto-provision=true — auto-provision is disabled, so there are "
                                + "no scopes to manage");
            }
            return callWith(arguments, ctx, svc);
        }

        protected abstract McpToolResult callWith(JsonNode arguments, McpCallContext ctx, ScopeAdminService svc);
    }

    public static final class ListTool extends Base {
        public ListTool(ObjectMapper mapper, ObjectProvider<ScopeAdminService> svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_list";
        }

        @Override
        public ObjectNode outputSchema() {
            return scopeListSchema(mapper);
        }

        @Override
        public String title() {
            return "List Scopes";
        }

        @Override
        public String description() {
            return "Lists all known DB scopes (registry ∪ startup seed) with state (ACTIVE/CLOSED/DETACHED), "
                    + "dialect, and current module count. Requires worker.db.auto-provision.";
        }

        @Override
        public ObjectNode inputSchema() {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties");
            schema.put("additionalProperties", false);
            return schema;
        }

        @Override
        public McpToolAnnotations annotations() {
            return McpToolAnnotations.readOnly();
        }

        @Override
        protected McpToolResult callWith(JsonNode arguments, McpCallContext ctx, ScopeAdminService svc) {
            ArrayNode arr = mapper.valueToTree(svc.list());
            ObjectNode structured = mapper.createObjectNode();
            structured.set("scopes", arr);
            return McpToolResult.ok("Known scopes: " + arr.size(), structured);
        }
    }

    public static final class GetTool extends Base {
        public GetTool(ObjectMapper mapper, ObjectProvider<ScopeAdminService> svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_get";
        }

        @Override
        public ObjectNode outputSchema() {
            return scopeViewSchema(mapper);
        }

        @Override
        public String title() {
            return "Get Scope";
        }

        @Override
        public String description() {
            return "Returns one scope's state, dialect, and module count (error if the scope is unknown). "
                    + "Requires worker.db.auto-provision.";
        }

        @Override
        public McpToolAnnotations annotations() {
            return McpToolAnnotations.readOnly();
        }

        @Override
        protected McpToolResult callWith(JsonNode arguments, McpCallContext ctx, ScopeAdminService svc) {
            String name = requiredName(arguments);
            ScopeAdminService.ScopeView view = svc.get(name)
                    .orElseThrow(() -> new IllegalArgumentException("unknown scope '" + name + "'"));
            return McpToolResult.ok("Scope " + name + " (" + view.state() + ")", tree(view));
        }
    }

    public static final class CreateTool extends Base {
        public CreateTool(ObjectMapper mapper, ObjectProvider<ScopeAdminService> svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_create";
        }

        @Override
        public ObjectNode outputSchema() {
            return scopeViewSchema(mapper);
        }

        @Override
        public String title() {
            return "Create Scope";
        }

        @Override
        public String description() {
            return "Creates (or reopens) a scope as ACTIVE. The database is provisioned lazily on the first deploy. "
                    + "Idempotent. Requires worker.db.auto-provision.";
        }

        @Override
        public McpToolAnnotations annotations() {
            return McpToolAnnotations.builder().readOnly(false).destructive(false).idempotent(true).openWorld(false).build();
        }

        @Override
        protected McpToolResult callWith(JsonNode arguments, McpCallContext ctx, ScopeAdminService svc) {
            String name = requiredName(arguments);
            return McpToolResult.ok("Scope " + name + " created/opened (ACTIVE)", tree(svc.create(name)));
        }
    }

    public static final class OpenTool extends Base {
        public OpenTool(ObjectMapper mapper, ObjectProvider<ScopeAdminService> svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_open";
        }

        @Override
        public ObjectNode outputSchema() {
            return scopeViewSchema(mapper);
        }

        @Override
        public String title() {
            return "Open Scope";
        }

        @Override
        public String description() {
            return "Reopens a CLOSED scope back to ACTIVE so modules can deploy to it again. "
                    + "Requires worker.db.auto-provision.";
        }

        @Override
        public McpToolAnnotations annotations() {
            return McpToolAnnotations.builder().readOnly(false).destructive(false).idempotent(true).openWorld(false).build();
        }

        @Override
        protected McpToolResult callWith(JsonNode arguments, McpCallContext ctx, ScopeAdminService svc) {
            String name = requiredName(arguments);
            return McpToolResult.ok("Scope " + name + " reopened (ACTIVE)", tree(svc.open(name)));
        }
    }

    public static final class CloseTool extends Base {
        public CloseTool(ObjectMapper mapper, ObjectProvider<ScopeAdminService> svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_close";
        }

        @Override
        public ObjectNode outputSchema() {
            return scopeViewSchema(mapper);
        }

        @Override
        public String title() {
            return "Close Scope";
        }

        @Override
        public String description() {
            return "Closes a scope: new deploys are rejected, running modules keep serving. Reversible via "
                    + "protean.scope_open. Data untouched. Requires worker.db.auto-provision.";
        }

        @Override
        public McpToolAnnotations annotations() {
            return McpToolAnnotations.builder().readOnly(false).destructive(false).idempotent(true).openWorld(false).build();
        }

        @Override
        protected McpToolResult callWith(JsonNode arguments, McpCallContext ctx, ScopeAdminService svc) {
            String name = requiredName(arguments);
            return McpToolResult.ok("Scope " + name + " closed (new deploys rejected)", tree(svc.close(name)));
        }
    }

    public static final class DetachTool extends Base {
        public DetachTool(ObjectMapper mapper, ObjectProvider<ScopeAdminService> svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_detach";
        }

        @Override
        public ObjectNode outputSchema() {
            return scopeViewSchema(mapper);
        }

        @Override
        public String title() {
            return "Detach Scope";
        }

        @Override
        public String description() {
            return "Detaches a scope: undeploys its modules and drops its DB login, but retains the database and all "
                    + "data. Reversible — re-create and redeploy re-provisions the login. Requires worker.db.auto-provision.";
        }

        @Override
        public McpToolAnnotations annotations() {
            // Takes modules down (a change) but data-safe and reversible → not destructive.
            return McpToolAnnotations.builder().readOnly(false).destructive(false).idempotent(true).openWorld(false).build();
        }

        @Override
        protected McpToolResult callWith(JsonNode arguments, McpCallContext ctx, ScopeAdminService svc) {
            String name = requiredName(arguments);
            return McpToolResult.ok("Scope " + name + " detached (data retained)", tree(svc.detach(name)));
        }
    }

    public static final class DestroyTool extends Base {
        public DestroyTool(ObjectMapper mapper, ObjectProvider<ScopeAdminService> svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_destroy";
        }

        @Override
        public ObjectNode outputSchema() {
            return scopeDestroySchema(mapper);
        }

        @Override
        public String title() {
            return "Destroy Scope";
        }

        @Override
        public String description() {
            return "IRREVERSIBLY drops a scope's database/schema after undeploying its modules — all data is lost. "
                    + "Guarded: requires worker.db.allow-destroy=true and 'confirm' equal to the scope name.";
        }

        @Override
        public ObjectNode inputSchema() {
            return nameSchema(true);
        }

        @Override
        public McpToolAnnotations annotations() {
            return McpToolAnnotations.builder().readOnly(false).destructive(true).idempotent(false).openWorld(false).build();
        }

        @Override
        protected McpToolResult callWith(JsonNode arguments, McpCallContext ctx, ScopeAdminService svc) {
            String name = requiredName(arguments);
            String confirm = arguments.hasNonNull("confirm") ? arguments.get("confirm").asText() : null;
            svc.destroy(name, confirm);
            ObjectNode structured = mapper.createObjectNode();
            structured.put("name", name);
            structured.put("destroyed", true);
            return McpToolResult.ok("Scope " + name + " destroyed (database dropped, irreversible)", structured);
        }
    }
}
