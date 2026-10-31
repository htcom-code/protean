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
import org.htcom.mcpext.McpReadPolicyBeans;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A caller that the consumer's {@link org.htcom.protean.mcp.ModuleActionAuthorizer} denies {@code READ} on {@code tools/call} must be denied
 * the same data on every other MCP path that exposes it: {@code resources/read}, {@code resources/subscribe} and
 * {@code completion/complete}. The authorizer is the consumer's policy; the dispatcher has to consult it on each path.
 *
 * <p>Policy under test ({@link McpReadPolicyBeans}): the anonymous caller is denied every {@code READ};
 * {@code alice} is denied {@code READ} on {@link #SECRET} only.
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
@Import(McpReadPolicyBeans.class)
class McpResourceAuthorizationTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-resource-authz-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    static final String SECRET = McpReadPolicyBeans.SECRET;
    static final String OPEN = "authz-open";
    static final Principal ALICE = () -> "alice";

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;
    @Autowired ModulePlatform platform;

    @BeforeEach
    void deploy() {
        platform.install(module(SECRET, "gen.authzs"));
        platform.install(module(OPEN, "gen.authzo"));
    }

    @AfterEach
    void cleanup() {
        for (String id : List.of(SECRET, OPEN)) {
            try {
                platform.uninstall(id);
            } catch (RuntimeException ignored) {
            }
        }
    }

    // --- baseline: tools/call already enforces the policy ---

    @Test
    void tool_read_of_secret_source_is_denied() {
        assertTrue(isToolDenied(callTool(ALICE, "protean.get_module_source", SECRET)));
        assertFalse(isToolDenied(callTool(ALICE, "protean.get_module_source", OPEN)));
    }

    @Test
    void tool_list_is_denied_to_anonymous() {
        assertTrue(isToolDenied(callTool(null, "protean.list_modules", null)));
    }

    // --- per-module resources follow the same policy ---

    @Test
    void resource_read_of_secret_module_is_denied() {
        for (String kind : List.of("source", "versions", "routes")) {
            JsonNode resp = readResource(ALICE, "protean://modules/" + SECRET + "/" + kind);
            assertTrue(resp.has("error"), kind + " of a READ-denied module must not be returned: " + resp);
        }
    }

    @Test
    void resource_read_of_allowed_module_still_works() {
        JsonNode resp = readResource(ALICE, "protean://modules/" + OPEN + "/source");
        assertFalse(resp.has("error"), resp.toString());
    }

    // --- module-less resources and completion follow the module-less tool decision ---

    @Test
    void resource_read_of_lists_is_denied_to_anonymous() {
        for (String uri : List.of("protean://modules", "protean://traces")) {
            JsonNode resp = readResource(null, uri);
            assertTrue(resp.has("error"), uri + " must not be returned to a caller denied READ: " + resp);
        }
    }

    @Test
    void completion_of_module_ids_is_denied_to_anonymous() {
        ObjectNode params = mapper.createObjectNode();
        params.putObject("ref").put("type", "ref/resource").put("uri", "protean://modules/{id}/source");
        params.putObject("argument").put("name", "id").put("value", "authz");
        JsonNode resp = call(null, "completion/complete", params);
        assertTrue(resp.has("error"), "module ids must not be completed for a caller denied READ: " + resp);
    }

    @Test
    void resource_subscribe_of_secret_module_is_denied() {
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", "protean://modules/" + SECRET + "/source");
        JsonNode resp = call(ALICE, "resources/subscribe", params);
        assertTrue(resp.has("error"), "subscribing to a READ-denied module must be refused: " + resp);
    }

    // --- helpers ---

    private JsonNode call(Principal caller, String method, ObjectNode params) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        return dispatcher.dispatch(req, new McpCallContext(caller, McpCallContext.NOOP));
    }

    private JsonNode readResource(Principal caller, String uri) {
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", uri);
        return call(caller, "resources/read", params);
    }

    private JsonNode callTool(Principal caller, String tool, String moduleId) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", tool);
        ObjectNode args = params.putObject("arguments");
        if (moduleId != null) {
            args.put("id", moduleId);
        }
        return call(caller, "tools/call", params).path("result");
    }

    private static boolean isToolDenied(JsonNode result) {
        return result.path("isError").asBoolean(false)
                && result.path("content").get(0).path("text").asText().contains("[PERMISSION_DENIED]");
    }

    private static ModuleDescriptor module(String id, String pkg) {
        String fqcn = pkg + ".C";
        String src = """
                package %s;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class C {
                    public static String ok() { return "pong"; }
                    @GetMapping("/%s/ping")
                    public String ping() { return ok(); }
                }
                """.formatted(pkg, id);
        String test = """
                package %s;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class CTest { @Test void ok() { assertEquals("pong", C.ok()); } }
                """.formatted(pkg);
        return ModuleDescriptor.builder()
                .id(id).version("1")
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn))
                .sources(Map.of(fqcn, src)).tests(Map.of(pkg + ".CTest", test))
                .build();
    }
}
