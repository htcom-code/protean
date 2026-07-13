/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProteanException;

/**
 * Module resource path normalization and validation. Resource paths must be relative to the classpath root;
 * leading slashes are stripped, and absolute paths or parent escapes ({@code ../}) are rejected (blocks traversal).
 */
public final class ResourcePaths {

    private ResourcePaths() {
    }

    /**
     * Normalizes to a classpath-relative path. Backslashes become slashes, and leading slashes are stripped.
     *
     * @throws ProteanException ({@link ErrorCode#INVALID_ARGUMENT}) on null/blank, absolute path, or a {@code ..}
     *         segment. The offending value is attached as the {@code path} extension member.
     */
    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            throw invalid("resource path is empty", path);
        }
        String p = path.replace('\\', '/').trim();
        // Strip leading slashes (classpath convention: ClassLoader.getResource looks up without a leading slash)
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) {
            throw invalid("resource path is empty: " + path, path);
        }
        if (p.contains(":")) {
            throw invalid("resource path must not contain a scheme/drive: " + path, path);
        }
        for (String seg : p.split("/")) {
            if (seg.equals("..")) {
                throw invalid("resource path parent escape (..) is not allowed: " + path, path);
            }
        }
        return p;
    }

    private static ProteanException invalid(String detail, String path) {
        return new ProteanException(ErrorCode.INVALID_ARGUMENT, detail).with("path", path);
    }
}
