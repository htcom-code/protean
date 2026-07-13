/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

/** Result of runtime compilation + load: the dedicated ClassLoader and the main class loaded on it. */
public record CompiledModule(ModuleClassLoader classLoader, Class<?> mainClass) {

    /** Creates an instance via the main class's default constructor. */
    public Object newInstance() throws ReflectiveOperationException {
        return mainClass.getDeclaredConstructor().newInstance();
    }
}
