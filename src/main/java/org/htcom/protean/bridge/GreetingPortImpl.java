/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Shared business logic implementation living on main. Worker modules call it via the RPC bridge.
 * Exists only on main (!worker) — on the worker a proxy of the same type takes its place.
 */
@Component
@Profile("!worker")
public class GreetingPortImpl implements GreetingPort {

    @Override
    public String greet(String name) {
        return "hello " + name;
    }
}
