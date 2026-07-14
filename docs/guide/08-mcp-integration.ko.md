[English](08-mcp-integration.md) | **한국어**

# 08. MCP 연동

Protean 은 MCP(Model Context Protocol) 어댑터를 내장해, AI 에이전트가 JSON-RPC 2.0 툴 호출로 모듈을 배포·업데이트·롤백·승인할 수 있게 한다. 이 문서는 라이브러리 소비자가 MCP 표면을 안전하게 켜고 연결하는 실무 절차를 다룬다.

## MCP 어댑터 켜기

MCP 서버는 **기본 off** 다. 런타임 코드 배포를 에이전트에 개방하는 것은 RCE(원격 코드 실행) 표면이고, 라이브러리는 인증을 구현하지 않으며 기본 인가가 permissive(전부 allow)이기 때문에, 소비자가 명시적으로 켜야만 기동한다(fail-safe).

```properties
# 최소 활성화 — Streamable HTTP 전송이 POST /platform/mcp 에 뜬다
protean.mcp.enabled=true
```

`protean.mcp.enabled=true` 가 아니면 `McpHttpController`·`McpDispatcher`·기본 툴 빈이 아예 등록되지 않는다(존재 자체가 사라짐). 또한 어댑터 전체는 `!worker` 프로파일에서만 활성이라, 워커 프로세스에서는 뜨지 않는다.

관련 설정 키:

| 키 | 기본값 | 용도 |
|---|---|---|
| `protean.mcp.enabled` | `false` | MCP 서버(HTTP 컨트롤러·디스패처·툴) 등록 여부 |
| `protean.mcp.stdio` | `false` | stdio 전송(newline-delimited JSON-RPC) 활성화 |
| `protean.mcp.debug.enabled` | `false` | Level 3 `debug.*` 툴 **실행 게이트**. 툴은 항상 노출되고 호출만 게이트한다(기본 false=prod, 런타임 flip 가능) → [09. 디버깅](09-debugging.ko.md) |
| `protean.mcp.session.enabled` | `true` | Streamable HTTP 세션(`Mcp-Session-Id`)·상시 GET 스트림 사용. false면 순수 stateless |
| `protean.mcp.session.timeout` | `30m` | 유휴 세션 자동 회수 임계 |
| `protean.mcp.capture-test-output` | `false` | 게이트① 테스트 실행 중 stdout/stderr 를 실패 진단에 포집(전역 `System.out` 가로채기라 opt-in) |

> 세션·재전송 버퍼 등 상세 키는 [03. 설정 레퍼런스](03-configuration.ko.md) 참고.

## 원격 서버 보안 자세(posture)

> ⚠️ **MCP 엔드포인트는 원격 코드 실행(RCE) 표면이다.** `deploy_module` 은 임의 Java 를 컴파일·실행하고, Level 3 `debug.*` 툴은 라이브 JDI 디버거(`evaluate`·`redefine`)를 붙인다. 기본 permissive 인가 + 소비자 인증 없음 상태로 도달 가능한 주소에 노출하면 그대로 **무인증 RCE** 다.

라이브러리는 **자체 인증을 제공하지 않으므로**, 안전한 자세는 전적으로 "엔드포인트가 어디서 도달 가능한가"에 달렸다. 하나를 고른다:

| 환경 | 인증 | 인가 | 추가 |
|---|---|---|---|
| 로컬 개발/데모 | 없음(허용) | permissive(기본) | **localhost 바인딩**, 포트 미노출. `examples/quickstart` 의 `mcp`/`debug` 자세 — 데모 전용. |
| 공유/스테이징 | **Bearer**(Spring Security resource server) | `READ` 와 쓰기를 가르는 `ModuleActionAuthorizer` | 토큰 로테이션; `protean.mcp.debug.enabled=false` 유지 |
| 프로덕션/멀티테넌트 | **OAuth 2.0**(스코프 → 동작) | 동작별 `ModuleActionAuthorizer` | 승인 게이트(`protean.gate.approval`) + Ed25519 서명 |

Protean 은 인증을 소비자 Spring Security 에, 인가를 `ModuleActionAuthorizer` SPI 에 위임한다. 메커니즘은 [인증·인가](#인증인가), 구체 설정은 아래 [Bearer](#bearer-토큰--엔드포인트-보호) / [OAuth 2.0](#oauth-20--엔드포인트-보호) 레시피 참고. `examples/oauth-mcp` 가 완전한 실행형 레퍼런스이며, `examples/quickstart` 의 `mcp`/`debug` 프로파일은 **로컬 데모 목적으로 의도적으로** permissive 다 — 그 자세를 도달 가능한 배포에 그대로 복사하지 말 것.

## 전송(Transport) — 두 가지

디스패처(`McpDispatcher`)는 전송 비종속이라 두 전송이 같은 코어를 공유한다. zero-dep 이며, JSON-RPC 는 Jackson 만으로 직접 처리한다(별도 MCP SDK 의존 없음).

### 1) Streamable HTTP — `POST /platform/mcp`

`protean.mcp.enabled=true` 면 `POST /platform/mcp` 엔드포인트가 뜬다. JSON-RPC 메시지를 `application/json` 바디로 보낸다.

```bash
# initialize (버전 협상)
curl -s http://localhost:8080/platform/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
       "params":{"protocolVersion":"2025-11-25"}}'

# tools/list (노출된 툴 카탈로그)
curl -s http://localhost:8080/platform/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

프로토콜 버전은 `2025-11-25` 로 핀되어 있다. `initialize` 는 요청 버전이 지원 집합이면 echo, 아니면 최신으로 응답한다.

**진행 알림(progress) 스트리밍**: 장시간 배포(게이트 단계)를 실시간으로 받고 싶으면 `tools/call` 의 `params._meta.progressToken` 을 넣는다. 그러면 응답이 단일 JSON 대신 SSE 스트림이 되어, 게이트 단계마다 `notifications/progress` 프레임을 흘리고 마지막에 결과 프레임을 보낸다. `progressToken` 이 없으면 단일 JSON 응답이다.

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call",
 "params":{"name":"protean.deploy_module",
           "arguments":{ "...": "..." },
           "_meta":{"progressToken":"deploy-1"}}}
```

**세션 & 상시 서버→클라 스트림**(`protean.mcp.session.enabled=true`, 기본 on): `initialize` 응답에 `Mcp-Session-Id` 헤더가 실려 온다. 이후 요청에 이 헤더를 넣으면 세션이 유지되고, 같은 엔드포인트에 **`GET /platform/mcp`**(`Accept: text/event-stream`, `Mcp-Session-Id` 필수)로 **상시 SSE 스트림**을 열 수 있다 — 서버가 호출 밖에서 알림을 여기로 민다. 연결이 끊기면 마지막으로 받은 이벤트 id 를 `Last-Event-ID` 헤더로 재연결해 그 이후를 **재전송(resumability)** 받는다.

- **`tools.listChanged`**: 서버는 `initialize` 에서 `capabilities.tools.listChanged=true` 를 광고한다. 툴 셋이 런타임에 바뀌면(소비자가 `McpDispatcher.registerTool`/`unregisterTool` 로 자기 툴을 추가/제거) `notifications/tools/list_changed` 를 상시 스트림으로 밀어, 클라가 **재연결 없이** `tools/list` 를 다시 받아 갱신한다.
- **하위호환**: `Mcp-Session-Id` 없이 보내면 예전처럼 **stateless**(요청/응답)로 동작한다. 알 수 없는/만료 세션 id 는 `404`(재-`initialize` 유도). `MCP-Protocol-Version` 헤더가 지원 밖이면 `400`.
- `MCP-Protocol-Version` 헤더는 `initialize` 이후 요청에 붙인다(없으면 최신으로 관용).

### 2) stdio — 로컬 spawn

로컬 에이전트(예: Claude Desktop/Code)가 서버 프로세스를 직접 spawn 하는 시나리오에는 stdio 전송을 쓴다. `McpStdioServer` 가 stdin 에서 newline-delimited JSON-RPC 를 읽어 stdout 에 한 줄씩 응답한다. EOF 는 클라이언트 종료로 간주해 프로세스가 종료된다.

두 가지 켜는 법:

```properties
# (a) 기존 앱에 stdio 전송만 추가로 등록
protean.mcp.enabled=true
protean.mcp.stdio=true
```

```bash
# (b) 전용 진입점으로 spawn — ProteanMcpStdioLauncher
#     mcp.enabled=true, mcp.stdio=true, logging.pattern.console= 를 자동 설정한다.
java -cp app.jar org.htcom.protean.boot.ProteanMcpStdioLauncher
```

`ProteanMcpStdioLauncher` 는 stdout 이 JSON-RPC 전용이 되도록 배너와 콘솔 로깅을 끈다(`logging.pattern.console=` 빈 값). 웹 서버는 그대로 떠서 배포된 모듈은 여전히 HTTP 로 서빙되고, stdio 는 제어 채널로만 쓰인다.

MCP 클라이언트(에이전트) 설정 예:

```json
{
  "mcpServers": {
    "protean": {
      "command": "java",
      "args": ["-cp", "/opt/app/app.jar",
               "org.htcom.protean.boot.ProteanMcpStdioLauncher"]
    }
  }
}
```

## 툴 카탈로그

기본 툴은 대부분 `protean.` 접두사를 쓴다. 예외는 설정 툴로, `config.` 접두사를 쓴다(`config.get` / `config.list` / `config.set`). `tools/list` 가 각 툴의 이름·설명·입력 스키마와 (설정된 경우) 표시명·출력 스키마·동작 힌트를 반환한다([툴 객체 메타데이터](#툴-객체-메타데이터-title--outputschema--annotations)). 소비자가 자기 `McpTool` 빈을 등록하면 함께 노출된다(열린 코어).

### 조회 툴

| 툴 이름 | 입력 | 용도 |
|---|---|---|
| `protean.list_modules` | 모두 선택: `query`·`mode`·`trustTier`·`limit`·`cursor` | 배포된(ACTIVE) 모듈 상태 목록(id·version·상태·격리모드). 인자 없이 부르면 전량. 검색·페이징은 [아래](#list_modules-검색·페이징) 참고 |
| `protean.get_module` | `id`(필수) | id 로 단일 모듈 상태 조회 |
| `protean.module_versions` | `id`(필수) | 버전 히스토리(최신순, 롤백 대상 확인용) |

#### `list_modules` 검색·페이징

모듈이 많을 때 id 로 목록을 훑기 어려우므로, `list_modules` 는 선택 필터와 커서 페이징을 받는다(모두 생략 가능 — 생략 시 ACTIVE 전량 반환, 기존 동작 그대로).

| 인자 | 뜻 |
|---|---|
| `query` | `id` 또는 `controllerFqcn` 부분일치(대소문자 무시) |
| `mode` | 격리모드 정확 일치(`in-process`·`worker`·`container`) |
| `trustTier` | 신뢰 등급 정확 일치(`TRUSTED`·`UNTRUSTED`) |
| `limit` | 최대 반환 개수(기본 50, 최대 200, **`0` = 전량/무제한**) |
| `cursor` | 이전 응답의 `nextCursor`(이어보기) |

`limit=0` 은 페이징 상한 없이 필터에 맞는 모듈을 **전부** 반환한다(이때 `nextCursor` 는 나오지 않는다). 결과는 `id` 오름차순으로 정렬되고, 더 남은 항목이 있으면 `structuredContent.nextCursor`(불투명 토큰)를 담는다. 이 값을 다음 요청 `cursor` 로 넘겨 이어 받는다. (이 페이징은 tool result 안의 `modules` 배열용이며, `tools/list` 등 JSON-RPC 목록의 [커서 페이지네이션](#페이지네이션-커서)과는 별개다.)

```jsonc
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
  "name":"protean.list_modules",
  "arguments":{"query":"order","mode":"worker","limit":2}
}}
// → structuredContent: {"modules":[ …최대 2건… ], "nextCursor":"Mg"}
// 다음 페이지: arguments 에 "cursor":"Mg" 추가
```

### 배포·수정 툴

| 툴 이름 | 입력 | 용도 |
|---|---|---|
| `protean.deploy_module` | `files[]` 또는 `manifest`, `id`·`version`·`controller`·`isolationMode` | 신규 모듈 배포(게이트 통과 시 ACTIVE) |
| `protean.update_module` | deploy 와 동일(`files[]`/`manifest`) | 카나리 hot-swap 업데이트(검증 실패 시 자동 롤백) |
| `protean.patch_module` | `id`·`version`·`files[]`·`removeFiles[]` | delta 업데이트 — 바뀐 파일만 overlay + 제거 후 카나리 update |
| `protean.reload_module_resources` | `id`·`files[]`·`removeFiles[]` | 리소스만 제자리 교체(컴파일·재빌드 없이 live-reload) |
| `protean.rollback_module` | `id`(필수)·`version`(필수) | 히스토리의 특정 version 으로 롤백 |
| `protean.uninstall_module` | `id`(필수) | 모듈 해제(엔드포인트·컨텍스트 제거) |

### 승인 게이트 툴

승인 게이트(`protean.gate.approval.required=true`)를 켜면 자동 게이트만 통과한 모듈이 `PENDING_APPROVAL` 로 저장되어 서빙되지 않는다. 아래 툴로 사람이 승격/거부한다. `approver` 는 감사 로그용 문자열이며, 신원 검증은 소비자 Security/`ModuleActionAuthorizer` 몫이다.

| 툴 이름 | 입력 | 용도 |
|---|---|---|
| `protean.approve_module` | `id`(필수)·`approver`(필수) | 승인 대기 모듈을 검증③+배포로 ACTIVE 승격 |
| `protean.reject_module` | `id`(필수)·`approver`(필수) | 승인 대기 모듈을 거부해 제거 |

승격 게이트 전반은 [06. 승격 게이트](06-promotion-gates.ko.md) 참고.

### 배포 입력 형식 — `files[]` 와 `manifest`

`deploy_module`/`update_module` 은 두 입력 방식 중 하나를 받는다(배타).

**(A) `files[]` 방식** (권장) — top-level 로 `id`·`version`·`controller` 를 주고, 소스/테스트/리소스를 파일 배열로 넣는다. 각 파일 항목:

- `kind`: `source` | `test` | `resource` (기본 `source`)
- `filename`: `source`/`test` 는 파일명(FQCN 은 `package`+파일명에서 자동 도출), `resource` 는 classpath 경로(예: `mapper/OrderMapper.xml`)
- `content`: 파일 내용
- `base64`: 리소스가 바이너리면 `true`(이때 `content` 는 Base64). 기본 `false`(평문)

```json
{"jsonrpc":"2.0","id":10,"method":"tools/call",
 "params":{"name":"protean.deploy_module","arguments":{
   "id":"orders",
   "version":"1.0.0",
   "controller":"com.acme.orders.OrderController",
   "isolationMode":"in-process",
   "files":[
     {"kind":"source","filename":"OrderController.java",
      "content":"package com.acme.orders; ... @RestController ..."},
     {"kind":"test","filename":"OrderControllerTest.java",
      "content":"package com.acme.orders; ... @Test ..."},
     {"kind":"resource","filename":"mapper/OrderMapper.xml",
      "content":"<mapper> ... </mapper>"}
   ]}}}
```

**(B) `manifest` 방식** — `module.yaml` 텍스트 하나를 `manifest` 필드에 넣는다(모듈 작성 형식은 [02. 모듈 작성](02-module-authoring.ko.md) 참고).

`isolationMode` 는 `in-process` | `worker` | `container` — [05. 격리 모드](05-isolation-modes.ko.md) 참고.

게이트 거부·컴파일 실패 같은 도메인 실패는 JSON-RPC error 가 아니라 tool result 의 `isError: true`(+진단 텍스트)로 매핑된다. 프로토콜 수준 오류(알 수 없는 툴·필수 인자 누락 등)만 JSON-RPC error 로 나간다.

## 인증·인가

**라이브러리는 인증을 구현하지 않는다.** 두 축으로 나뉜다.

- **인증(누구인가)**: 소비자의 Spring Security 몫. `POST /platform/mcp` 로 들어온 요청의 `Principal` 을 그대로 인가 컨텍스트(`McpCallContext.caller`)로 흘려보낸다. 무보안이면 `null`. stdio 는 **로컬 신뢰 경계**라 spawn 주체가 곧 인가 주체이므로 항상 `caller=null` 이다.
- **인가(무엇을 할 수 있나)**: `ModuleActionAuthorizer` SPI. 모든 툴 호출(코어 툴 + 소비자 커스텀 툴)이 이 하나의 choke point 를 거친다. 툴마다 `ModuleAction`(`READ`·`DEPLOY`·`UPDATE`·`DELETE`·`APPROVE`·`DEBUG`·`CUSTOM`)이 분류되어 있어, 동작별로 정책을 분기할 수 있다.

기본 구현은 `PermissiveModuleActionAuthorizer`(전부 allow)다. 소비자가 `ModuleActionAuthorizer` 빈을 등록하면 `@ConditionalOnMissingBean` 으로 기본이 대체된다.

```java
@Bean
ModuleActionAuthorizer moduleActionAuthorizer() {
    return (caller, action, moduleId) -> {
        // 익명(무인증 HTTP)은 읽기만 허용
        if (caller == null) {
            return action == ModuleActionAuthorizer.ModuleAction.READ
                    ? ModuleActionAuthorizer.Decision.allow()
                    : ModuleActionAuthorizer.Decision.deny("인증 필요");
        }
        // 배포·수정·삭제·디버그는 관리자만
        boolean admin = caller instanceof org.springframework.security.core.Authentication a
                && a.getAuthorities().stream()
                     .anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN"));
        return admin
                ? ModuleActionAuthorizer.Decision.allow()
                : ModuleActionAuthorizer.Decision.deny("ROLE_ADMIN 필요: " + action);
    };
}
```

거부 시 툴 호출은 실행되지 않고 `isError` result 로 "권한 거부: <reason>" 을 돌려준다. Spring Security 로 `POST /platform/mcp` 를 보호하는 구체 설정은 [12. 보안](12-security.ko.md) 참고.

### Bearer 토큰 — 엔드포인트 보호

도달 가능한 배포에서 가장 단순한 자세: `POST /platform/mcp` 앞에 Spring Security resource server 를 두고 bearer 토큰을 요구한다. Protean 은 관여하지 않는다 — 검증된 `Principal` 이 `McpCallContext.caller` 로 흘러오고, 동작별 판단은 `ModuleActionAuthorizer` 가 한다.

```java
@Bean
SecurityFilterChain mcpChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/platform/mcp/**")
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
    return http.build();
}
```

토큰이 없으면 `401 WWW-Authenticate: Bearer` — Protean 컨트롤러에 **도달하기 전** resource server 가 낸다(라이브러리는 그 요청을 보지도 못한다). 토큰은 각자의 authorization server 방식으로 발급받으면 된다(예: `client_credentials`):

```bash
curl -s -u mcp-service:service-secret \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode "scope=mcp.read mcp.write" \
  http://localhost:8080/oauth2/token
# → access_token 을 클라이언트에 `Authorization: Bearer <token>` 로 물린다
```

> Bearer 는 **누구인가**에만 답하지 **무엇을 할 수 있나**는 제한하지 않는다. 위의 `ModuleActionAuthorizer` 와 짝지어 읽기 전용 호출자가 `deploy_module` 을 못 하게 한다. 정적 토큰 전체 구성은 `examples/oauth-mcp` 참고.

### OAuth 2.0 — 엔드포인트 보호

프로덕션에서는 엔드포인트를 OAuth 2.0 으로 감싸고 **스코프 → `ModuleAction`** 으로 매핑한다. `examples/oauth-mcp` 모듈이 완전한 실행형 레퍼런스(authorization server + resource server + `McpScopeAuthorizer`)이며 두 가지 클라이언트 방식을 지원한다:

| | 정적 토큰 | 네이티브 discovery (`native-oauth`) |
|---|---|---|
| 토큰 | 수동 주입 bearer | 클라이언트가 자동 획득 |
| 흐름 | 외부에서 발급해 클라에 붙임 | 401 → `resource_metadata` → discovery → authorization_code + PKCE |
| 서명 키 | 매 부팅 재생성(재기동 시 토큰 무효) | 파일 영속 → 재기동 내성 |

`authorization` 블록을 설정하면 Protean 이 **opt-in** 으로 `/.well-known/oauth-protected-resource`(RFC 9728)를 낸다 → discovery 지원 클라이언트가 엔드포인트 URL 만으로 authorization server 를 찾아온다. **토큰 검증은 여전히 소비자 Security 몫** — Protean 은 메타데이터만 광고한다:

```yaml
protean:
  mcp:
    enabled: true
    authorization:                 # 있으면 /.well-known/oauth-protected-resource 자동 노출
      resource: http://localhost:8080/platform/mcp
      authorization-servers:
        - http://localhost:8080
      scopes-supported: [mcp.read, mcp.write]
```

스코프→동작 매핑은 `ModuleActionAuthorizer` 에 둔다(예: `DEPLOY`/`UPDATE`/`DELETE` 에 `mcp.write`, `READ` 에 `mcp.read` 요구). 흐름 설명과 결정 배경은 [`examples/oauth-mcp/README.ko.md`](../../examples/oauth-mcp/README.ko.md) 참고.

## MCP 클라이언트에서 연결·구동 (Claude Code · Curator · …)

어떤 MCP 클라이언트(Claude Code·Curator·기타 에이전트)든 실행 중인 서버를 같은 방식으로 구동한다: 원격 엔드포인트를 등록하고 툴을 호출한다. 로컬 spawn 은 위 [stdio](#2-stdio--로컬-spawn), 도달 가능한 서버는 Streamable HTTP 엔드포인트를 쓴다.

HTTP 엔드포인트 등록(Claude Code 예시 — 다른 클라이언트도 같은 `mcpServers` 형태):

```bash
# CLI
claude mcp add --transport http protean http://localhost:8080/platform/mcp
```

```jsonc
// 또는 .mcp.json — 엔드포인트를 보호했으면(Bearer/OAuth) Authorization 헤더 추가
{
  "mcpServers": {
    "protean": {
      "type": "http",
      "url": "http://localhost:8080/platform/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

연결되면 기본 툴이 서버명 아래로 노출된다(예: `mcp__protean__protean.deploy_module`). 전형적인 에이전트 구동 루프 — 에이전트에게 주는 지시로 표현하면 — 는:

1. **배포** — "이 소스로 `orders` 모듈을 배포해줘, 번들 테스트가 게이트야." → `protean.deploy_module`. 게이트 실패는 진단이 담긴 `isError` result 로 돌아와, 에이전트가 고쳐 재시도한다.
2. **라이브 검증** — 모듈의 실제 엔드포인트(`GET /orders/{id}`)를 호출하거나 `protean.query_traces` 로 서빙 확인.
3. **디버그** — "`OrderController:42` 에 브레이크포인트 걸고 debug 로 띄운 뒤 `order.total` 평가해줘." → `debug.launch` / `debug.set_breakpoint` / `debug.await_stop` / `debug.evaluate` (`protean.mcp.debug.enabled=true` 필요).
4. **반복** — `protean.patch_module`(바뀐 파일만) 또는 `debug.redefine`(fix-and-continue) — 전체 재배포 없이.
5. **롤백** — "`orders` 를 1.0.0 으로 되돌려줘." → `protean.rollback_module`.

오류가 산문이 아니라 `traceId` 로 상관된 구조화 RFC 9457 result 로 표면화되므로, 에이전트는 추측 대신 결정론적으로 자기교정한다. 전체 툴 목록은 [툴 카탈로그](#툴-카탈로그), 디버그 툴은 [09. 디버깅](09-debugging.ko.md) 참고.

## 툴 객체 메타데이터 (title · outputSchema · annotations)

`McpTool` 은 이름·설명·입력 스키마 외에 MCP 스펙 `2025-11-25` 의 툴 객체 필드를 **선택적으로** 노출할 수 있다. 모두 default 메서드라 구현하지 않으면 `null` 이고, `tools/list` 직렬화에서 **생략**된다(미설정 ≠ false — 구현분만 정직하게 광고).

| 메서드 | 필드 | 뜻 |
|---|---|---|
| `title()` | `title` | `name` 과 별개의 사람용 표시명. 클라 UI 는 이걸 우선 노출한다. |
| `outputSchema()` | `outputSchema` | 성공 결과 `structuredContent` 의 JSON Schema. |
| `annotations()` | `annotations` | 동작 힌트 — `readOnlyHint` / `destructiveHint` / `idempotentHint` / `openWorldHint`. |

동작 힌트는 `McpToolAnnotations` 빌더로 만든다. 설정한 힌트만 직렬화된다:

```java
@Override public String title() { return "Deploy Module"; }

@Override public McpToolAnnotations annotations() {
    return McpToolAnnotations.builder()
            .readOnly(false).destructive(false).idempotent(false).openWorld(false).build();
}

@Override public ObjectNode outputSchema() { /* JSON Schema (type:object) */ }
```

> **힌트는 신뢰 대상이 아니다.** `annotations` 는 클라이언트 UX/게이팅 판단용 힌트일 뿐 인가·안전 경계가 아니다. 실제 권한은 항상 `ModuleActionAuthorizer` 가, 디버그 실행 여부는 `DebugSurfaceState` 게이트가 강제한다.

### outputSchema 검증 범위 (서버 vs 클라이언트)

`outputSchema` 를 선언한 툴이 **성공**을 반환하면, 디스패처가 결과 `structuredContent` 에 대해 **최소 정합만** 검사한다:

- `structuredContent` 가 존재하고 **객체**인지
- 스키마의 **top-level `required`** 필드가 모두 있는지

둘 중 하나라도 어기면 조용히 흘리지 않고 `isError`("출력 스키마 위반: …") 로 막는다 — 잘못된 구조화 출력이 클라로 새는 버그 계열을 서버에서 차단하기 위함이다. `isError` result(도메인 실패)에는 이 검사를 적용하지 않는다(구조화 출력이 없는 게 정상).

**기본값에선 중첩 필드·타입·형식 등 전체 JSON Schema 검증은 하지 않는다.** MCP 코어는 의도적으로 zero-dep 이라 스키마 밸리데이터를 끌어오지 않으며, 스펙도 전체 검증을 **클라이언트 몫**("Clients SHOULD validate structured results against this schema")으로 둔다. 즉 `outputSchema` 는 클라가 완전 검증할 수 있도록 **정확히 광고**하는 것이 서버의 역할이고, 기본 서버측 가드는 명백한 계약 위반(누락·비객체)만 잡는 안전망이다.

> **strict 모드 (opt-in) — 전체 검증.** `protean.mcp.strict-schema=true` 면 위 최소 정합 대신 **전체 JSON Schema 검증**을 수행한다: 인입 `arguments`↔`inputSchema`, 성공 `structuredContent`↔`outputSchema` 를 중첩·타입까지 검사(입력 위반→`INVALID_ARGUMENT`, 출력 위반→`OUTPUT_SCHEMA_VIOLATION`). 코어 zero-dep 을 지키려 검증기(networknt)는 **탑재하지 않는다**(`compileOnly`) — strict 를 켜고 검증기를 클래스패스에 두면 활성, 없으면 위 top-level 가드로 자동 폴백. 라이브러리 자신의 툴은 테스트로 정합성이 보장되므로, 이 모드는 주로 **소비자 커스텀 툴**의 계약을 런타임에서 강제하고 싶을 때 쓴다.

## 커스텀 툴 추가

소비자가 `McpTool` 인터페이스를 구현한 빈을 등록하면 `McpDispatcher` 가 `List<McpTool>` 로 함께 수집해 자동 노출한다. 커스텀 툴의 기본 `action()` 은 `CUSTOM` 이라 authorizer 가 별도 판정한다. 위 [툴 객체 메타데이터](#툴-객체-메타데이터-title--outputschema--annotations)(title·outputSchema·annotations)도 커스텀 툴에서 그대로 쓸 수 있다. SPI 확장 전반은 [10. SPI 확장](10-spi-extension.ko.md) 참고.

라이브러리 테스트는 커스텀 툴을 덮을 수 없으므로, 커스텀 툴의 in/out 계약 정합성은 두 가지로 보장한다: **(1) 런타임** — `protean.mcp.strict-schema=true`(위 [outputSchema 검증 범위](#outputschema-검증-범위-서버-vs-클라이언트)) 로 경계에서 전체 검증; **(2) 테스트** — 공개 `SchemaValidator`(`SchemaValidator.create().validate(schema, instance)` → 위반 메시지 목록, networknt 를 test 의존성으로 추가) 로 자기 테스트에서 검증. 둘 다 networknt 를 소비자 쪽에서만 추가하며 코어 런타임엔 영향이 없다.

## 서버 기능 표면 (logging · completions · templates · subscribe · pagination)

`initialize` 응답 `capabilities` 에 아래를 광고한다(구현분만 정직하게).

### 로깅 (`logging`)

`logging/setLevel` 로 서버 로그 임계값을 조정하면, 서버가 `notifications/message`(syslog 8단계: debug…emergency)를 상시 스트림으로 흘린다. 임계값 미만 레벨은 억제된다.

```json
{"jsonrpc":"2.0","id":10,"method":"logging/setLevel","params":{"level":"debug"}}
```

> 임계값은 **서버-전역**이다(연결된 모든 클라 공통) — 제어 평면 특성상 단순·정직한 선택. 방출은 세션 표면이 켜져 있을 때만 상시 스트림으로 나간다. 라이브러리 소비자는 `McpDispatcher.emitLog(level, logger, data)` 로 자기 로그를 방출할 수 있다.

### 자동완성 (`completions`)

`completion/complete` 로 프롬프트/리소스템플릿 인자 후보를 받는다. protean 은 리소스 템플릿 `protean://modules/{id}/…` 의 `id` 를 배포된 모듈 id 접두사로 완성한다(프롬프트 자유 텍스트 인자는 후보 없음).

```json
{"jsonrpc":"2.0","id":11,"method":"completion/complete",
 "params":{"ref":{"type":"ref/resource","uri":"protean://modules/{id}/source"},
           "argument":{"name":"id","value":"ord"}}}
```

### 리소스 템플릿 (`resources/templates/list`)

정적 리소스 목록(`resources/list`)과 별개로, 파라미터화된 URI 템플릿을 노출한다: `protean://modules/{id}/source`, `protean://modules/{id}/versions`, `protean://modules/{id}/routes`. `{id}` 를 채워 `resources/read` 로 조회한다.

**`protean://modules/{id}/routes` — 라우터 실측.** 모듈이 런타임에 **실제 등록한** HTTP 라우트(메서드 + 경로 패턴)를 돌려준다. 스토어의 `desiredState`(선언)가 아니라 살아있는 매핑에서 읽으므로, "상태는 ACTIVE 인데 호출은 404"(예: 재컴파일/복구 실패로 등록 0건) 불일치를 그대로 드러낸다 — **빈 배열이면 실제로 서빙되는 라우트가 없다는 신호**다. 존재하지 않는 모듈 id 는 `-32602`(invalid params).

```jsonc
{"jsonrpc":"2.0","id":1,"method":"resources/read",
 "params":{"uri":"protean://modules/order-svc/routes"}}
// → contents[0].text (JSON):
// [{"methods":["GET"],"patterns":["/orders/{id}"]},
//  {"methods":["POST"],"patterns":["/orders"]}]
```

### 리소스 구독 (`resources.subscribe`)

`resources/subscribe`(+`unsubscribe`)로 특정 리소스 uri 변경을 구독하면, 모듈이 바뀔 때(배포·승인·거부·업데이트·리소스리로드·제거·롤백) 서버가 `notifications/resources/updated{uri}` 를 밀어 클라가 다시 `read` 하도록 한다.

```json
{"jsonrpc":"2.0","id":12,"method":"resources/subscribe","params":{"uri":"protean://modules"}}
```

> 구독은 **서버-전역**으로 추적하고, 변경 시 연결된 모든 세션에 브로드캐스트한다. 코어 `ModulePlatform` 변경이 `McpResourceUpdateBridge` 를 거쳐 통지로 이어진다(REST·MCP 어느 경로의 변경이든 동일하게 반영).

### 페이지네이션 (커서)

`tools/list`·`resources/list`·`resources/templates/list`·`prompts/list` 는 커서 페이지네이션을 지원한다. 응답에 `nextCursor` 가 있으면 다음 요청 `params.cursor` 로 넘겨 이어 받는다(불투명 토큰). 기본 페이지 크기 100 — 소비자 툴이 많아져도 전부 회수할 수 있다.

```json
{"jsonrpc":"2.0","id":13,"method":"tools/list","params":{"cursor":"MTAw"}}
```

## 클라이언트 기능 요청 (sampling · roots · elicitation)

서버가 **클라이언트에게** 요청하는 양방향 기능이다(서버→클라 JSON-RPC 요청). 세션 표면이 켜져 있어야 하고(상시 스트림으로 요청을 밀고, 클라가 같은 엔드포인트에 응답을 POST 하면 요청 id 로 매칭), 클라가 `initialize` 에서 해당 capability 를 신고해야 한다. 라이브러리 소비자는 `McpDispatcher` 의 프리미티브로 호출한다:

| 프리미티브 | MCP 메서드 | 필요한 클라 capability |
|---|---|---|
| `createMessage(sessionId, params, timeoutMs)` | `sampling/createMessage` | `sampling` |
| `listRoots(sessionId, timeoutMs)` | `roots/list` | `roots` |
| `elicit(sessionId, params, timeoutMs)` | `elicitation/create` | `elicitation` |

호출은 클라 응답까지 **블로킹**하며(timeout), 채널이 없거나(stateless/stdio) 클라가 capability 를 신고하지 않았으면 예외를 던진다. 세션(`ctx.sessionId()`)이 목적지 — `McpCallContext` 로 흘러온다. `notifications/roots/list_changed` 는 수신하지만 roots 는 즉시-조회라 캐시가 없어 no-op 다.

> 응답 상관: 서버는 요청에 `srv:<n>` id 를 부여해 세션 스트림으로 보내고, 클라가 그 id 로 응답을 POST 하면 `McpSessionRegistry` 가 대기 future 를 완료한다. stdio 전송은 상관 채널이 없어 이 기능을 지원하지 않는다(응답 프레임은 조용히 무시).

## 취소 · _meta · 인가 오류

- **요청 취소** — 클라이언트가 `notifications/cancelled{requestId, reason}` 를 보내면 해당 in-flight 요청의 취소 토큰을 세우고 워커 스레드를 인터럽트한다. 협조적 취소 지점은 **진행 알림**이다 — 장시간 툴이 `ctx.progress().report(...)` 를 호출하는 순간 취소됐으면 중단되어 `isError` 결과가 된다(다음 단계 경계에서 끊김). 취소 상관은 세션 스코프이며, 이미 끝났거나 모르는 requestId 는 무시(멱등).
- **`_meta`** — 요청 `params._meta` 는 `McpCallContext.meta()` 로 툴에 전달된다. 툴은 `McpToolResult.withMeta(json)` 로 결과에 `_meta` 를 실어 되돌릴 수 있다(양방향 통과).
- **인가 오류 텍스트** — 인가 거부 시 결과 텍스트는 `접근 권한 없음(permission denied): <사유> [action=…, tool=…]` 형식이라, 에이전트가 "권한 없음"을 명확히 인지한다(재시도 대신 승인/역할 조정 유도).

## 관련 문서

- [02. 모듈 작성](02-module-authoring.ko.md)
- [05. 격리 모드](05-isolation-modes.ko.md)
- [06. 승격 게이트](06-promotion-gates.ko.md)
- [09. 디버깅](09-debugging.ko.md)
- [10. SPI 확장](10-spi-extension.ko.md)
- [12. 보안](12-security.ko.md)
- [README](../../README.ko.md)
