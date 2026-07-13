/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

/**
 * RPC bridge response envelope. The result is always serialized as a JSON object
 * ({@code {"result": ..., "error": ...}}) to avoid the text/plain special-casing of String returns
 * and to let the worker deserialize consistently.
 *
 * <p>A business exception is treated as a normal RPC result, not a transport failure — its type/message
 * is carried in {@code error} and returned as HTTP 200, and the worker reconstructs and rethrows it.
 */
public record BridgeResult(Object result, BridgeError error) {

    public static BridgeResult ok(Object result) {
        return new BridgeResult(result, null);
    }

    public static BridgeResult failed(BridgeError error) {
        return new BridgeResult(null, error);
    }
}
