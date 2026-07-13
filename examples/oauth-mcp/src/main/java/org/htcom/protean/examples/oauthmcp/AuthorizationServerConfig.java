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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

/**
 * OAuth2 Authorization Server wiring — consumer side (independent of protean).
 *
 * <p>Registers two clients:
 * <ul>
 *   <li>{@code mcp-agent} (public) — {@code authorization_code}+PKCE. The real MCP client (user login) flow.</li>
 *   <li>{@code mcp-service} (confidential) — {@code client_credentials}. For deterministic automated tests / service tokens.</li>
 * </ul>
 * Scopes are {@code mcp.read}/{@code mcp.write}, which protean's {@link McpScopeAuthorizer} maps to action permissions.
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerConfig {

    public static final String SERVICE_CLIENT_ID = "mcp-service";
    public static final String SERVICE_CLIENT_SECRET = "service-secret";
    public static final String AGENT_CLIENT_ID = "mcp-agent";
    public static final String AGENT_REDIRECT_URI = "http://127.0.0.1:9999/callback";

    /** Security chain for the AS endpoints (/oauth2/**). applyDefaultSecurity already sets matcher/authenticated/csrf;
     *  here we only add an entry point that redirects unauthenticated authorize requests to the login form.
     *  <p><b>native-oauth profile (additive)</b>: enables OIDC discovery ({@code /.well-known/openid-configuration})
     *  so the MCP client can discover the authorize/token endpoints (the server-side piece of the "register a URL only" path).
     *  <p>Note: RFC 7591 Dynamic Client Registration cannot be used here — the SAS default policy limits the registration
     *  token scope to the single {@code {client.create}} and forces requested scopes to be a subset of it, so a client
     *  with mcp.* scopes cannot be dynamically registered. This example therefore pre-registers the MCP client
     *  ({@code mcp-agent}) and connects it via discovery + login (DCR is documented as an extension point that requires
     *  customizing the converter). */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http, Environment env) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        if (env.acceptsProfiles(Profiles.of("native-oauth"))) {
            http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                    .oidc(Customizer.withDefaults());
        }
        http.exceptionHandling(e -> e.authenticationEntryPoint(
                new LoginUrlAuthenticationEntryPoint("/login")));
        return http.build();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository() {
        RegisteredClient agent = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(AGENT_CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE) // public + PKCE
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(AGENT_REDIRECT_URI)
                .scope("mcp.read")
                .scope("mcp.write")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)              // enforce PKCE
                        .requireAuthorizationConsent(false) // simplified for the example — skip the consent screen
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .build())
                .build();

        RegisteredClient service = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(SERVICE_CLIENT_ID)
                .clientSecret("{noop}" + SERVICE_CLIENT_SECRET)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("mcp.read")
                .scope("mcp.write")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1)) // convenience for manual testing (default 5 min → 1 hour)
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(agent, service);
    }

    /** Default (current approach): generates an in-memory RSA key on every boot → the kid changes on restart,
     *  invalidating previously issued tokens. Under the native-oauth profile, {@link NativeOAuthConfig}'s
     *  file-backed stable key is used instead. */
    @Bean
    @Profile("!native-oauth")
    JWKSource<SecurityContext> ephemeralJwkSource() {
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }

    static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key", e);
        }
    }
}
