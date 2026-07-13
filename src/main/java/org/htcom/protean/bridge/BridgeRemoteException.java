/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

/**
 * Fallback exception thrown when the worker cannot reconstruct a main-side remote exception as its
 * original type (not on the worker classpath, or no (String) constructor). Preserves the original
 * type/message.
 */
public class BridgeRemoteException extends RuntimeException {

    private final String remoteType;

    public BridgeRemoteException(String remoteType, String message) {
        super(remoteType + ": " + message);
        this.remoteType = remoteType;
    }

    public String remoteType() {
        return remoteType;
    }
}
