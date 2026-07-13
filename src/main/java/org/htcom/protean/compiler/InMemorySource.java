/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

import javax.tools.SimpleJavaFileObject;
import java.net.URI;

/** Exposes an in-memory Java source string as input to the JavaCompiler. */
final class InMemorySource extends SimpleJavaFileObject {

    private final String code;

    InMemorySource(String fqcn, String code) {
        super(URI.create("string:///" + fqcn.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
        this.code = code;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return code;
    }
}
