/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.gate;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Captures and holds the actual servlet container port at startup (used by gate ③ to call local endpoints).
 * In a MOCK environment (where WebServerInitializedEvent is not fired) it stays at -1.
 */
@Component
public class ServerPortHolder implements ApplicationListener<WebServerInitializedEvent> {

    private volatile int port = -1;

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
    }

    public int port() {
        return port;
    }
}
