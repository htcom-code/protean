/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.worker;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * On worker startup, prints the actual bound port as a single line to stdout (for the main
 * process's port handshake). Active only on the worker profile.
 */
@Component
@Profile("worker")
public class WorkerPortAnnouncer implements ApplicationListener<WebServerInitializedEvent> {

    public static final String MARKER = "WORKER_PORT=";

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        // The marker is a single ASCII line, flushed immediately (cross-platform handshake).
        System.out.println(MARKER + port);
        System.out.flush();
    }
}
