/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

/**
 * Shared SPI for the RPC bridge demo. Present on the app classpath so both main and worker load it as
 * the same type. (In practice this is a shared business logic contract defined by the user; this is a
 * demo sample.)
 */
public interface GreetingPort {

    String greet(String name);
}
