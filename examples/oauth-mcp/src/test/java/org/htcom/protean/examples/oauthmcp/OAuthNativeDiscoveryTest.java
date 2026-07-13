/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.examples.oauthmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Add-on style</b> ({@code native-oauth} profile) regression — exercises the server-side pieces of
 * the "register by URL only" path.
 *
 * <ol>
 *   <li>The {@code WWW-Authenticate} of a token-less 401 carries a {@code resource_metadata} pointer (in contrast to the bare Bearer of the default profile).</li>
 *   <li>OIDC discovery ({@code /.well-known/openid-configuration}) exposes the authorize/token/jwks endpoints ("register by URL only").</li>
 *   <li>The signing key is persisted to a file and issued tokens are signed with that key ({@code kid}) -> the basis for restart resilience.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("native-oauth")
@Testcontainers(disabledWithoutDocker = true)
class OAuthNativeDiscoveryTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final Path JWKS = Path.of("build/test-native-jwks.json");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        Files.deleteIfExists(JWKS); // start clean on every run
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("protean.example.jwks-path", JWKS::toString);
    }

    @LocalServerPort
    int port;

    private final ObjectMapper mapper = new ObjectMapper();

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void missingToken401CarriesResourceMetadataPointer() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + "/platform/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(401);
        String wwwAuth = resp.headers().firstValue("WWW-Authenticate").orElse("");
        assertThat(wwwAuth)
                .contains("resource_metadata")
                .contains("/.well-known/oauth-protected-resource");
    }

    @Test
    void oidcDiscoveryExposesAuthorizationEndpoints() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + "/.well-known/openid-configuration")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(resp.body());
        // A client that "only knows the URL" must be able to discover authorize/token/jwks.
        assertThat(body.path("authorization_endpoint").asText()).endsWith("/oauth2/authorize");
        assertThat(body.path("token_endpoint").asText()).endsWith("/oauth2/token");
        assertThat(body.path("jwks_uri").asText()).endsWith("/oauth2/jwks");
    }

    @Test
    void signingKeyPersistedAndUsedForTokens() throws Exception {
        // The file was created at boot.
        assertThat(Files.exists(JWKS)).isTrue();
        String fileKid = mapper.readTree(Files.readString(JWKS)).path("keys").get(0).path("kid").asText();

        // The issued token is signed with that file key (header kid matches) -> same key across restarts.
        HttpResponse<String> tok = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + "/oauth2/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                                (AuthorizationServerConfig.SERVICE_CLIENT_ID + ":"
                                        + AuthorizationServerConfig.SERVICE_CLIENT_SECRET).getBytes(StandardCharsets.UTF_8)))
                        .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=mcp.read"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tok.statusCode()).as(tok.body()).isEqualTo(200);

        String jwt = mapper.readTree(tok.body()).path("access_token").asText();
        String headerJson = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]), StandardCharsets.UTF_8);
        String tokenKid = mapper.readTree(headerJson).path("kid").asText();

        assertThat(tokenKid).isEqualTo(fileKid);
    }
}
