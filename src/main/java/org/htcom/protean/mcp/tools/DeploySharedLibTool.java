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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.compiler.Generation;
import org.htcom.protean.gate.SharedLibSignatureGate;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpException;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolAnnotations;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.module.SharedLibStore;

import java.util.Base64;
import java.util.List;

/**
 * {@code protean.deploy_shared_lib} — uploads a single native jar (Base64) to the live shared-lib store, publishing a
 * new parent-tier generation. MCP is the small/reference transport; the REST multipart endpoint is primary for large
 * jars. Idempotent on {@code name+version+sha256}; a coordinate conflict is rejected.
 */
public class DeploySharedLibTool implements McpTool {

    private final ObjectMapper mapper;
    private final SharedLibStore store;
    private final SharedLibSignatureGate signatureGate;

    public DeploySharedLibTool(ObjectMapper mapper, SharedLibStore store, SharedLibSignatureGate signatureGate) {
        this.mapper = mapper;
        this.store = store;
        this.signatureGate = signatureGate;
    }

    @Override
    public String name() {
        return "protean.deploy_shared_lib";
    }

    @Override
    public String description() {
        return "Uploads a native jar (Base64) to the live shared-lib store and publishes a new shared-lib generation. "
                + "For large jars prefer the REST multipart endpoint POST /platform/shared-libs.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode p = schema.putObject("properties");
        p.putObject("name").put("type", "string").put("description", "Logical lib name (unique key in the store)");
        p.putObject("version").put("type", "string").put("description", "Lib version");
        p.putObject("bytesBase64").put("type", "string").put("description", "Base64 of the jar file bytes");
        p.putObject("signerKeyId").put("type", "string").put("description", "Optional Ed25519 signer key id (trust seam)");
        p.putObject("signature").put("type", "string").put("description", "Optional Base64 signature (trust seam)");
        schema.putArray("required").add("name").add("version").add("bytesBase64");
        return schema;
    }

    @Override
    public String title() {
        return "Deploy Shared Lib";
    }

    @Override
    public McpToolAnnotations annotations() {
        // Adds/replaces a lib (non-destructive); not idempotent (a new lib advances the generation); closed domain.
        return McpToolAnnotations.builder()
                .readOnly(false).destructive(false).idempotent(false).openWorld(false).build();
    }

    @Override
    public ObjectNode outputSchema() {
        return SharedLibToolSchemas.sharedLibsView(mapper);
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        if (!arguments.hasNonNull("name") || !arguments.hasNonNull("version") || !arguments.hasNonNull("bytesBase64")) {
            throw McpException.invalidParams("deploy_shared_lib: name, version and bytesBase64 are required");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(arguments.get("bytesBase64").asText());
        } catch (IllegalArgumentException e) {
            throw McpException.invalidParams("deploy_shared_lib: bytesBase64 is not valid Base64");
        }
        SharedLibStore.IncomingLib in = new SharedLibStore.IncomingLib(
                arguments.get("name").asText(), arguments.get("version").asText(), bytes,
                arguments.hasNonNull("signerKeyId") ? arguments.get("signerKeyId").asText() : null,
                arguments.hasNonNull("signature") ? arguments.get("signature").asText() : null);
        signatureGate.enforce(in.name(), in.bytes(), in.signerKeyId(), in.signature());
        Generation gen = store.deploy(List.of(in));
        JsonNode structured = mapper.valueToTree(store.view());
        return McpToolResult.ok("Shared lib " + in.name() + " " + in.version()
                + " stored → generation " + gen.id(), structured);
    }
}
