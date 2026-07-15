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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.dynamic.DynamicEndpointRegistrar;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.proxy.ReverseProxy;
import org.htcom.protean.runtime.TraceStore;
import org.htcom.protean.web.ModuleStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * MCP resources (read-only exposure) — lets an agent inspect state before and after deployment:
 * {@code protean://modules} (list) · {@code protean://traces} (runtime traces) ·
 * {@code protean://modules/{id}/versions} · {@code protean://modules/{id}/source} ·
 * {@code protean://modules/{id}/routes} (the actual routes registered at runtime).
 */
public class McpResources {

    private final ObjectMapper mapper;
    private final ModulePlatform platform;
    private final TraceStore traceStore;
    private final DynamicEndpointRegistrar registrar;
    private final ReverseProxy reverseProxy;

    public McpResources(ObjectMapper mapper, ModulePlatform platform, TraceStore traceStore,
                        DynamicEndpointRegistrar registrar, ReverseProxy reverseProxy) {
        this.mapper = mapper;
        this.platform = platform;
        this.traceStore = traceStore;
        this.registrar = registrar;
        this.reverseProxy = reverseProxy;
    }

    /** resources/list — the two fixed resources (module list and traces). Per-module source/versions are read via the URI convention. */
    public JsonNode list() {
        ObjectNode res = mapper.createObjectNode();
        ArrayNode arr = res.putArray("resources");
        resource(arr, "protean://modules", "modules", "List of deployed module states");
        resource(arr, "protean://traces", "traces", "Recent runtime request traces");
        return res;
    }

    /**
     * resources/templates/list — parameterized resource URI templates (RFC 6570). Fill in a module id and
     * read them via {@code resources/read}: source/versions. This is a separate surface from the static
     * list ({@link #list()}).
     */
    public JsonNode listTemplates() {
        ObjectNode res = mapper.createObjectNode();
        ArrayNode arr = res.putArray("resourceTemplates");
        template(arr, "protean://modules/{id}/source", "module-source", "Module source files (FQCN-to-content map)");
        template(arr, "protean://modules/{id}/versions", "module-versions", "Module version history");
        template(arr, "protean://modules/{id}/routes", "module-routes",
                "Routes the module actually registered at runtime (HTTP method + path). An empty list means none were registered (for example, a compile failure)");
        return res;
    }

    /** resources/read — returns a snapshot JSON for the given uri. */
    public JsonNode read(JsonNode params) {
        String uri = params == null ? null : params.path("uri").asText(null);
        if (uri == null) {
            throw McpException.invalidParams("resources/read: uri required");
        }
        Object payload = resolve(uri);
        ObjectNode res = mapper.createObjectNode();
        ObjectNode entry = res.putArray("contents").addObject();
        entry.put("uri", uri);
        entry.put("mimeType", "application/json");
        try {
            entry.put("text", mapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new McpException(org.htcom.protean.error.ErrorCode.INTERNAL_ERROR,
                    "resource serialization failed: " + e.getMessage());
        }
        return res;
    }

    /**
     * completion/complete — argument autocompletion for resource templates. Completes the {@code id} in
     * {@code protean://modules/{id}/…} by prefix-matching against deployed module ids. Any other uri or
     * argument yields no candidates.
     */
    public java.util.List<String> complete(String uri, String argName, String value) {
        if (uri != null && uri.startsWith("protean://modules/") && "id".equals(argName)) {
            String prefix = value == null ? "" : value;
            return platform.list().stream()
                    .map(ModuleDescriptor::id)
                    .filter(id -> id.startsWith(prefix))
                    .sorted()
                    .limit(100)
                    .toList();
        }
        return java.util.List.of();
    }

    private Object resolve(String uri) {
        if (uri.equals("protean://modules")) {
            // Full status including generation bindings, matching the REST /platform/modules list and the
            // list_modules tool (the 2-arg form left boundGeneration/boundLibraryGenerations/libraryGeneration null).
            return platform.list().stream()
                    .map(d -> ModuleStatus.from(d, platform.effectiveMode(d), platform.boundGeneration(d.id()),
                            platform.boundLibraryGenerations(d.id()), platform.libraryGeneration(d.id())))
                    .toList();
        }
        if (uri.equals("protean://traces")) {
            return traceStore.recent(50, null);
        }
        if (uri.startsWith("protean://modules/")) {
            String rest = uri.substring("protean://modules/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                String id = rest.substring(0, slash);
                String kind = rest.substring(slash + 1);
                if (kind.equals("versions")) {
                    return platform.history(id);
                }
                if (kind.equals("source")) {
                    Optional<ModuleDescriptor> d = platform.find(id);
                    if (d.isEmpty()) {
                        throw McpException.invalidParams("module not found: " + id);
                    }
                    return d.get().sources();
                }
                if (kind.equals("routes")) {
                    if (platform.find(id).isEmpty()) {
                        throw McpException.invalidParams("module not found: " + id);
                    }
                    // Aggregate across isolation modes, mirroring REST GET /platform/modules/{id}/routes: in-process
                    // routes (HTTP methods + path patterns) come from the registrar, while worker/container routes are
                    // served through the ReverseProxy, which does not track the forwarded method (GET-only PoC), so
                    // their methods set is empty. A module is served by exactly one of the two, so no de-duplication is
                    // needed. An empty list means none were registered (for example, a compile failure) — itself a
                    // diagnostic signal. Reading only the registrar would drop every worker/container module's routes.
                    List<DynamicEndpointRegistrar.RouteInfo> routes = new ArrayList<>(registrar.routesOf(id));
                    for (String path : reverseProxy.pathsForModule(id)) {
                        routes.add(new DynamicEndpointRegistrar.RouteInfo(Set.of(), List.of(path)));
                    }
                    return routes;
                }
            }
        }
        throw McpException.invalidParams("unknown resource uri: " + uri);
    }

    private void resource(ArrayNode arr, String uri, String name, String description) {
        ObjectNode r = arr.addObject();
        r.put("uri", uri);
        r.put("name", name);
        r.put("description", description);
        r.put("mimeType", "application/json");
    }

    private void template(ArrayNode arr, String uriTemplate, String name, String description) {
        ObjectNode r = arr.addObject();
        r.put("uriTemplate", uriTemplate);
        r.put("name", name);
        r.put("description", description);
        r.put("mimeType", "application/json");
    }
}
