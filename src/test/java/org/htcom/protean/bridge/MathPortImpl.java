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

/** A second shared business logic living on main. The worker calls it via the RPC bridge. */
@Component
@Profile("!worker")
public class MathPortImpl implements MathPort {

    @Override
    public Integer add(Integer a, Integer b) {
        return a + b;
    }
}
