[English](03-configuration.md) | **한국어**

# 03. 설정 레퍼런스

Protean 의 모든 설정은 `protean.*` 프리픽스 아래 있다(`ProteanProperties`). 타입 안전하며
spring-boot-configuration-processor 메타데이터가 생성되므로 소비자 IDE 에서 자동완성/검증을 받는다.
키는 relaxed-binding(kebab-case) 규약을 따른다(예: `protean.module.request-timeout-ms`).

아래는 `application.yml`/`application.properties` 에서 그대로 쓸 수 있는 그룹별 전체 키 목록이다. 기본값은
소스 바인딩과 일치한다 — 대부분은 `ProteanProperties`, 일부는 해당 설정의 `@Value`(예: `protean.mcp.session.*`
는 `McpConfiguration`, `protean.mcp.debug.*` 는 `DebugMcpConfiguration`).

**반영** 열은 변경이 언제 적용되는지 나타낸다: `라이브`(즉시, 작업 시점), `future`(이후 생성되는 인스턴스에만 —
새 배포/워커/컨테이너; 이미 떠 있는 것은 영향 없음), `restart`(앱 재기동 필요). `라이브`/`future` 키는 런타임에
`PATCH /platform/config`([04. REST API 레퍼런스](04-rest-api.ko.md))로도 변경할 수 있고, `restart` 키는 거기서 읽기 전용이다.

## admin — 관리 REST surface

`/platform/*` 컨트롤 플레인 노출 여부.

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.admin.enabled` | `boolean` | `true` | `restart` | `false` 면 `/platform/*` admin 컨트롤러(`ModuleAdminController`, `TraceAdminController`, `ConfigAdminController`, `SharedLibAdminController`)를 등록하지 않는다. |

## mcp — MCP 어댑터 surface

MCP 에이전트가 모듈을 직접 배포하는 입구. RCE 표면이라 **기본 off** — 명시적으로 켜야 기동한다.

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.mcp.enabled` | `boolean` | `false` | `restart` | `true` 여야 MCP 서버(`McpHttpController` 등)를 등록한다. |
| `protean.mcp.stdio` | `boolean` | `false` | `restart` | stdio 전송(newline-delimited JSON-RPC) 활성화. 로컬 에이전트 spawn 진입점용. |
| `protean.mcp.debug.enabled` | `boolean` | `false` | `restart` | `debug.*` 툴 **실행 게이트**(초기값). 툴은 `mcp.enabled` 면 항상 노출되고, 이 값이 false면 호출 시 `isError`("debug surface disabled")로 거부(기본 false=prod). 런타임 가변(`DebugSurfaceState`). |
| `protean.mcp.debug.session-idle-timeout` | `Duration` | `30m` | `restart` | 유휴 디버그 세션 자동 회수 임계. `0`/음수면 비활성. |
| `protean.mcp.capture-test-output` | `boolean` | `false` | `라이브` | 게이트① 테스트 실행 중 stdout/stderr 를 포집해 실패 진단에 포함(전역 `System.out` 가로채기라 opt-in). |
| `protean.mcp.strict-schema` | `boolean` | `false` | `라이브` | 켜면 디스패처가 툴 `arguments`↔`inputSchema`, 성공 `structuredContent`↔`outputSchema` 를 **전체 JSON Schema**(중첩·타입)로 검증(입력 위반→`INVALID_ARGUMENT`, 출력→`OUTPUT_SCHEMA_VIOLATION`). 검증기(networknt)는 코어에 미탑재(`compileOnly`) — 클래스패스에 있을 때만 활성, 없으면 top-level `required` 가드로 폴백. 주로 소비자 커스텀 툴 계약을 런타임에서 강제할 때. |
| `protean.mcp.session.enabled` | `boolean` | `true` | `restart` | Streamable HTTP 세션(`Mcp-Session-Id`)·상시 GET SSE 스트림 사용. false면 순수 stateless(요청/응답). |
| `protean.mcp.session.timeout` | `Duration` | `30m` | `restart` | 유휴 MCP 세션 자동 회수 임계. |
| `protean.mcp.session.replay-buffer` | `int` | `256` | `restart` | 상시 스트림 재전송 버퍼 크기(세션당 최근 N개 이벤트, `Last-Event-ID` 재연결용). |
| `protean.mcp.session.stream-timeout` | `Duration` | `1h` | `restart` | GET 상시 스트림 emitter 타임아웃(heartbeat 로 유지, 초과 시 클라가 재연결). |

### mcp.authorization — OAuth protected-resource 메타데이터 (RFC 9728, opt-in)

이 MCP 엔드포인트를 OAuth 2.0 protected resource 로 광고해 클라이언트가 authorization server 를 발견하게 한다. `resource` 를 설정해야 켜진다. 라이브러리는 메타데이터만 서빙하고, 실제 토큰 검증은 소비자가 배선한다([12. 보안](12-security.ko.md), `examples/oauth-mcp` 예제 참고).

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.mcp.authorization.resource` | `String` | (없음) | `restart` | protected-resource 식별자(보통 MCP 엔드포인트 URL). 설정 시 `/.well-known/oauth-protected-resource` 메타데이터 엔드포인트가 활성화된다. |
| `protean.mcp.authorization.authorization-servers` | `List<String>` | (빈 목록) | `라이브` | 이 리소스용 토큰을 발급하는 Authorization Server issuer URL 목록. |
| `protean.mcp.authorization.scopes-supported` | `List<String>` | (빈 목록) | `라이브` | 광고할 지원 스코프(선택). |
| `protean.mcp.authorization.bearer-methods-supported` | `List<String>` | `["header"]` | `라이브` | 베어러 토큰 전달 방식(RFC 9728 `bearer_methods_supported`). |

## bridge — 워커→메인 RPC 브리지

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.bridge.url` | `String` | (없음) | `restart` | 워커가 공유 빈 호출을 포워딩할 메인 브리지 URL(워커 프로세스에 주입됨). |
| `protean.bridge.auth-enabled` | `boolean` | `false` | `restart` | `/__bridge/*` 에 공유 시크릿 인증을 강제(opt-in). 켜면 메인이 시크릿을 생성/사용해 spawn 워커에 주입하고, 미인증 호출은 401. 전송 암호화(TLS)는 별개. |
| `protean.bridge.secret` | `String` | (없음) | `restart` | 브리지 인증 공유 시크릿. `auth-enabled=true` 인데 비어 있으면 메인이 JVM 수명 단위로 자동 생성해 워커에 주입. 외부 관리형 고정 시크릿을 쓰려면 명시. |
| `protean.bridge.auth-mode` | `String` | `token` | `restart` | 인증 방식: `token`(정적 베어러 토큰) 또는 `hmac`(요청별 HMAC-SHA256 서명, 재전송·바디 변조까지 방어). 둘 다 같은 대칭 시크릿 사용. |
| `protean.bridge.hmac-window-ms` | `long` | `30000` | `라이브` | `hmac` 모드에서 허용하는 워커 요청 타임스탬프와 메인 시계의 최대 오차(ms). 초과 시 401. |

## gate — 승격 게이트

기본은 안전한 쪽(전부 on). 소비자가 신뢰 수준에 맞춰 개별 게이트를 완화할 수 있다.

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.gate.tests-enabled` | `boolean` | `true` | `라이브` | 게이트 ①: 단위 테스트 동봉·통과 강제. `false` 면 테스트 없어도 통과. |
| `protean.gate.review-enabled` | `boolean` | `true` | `라이브` | 게이트 ②: 바이트코드 리뷰(`ForbiddenApiRule` 등). `false` 면 코드 체크 생략. |
| `protean.gate.signature.required` | `boolean` | `false` | `라이브` | 서명 검증 게이트 강제(opt-in). 켜면 모든 install 이 신뢰 키로 서명돼야 통과. |
| `protean.gate.signature.keys` | `Map<String,String>` | (빈 맵) | `라이브` | 신뢰 공개키: `keyId → Base64(X.509 Ed25519 공개키)`. |
| `protean.gate.signature.shared-lib-required` | `boolean` | `false` | `라이브` | 라이브 shared-lib 업로드(put-jar 표면)를 신뢰 키로 서명하도록 강제(opt-in; `signature.keys` 를 신뢰 저장소로 재사용). |
| `protean.gate.approval.required` | `boolean` | `false` | `라이브` | 승인 게이트 강제(opt-in, 사람 인가). 켜면 install 이 `PENDING_APPROVAL` 로 저장되고 `POST /{id}/approve` 로 승인해야 `ACTIVE`. |

## isolation — 격리 전략

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.isolation.mode` | `String` | `in-process` | `future` | 전역 기본 격리 모드: `in-process` \| `worker` \| `container`. |

격리 모드 상세는 [05. 격리 모드](05-isolation-modes.ko.md) 를 본다.

## module — 모듈 요청 실행 제어

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.module.request-timeout-ms` | `long` | `0` | `라이브` | 모듈 요청 타임아웃(ms). `0`=무제한. |
| `protean.module.shared-lib-dir` | `String` | `""` | `restart` | 공유 lib 디렉터리. 지정 시 그 디렉터리의 `*.jar` 로 앱-수명 `URLClassLoader` 를 만들어 모듈 ClassLoader 부모로 삽입하고 컴파일 클래스패스에도 더한다. 비면 off. in-process 대상. |
| `protean.module.shared-lib-store-dir` | `String` | `${java.io.tmpdir}/protean-shared-libs` | `restart` | 서버 관리형 라이브 shared-lib 스토어(put-jar 표면) 디렉터리. 런타임 업로드 jar 를 여기 영속(읽기 전용 `shared-lib-dir` 시드와 분리). 경로는 재기동 아티팩트, 담긴 jar 집합은 라이브. |
| `protean.module.eager-shared-lib-invalidation` | `boolean` | `true` | `라이브` | 새 shared-lib generation 발행 시(put-jar deploy/remove 가 사용 중 jar 를 바꿈) 그것을 쓰는 ACTIVE 모듈을 새 generation 으로 즉시 rebind(무중단). `false` 면 재배포 전까지 바인딩된 generation 유지. 라이브. |
| `protean.module.eager-shared-module-invalidation` | `boolean` | `true` | `restart` | 라이브러리 모듈이 자기 generation 을 재발행할 때(타입 공유 shared-module) 그것을 `use` 하는 ACTIVE 의존자에게 즉시 전파. `false` 면 재배포 전까지 바인딩된 generation 유지. |
| `protean.module.executor.pool-size` | `int` | `2` | `future` | per-module 관리형 실행기(`ProteanTaskExecutor`) 스레드 풀 크기. |

## module-store — 디스크립터 내구 저장소

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.module-store.backend` | `String` | `filesystem` | `restart` | 저장 백엔드: `filesystem` \| `jdbc`. |
| `protean.module-store.dir` | `String` | `${java.io.tmpdir}/protean-modules` | `restart` | filesystem 백엔드 저장 디렉터리. |

## reconcile — 기동 시 reconcile(ACTIVE 모듈 복구)

기동 시 플랫폼은 모든 `ACTIVE` 모듈을 소스에서 재컴파일·재배포한다. 아래는 그 단계를 조정한다(기동 시점 전용, 라이브 리로드 불가).

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.reconcile.compile-parallelism` | `int` | `0` | `restart` | 병렬 사전 컴파일 단계의 스레드 풀 크기. `0`=auto(`availableProcessors()`), `1`=완전 직렬(kill switch), `N`=N개로 상한. |
| `protean.reconcile.reuse-file-manager` | `boolean` | `true` | `restart` | 병렬 사전 컴파일에서 워커 스레드당 javac file manager 하나를 재사용(읽기 전용 컴파일 클래스패스를 컴파일마다 대신 스레드당 한 번만 스캔). `false` 면 호출별 file manager 로 복귀(kill switch). 병렬 단계가 돌 때만 유효. |

## trace — 런타임 trace 기록

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.trace.enabled` | `boolean` | `true` | `라이브` | trace 기록 활성화. |
| `protean.trace.capacity` | `int` | `200` | `라이브` | 링 버퍼 용량(최근 N개 요청). |
| `protean.trace.summary-window-ms` | `long` | `60000` | `라이브` | 콘솔 `summary` SSE 이벤트의 롤링 윈도(ms): 현재 윈도 `(now-windowMs, now]` 와 이전 동일 윈도 대비 trend. `metrics.enabled` 와 무관(링버퍼서 계산), 정확도는 `capacity` 에 종속. |

### trace.metrics — 모듈별 집계 메트릭(opt-in)

요청 수·오류율·지연 백분위를 모듈별로 집계한다. 꺼져 있으면(기본) 기록 핫패스는 boolean 체크 하나 외 비용이 없다. 조회는 `GET /platform/traces/metrics`(→ [04. REST API 레퍼런스](04-rest-api.ko.md))와 MCP `protean.module_metrics` 툴.

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.trace.metrics.enabled` | `boolean` | `false` | `라이브` | 모듈별 카운터/지연 히스토그램 집계 활성화. |
| `protean.trace.metrics.latency-buckets` | `int` | `20` | `future` | 모듈당 지연 히스토그램 버킷 수(로그-선형). 많을수록 백분위가 정밀하지만 메모리 증가. |
| `protean.trace.metrics.max-modules` | `int` | `512` | `future` | 추적할 최대 모듈 수. 초과 시 가장 오래 안 보인 모듈부터 축출. |

## worker — 외부 워커(process/container)

워커 격리 실행 설정.

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.worker.modules-per-worker` | `int` | `4` | `future` | 워커당 최대 모듈 수(`1`=모듈당 전용 JVM). |
| `protean.worker.min-warm` | `int` | `0` | `future` | 빈 워커를 따뜻하게 유지할 수(재사용). |
| `protean.worker.auto-restart` | `boolean` | `false` | `라이브` | 크래시한 워커의 모듈 자동 재기동(process track). |
| `protean.worker.shutdown-grace-ms` | `long` | `5000` | `라이브` | 메인 종료 시 각 워커 JVM에 graceful 종료(SIGTERM)를 위해 주는 유예(ms). 이후 강제 종료. `0`=즉시 강제 종료; 음수→`0`으로 처리. |
| `protean.worker.rpc-bridge` | `boolean` | `false` | `restart` | 워커가 메인 공유 빈을 RPC 브리지로 호출 허용. |
| `protean.worker.runtime` | `String` | `embed` | `restart` | 워커 런타임 배포 모델: `embed` \| `sidecar`. |

### worker.admin-auth — 워커 `/__admin/*` 인증(opt-in)

변경성 워커 admin 호출에 대한 심층 방어 인증(특히 포트가 더 노출되는 container track). 읽기 전용 `/__admin/health` 프로브는 열려 있다. `protean.bridge.*` 와 독립.

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.worker.admin-auth.enabled` | `boolean` | `false` | `restart` | 변경성 `/__admin/*` 호출에 인증 강제. 켜면 메인이 시크릿을 생성/주입해 spawn 워커에 넣고, 미인증 변경 호출은 401. |
| `protean.worker.admin-auth.secret` | `String` | (없음) | `restart` | 공유 시크릿. 비어 있고 enabled 면 메인이 JVM 수명 단위로 자동 생성해 주입. 외부 관리형 고정 시크릿은 명시. |
| `protean.worker.admin-auth.mode` | `String` | `hmac` | `restart` | `hmac`(요청별 HMAC-SHA256, timestamp+nonce+body 서명, 재전송·변조 방어 — 기본) 또는 `token`(정적 베어러 토큰). |
| `protean.worker.admin-auth.hmac-window-ms` | `long` | `30000` | `restart` | `hmac` 모드: 발신 타임스탬프와 워커 시계의 최대 허용 오차(ms). |

### worker.datasource — 전역 수동 DB 스코프

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.worker.datasource.url` | `String` | `""` | `future` | auto-provision 미사용 시 워커 전역 수동 DB 스코프 URL. |

### worker.container — container track(Docker)

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.worker.container.image` | `String` | `eclipse-temurin:21-jdk` | `future` | 워커 컨테이너 이미지. |
| `protean.worker.container.jar` | `String` | `""` | `future` | 명시 워커 jar 경로. 비면 `build/libs` 의 `-boot.jar` 자동 탐색. |
| `protean.worker.container.memory` | `String` | `256m` | `future` | 컨테이너 메모리 한도. |
| `protean.worker.container.pids-limit` | `long` | `512` | `future` | fork-bomb 방어용 PID 한도. |
| `protean.worker.container.network` | `String` | `""` | `future` | egress 격리용 네트워크(예: `internal`). 비면 기본. |
| `protean.worker.container.seccomp` | `String` | `""` | `future` | seccomp 프로파일 경로. 비면 docker 기본. |
| `protean.worker.container.auto-restart` | `boolean` | `false` | `라이브` | 컨테이너 워커 자동 재기동. |
| `protean.worker.container.db-host` | `String` | `host.docker.internal` | `future` | 컨테이너에서 호스트 DB 로 닿기 위한 호스트명 재작성 대상. |

### worker.db — 모듈당 격리 DB 스코프 자동 프로비저닝

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.worker.db.auto-provision` | `boolean` | `false` | `restart` | 모듈당 격리 DB 스코프 자동 프로비저닝 활성화. |
| `protean.worker.db.dialect` | `String` | (없음) | `restart` | `mysql` \| `postgresql`. `restart`(라이브 아님): 기존 스코프가 현재 dialect 의 DDL/URL 형태로 만들어져 라이브 교체 시 관리 불능이 되므로. |
| `protean.worker.db.admin-url` | `String` | (없음) | `future` | 프로비저닝용 관리 접속 URL. 런타임 교체(rotation) 가능 — 변경은 재시작 없이 다음 provision 에 반영되고, 채택 전 새 연결을 검증해 잘못된 값은 거부한다(기존 연결 유지). |
| `protean.worker.db.admin-username` | `String` | (없음) | `future` | 관리 사용자명 (런타임 교체 가능; `admin-url` 참조). |
| `protean.worker.db.admin-password` | `String` | (없음) | `future` | 관리 비밀번호 (재시작 없이 런타임 교체 가능; `admin-url` 참조). |
| `protean.worker.db.deprovision-on-undeploy` | `boolean` | `false` | `라이브` | undeploy 시 프로비저닝된 스코프 제거 여부. |

### worker.sidecar — sidecar 워커 런타임(opt-in)

| 키 | 타입 | 기본값 | 반영 | 설명 |
|----|------|--------|------|------|
| `protean.worker.sidecar.jar` | `String` | `""` | `future` | sidecar 워커 jar 경로. |
| `protean.worker.sidecar.image` | `String` | `""` | `future` | sidecar 워커 이미지. |
| `protean.worker.sidecar.shared-api` | `String` | `""` | `future` | 워커 컴파일용 공유 타입 jar. |

## 예시

```yaml
protean:
  isolation:
    mode: in-process
  admin:
    enabled: true
  gate:
    tests-enabled: true
    review-enabled: true
  module:
    request-timeout-ms: 5000
    executor:
      pool-size: 4
  module-store:
    backend: filesystem
    dir: /var/lib/protean/modules
  trace:
    enabled: true
    capacity: 500
```

## 관련 문서

- [01. 시작하기](01-getting-started.ko.md)
- [02. 모듈 작성](02-module-authoring.ko.md)
- [05. 격리 모드](05-isolation-modes.ko.md)
- [06. 승격 게이트](06-promotion-gates.ko.md)
- [08. MCP 연동](08-mcp-integration.ko.md)
- [README](../../README.ko.md)
