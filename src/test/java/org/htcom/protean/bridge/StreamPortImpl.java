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

import java.io.InputStream;

/**
 * Shared streaming business logic on main. The bytes are produced lazily (never materialized whole),
 * proving that a large return can cross the bridge without buffering the entire payload in memory.
 */
@Component
@Profile("!worker")
public class StreamPortImpl implements StreamPort {

    @Override
    public InputStream download(int sizeBytes) {
        return new InputStream() {
            private int pos = 0;

            @Override
            public int read() {
                // Deterministic pattern so the caller can verify integrity; generated on demand.
                return pos < sizeBytes ? (pos++ % 251) : -1;
            }
        };
    }
}
