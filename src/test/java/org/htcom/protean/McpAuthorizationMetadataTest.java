/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OAuth Protected Resource Metadata (RFC 9728) opt-in surface regression.
 * Injects the gate key ({@code protean.mcp.authorization.resource}) through the <b>binding path</b> and
 * verifies over real HTTP that, when configured, {@code /.well-known/oauth-protected-resource} serves the
 * declared discovery metadata.
 * (protean does not perform authentication/token validation — pure delegation preserved. It only exposes
 * the surface.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "protean.mcp.enabled=true",
                "protean.mcp.authorization.resource=https://host/platform/mcp",
                "protean.mcp.authorization.authorization-servers=https://as.example.com",
                "protean.mcp.authorization.scopes-supported=mcp.read,mcp.write"
        })
class McpAuthorizationMetadataTest {

    @LocalServerPort
    int port;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void metadataServedWhenResourceConfigured() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/.well-known/oauth-protected-resource")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("https://host/platform/mcp", body.path("resource").asText());
        assertEquals("https://as.example.com", body.path("authorization_servers").get(0).asText());
        assertTrue(body.path("scopes_supported").toString().contains("mcp.write"));
        assertEquals("header", body.path("bearer_methods_supported").get(0).asText());
    }
}
