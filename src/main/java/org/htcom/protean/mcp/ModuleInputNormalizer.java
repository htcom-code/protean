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
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleManifestLoader;
import org.htcom.protean.module.ModuleResource;
import org.htcom.protean.module.ResourcePaths;
import org.htcom.protean.module.VerificationPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes MCP deployment tool input into a {@link ModuleDescriptor}. Two forms are accepted, and
 * only one may be specified:
 * <ul>
 *   <li><b>C. Hybrid {@code files[]}</b> (primary) — metadata as flat fields, sources as a file array.
 *       The FQCN is derived automatically from {@code package} + file name
 *       ({@link ModuleManifestLoader#deriveFqcn}). Can express advanced fields such as
 *       {@code verification}.</li>
 *   <li><b>B. {@code manifest}</b> (alternative) — converts {@code module.yaml} text via
 *       {@link ModuleManifestLoader#fromYaml}. A convenience path (but cannot express
 *       verification / signing / PENDING).</li>
 * </ul>
 */
public class ModuleInputNormalizer {

    private final ObjectMapper mapper;
    private final ModuleManifestLoader manifestLoader;

    public ModuleInputNormalizer(ObjectMapper mapper, ModuleManifestLoader manifestLoader) {
        this.mapper = mapper;
        this.manifestLoader = manifestLoader;
    }

    public ModuleDescriptor normalize(JsonNode args) {
        boolean hasFiles = args.hasNonNull("files") && args.get("files").isArray();
        boolean hasManifest = args.hasNonNull("manifest");
        if (hasFiles && hasManifest) {
            throw McpException.invalidParams("cannot provide both files and manifest (specify exactly one)");
        }
        if (hasManifest) {
            return manifestLoader.fromYaml(args.get("manifest").asText(), null);
        }
        if (hasFiles) {
            return fromFiles(args);
        }
        throw McpException.invalidParams("either files[] or manifest is required");
    }

    private ModuleDescriptor fromFiles(JsonNode args) {
        String id = require(args, "id");
        String version = require(args, "version");
        ModuleDescriptor.ModuleKind moduleKind = args.hasNonNull("kind")
                ? ModuleDescriptor.ModuleKind.valueOf(args.get("kind").asText().toUpperCase(java.util.Locale.ROOT))
                : ModuleDescriptor.ModuleKind.NORMAL;
        // A library module registers no routes, so it has no controller; a normal module requires one.
        String controller = moduleKind == ModuleDescriptor.ModuleKind.LIBRARY
                ? (args.hasNonNull("controller") ? args.get("controller").asText() : null)
                : require(args, "controller");

        ModuleDescriptor.TrustTier tier = args.hasNonNull("trustTier")
                ? ModuleDescriptor.TrustTier.valueOf(args.get("trustTier").asText())
                : ModuleDescriptor.TrustTier.TRUSTED;
        String isolationMode = args.hasNonNull("isolationMode") ? args.get("isolationMode").asText() : null;
        boolean needsSharedBeans = args.path("needsSharedBeans").asBoolean(false);
        List<String> components = stringList(args, "components");
        if (components.isEmpty() && controller != null) {
            components = List.of(controller);
        }
        List<String> bridged = stringList(args, "bridgedInterfaces");
        List<String> exports = stringList(args, "exports");
        List<String> uses = stringList(args, "uses");

        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, String> tests = new LinkedHashMap<>();
        Map<String, ModuleResource> resources = new LinkedHashMap<>();
        for (JsonNode f : args.get("files")) {
            String filename = require(f, "filename");
            String content = require(f, "content");
            String kind = f.path("kind").asText("source");
            if ("resource".equalsIgnoreCase(kind)) {
                // Resource: filename is a classpath path (no FQCN derivation). The base64 flag distinguishes text from binary.
                boolean base64 = f.path("base64").asBoolean(false);
                resources.put(ResourcePaths.normalize(filename), new ModuleResource(content, base64));
                continue;
            }
            String stem = filename.endsWith(".java") ? filename.substring(0, filename.length() - 5) : filename;
            String fqcn = ModuleManifestLoader.deriveFqcn(stem, content);
            if ("test".equalsIgnoreCase(kind)) {
                tests.put(fqcn, content);
            } else {
                sources.put(fqcn, content);
            }
        }

        VerificationPlan verification = null;
        if (args.hasNonNull("verification")) {
            try {
                verification = mapper.treeToValue(args.get("verification"), VerificationPlan.class);
            } catch (Exception e) {
                throw McpException.invalidParams("failed to parse verification: " + e.getMessage());
            }
        }

        return ModuleDescriptor.builder()
                .id(id).version(version).trustTier(tier).desiredState(ModuleDescriptor.DesiredState.ACTIVE)
                .controllerFqcn(controller).componentFqcns(components)
                .sources(sources).tests(tests).needsSharedBeans(needsSharedBeans)
                .verification(verification).isolationMode(isolationMode)
                .bridgedInterfaces(bridged.isEmpty() ? null : bridged)
                .resources(resources)
                .kind(moduleKind).exports(exports).uses(uses)
                .build();
    }

    private static String require(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw McpException.invalidParams("missing required field: " + field);
        }
        return node.get(field).asText();
    }

    private static List<String> stringList(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        if (node.hasNonNull(field) && node.get(field).isArray()) {
            node.get(field).forEach(n -> out.add(n.asText()));
        }
        return out;
    }
}
