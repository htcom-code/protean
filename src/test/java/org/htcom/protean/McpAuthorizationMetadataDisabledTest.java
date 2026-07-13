/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Gate OFF verification (non-destructive, pure delegation preserved).
 * When {@code protean.mcp.authorization.resource} is not set, the metadata controller bean does not exist,
 * so {@code /.well-known/oauth-protected-resource} returns 404 (current behavior). The same holds even
 * when MCP itself is enabled.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"protean.mcp.enabled=true"})
class McpAuthorizationMetadataDisabledTest {

    @LocalServerPort
    int port;

    @Test
    void endpointAbsentWhenResourceNotConfigured() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/.well-known/oauth-protected-resource")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode());
    }
}
