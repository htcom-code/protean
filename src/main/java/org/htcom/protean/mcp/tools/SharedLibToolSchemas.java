/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Output JSON Schema builders for the shared-lib (put-jar) MCP tools. Must stay consistent with the fields of
 * {@link org.htcom.protean.module.SharedLibStore.StoredLib} / {@code SharedLibsView} — the tools emit exactly these
 * shapes (verified by {@code SchemaConformance}).
 */
final class SharedLibToolSchemas {

    private SharedLibToolSchemas() {
    }

    /** A single stored lib's metadata. {@code signerKeyId}/{@code signature} are null when the upload was unsigned. */
    static ObjectNode storedLib(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("name").put("type", "string");
        p.putObject("version").put("type", "string");
        p.putObject("sha256").put("type", "string");
        p.putObject("size").put("type", "integer");
        p.putObject("signerKeyId").putArray("type").add("string").add("null");
        p.putObject("signature").putArray("type").add("string").add("null");
        s.putArray("required").add("name").add("version").add("sha256").add("size");
        return s;
    }

    /** The store view: the current generation id plus the live stored libs (array wrapped in an object). */
    static ObjectNode sharedLibsView(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("generation").put("type", "integer")
                .put("description", "The current parent-tier generation id");
        ObjectNode libs = p.putObject("libs");
        libs.put("type", "array");
        libs.set("items", storedLib(m));
        s.putArray("required").add("generation").add("libs");
        return s;
    }
}
