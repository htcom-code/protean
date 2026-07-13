/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Holds the shared secret that authenticates the main's calls to a worker's {@code /__admin/*} control plane (main
 * side only). Registered only when {@code protean.worker.admin-auth.enabled=true}. If a secret is configured it is
 * used verbatim (for an externally managed, stable secret); otherwise a random 256-bit token is generated once per JVM
 * lifetime. The same holder is consumed by {@link WorkerAdminClient} (to sign outgoing admin calls) and by the
 * worker-spawn paths (to inject the secret into spawned process/container workers, whose
 * {@code WorkerAdminAuthFilter} verifies it). Mirrors {@code BridgeSecretHolder} for the opposite (worker → main)
 * direction.
 */
@Component
@Profile("!worker")
@ConditionalOnProperty(name = "protean.worker.admin-auth.enabled", havingValue = "true")
public class WorkerAdminSecretHolder {

    private final String token;

    public WorkerAdminSecretHolder(ProteanProperties props) {
        String configured = props.getWorker().getAdminAuth().getSecret();
        if (configured != null && !configured.isBlank()) {
            this.token = configured;
        } else {
            byte[] buf = new byte[32];
            new SecureRandom().nextBytes(buf);
            this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        }
    }

    /** The secret the main presents (bearer token, or HMAC key) on {@code /__admin/*} calls. */
    public String token() {
        return token;
    }
}
