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
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The opt-in Ed25519 trust seam for shared-lib uploads: off by default (accept anything), and
 * when enabled it verifies a signature over the jar bytes against the shared trust store.
 */
class SharedLibSignatureGateTest {

    private static final byte[] JAR = "native-jar-bytes".getBytes();

    private static ProteanProperties propsWith(boolean required, String keyId, KeyPair kp) {
        ProteanProperties props = new ProteanProperties();
        props.getGate().getSignature().setSharedLibRequired(required);
        if (keyId != null) {
            props.getGate().getSignature().getKeys().put(keyId, ModuleSigning.publicKeyToBase64(kp.getPublic()));
        }
        return props;
    }

    @Test
    void off_by_default_accepts_unsigned_uploads() {
        SharedLibSignatureGate gate = new SharedLibSignatureGate(new ProteanProperties());
        assertDoesNotThrow(() -> gate.enforce("acme", JAR, null, null));
    }

    @Test
    void when_required_a_valid_signature_passes() {
        KeyPair kp = ModuleSigning.generateKeyPair();
        SharedLibSignatureGate gate = new SharedLibSignatureGate(propsWith(true, "k1", kp));
        String sig = ModuleSigning.sign(JAR, kp.getPrivate());
        assertDoesNotThrow(() -> gate.enforce("acme", JAR, "k1", sig));
    }

    @Test
    void when_required_a_missing_signature_is_rejected() {
        SharedLibSignatureGate gate = new SharedLibSignatureGate(propsWith(true, null, null));
        ProteanException ex = assertThrows(ProteanException.class, () -> gate.enforce("acme", JAR, null, null));
        assertEquals(ErrorCode.GATE_FAILED, ex.code());
    }

    @Test
    void when_required_an_untrusted_key_is_rejected() {
        KeyPair signer = ModuleSigning.generateKeyPair();
        // Trust store holds a different key id than the one the upload claims.
        SharedLibSignatureGate gate = new SharedLibSignatureGate(propsWith(true, "trusted", signer));
        String sig = ModuleSigning.sign(JAR, signer.getPrivate());
        assertEquals(ErrorCode.GATE_FAILED,
                assertThrows(ProteanException.class, () -> gate.enforce("acme", JAR, "unknown", sig)).code());
    }

    @Test
    void when_required_a_tampered_payload_is_rejected() {
        KeyPair kp = ModuleSigning.generateKeyPair();
        SharedLibSignatureGate gate = new SharedLibSignatureGate(propsWith(true, "k1", kp));
        String sig = ModuleSigning.sign(JAR, kp.getPrivate());
        assertEquals(ErrorCode.GATE_FAILED,
                assertThrows(ProteanException.class,
                        () -> gate.enforce("acme", "tampered-bytes".getBytes(), "k1", sig)).code());
    }
}
