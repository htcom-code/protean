/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.gate;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.module.ModuleDescriptor;
import org.springframework.stereotype.Component;

import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/**
 * Promotion gate (signature) — verifies the module is signed with a trusted key, ensuring integrity, authenticity,
 * and authorization. When {@code protean.gate.signature.required=true}, {@link PromotionPipeline} runs it at the
 * front of the gates. The trust store is loaded from {@code protean.gate.signature.keys}
 * (keyId to Base64 X.509 Ed25519 public key).
 */
@Component
public class SignatureGate {

    private final boolean required;
    private final Map<String, PublicKey> trustStore = new HashMap<>();

    public SignatureGate(ProteanProperties props) {
        ProteanProperties.Gate.Signature cfg = props.getGate().getSignature();
        this.required = cfg.isRequired();
        cfg.getKeys().forEach((keyId, base64) -> trustStore.put(keyId, ModuleSigning.publicKeyFromBase64(base64)));
    }

    public boolean isRequired() {
        return required;
    }

    /**
     * Verifies the signature. Throws {@link PromotionPipeline.GateFailedException} on a missing signature,
     * an untrusted keyId, or a mismatch (tampering).
     */
    public void enforce(ModuleDescriptor d) {
        if (d.signerKeyId() == null || d.signature() == null) {
            throw new PromotionPipeline.GateFailedException("signature",
                    "signature missing (signerKeyId/signature required) — module " + d.id());
        }
        PublicKey key = trustStore.get(d.signerKeyId());
        if (key == null) {
            throw new PromotionPipeline.GateFailedException("signature",
                    "untrusted keyId=" + d.signerKeyId() + " — module " + d.id());
        }
        if (!ModuleSigning.verify(d, d.signature(), key)) {
            throw new PromotionPipeline.GateFailedException("signature",
                    "signature mismatch (tampered or wrong key) keyId=" + d.signerKeyId() + " — module " + d.id());
        }
    }
}
