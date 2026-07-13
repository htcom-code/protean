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
import org.htcom.protean.mcp.McpResourceUpdateBridge;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.htcom.protean.mcp.session.McpServerNotifier;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the full path where a real {@link ModulePlatform} change is emitted as
 * {@code notifications/resources/updated} through {@link McpResourceUpdateBridge} (real-JVM verification).
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
class McpResourceUpdateBridgeTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-d4-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired ObjectMapper mapper;
    @Autowired ModulePlatform platform;

    static final String ID = "d4-mod";
    static final String FQCN = "runtime.d4.D4Controller";
    static final String TEST_FQCN = "runtime.d4.D4ControllerTest";

    static final class CapturingNotifier implements McpServerNotifier {
        final List<JsonNode> sent = new ArrayList<>();
        @Override public void broadcast(JsonNode n) { sent.add(n); }
        @Override public void notifySession(String id, JsonNode n) { sent.add(n); }
    }

    static ModuleDescriptor descriptor() {
        String src = """
                package runtime.d4;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class D4Controller {
                    @GetMapping("/d4-mod/ping") public String ping() { return "ok"; }
                }
                """;
        String test = """
                package runtime.d4;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class D4ControllerTest {
                    @Test void ping() { assertEquals("ok", new D4Controller().ping()); }
                }
                """;
        return ModuleDescriptor.builder()
                .id(ID).version("1.0.0")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN))
                .sources(Map.of(FQCN, src)).tests(Map.of(TEST_FQCN, test))
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall(ID);
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void module_install_emits_resources_updated_for_subscribed_uris() {
        CapturingNotifier notifier = new CapturingNotifier();
        ModuleActionAuthorizer allowAll =
                (caller, action, moduleId) -> ModuleActionAuthorizer.Decision.allow();
        McpDispatcher dispatcher = new McpDispatcher(mapper, List.of(), allowAll, null, null, notifier, null);
        new McpResourceUpdateBridge(dispatcher, platform);   // register platform listener

        subscribe(dispatcher, "protean://modules");
        subscribe(dispatcher, "protean://modules/" + ID + "/source");

        platform.install(descriptor());

        List<String> updated = new ArrayList<>();
        for (JsonNode n : notifier.sent) {
            if ("notifications/resources/updated".equals(n.path("method").asText())) {
                updated.add(n.path("params").path("uri").asText());
            }
        }
        assertTrue(updated.contains("protean://modules"), "list resource updated: " + updated);
        assertTrue(updated.contains("protean://modules/" + ID + "/source"), "source resource updated: " + updated);
    }

    private void subscribe(McpDispatcher d, String uri) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "resources/subscribe");
        ObjectNode p = req.putObject("params");
        p.put("uri", uri);
        d.dispatch(req, McpCallContext.anonymous());
    }
}
