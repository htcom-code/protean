/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.gate.ModuleSigning;
import org.htcom.protean.gate.PromotionPipeline;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.ModuleStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Promotion gate (signature) verification: with {@code protean.gate.signature.required=true}, only
 * modules signed by a key registered in the trust store pass. The test/review gates are turned off
 * so the signature check is verified in isolation.
 *
 * <p>Demonstrates: trusted-key signature = pass / unsigned = rejected / untrusted keyId = rejected /
 * tampered (content changed after signing) = rejected.
 */
@SpringBootTest(properties = {
        "protean.gate.tests-enabled=false",
        "protean.gate.review-enabled=false",
        "protean.gate.signature.required=true"
})
@AutoConfigureMockMvc
class ModuleSignatureGateTest {

    static final KeyPair TRUSTED = ModuleSigning.generateKeyPair();
    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-signature-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        // trust store: keyId "ci-key" -> trusted public key (Base64 X.509).
        registry.add("protean.gate.signature.keys.ci-key",
                () -> ModuleSigning.publicKeyToBase64(TRUSTED.getPublic()));
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired ModuleStore store;

    static final String CTRL = "runtime.sig.SigController";

    static ModuleDescriptor unsigned(String reply) {
        String src = """
                package runtime.sig;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class SigController {
                    @GetMapping("/sig/ping") public String ping() { return "%s"; }
                }
                """.formatted(reply);
        return ModuleDescriptor.builder()
                .id("sig-mod").version("1.0.0")
                .controllerFqcn(CTRL).componentFqcns(List.of(CTRL)).sources(Map.of(CTRL, src))
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall("sig-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void module_signed_with_trusted_key_installs() throws Exception {
        ModuleDescriptor d = unsigned("signed");
        ModuleDescriptor signed = d.withSignature("ci-key", ModuleSigning.sign(d, TRUSTED.getPrivate()));

        platform.install(signed);
        mockMvc.perform(get("/sig/ping")).andExpect(status().isOk());
    }

    @Test
    void unsigned_module_is_rejected() {
        assertThrows(PromotionPipeline.GateFailedException.class, () -> platform.install(unsigned("x")));
        assertFalse(store.load("sig-mod").isPresent(), "an unsigned module must not be stored");
    }

    @Test
    void untrusted_key_id_is_rejected() {
        ModuleDescriptor d = unsigned("x");
        // the signature itself is valid, but the keyId is not in the trust store -> rejected as untrusted.
        ModuleDescriptor signed = d.withSignature("unknown-key", ModuleSigning.sign(d, TRUSTED.getPrivate()));
        assertThrows(PromotionPipeline.GateFailedException.class, () -> platform.install(signed));
        assertFalse(store.load("sig-mod").isPresent());
    }

    @Test
    void tampered_content_after_signing_is_rejected() {
        // sign the original, then change the content (reply string = source) while keeping the same signature -> rejected on signature mismatch.
        ModuleDescriptor original = unsigned("original");
        String sig = ModuleSigning.sign(original, TRUSTED.getPrivate());
        ModuleDescriptor tampered = unsigned("TAMPERED").withSignature("ci-key", sig);
        assertThrows(PromotionPipeline.GateFailedException.class, () -> platform.install(tampered));
        assertFalse(store.load("sig-mod").isPresent());
    }
}
