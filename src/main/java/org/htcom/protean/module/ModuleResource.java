/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single non-Java resource carried by a module (mapper XML, persistence.xml, migration SQL, .properties, keystore, etc.).
 *
 * <p>{@code content} is plain UTF-8 text when {@code base64=false}, or Base64-encoded bytes when {@code base64=true}.
 * Text configuration travels as plain text and binaries (certificates, keystores) travel as base64, all over one channel.
 *
 * @param content resource content (plain text or Base64)
 * @param base64  true if {@code content} is Base64-encoded bytes
 */
public record ModuleResource(String content, boolean base64) {

    /** Plain-text resource. */
    public static ModuleResource text(String content) {
        return new ModuleResource(content, false);
    }

    /** Binary resource (stored Base64-encoded). */
    public static ModuleResource binary(byte[] bytes) {
        return new ModuleResource(Base64.getEncoder().encodeToString(bytes), true);
    }

    /** The decoded raw bytes. */
    public byte[] bytes() {
        return base64
                ? Base64.getDecoder().decode(content)
                : content.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes a {@code (path -> ModuleResource)} map into {@code (normalized path -> bytes)}.
     * Paths are normalized and validated via {@link ResourcePaths#normalize} (blocks traversal).
     */
    public static Map<String, byte[]> decodeAll(Map<String, ModuleResource> resources) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (resources == null) {
            return out;
        }
        resources.forEach((path, res) -> out.put(ResourcePaths.normalize(path), res.bytes()));
        return out;
    }
}
