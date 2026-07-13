/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

import javax.tools.SimpleJavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;

/** Holds the .class bytecode emitted by javac in memory instead of on disk. */
final class InMemoryClassFile extends SimpleJavaFileObject {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    InMemoryClassFile(String fqcn) {
        super(URI.create("bytes:///" + fqcn.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
    }

    @Override
    public OutputStream openOutputStream() {
        return bytes;
    }

    byte[] toByteArray() {
        return bytes.toByteArray();
    }
}
