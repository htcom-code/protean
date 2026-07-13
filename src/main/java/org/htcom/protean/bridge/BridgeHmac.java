/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Shared HMAC computation for the RPC bridge {@code hmac} auth mode. The worker and main compute the
 * same signature over {@code timestamp + "\n" + nonce + "\n" + body} using the shared secret, so main
 * can detect body tampering, and (combined with a timestamp window and nonce cache) replay. Uses only
 * the JDK ({@code javax.crypto}), no external dependency.
 */
public final class BridgeHmac {

    public static final String TS_HEADER = "X-Protean-Bridge-Ts";
    public static final String NONCE_HEADER = "X-Protean-Bridge-Nonce";
    public static final String SIG_HEADER = "X-Protean-Bridge-Sig";

    private static final String ALGORITHM = "HmacSHA256";

    private BridgeHmac() {
    }

    /** Computes the Base64 HMAC-SHA256 signature over the canonical (timestamp, nonce, body) message. */
    public static String sign(String secret, long timestamp, String nonce, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update((timestamp + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return Base64.getEncoder().encodeToString(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to compute bridge HMAC", e);
        }
    }
}
