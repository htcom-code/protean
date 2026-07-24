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
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolAnnotations;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;

/**
 * MCP {@code protean.scope_*} tool family — parity with the REST scope-admin surface ({@code /platform/scopes}). An
 * agent can list/inspect scopes and, as an operator, create/open/close/detach/destroy them. All mutations classify as
 * {@link ModuleActionAuthorizer.ModuleAction#CUSTOM} (a consumer authorizer decides); {@code destroy} carries the extra
 * {@code allow-destroy + confirm} gate inside {@link ScopeAdminService}.
 *
 * <p>Registered only under {@code protean.worker.db.auto-provision=true} (see {@code McpConfiguration}) — scopes exist
 * only then. Each tool is a small class here sharing {@link Base} to keep the schema/serialization uniform.
 */
public final class ScopeTools {

    private ScopeTools() {
    }

    abstract static class Base implements McpTool {
        final ObjectMapper mapper;
        final ScopeAdminService svc;

        Base(ObjectMapper mapper, ScopeAdminService svc) {
            this.mapper = mapper;
            this.svc = svc;
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
    }

    public static final class ListTool extends Base {
        public ListTool(ObjectMapper mapper, ScopeAdminService svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_list";
        }

        @Override
        public String title() {
            return "List Scopes";
        }

        @Override
        public String description() {
            return "Lists all known DB scopes (registry ∪ startup seed) with state (ACTIVE/CLOSED/DETACHED), "
                    + "dialect, and current module count.";
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
        public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
            ArrayNode arr = mapper.valueToTree(svc.list());
            ObjectNode structured = mapper.createObjectNode();
            structured.set("scopes", arr);
            return McpToolResult.ok("Known scopes: " + arr.size(), structured);
        }
    }

    public static final class GetTool extends Base {
        public GetTool(ObjectMapper mapper, ScopeAdminService svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_get";
        }

        @Override
        public String title() {
            return "Get Scope";
        }

        @Override
        public String description() {
            return "Returns one scope's state, dialect, and module count (error if the scope is unknown).";
        }

        @Override
        public McpToolAnnotations annotations() {
            return McpToolAnnotations.readOnly();
        }

        @Override
        public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
            String name = requiredName(arguments);
            ScopeAdminService.ScopeView view = svc.get(name)
                    .orElseThrow(() -> new IllegalArgumentException("unknown scope '" + name + "'"));
            return McpToolResult.ok("Scope " + name + " (" + view.state() + ")", tree(view));
        }
    }

    public static final class CreateTool extends Base {
        public CreateTool(ObjectMapper mapper, ScopeAdminService svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_create";
        }

        @Override
        public String title() {
            return "Create Scope";
        }

        @Override
        public String description() {
            return "Creates (or reopens) a scope as ACTIVE. The database is provisioned lazily on the first deploy. "
                    + "Idempotent.";
        }

        @Override
        public McpToolAnnotations annotations() {
            return McpToolAnnotations.builder().readOnly(false).destructive(false).idempotent(true).openWorld(false).build();
        }

        @Override
        public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
            String name = requiredName(arguments);
            return McpToolResult.ok("Scope " + name + " created/opened (ACTIVE)", tree(svc.create(name)));
        }
    }

    public static final class OpenTool extends Base {
        public OpenTool(ObjectMapper mapper, ScopeAdminService svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_open";
        }

        @Override
        public String title() {
            return "Open Scope";
        }

        @Override
        public String description() {
            return "Reopens a CLOSED scope back to ACTIVE so modules can deploy to it again.";
        }

        @Override
        public McpToolAnnotations annotations() {
            return McpToolAnnotations.builder().readOnly(false).destructive(false).idempotent(true).openWorld(false).build();
        }

        @Override
        public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
            String name = requiredName(arguments);
            return McpToolResult.ok("Scope " + name + " reopened (ACTIVE)", tree(svc.open(name)));
        }
    }

    public static final class CloseTool extends Base {
        public CloseTool(ObjectMapper mapper, ScopeAdminService svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_close";
        }

        @Override
        public String title() {
            return "Close Scope";
        }

        @Override
        public String description() {
            return "Closes a scope: new deploys are rejected, running modules keep serving. Reversible via "
                    + "protean.scope_open. Data untouched.";
        }

        @Override
        public McpToolAnnotations annotations() {
            return McpToolAnnotations.builder().readOnly(false).destructive(false).idempotent(true).openWorld(false).build();
        }

        @Override
        public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
            String name = requiredName(arguments);
            return McpToolResult.ok("Scope " + name + " closed (new deploys rejected)", tree(svc.close(name)));
        }
    }

    public static final class DetachTool extends Base {
        public DetachTool(ObjectMapper mapper, ScopeAdminService svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_detach";
        }

        @Override
        public String title() {
            return "Detach Scope";
        }

        @Override
        public String description() {
            return "Detaches a scope: undeploys its modules and drops its DB login, but retains the database and all "
                    + "data. Reversible — re-create and redeploy re-provisions the login.";
        }

        @Override
        public McpToolAnnotations annotations() {
            // Takes modules down (a change) but data-safe and reversible → not destructive.
            return McpToolAnnotations.builder().readOnly(false).destructive(false).idempotent(true).openWorld(false).build();
        }

        @Override
        public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
            String name = requiredName(arguments);
            return McpToolResult.ok("Scope " + name + " detached (data retained)", tree(svc.detach(name)));
        }
    }

    public static final class DestroyTool extends Base {
        public DestroyTool(ObjectMapper mapper, ScopeAdminService svc) {
            super(mapper, svc);
        }

        @Override
        public String name() {
            return "protean.scope_destroy";
        }

        @Override
        public String title() {
            return "Destroy Scope";
        }

        @Override
        public String description() {
            return "IRREVERSIBLY drops a scope's database/schema after undeploying its modules — all data is lost. "
                    + "Guarded: requires protean.worker.db.allow-destroy=true and 'confirm' equal to the scope name.";
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
        public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
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
