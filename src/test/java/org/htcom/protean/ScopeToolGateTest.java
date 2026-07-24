/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.db.ScopeAdminService;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.tools.ScopeTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code protean.scope_*} tools follow the {@code debug.*} convention: always exposed (so an agent can discover
 * them), but gated at call time on auto-provision. When {@code worker.db.auto-provision} is off the
 * {@link ScopeAdminService} bean is absent, so a call returns a clear {@code isError} rather than the tool being hidden.
 */
class ScopeToolGateTest {

    /** ObjectProvider that resolves to nothing (simulates auto-provision off → no ScopeAdminService bean). */
    private static ObjectProvider<ScopeAdminService> absent() {
        return new ObjectProvider<>() {
            @Override
            public ScopeAdminService getObject() {
                throw new NoSuchBeanDefinitionException(ScopeAdminService.class);
            }

            @Override
            public ScopeAdminService getObject(Object... args) {
                throw new NoSuchBeanDefinitionException(ScopeAdminService.class);
            }
        };
    }

    @Test
    void scope_tools_are_listed_but_error_at_call_time_when_auto_provision_is_off() {
        ObjectMapper mapper = new ObjectMapper();
        // Exposure: the tool exists in the registry with a name/schema regardless of auto-provision.
        McpTool tool = new ScopeTools.ListTool(mapper, absent());
        assertEquals("protean.scope_list", tool.name());
        org.junit.jupiter.api.Assertions.assertNotNull(tool.inputSchema());

        // Call-time gate: auto-provision off → isError with a message pointing at the toggle (not a hidden tool).
        McpToolResult r = tool.call(mapper.createObjectNode(), null);
        assertTrue(r.isError(), "call must error when auto-provision is off");
        assertTrue(r.text() != null && r.text().contains("auto-provision"),
                "the error must name auto-provision: " + r.text());
    }

    @Test
    void required_argument_check_runs_before_the_auto_provision_gate() {
        // Ordering guarantee: a missing required field must fail as an argument error even when auto-provision is off —
        // otherwise the capability gate would mask it and McpInputContractTest's required-field guarantee would be
        // vacuous for these tools (it only checks isError, not why).
        ObjectMapper mapper = new ObjectMapper();
        McpTool getTool = new ScopeTools.GetTool(mapper, absent());

        // No 'name' → argument contract fires first (INVALID_ARGUMENT), NOT the auto-provision gate.
        McpToolResult missing = getTool.call(mapper.createObjectNode(), null);
        assertTrue(missing.isError());
        assertTrue(missing.text() != null && missing.text().contains("required"),
                "missing required field must fail as an argument error, not the gate: " + missing.text());

        // 'name' present but auto-provision off → now the capability gate fires.
        McpToolResult gated = getTool.call(mapper.createObjectNode().put("name", "tenant-a"), null);
        assertTrue(gated.isError());
        assertTrue(gated.text() != null && gated.text().contains("auto-provision"),
                "with the argument present, the auto-provision gate must fire: " + gated.text());
    }

    @Test
    void every_scope_tool_declares_an_output_schema() {
        // Contract completeness (the original gap a): every scope tool emits structuredContent, so each MUST declare an
        // outputSchema. Locks it for all seven so re-introducing the gap on any one fails here.
        ObjectMapper m = new ObjectMapper();
        McpTool[] tools = {
                new ScopeTools.ListTool(m, absent()), new ScopeTools.GetTool(m, absent()),
                new ScopeTools.CreateTool(m, absent()), new ScopeTools.OpenTool(m, absent()),
                new ScopeTools.CloseTool(m, absent()), new ScopeTools.DetachTool(m, absent()),
                new ScopeTools.DestroyTool(m, absent()),
        };
        for (McpTool t : tools) {
            org.junit.jupiter.api.Assertions.assertNotNull(t.outputSchema(),
                    t.name() + " emits structuredContent, so it must declare an outputSchema");
            assertTrue(t.outputSchema().path("type").asText().equals("object"),
                    t.name() + " outputSchema must be an object (structuredContent is an object)");
        }
    }

    @Test
    void destroy_tool_declares_the_confirm_argument() {
        ObjectMapper mapper = new ObjectMapper();
        ScopeTools.DestroyTool tool = new ScopeTools.DestroyTool(mapper, absent());
        // destroy exposes name + confirm even while gated (discoverable contract).
        assertTrue(tool.inputSchema().get("required").toString().contains("confirm"));
        assertTrue(tool.annotations().toJson(mapper).get("destructiveHint").asBoolean(),
                "destroy must be flagged destructive");
    }
}
