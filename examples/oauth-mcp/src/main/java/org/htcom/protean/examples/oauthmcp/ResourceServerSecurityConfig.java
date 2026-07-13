/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.examples.oauthmcp;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resource Server + login/user store — consumer side (independent of protean).
 *
 * <p>Protects {@code /platform/mcp/**} with a JWT bearer. Once verified, Spring injects the
 * {@code JwtAuthenticationToken} as a {@link java.security.Principal} into the protean MCP controller, and the
 * token's {@code scope} claim becomes {@code SCOPE_mcp.*} authorities that drive {@link McpScopeAuthorizer}'s decisions.
 *
 * <p>Key point: <b>the single source of truth for per-user permissions is the H2 {@code authorities} table</b>.
 * {@link #tokenCustomizer()} intersects so that only the {@code mcp.*} authorities the logged-in user holds are
 * issued as access-token scopes (admin=read+write, viewer=read).
 */
@Configuration(proxyBeanMethods = false)
public class ResourceServerSecurityConfig {

    /** MCP + login + metadata chain (after the AS chain, @Order(1)).
     *  <p>If the {@code mcpEntryPoint} bean is present (native-oauth profile), the 401's {@code WWW-Authenticate}
     *  carries a {@code resource_metadata} pointer so the client starts discovery automatically. Otherwise the
     *  default (bare {@code Bearer}) is used. */
    @Bean
    @Order(2)
    SecurityFilterChain appSecurityFilterChain(HttpSecurity http,
                                               ObjectProvider<AuthenticationEntryPoint> mcpEntryPoint) throws Exception {
        AuthenticationEntryPoint entryPoint = mcpEntryPoint.getIfAvailable();
        http
                .authorizeHttpRequests(a -> a
                        // protected-resource metadata is public (the entry point where an unauthenticated client discovers the AS).
                        .requestMatchers("/.well-known/**").permitAll()
                        .requestMatchers("/login", "/error").permitAll()
                        .requestMatchers("/platform/mcp/**").authenticated()
                        .anyRequest().authenticated())
                // /platform/mcp uses a bearer token. On a missing/invalid token the resource server returns 401 + WWW-Authenticate.
                .oauth2ResourceServer(oauth2 -> {
                    oauth2.jwt(Customizer.withDefaults());
                    if (entryPoint != null) {
                        oauth2.authenticationEntryPoint(entryPoint);
                    }
                })
                // Login form (used to drive AS authorize). CSRF is meaningless for MCP (JSON, stateless), so it is excluded.
                .formLogin(Customizer.withDefaults())
                .csrf(c -> c.ignoringRequestMatchers("/platform/mcp/**"));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsManager users(DataSource dataSource) {
        return new JdbcUserDetailsManager(dataSource);
    }

    /**
     * Boot-time seed — admin (mcp.read+mcp.write), viewer (mcp.read). The authority strings are kept identical to
     * the scope names so the token customizer can intersect them directly.
     */
    @Bean
    CommandLineRunner userSeed(UserDetailsManager users, PasswordEncoder encoder) {
        return args -> {
            if (!users.userExists("admin")) {
                UserDetails admin = User.withUsername("admin")
                        .password(encoder.encode("admin-pw"))
                        .authorities("mcp.read", "mcp.write")
                        .build();
                users.createUser(admin);
            }
            if (!users.userExists("viewer")) {
                UserDetails viewer = User.withUsername("viewer")
                        .password(encoder.encode("viewer-pw"))
                        .authorities("mcp.read")
                        .build();
                users.createUser(viewer);
            }
        };
    }

    /** Access-token scope = requested scope ∩ the logged-in user's {@code mcp.*} authorities (per-user differentiation). */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return ctx -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(ctx.getTokenType())) {
                return;
            }
            // client_credentials has no user → keep the requested scopes without customization (service token).
            if (ctx.getPrincipal() == null || ctx.getPrincipal().getAuthorities() == null) {
                return;
            }
            Set<String> userScopes = ctx.getPrincipal().getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith("mcp."))
                    .collect(Collectors.toSet());
            if (userScopes.isEmpty()) {
                return; // not user-based (service) — leave untouched.
            }
            Set<String> granted = new HashSet<>(ctx.getAuthorizedScopes());
            granted.retainAll(userScopes);
            ctx.getClaims().claim("scope", granted);
        };
    }
}
