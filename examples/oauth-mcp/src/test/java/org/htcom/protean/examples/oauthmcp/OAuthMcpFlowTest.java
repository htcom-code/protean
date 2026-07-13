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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Realistic OAuth2 stack experiment — exercises protean MCP authorization on top of a real OAuth2 stack.
 *
 * <p>Verifies:
 * <ol>
 *   <li>Logging in an H2 user (admin/viewer) via authorization_code+PKCE actually issues a JWT.</li>
 *   <li>admin (mcp.write) passes deploy_module authorization / viewer (mcp.read only) is denied (protean authorizer).</li>
 *   <li>viewer is allowed to call list_modules (READ).</li>
 *   <li><b>A token-less call yields 401 + WWW-Authenticate</b>. The header content is logged for observation.</li>
 *   <li>The public /.well-known/oauth-protected-resource metadata works.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class OAuthMcpFlowTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    int port;

    private final ObjectMapper mapper = new ObjectMapper();

    private String base() {
        return "http://localhost:" + port;
    }

    // --- 1 & 2 & 3: user token -> MCP authorization mapping ------------------------------------------------

    @Test
    void adminTokenPassesDeployAuthorization() throws Exception {
        String token = userAccessToken("admin", "admin-pw");
        JsonNode result = callTool(token, "protean.deploy_module", "{\"id\":\"x\"}");
        // The authorizer (holds mcp.write) passes -> it must not be an authorization denial (domain failures are irrelevant).
        assertThat(toolText(result)).doesNotContain("PERMISSION_DENIED");
    }

    @Test
    void viewerTokenDeniedDeployAuthorization() throws Exception {
        String token = userAccessToken("viewer", "viewer-pw");
        JsonNode result = callTool(token, "protean.deploy_module", "{\"id\":\"x\"}");
        assertThat(result.path("result").path("isError").asBoolean()).isTrue();
        assertThat(toolText(result))
                .contains("PERMISSION_DENIED")
                .contains("mcp.write");
    }

    @Test
    void viewerTokenAllowedToList() throws Exception {
        String token = userAccessToken("viewer", "viewer-pw");
        JsonNode result = callTool(token, "protean.list_modules", "{}");
        assertThat(toolText(result)).doesNotContain("permission denied");
    }

    // --- 4: token-less -> 401 + WWW-Authenticate -----------------------------------

    @Test
    void missingTokenYields401WithWwwAuthenticate() throws Exception {
        HttpClient client = HttpClient.newBuilder().build();
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(base() + "/platform/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(rpcCall("protean.list_modules", "{}")))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(401);
        String wwwAuth = resp.headers().firstValue("WWW-Authenticate").orElse(null);
        assertThat(wwwAuth).isNotNull();
        // For observation: the header emitted by the resource server. Whether it includes a resource_metadata pointer is informative.
        System.out.println("[G-EXPERIMENT] 401 WWW-Authenticate = " + wwwAuth);
    }

    // --- 5: public metadata --------------------------------------------------

    @Test
    void protectedResourceMetadataIsPublic() throws Exception {
        HttpClient client = HttpClient.newBuilder().build();
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(base() + "/.well-known/oauth-protected-resource")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.path("resource").asText()).contains("/platform/mcp");
        assertThat(body.path("authorization_servers").isArray()).isTrue();
        assertThat(body.path("authorization_servers").get(0).asText()).isNotBlank();
        assertThat(body.path("scopes_supported").toString()).contains("mcp.write");
        System.out.println("[G-EXPERIMENT] protected-resource metadata = " + resp.body());
    }

    // ================== helpers ==================

    /** Drives authorization_code + PKCE programmatically to obtain a user access token. */
    private String userAccessToken(String user, String pw) throws Exception {
        CookieManager cookies = new CookieManager();
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        String verifier = base64Url(randomBytes(32));
        String challenge = base64Url(sha256(verifier));

        String authorizeUrl = base() + "/oauth2/authorize?response_type=code"
                + "&client_id=" + AuthorizationServerConfig.AGENT_CLIENT_ID
                + "&redirect_uri=" + enc(AuthorizationServerConfig.AGENT_REDIRECT_URI)
                + "&scope=" + enc("mcp.read mcp.write")
                + "&code_challenge=" + challenge
                + "&code_challenge_method=S256";

        // 1) authorize -> unauthenticated, so 302 to /login (saves the SavedRequest + session cookie).
        get(client, authorizeUrl);

        // 2) Obtain the CSRF token from the login form.
        HttpResponse<String> loginPage = get(client, base() + "/login");
        String csrf = extract(loginPage.body(), "name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

        // 3) Login POST -> 302 to the SavedRequest (authorize).
        String form = "username=" + enc(user) + "&password=" + enc(pw) + "&_csrf=" + enc(csrf);
        HttpResponse<String> afterLogin = client.send(
                HttpRequest.newBuilder(URI.create(base() + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());

        // 4) Follow the redirect chain and extract code from redirect_uri?code=... (consent screen off).
        String code = followUntilCode(client, afterLogin);
        assertThat(code).as("authorization code").isNotBlank();

        // 5) Token exchange (public client + PKCE).
        String tokenForm = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(AuthorizationServerConfig.AGENT_REDIRECT_URI)
                + "&client_id=" + AuthorizationServerConfig.AGENT_CLIENT_ID
                + "&code_verifier=" + enc(verifier);
        HttpResponse<String> tokenResp = client.send(
                HttpRequest.newBuilder(URI.create(base() + "/oauth2/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(tokenForm)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tokenResp.statusCode()).as("token endpoint: " + tokenResp.body()).isEqualTo(200);
        return mapper.readTree(tokenResp.body()).path("access_token").asText();
    }

    private String followUntilCode(HttpClient client, HttpResponse<String> resp) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            if (resp.statusCode() / 100 != 3) {
                break;
            }
            String location = resp.headers().firstValue("Location").orElse(null);
            if (location == null) {
                break;
            }
            if (!location.startsWith("http")) {
                location = base() + location;
            }
            if (location.startsWith(AuthorizationServerConfig.AGENT_REDIRECT_URI)) {
                return queryParam(location, "code");
            }
            resp = get(client, location);
        }
        return null;
    }

    private JsonNode callTool(String bearer, String tool, String argsJson) throws Exception {
        HttpClient client = HttpClient.newBuilder().build();
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(base() + "/platform/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .POST(HttpRequest.BodyPublishers.ofString(rpcCall(tool, argsJson))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("mcp call: " + resp.body()).isEqualTo(200);
        return mapper.readTree(resp.body());
    }

    private String rpcCall(String tool, String argsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + argsJson + "}}";
    }

    private String toolText(JsonNode rpcResponse) {
        JsonNode content = rpcResponse.path("result").path("content");
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            content.forEach(c -> sb.append(c.path("text").asText()).append('\n'));
        }
        return sb.toString();
    }

    private HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String extract(String body, String regex) {
        Matcher m = Pattern.compile(regex).matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static String queryParam(String url, String key) {
        Matcher m = Pattern.compile("[?&]" + key + "=([^&]+)").matcher(url);
        return m.find() ? URLDecode(m.group(1)) : null;
    }

    private static String URLDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static byte[] sha256(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static String base64Url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
