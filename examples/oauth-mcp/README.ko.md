[English](README.md) | **한국어**

# examples/oauth-mcp — OAuth2 인증/인가 × protean MCP 연계

현실적인 **소비자 인증 스택**을 세워 protean MCP 의 위임 인가와 실제로 연결하는 소비 예제다.
한 앱이 세 역할을 겸한다(전부 소비자 측 — protean 은 인증을 구현하지 않는다):

| 역할 | 구성 | 하는 일 |
|---|---|---|
| Authorization Server | Spring Authorization Server | H2 사용자 로그인 → JWT 액세스 토큰 발급(`mcp.read`/`mcp.write` 스코프) |
| Resource Server | spring-boot-starter-oauth2-resource-server | `/platform/mcp/**` 를 JWT 로 보호 → 검증된 `Principal` 을 protean 에 주입 |
| protean MCP | `McpScopeAuthorizer`(SPI 구현) | 그 주체의 스코프를 MCP 동작 권한으로 매핑 |

- **H2**: 사용자 저장소. 부팅 시드 — `admin`(mcp.read+write), `viewer`(mcp.read). 권한의 단일 진실.
- **Redis(docker-compose)**: AS 로그인 세션(Spring Session) 영속.

이 예제는 원래 **11.0 Phase G(OAuth discovery 메타데이터)의 범위를 실측으로 결정**하기 위해 만들어졌다
(아래 "이 예제가 내린 결정" 참조).

## 실행

```bash
# 1) Redis 기동
docker compose -f examples/oauth-mcp/docker-compose.yml up -d

# 2) 앱 기동(AS + RS + protean MCP)
./gradlew :examples:oauth-mcp:bootRun
```

### 흐름 관찰(요약 — 자동 검증은 `OAuthMcpFlowTest`)

- `admin` 로그인 토큰(mcp.write) → `tools/call deploy_module` **인가 통과**.
- `viewer` 토큰(mcp.read) → `deploy_module` **거부**(`permission denied … mcp.write`), `list_modules` **허용**.
- 토큰 없이 `/platform/mcp` 호출 → **401 `WWW-Authenticate: Bearer`** (Resource Server 가 냄).
- `GET /.well-known/oauth-protected-resource` → discovery 메타데이터(RFC 9728).

자동 e2e:

```bash
docker compose -f examples/oauth-mcp/docker-compose.yml up -d   # 또는 테스트가 Testcontainers 로 자체 기동
./gradlew :examples:oauth-mcp:test
```
(테스트는 authorization_code+PKCE 를 프로그램적으로 몰아 사용자 토큰을 얻고, Redis 는 Testcontainers 로 띄운다.)

## 두 인증 방식 (Spring 프로필로 선택)

| | 기본 프로필 (지금 방식) | `native-oauth` 프로필 (추가 방식) |
|---|---|---|
| 토큰 | 클라에 **정적 Bearer 수동 주입** | 클라가 discovery+로그인으로 **자동 획득** |
| 서명 키 | 매 부팅 새로 생성(재기동 시 토큰 무효) | **파일 영속**(`protean.example.jwks-path`) → 재기동 내성 |
| 무토큰 401 | 맨 `Bearer` | `Bearer resource_metadata="…"`(discovery 유발) |
| discovery | 없음 | OIDC(`/.well-known/openid-configuration`: authorize/token/jwks) |

### 기본 방식 — 정적 토큰

```bash
docker compose -f examples/oauth-mcp/docker-compose.yml up -d
./gradlew :examples:oauth-mcp:bootRun
# 토큰 발급(client_credentials, 스코프 좁혀 write/read 구분):
curl -s -u mcp-service:service-secret \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "scope=mcp.read mcp.write" http://localhost:8080/oauth2/token
# → 그 access_token 을 MCP 클라에 Authorization: Bearer 로 물린다.
```

### 추가 방식 — `native-oauth`("URL만 등록")

```bash
docker compose -f examples/oauth-mcp/docker-compose.yml up -d
./gradlew :examples:oauth-mcp:bootRun --args="\
  --spring.profiles.active=native-oauth \
  --server.port=8081 \
  --protean.example.base-url=http://localhost:8081 \
  --protean.mcp.authorization.authorization-servers[0]=http://localhost:8081"
```

MCP 클라는 서버 URL만 알면 된다: 무토큰 401 의 `resource_metadata` 포인터 → protected-resource 메타데이터 →
`authorization_servers` → OIDC discovery → authorization_code+PKCE 로그인(H2 사용자) → 토큰 자동 획득·갱신.
사람이 토큰을 만질 일이 없고, 재기동해도 키가 고정이라 등록을 다시 할 필요가 없다.

> **Dynamic Client Registration(RFC 7591)은 이 예제에서 뺐다.** Spring Authorization Server 기본 정책이
> 등록 토큰 스코프를 `{client.create}` 하나로 강제하고 요청 스코프를 그 부분집합으로 제한해, `mcp.*` 스코프를 가진
> 클라를 동적 등록할 수 없다(각각 `invalid_token`/`invalid_scope`). 뚫으려면 `OidcClientRegistrationAuthenticationProvider`
> 커스터마이즈가 필요하다. 그래서 MCP 클라는 **사전 등록**(`mcp-agent`, redirect URI 지정)하고 discovery+로그인으로 잇는다
> — 다중 서버라도 클라이언트를 한 번 등록해 두면 토큰은 자동 관리된다.

## 이 예제가 내린 결정 (11.0 Phase G)

이 예제로 실제 스택을 돌려 두 가지를 관찰했다:

1. **무토큰 → `WWW-Authenticate: Bearer` 를 Spring Resource Server 가 이미 낸다**(protean 컨트롤러 도달 전, Security 필터 단계). protean 이 401 을 내려면 소비자 필터체인을 침범해야 하므로 **낄 자리가 없다.**
2. **protected-resource 메타데이터는 소비자 컨트롤러 몇 줄로 되지만, 소비자마다 반복되는 보일러플레이트다.**

결론:

- **G1b(protean 이 401/`WWW-Authenticate` 발행) = 기각.** Resource Server 가 소유. `resource_metadata` 포인터를 401 에 싣고 싶으면 소비자의 `AuthenticationEntryPoint`(예: `BearerTokenAuthenticationEntryPoint`)에서 추가한다.
- **G1a(discovery 메타데이터) = protean 코어로 opt-in 승격.** `protean.mcp.authorization.resource` 가 설정되면 protean 이 `/.well-known/oauth-protected-resource` 를 자동 등록한다. **검증·토큰은 여전히 소비자 Security 몫**(순수 위임 불변).

그래서 이 예제의 메타데이터 엔드포인트는 이제 소비자 코드가 아니라 **protean 코어**가 낸다. 소비자는 설정만 한다:

```yaml
protean:
  mcp:
    enabled: true
    authorization:                 # 이 블록이 있으면 /.well-known/oauth-protected-resource 자동 등장
      resource: http://localhost:8080/platform/mcp
      authorization-servers:
        - http://localhost:8080
      scopes-supported: [mcp.read, mcp.write]
```

## 소비자 유의점

- `/.well-known/**` 는 미인증 접근이 가능해야 하므로 Security 에서 `permitAll` 한다(이 예제 `ResourceServerSecurityConfig` 참조).
- `/platform/mcp/**` 는 bearer 토큰으로 보호하고, 그 위에서 `ModuleActionAuthorizer` 로 동작별 정책을 건다.
- 이 예제 AS 는 자체 서명(로컬 JWKS)한다. RS 는 같은 앱의 `JwtDecoder` 빈을 재사용한다(별도 `jwk-set-uri` 불필요).
