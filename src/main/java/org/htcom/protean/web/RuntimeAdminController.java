/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.web;

import org.htcom.protean.isolation.RuntimeInfo;
import org.htcom.protean.module.ModulePlatform;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only view of the runtimes hosting modules: the main JVM, each worker JVM, each worker container.
 *
 * <p>Module status carries a {@code runtimeId}, so grouping modules by it already answers "which modules share a JVM".
 * This surface answers what that grouping cannot: a runtime hosting <b>nothing</b> — kept warm for reuse, or retiring
 * while its last requests drain — and how long each has been up. Under auto-provision it is also the per-scope capacity
 * view, since a runtime is bound to at most one scope.
 *
 * <p>Read-only by design. Draining or retiring a specific worker overlaps with the scope lifecycle
 * ({@code /platform/scopes}) and with the packing guarantees the platform makes, so it is a separate decision rather
 * than an action bolted onto an observability endpoint.
 */
@RestController
@RequestMapping("/platform/runtimes")
@Profile("!worker")
public class RuntimeAdminController {

    private final ModulePlatform platform;

    public RuntimeAdminController(ModulePlatform platform) {
        this.platform = platform;
    }

    /** Every live runtime, warm and retiring ones included. {@code runtimeId} joins to module status. */
    @GetMapping
    public List<RuntimeInfo> list() {
        return platform.runtimes();
    }
}
