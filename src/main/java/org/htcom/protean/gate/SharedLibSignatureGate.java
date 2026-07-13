/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.gate;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProteanException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.PublicKey;

/**
 * Opt-in trust seam for the live shared-lib put-jar surface. By
 * default nothing is enforced — the trusted-developer model relies on consumer authz. When
 * {@code protean.gate.signature.shared-lib-required=true}, every uploaded jar must carry a valid Ed25519 signature
 * (over the jar bytes) from a key in the shared trust store ({@code protean.gate.signature.keys}) — the path for a
 * consumer that accepts untrusted/relay submissions. The toggle and trust store are read live so a config change
 * takes effect without a restart.
 */
@Component
@Profile("!worker")
public class SharedLibSignatureGate {

    private final ProteanProperties props;

    public SharedLibSignatureGate(ProteanProperties props) {
        this.props = props;
    }

    /** Whether shared-lib signature verification is currently enforced. */
    public boolean isRequired() {
        return props.getGate().getSignature().isSharedLibRequired();
    }

    /**
     * Enforces the signature over {@code bytes} when enabled: throws {@link ProteanException} (GATE_FAILED) on a
     * missing signature, an untrusted keyId, or a mismatch. A no-op when the gate is off.
     */
    public void enforce(String name, byte[] bytes, String signerKeyId, String signature) {
        ProteanProperties.Gate.Signature cfg = props.getGate().getSignature();
        if (!cfg.isSharedLibRequired()) {
            return;
        }
        if (signerKeyId == null || signature == null) {
            throw new ProteanException(ErrorCode.GATE_FAILED, "shared-lib-signature",
                    "signature missing (signerKeyId/signature required) — shared lib " + name).with("name", name);
        }
        String base64Key = cfg.getKeys().get(signerKeyId);
        if (base64Key == null) {
            throw new ProteanException(ErrorCode.GATE_FAILED, "shared-lib-signature",
                    "untrusted keyId=" + signerKeyId + " — shared lib " + name).with("name", name);
        }
        PublicKey key = ModuleSigning.publicKeyFromBase64(base64Key);
        if (!ModuleSigning.verify(bytes, signature, key)) {
            throw new ProteanException(ErrorCode.GATE_FAILED, "shared-lib-signature",
                    "signature mismatch (tampered or wrong key) keyId=" + signerKeyId + " — shared lib " + name)
                    .with("name", name);
        }
    }
}
