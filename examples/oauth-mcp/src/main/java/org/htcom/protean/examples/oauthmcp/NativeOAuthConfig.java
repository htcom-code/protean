/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.examples.oauthmcp;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * <b>Additive approach</b> — active only under the {@code native-oauth} profile. Enables the consumer-side pieces
 * that make the "register only a URL with the MCP client and the rest is automatic" path work. (The default
 * profile is the current approach: static token + ephemeral key + bare Bearer 401.)
 *
 * <ol>
 *   <li><b>resource_metadata pointer</b>: carries {@code resource_metadata="…/.well-known/oauth-protected-resource"}
 *       in the {@code WWW-Authenticate} header of a token-less 401, so the client starts discovery automatically
 *       (RFC 9728). By protean's design this is the consumer's {@code AuthenticationEntryPoint} responsibility, hence it lives here.</li>
 *   <li><b>Stable signing key</b>: persists the RSA key to a file so it is reused across restarts and multiple
 *       instances → issued tokens survive a reboot and the {@code kid} does not change (a scaled-down version of a
 *       production setup). If the file is absent it is generated and saved; otherwise it is loaded.</li>
 * </ol>
 * (The DCR/OIDC endpoints are enabled together in the same profile by {@link AuthorizationServerConfig}'s AS chain.)
 */
@Configuration(proxyBeanMethods = false)
@Profile("native-oauth")
public class NativeOAuthConfig {

    /** Resource Server entry point that adds the resource_metadata pointer to the 401 response. */
    @Bean
    AuthenticationEntryPoint mcpResourceMetadataEntryPoint(
            @Value("${protean.example.base-url:http://localhost:8080}") String baseUrl) {
        String metadataUrl = baseUrl + "/.well-known/oauth-protected-resource";
        String challenge = "Bearer resource_metadata=\"" + metadataUrl + "\"";
        return (request, response, authException) -> {
            response.setHeader("WWW-Authenticate", challenge);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        };
    }

    /** File-backed stable RSA key (survives restarts). */
    @Bean
    JWKSource<SecurityContext> fileBackedJwkSource(
            @Value("${protean.example.jwks-path:build/oauth-mcp-jwks.json}") String jwksPath) throws Exception {
        Path path = Path.of(jwksPath);
        RSAKey rsaKey;
        if (Files.exists(path)) {
            rsaKey = (RSAKey) JWKSet.parse(Files.readString(path)).getKeys().get(0);
        } else {
            KeyPair keyPair = AuthorizationServerConfig.generateRsaKey();
            rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
            if (path.toAbsolutePath().getParent() != null) {
                Files.createDirectories(path.toAbsolutePath().getParent());
            }
            Files.writeString(path, new JWKSet(rsaKey).toString(false)); // false = include the private key (for the example demo)
        }
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }
}
