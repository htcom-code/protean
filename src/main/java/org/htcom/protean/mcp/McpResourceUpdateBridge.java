/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

import org.htcom.protean.module.ModulePlatform;

/**
 * Bridges module changes to MCP {@code notifications/resources/updated}. Registers a platform change
 * listener so that when a module changes, the affected resource URIs are notified via
 * {@link McpDispatcher#notifyResourceUpdated} (only subscribed URIs are actually emitted). A thin
 * adapter that connects the core platform and the MCP surface without coupling them.
 */
public class McpResourceUpdateBridge {

    public McpResourceUpdateBridge(McpDispatcher dispatcher, ModulePlatform platform) {
        platform.addChangeListener(moduleId -> {
            dispatcher.notifyResourceUpdated("protean://modules");                       // list resource
            if (moduleId != null) {
                dispatcher.notifyResourceUpdated("protean://modules/" + moduleId + "/source");
                dispatcher.notifyResourceUpdated("protean://modules/" + moduleId + "/versions");
            }
        });
    }
}
