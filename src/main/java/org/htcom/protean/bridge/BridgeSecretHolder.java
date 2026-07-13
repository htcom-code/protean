/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Holds the shared secret that authenticates worker-to-main RPC bridge calls (main side only).
 * Registered only when {@code protean.bridge.auth-enabled=true}. If a secret is configured it is used
 * verbatim (for an externally managed, stable token); otherwise a random 256-bit token is generated
 * once per JVM lifetime. The same holder is consumed by {@code BridgeAuthFilter} (to verify incoming
 * calls) and by the worker-spawn path (to inject the token into spawned workers).
 */
@Component
@Profile("!worker")
@ConditionalOnProperty(name = "protean.bridge.auth-enabled", havingValue = "true")
public class BridgeSecretHolder {

    private final String token;

    public BridgeSecretHolder(ProteanProperties props) {
        String configured = props.getBridge().getSecret();
        if (configured != null && !configured.isBlank()) {
            this.token = configured;
        } else {
            byte[] buf = new byte[32];
            new SecureRandom().nextBytes(buf);
            this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        }
    }

    /** The bearer token workers must present on {@code /__bridge/*} calls. */
    public String token() {
        return token;
    }
}
