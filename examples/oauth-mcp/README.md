**English** | [한국어](README.ko.md)

# examples/oauth-mcp — OAuth2 authentication/authorization × protean MCP

A consumer example that stands up a realistic **consumer authentication stack** and wires it to protean MCP's delegated
authorization for real. One app plays three roles (all on the consumer side — protean does not implement authentication):

| Role | Built with | What it does |
|---|---|---|
| Authorization Server | Spring Authorization Server | H2 user login → issues JWT access tokens (`mcp.read`/`mcp.write` scopes) |
| Resource Server | spring-boot-starter-oauth2-resource-server | protects `/platform/mcp/**` with JWT → injects the verified `Principal` into protean |
| protean MCP | `McpScopeAuthorizer` (SPI implementation) | maps that principal's scopes to MCP action permissions |

- **H2**: the user store. Seeded on boot — `admin` (mcp.read+write), `viewer` (mcp.read). The single source of truth for permissions.
- **Redis (docker-compose)**: persists the AS login session (Spring Session).

This example was originally built to **empirically settle the scope of 11.0 Phase G (OAuth discovery metadata)**
(see "Decisions this example drove" below).

## Run

```bash
# 1) Start Redis
docker compose -f examples/oauth-mcp/docker-compose.yml up -d

# 2) Start the app (AS + RS + protean MCP)
./gradlew :examples:oauth-mcp:bootRun
```

### Observe the flow (summary — automated verification is `OAuthMcpFlowTest`)

- `admin` login token (mcp.write) → `tools/call deploy_module` **authorized**.
- `viewer` token (mcp.read) → `deploy_module` **denied** (`permission denied … mcp.write`), `list_modules` **allowed**.
- Call `/platform/mcp` with no token → **401 `WWW-Authenticate: Bearer`** (emitted by the Resource Server).
- `GET /.well-known/oauth-protected-resource` → discovery metadata (RFC 9728).

Automated e2e:

```bash
docker compose -f examples/oauth-mcp/docker-compose.yml up -d   # or let the test bring it up via Testcontainers
./gradlew :examples:oauth-mcp:test
```
(The test programmatically drives authorization_code+PKCE to obtain a user token, and brings Redis up via Testcontainers.)

## Two authentication modes (selected by Spring profile)

| | Default profile (current mode) | `native-oauth` profile (additional mode) |
|---|---|---|
| Token | **static Bearer, manually injected** into the client | client **obtains it automatically** via discovery + login |
| Signing key | regenerated on every boot (tokens invalid after restart) | **file-persisted** (`protean.example.jwks-path`) → restart-tolerant |
| No-token 401 | bare `Bearer` | `Bearer resource_metadata="…"` (triggers discovery) |
| discovery | none | OIDC (`/.well-known/openid-configuration`: authorize/token/jwks) |

### Default mode — static token

```bash
docker compose -f examples/oauth-mcp/docker-compose.yml up -d
./gradlew :examples:oauth-mcp:bootRun
# Issue a token (client_credentials, narrowing scope to distinguish write/read):
curl -s -u mcp-service:service-secret \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "scope=mcp.read mcp.write" http://localhost:8080/oauth2/token
# → attach that access_token to the MCP client as Authorization: Bearer.
```

### Additional mode — `native-oauth` ("register the URL only")

```bash
docker compose -f examples/oauth-mcp/docker-compose.yml up -d
./gradlew :examples:oauth-mcp:bootRun --args="\
  --spring.profiles.active=native-oauth \
  --server.port=8081 \
  --protean.example.base-url=http://localhost:8081 \
  --protean.mcp.authorization.authorization-servers[0]=http://localhost:8081"
```

The MCP client only needs to know the server URL: the `resource_metadata` pointer in the no-token 401 → protected-resource
metadata → `authorization_servers` → OIDC discovery → authorization_code+PKCE login (H2 user) → automatic token
acquisition/refresh. No human ever touches a token, and because the key is fixed across restarts, there's no need to re-register.

> **Dynamic Client Registration (RFC 7591) is left out of this example.** Spring Authorization Server's default policy
> forces the registration-token scope to the single `{client.create}` and restricts the requested scope to a subset of it,
> so a client holding `mcp.*` scopes cannot be dynamically registered (yields `invalid_token`/`invalid_scope` respectively).
> Getting around it requires customizing `OidcClientRegistrationAuthenticationProvider`. So the MCP client is
> **pre-registered** (`mcp-agent`, with a redirect URI) and joined via discovery + login — even across multiple servers,
> registering the client once means tokens are managed automatically.

## Decisions this example drove (11.0 Phase G)

Running the real stack with this example surfaced two observations:

1. **On no token, Spring's Resource Server already emits `WWW-Authenticate: Bearer`** (before reaching the protean controller,
   at the Security filter stage). For protean to emit the 401 it would have to intrude on the consumer's filter chain, so **there's no seat for it.**
2. **The protected-resource metadata takes only a few lines of consumer-controller code, but it's boilerplate repeated by every consumer.**

Conclusions:

- **G1b (protean emits 401/`WWW-Authenticate`) = rejected.** The Resource Server owns it. If you want the `resource_metadata`
  pointer on the 401, add it in the consumer's `AuthenticationEntryPoint` (e.g. `BearerTokenAuthenticationEntryPoint`).
- **G1a (discovery metadata) = promoted into protean core as opt-in.** When `protean.mcp.authorization.resource` is set,
  protean auto-registers `/.well-known/oauth-protected-resource`. **Verification and tokens remain the consumer's Security
  responsibility** (the pure-delegation invariant holds).

So this example's metadata endpoint is now served by **protean core**, not consumer code. The consumer only configures it:

```yaml
protean:
  mcp:
    enabled: true
    authorization:                 # when this block is present, /.well-known/oauth-protected-resource appears automatically
      resource: http://localhost:8080/platform/mcp
      authorization-servers:
        - http://localhost:8080
      scopes-supported: [mcp.read, mcp.write]
```

## Consumer notes

- `/.well-known/**` must be reachable unauthenticated, so `permitAll` it in Security (see this example's `ResourceServerSecurityConfig`).
- Protect `/platform/mcp/**` with a bearer token, and on top of that apply per-action policy via `ModuleActionAuthorizer`.
- This example's AS self-signs (local JWKS). The RS reuses the same app's `JwtDecoder` bean (no separate `jwk-set-uri` needed).
