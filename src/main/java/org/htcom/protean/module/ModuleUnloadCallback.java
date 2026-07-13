/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

/**
 * Module unload hook. If a module (child context) or a consumer (root context) declares a bean of this type,
 * the platform invokes it <b>just before</b> closing the module's child context.
 *
 * <p>Purpose = cleaning up <b>out-of-context resources</b> that child.close() cannot reach: ThreadLocals set on
 * shared/pooled threads, static cache registrations, JMX MBeans, custom clients, etc. This is where a module
 * cleans up after itself.
 *
 * <p>Exceptions are swallowed and logged (one callback's failure does not block other cleanup or the unload).
 */
public interface ModuleUnloadCallback {

    /**
     * @param moduleId identifier of the module being unloaded
     */
    void onUnload(String moduleId);
}
