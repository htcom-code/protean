/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

/**
 * A second shared SPI for the RPC bridge generalization demo (an arbitrary interface distinct from
 * GreetingPort). Demonstrates a wrapper (Integer) signature — primitive arguments/returns are
 * supported and verified by EchoPort (addInt).
 */
public interface MathPort {

    Integer add(Integer a, Integer b);
}
