[English](04-rest-api.md) | **한국어**

# 04. REST API 레퍼런스

Protean 컨트롤 플레인 HTTP API 전체 레퍼런스다. `/platform` 아래 네 개 admin 컨트롤러(`ModuleAdminController`, `TraceAdminController`, `ConfigAdminController`, `SharedLibAdminController`)가 노출하는 모든 엔드포인트를 다룬다. 모듈 라이프사이클(배포·업데이트·해제·조회·승인·롤백), 런타임 trace 조회, 라이브 설정, shared-lib 스토어를 HTTP 로 수행한다.

## 노출 토글

네 컨트롤러 모두 다음 조건에서만 등록된다.

- `protean.admin.enabled=true` (기본값, 미설정 시 on). `false` 로 두면 `/platform` admin 컨트롤러(`/modules`·`/traces`·`/config`·`/shared-libs`)가 전혀 등록되지 않는다.
- 워커 프로파일이 아닐 때(`@Profile("!worker")`).

인증/인가는 이 라이브러리가 구현하지 않는다. 관리 surface 는 신뢰 개발자 전제이며, 노출 시 소비자가 Spring Security 등으로 접근을 통제해야 한다.

## 공통 사항

- 모든 요청/응답 바디는 JSON(`from-manifest` 만 예외 — YAML 텍스트).
- 에러 응답은 [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details(`application/problem+json`)다: `{ "type", "title", "status", "detail", "code", ... }`. `code` 는 안정적인 기계 키(아래 [상태코드 매핑](#상태코드-매핑) 참고), `type` 은 `urn:protean:error:<code>` URN 이며, 게이트 실패는 실패한 게이트를 지목하는 `gate` 확장 멤버를 덧붙인다. 요청 스코프 trace id 가 있으면 `traceId` 로 함께 실린다.

### 상태코드 매핑

모든 에러는 `ProteanException`(안정적인 `ErrorCode` 를 지니며 그 `httpStatus` 가 응답 코드를 정함)을 거치거나, 아래 두 폴백 핸들러 중 하나로 처리된다.

| 예외 (code) | 상태코드 | 상황 |
|---|---|---|
| `GateFailedException` (`GATE_FAILED`) | `422 Unprocessable Entity` | 모든 승격 게이트 거부(signature / tests / review / verification / shared-lib-signature) |
| `CompilationException` (`COMPILATION_FAILED`) | `422 Unprocessable Entity` | 모듈/테스트 소스 컴파일 실패 |
| `ProteanException` (`MODULE_NOT_FOUND` / `SHARED_LIB_NOT_FOUND`) | `404 Not Found` | 대상 모듈/공유 lib 없음 |
| `ProteanException` (`INVALID_ARGUMENT`) | `400 Bad Request` | 경로 id ↔ 본문 id 불일치, reload 비-리소스 파일 |
| `IllegalArgumentException` (`INVALID_ARGUMENT`) | `400 Bad Request` | 잘못된 매니페스트/입력(필수 필드 누락 등) |
| `IllegalStateException` (`STATE_CONFLICT`) | `409 Conflict` | 격리 모드 미지원/알 수 없는 모드/상태 충돌 |

---

## 모듈 관리 — 베이스 경로 `/platform/modules`

### GET `/platform/modules`

ACTIVE 모듈 상태 목록.

- 응답 `200`: `ModuleStatus[]`

```json
[
  {
    "id": "cp-mod",
    "version": "1.0.0",
    "trustTier": "TRUSTED",
    "desiredState": "ACTIVE",
    "controllerFqcn": "runtime.cp.CpController",
    "mode": "in-process",
    "needsSharedBeans": false,
    "bridgedInterfaces": null,
    "kind": "NORMAL",
    "exports": [],
    "uses": [],
    "boundGeneration": 3,
    "boundLibraryGenerations": [],
    "libraryGeneration": null
  }
]
```

`ModuleStatus` 필드: `id`, `version`, `trustTier`(`TRUSTED`|`UNTRUSTED`), `desiredState`(`ACTIVE`|`INACTIVE`|`PENDING_APPROVAL`), `controllerFqcn`, `mode`(실제 적용 격리 모드), `needsSharedBeans`, `bridgedInterfaces`, 그리고 아래의 shared-module 타입 공유 / generation 필드. 소스/테스트/검증계획 같은 무거운 필드는 응답에서 제외된다.

| 필드 | 의미 |
|---|---|
| `kind` | `NORMAL` \| `LIBRARY` — descriptor 를 그대로 반영(항상 존재). `LIBRARY` 는 라우트를 등록하지 않고 `exports` 를 parent-tier generation 으로 발행 |
| `exports` | 이 모듈이 공유 타입으로 발행하는 패키지(`LIBRARY` 일 때만 비어있지 않음) |
| `uses` | 이 모듈이 링크하는 `LIBRARY` 모듈들의 id |
| `boundGeneration` | 라이브 ClassLoader 가 바인딩된 shared-lib(네이티브 jar) generation id(로드 안 됐으면 `null`) |
| `boundLibraryGenerations` | `uses` 를 통해 실제 바인딩된 라이브러리 generation id들(sticky fallback 시 라이브러리 현재 generation 보다 뒤처질 수 있음; 없거나 미로드면 빈 배열) |
| `libraryGeneration` | `LIBRARY` 전용: 자신이 현재 발행 중인 generation id(그 외 `null`) |

`kind`/`exports`/`uses` 는 descriptor 반영이라 항상 존재한다. `boundGeneration`/`boundLibraryGenerations`/`libraryGeneration` 은 라이브 런타임 관측 필드로, `GET /platform/modules` 와 `GET /platform/modules/{id}` 에서는 채워지지만 deploy/update/rollback/approve 응답(로드 이전의 descriptor 를 보고)에서는 `null`/빈 값이다. 타입 공유 모델은 [02. 모듈 작성 §8](02-module-authoring.ko.md) 참고.

### GET `/platform/modules/{id}`

단일 모듈 상태.

- 응답 `200`: `ModuleStatus`
- `404`: 모듈 없음

### POST `/platform/modules`

모듈 배포. 본문은 `ModuleDescriptor` JSON. 게이트/검증을 통과하면 `201`.

- 요청 바디: `ModuleDescriptor`
- 응답 `201`: `Location: /platform/modules/{id}` 헤더 + `ModuleStatus` 본문
- `422`: 게이트 거부(예: 테스트 미동봉)
- `409`: 격리 모드가 모듈을 지원하지 않음(예: 공유 빈 의존)
- `400`: 잘못된 입력

`ModuleDescriptor` 요청 바디 예시:

```json
{
  "id": "cp-mod",
  "version": "1.0.0",
  "trustTier": "TRUSTED",
  "desiredState": "ACTIVE",
  "controllerFqcn": "runtime.cp.CpController",
  "componentFqcns": ["runtime.cp.CpController"],
  "sources": {
    "runtime.cp.CpController": "package runtime.cp; import org.springframework.web.bind.annotation.*; @RestController public class CpController { @GetMapping(\"/cp/ping\") public String ping() { return \"v1\"; } }"
  },
  "tests": {
    "runtime.cp.CpControllerTest": "package runtime.cp; import org.junit.jupiter.api.*; import static org.junit.jupiter.api.Assertions.*; public class CpControllerTest { @Test void ping() { assertEquals(\"v1\", new CpController().ping()); } }"
  },
  "needsSharedBeans": false,
  "verification": null,
  "isolationMode": null,
  "bridgedInterfaces": null
}
```

`ModuleDescriptor` 주요 필드:

| 필드 | 설명 |
|---|---|
| `id` | 모듈 식별자 |
| `version` | 버전(복구 시 재컴파일 핀, 롤백 히스토리 키) |
| `trustTier` | `TRUSTED` \| `UNTRUSTED` |
| `desiredState` | `ACTIVE` \| `INACTIVE` \| `PENDING_APPROVAL` |
| `controllerFqcn` | REST 매핑을 등록할 컨트롤러 FQCN |
| `componentFqcns` | child 컨텍스트에 등록할 컴포넌트 FQCN(컨트롤러 포함) |
| `sources` | `FQCN → Java 소스`(런타임 컴파일 입력) |
| `tests` | `FQCN → JUnit 테스트 소스`(게이트 ① 입력, 강제) |
| `needsSharedBeans` | 공유 in-process 빈 의존 여부(격리 모드 양립성 판단) |
| `verification` | 게이트 ③ 검증 계획(`null`=검증 스킵) |
| `isolationMode` | `"in-process"` \| `"worker"` \| `"container"`(`null`=전역 기본) |
| `bridgedInterfaces` | 워커 모드에서 RPC 로 호출할 인터페이스 FQCN(`null`/빈=없음) |
| `signerKeyId`, `signature` | 서명 게이트용(`null`=미서명) |
| `resources` | `classpath 경로 → ModuleResource`(mapper XML 등, `null`=없음) |
| `kind` | 배포 종류 — `NORMAL` \| `LIBRARY`(`null`=`NORMAL`). `LIBRARY` 는 라우트를 등록하지 않고 `exports` 를 parent-tier generation 으로 발행. [02. 모듈 작성 §8](02-module-authoring.ko.md) 참고 |
| `exports` | `kind == LIBRARY` 일 때 공유 타입으로 노출할 패키지(그 외 무시). 작성자 지정 → 서명 대상. `null`=없음 |
| `uses` | 이 모듈이 컴파일/링크하는 `LIBRARY` 모듈들의 id. 작성자 지정 → 서명 대상. `null`=없음 |
| `usedSharedLibs` | 이 컴파일이 실제 연 네이티브 shared-lib jar 의 `{name, sha256}`. **서버 관측**(작성자 지정 아님, 서명 대상 제외) — 정밀 shared-lib 무효화에 사용. `null`=없음 |

`verification`(`VerificationPlan`)을 넣으면 게이트 ③ 이 살아있는 엔드포인트를 검증한다. 각 항목 `null`이면 건너뛴다.

```json
{
  "integration": [
    { "method": "GET", "path": "/cp/ping", "expectedStatus": 200, "bodyContains": "v1" }
  ],
  "loadPath": "/cp/ping",
  "concurrency": 4,
  "requestsPerThread": 50,
  "maxAvgLatencyMs": 100,
  "maxHeapGrowthBytes": 10485760
}
```

### POST `/platform/modules/from-manifest`

`module.yaml` 선언 매니페스트(인라인 소스)로 배포. 본문은 YAML 텍스트.

- `Content-Type`: `text/plain` | `application/yaml` | `application/x-yaml`
- 요청 바디: YAML 텍스트
- 응답 `201`: `Location` 헤더 + `ModuleStatus`

```yaml
id: cp-mod
version: 1.0.0
controller: runtime.cp.CpController
trustTier: TRUSTED
needsSharedBeans: false
sources:
  runtime.cp.CpController: |
    package runtime.cp;
    import org.springframework.web.bind.annotation.*;
    @RestController public class CpController {
      @GetMapping("/cp/ping") public String ping() { return "v1"; }
    }
tests:
  runtime.cp.CpControllerTest: |
    package runtime.cp;
    import org.junit.jupiter.api.*;
    import static org.junit.jupiter.api.Assertions.*;
    public class CpControllerTest {
      @Test void ping() { assertEquals("v1", new CpController().ping()); }
    }
```

매니페스트 키: `id`·`version`·`controller` 는 필수. `trustTier`(기본 `TRUSTED`), `isolationMode`, `needsSharedBeans`(기본 false), `components`, `bridgedInterfaces`, `sources`/`tests`/`resources`(인라인 맵). HTTP 인라인 배포에는 `sourceDir`/`testDir`/`resourceDir`(파일 스캔) 키는 쓸 수 없다. `desiredState` 는 `ACTIVE` 로 고정된다.

### PUT `/platform/modules/{id}`

모듈 카나리 업데이트(무중단 hot-swap). 경로 `id` 와 본문 `id` 는 일치해야 한다.

- 요청 바디: `ModuleDescriptor`(full-replace, canonical)
- 응답 `200`: `ModuleStatus`
- `400`: 경로 id ↔ 본문 id 불일치
- `404`: 업데이트 대상 없음
- `422`: 게이트 거부. 검증(③) 실패 시 자동으로 이전 버전으로 롤백된다.

### PATCH `/platform/modules/{id}`

delta/patch 업데이트 — 바뀐 파일만 보내 현재 descriptor 에 overlay 후 카나리 update. 입력 조립 편의용이며, 내부적으로 full-replace `update` 파이프라인을 탄다.

- 요청 바디: `ModulePatchRequest`
- 응답 `200`: `ModuleStatus`
- `404`: 패치 대상 없음

```json
{
  "version": "2.0.0",
  "files": [
    { "kind": "source", "filename": "runtime.cp.CpController", "content": "...java...", "base64": false }
  ],
  "removeFiles": ["runtime.cp.OldClass"]
}
```

`ModulePatchRequest` 필드: `version`(새 버전, 비면 현재 유지), `files`(`FileSpec[]`, 추가/치환), `removeFiles`(제거할 key — source/test FQCN 또는 resource 경로).

`FileSpec`: `kind`(`source`|`test`|`resource`), `filename`(source/test 는 FQCN 도출, resource 는 classpath 경로), `content`, `base64`(content 가 Base64 면 true).

### POST `/platform/modules/{id}/reload-resources`

리소스 live-reload — 컴파일·컨텍스트 재빌드 없이 리소스만 제자리 교체. 요청마다 읽는 리소스용(ORM init-parse 리소스엔 무효). 리소스 파일만 허용한다. 격리 모드가 live-reload 를 지원하지 않으면(워커/컨테이너) 전체 `update` 로 폴백한다.

- 요청 바디: `ModulePatchRequest`(단, `files[].kind` 는 비었거나 `resource` 여야 함)
- 응답 `200`: `ModuleStatus`
- `400`: 리소스 아닌 파일 포함(`kind` 가 resource 가 아님)
- `404`: 대상 없음

### GET `/platform/modules/{id}/versions`

모듈 버전 히스토리(최신순).

- 응답 `200`: `ModuleVersion[]`
- `404`: 모듈 없음

```json
[
  { "seq": 2, "version": "2.0.0", "savedAtMillis": 1720000000000, "desiredState": "ACTIVE" },
  { "seq": 1, "version": "1.0.0", "savedAtMillis": 1719990000000, "desiredState": "ACTIVE" }
]
```

### GET `/platform/modules/{id}/routes`

모듈이 런타임에 실제로 등록한 라우트 — `ACTIVE` 상태의 실체다([13. 트러블슈팅](13-troubleshooting.ko.md)). 스토어가 `ACTIVE` 여도 배열이 비면 살아 있는 라우트가 없다는 확증이다.

- 응답 `200`: `RouteInfo[]` — 각 항목은 `{ "methods": [...], "patterns": [...] }`. in-process 라우트는 HTTP 메서드·경로 패턴을 모두 담고, worker/container 라우트는 리버스 프록시로 서빙되어 전달 메서드를 추적하지 않으므로(GET-only PoC) `methods` 가 빈 집합이다.
- `404`: 모듈 없음

```json
[
  { "methods": ["GET", "POST"], "patterns": ["/cp/ping"] }
]
```

### POST `/platform/modules/{id}/rollback`

히스토리의 특정 버전으로 명시 롤백. 그 버전 디스크립터를 다시 배포하므로 카나리 hot-swap + 게이트①②③ + 실패 시 자동 롤백의 안전 경로를 탄다. 롤백 결과도 새 히스토리 항목으로 남는다.

- 쿼리 파라미터: `version`(필수) — 되돌릴 버전 문자열
- 응답 `200`: `ModuleStatus`
- `409`: 설치되지 않은 모듈 / 대상 버전 없음

```
POST /platform/modules/cp-mod/rollback?version=1.0.0
```

### POST `/platform/modules/{id}/approve`

승인 대기(`PENDING_APPROVAL`) 모듈을 사람 인가로 승격(검증③ + 배포 → ACTIVE). 승인 게이트(`protean.gate.approval.required=true`)를 켰을 때 사용한다.

- 쿼리 파라미터: `approver`(필수) — 승인자 신원(감사 로그)
- 응답 `200`: `ModuleStatus`
- `409`: 승인 대상 없음 / 승인 대기 상태 아님. 검증/배포 실패 시 다시 PENDING 으로 원복된다.

```
POST /platform/modules/cp-mod/approve?approver=alice
```

### POST `/platform/modules/{id}/reject`

승인 대기 모듈을 거부해 제거.

- 쿼리 파라미터: `approver`(필수)
- 응답 `204`
- `409`: 거부 대상 없음 / 승인 대기 상태 아님

### DELETE `/platform/modules/{id}`

모듈 해제.

- 응답 `204`
- `404`: 제거 대상 없음(이미 없는 모듈 해제는 404 — 멱등적 관측)

---

## 런타임 trace — 베이스 경로 `/platform/traces`

### GET `/platform/traces`

최근 요청 실행 trace 를 최신순으로 조회한다. `protean.trace.enabled=false` 면 기록되지 않아 빈 목록이 된다. trace 조회 엔드포인트 자신(`/platform/traces`)은 자기-소음 방지를 위해 기록되지 않는다.

- 쿼리 파라미터(모두 선택, AND 로 결합되며 결과는 최신순 유지):
  - `limit`(기본 `50`) — 최대 반환 건수. 최소 1로 보정된다.
  - `moduleId` — 지정 시 그 모듈에 귀속된 trace 만.
  - `errorsOnly`(기본 `false`) — `true` 면 예외/오류 응답만.
  - `status` — 지정 시 그 상태 코드만.
  - `minLatencyMs` — 지정 시 지연이 이 값 이상인 trace 만(느린 요청 진단).
  - `since` — epoch-millis 하한(이 시각 이후 완료된 trace 만).
  - `beforeSeq` — 과거로 페이징하기 위한 커서(이 `seq` 미만만).
- 응답 `200`: `RequestTrace[]`

```json
[
  {
    "seq": 12,
    "epochMillis": 1720000000123,
    "method": "GET",
    "uri": "/cp/ping",
    "pattern": "/cp/ping",
    "moduleId": "cp-mod",
    "status": 200,
    "latencyMs": 3,
    "error": null,
    "traceId": "b1c2d3e4f5a60718"
  }
]
```

`RequestTrace` 필드: `seq`(단조 증가), `epochMillis`(완료 시각), `method`, `uri`, `pattern`(매칭 핸들러 패턴, 미매칭 시 `null`), `moduleId`(귀속 모듈, 정적/플랫폼 경로면 `null`), `status`, `latencyMs`, `error`(핸들러 예외 FQCN, 없으면 `null`), `traceId`(같은 요청의 로그 라인·RFC 9457 오류 응답과 공유하는 상관 ID `X-Request-Id`/MDC `traceId`, 없으면 `null`).

### GET `/platform/traces/metrics`

모듈별 집계 메트릭(요청 수·오류율·지연 백분위)을 조회한다. **opt-in** — `protean.trace.metrics.enabled=true` 일 때만 집계되며, 꺼져 있으면 빈 목록을 반환한다.

- 쿼리 파라미터:
  - `moduleId`(선택) — 지정 시 그 모듈만(추적 안 된 모듈이면 빈 목록), 생략 시 추적 중인 전 모듈.
- 응답 `200`: `ModuleMetricsSnapshot[]`

```json
[
  {
    "moduleId": "cp-mod",
    "count": 1200,
    "errorCount": 3,
    "errorRate": 0.0025,
    "p50LatencyMs": 2,
    "p95LatencyMs": 11,
    "p99LatencyMs": 25,
    "maxLatencyMs": 140,
    "lastSeenEpochMillis": 1720000000123
  }
]
```

`ModuleMetricsSnapshot` 필드: `moduleId`(귀속 모듈, 플랫폼/정적 경로면 `"(platform)"`), `count`(총 요청), `errorCount`(예외 또는 status ≥ 500), `errorRate`(`errorCount/count`, count 0 이면 0.0), `p50/p95/p99LatencyMs`(근사 백분위 지연), `maxLatencyMs`(최악 지연), `lastSeenEpochMillis`(최근 요청 시각). 백분위 정밀도·추적 모듈 수는 `protean.trace.metrics.latency-buckets`·`max-modules` 로 조정한다([03. 설정 레퍼런스](03-configuration.ko.md#trace--런타임-trace-기록)).

### GET `/platform/traces/stream`

라이브 푸시 스트림(SSE) — 콘솔이 5초 폴링 대신 쓴다. 한 연결이 네 종류의 named 이벤트를 멀티플렉싱하며, 새로 연 연결은 네 종류의 초기 스냅샷을 먼저 받은 뒤 증분 푸시(대략 1초마다)를 받는다.

- Produces: `text/event-stream`
- named 이벤트:
  - `trace` — 새 `RequestTrace` 델타
  - `metrics` — `ModuleMetricsSnapshot[]` 갱신(모듈별 누적 집계, `protean.trace.metrics.enabled=true` 일 때만 채워짐)
  - `modules` — 현재 `ModuleStatus[]`
  - `summary` — `TraceSummary`: 콘솔 헤더용 **윈도** 집계(아래 참고)
- `/platform/traces` 와 마찬가지로 스트림 연결 자체는 trace 기록에서 제외된다(자기-관측 방지).

```
event: trace
data: {"seq":13,"method":"GET","uri":"/cp/ping","status":200,"latencyMs":2,"moduleId":"cp-mod"}
```

`summary` 이벤트는 `TraceSummary` 를 싣는다 — trace 링버퍼에서 매 tick 계산하는 롤링-윈도 집계(`protean.trace.metrics.enabled` 와 무관)에 이전 동일-길이 윈도 대비 trend 를 더한 것:

```
event: summary
data: {"windowMs":60000,"count":35,"errorCount":0,"errorRate":0.0,
       "p50LatencyMs":0,"p95LatencyMs":259,"p99LatencyMs":1471,"maxLatencyMs":1471,
       "requestsDeltaPct":null,"errorRateDeltaPp":null,"p95DeltaMs":null,
       "activeModules":3,"modulesByMode":{"in-process":2,"worker":1}}
```

`TraceSummary` 필드: `windowMs`(롤링 윈도 길이); 현재 윈도 `(now-windowMs, now]` 의 `count`/`errorCount`/`errorRate` 및 `p50/p95/p99/maxLatencyMs`; `requestsDeltaPct`(이전 동일 윈도 대비 요청 수 비율 변화, 예 `0.12`=+12%), `errorRateDeltaPp`(에러율 변화, 퍼센트포인트), `p95DeltaMs`(p95 변화, ms) — 이 trend 3필드는 **이전 윈도에 표본이 없으면 `null`**(baseline 없으면 가짜 delta 안 만듦); `activeModules` 및 `modulesByMode`(현재 `ACTIVE` 모듈을 isolation mode 별로 센 point-in-time 카운트). 윈도 길이는 `protean.trace.summary-window-ms`(기본 60s)로 정하고, 정확도는 `protean.trace.capacity` 에 종속된다.

---

## 런타임 설정 — 베이스 경로 `/platform/config`

실행 중 `ProteanConfigService` 를 통해 `protean.*` 설정을 읽고 라이브로 갱신한다. 각 키는 변경이 즉시 반영되는지 재기동이 필요한지 결정하는 **tier** 를 가진다: `LIVE`(즉시 적용), `FUTURE`(저장 후 다음 관련 작업에 적용), `RESTART_CONDITIONAL` / `RESTART_ARTIFACT`(재기동 필요). [03. 설정 레퍼런스](03-configuration.ko.md) 참고.

### GET `/platform/config`

모든 키와 현재 값·tier.

- 응답 `200`: `ConfigEntry[]` — 각 항목은 `{ "key", "value", "tier", "liveApplicable" }`.

```json
[
  { "key": "protean.trace.enabled", "value": true, "tier": "LIVE", "liveApplicable": true },
  { "key": "protean.isolation.mode", "value": "in-process", "tier": "RESTART_CONDITIONAL", "liveApplicable": false }
]
```

### GET `/platform/config/{key}`

단일 키.

- 응답 `200`: `ConfigEntry`
- `400`: 알 수 없는 설정 키

### PATCH `/platform/config`

`{ "key": value }` 패치를 원자적으로 적용한다. 키 하나라도 알 수 없거나 유효하지 않으면 배치 전체가 중단되고 **아무것도** 적용되지 않는다.

- 요청 본문: 키 → 새 값 매핑 JSON 객체
- 응답 `200`: 커밋 시 `ApplyResult`
- 응답 `400`: 중단 시(알 수 없는/유효하지 않은 키) `ApplyResult`, 미적용
- `ApplyResult`: `{ "applied": <bool>, "outcomes": [ { "key", "tier", "outcome", "reason" } ] }`. `outcome` 은 `APPLIED_LIVE`, `APPLIED_FUTURE`, `REQUIRES_RESTART`, `REJECTED_UNKNOWN`, `REJECTED_INVALID`, `NOT_APPLIED_BATCH_ABORTED` 중 하나.

```json
{
  "applied": true,
  "outcomes": [
    { "key": "protean.trace.enabled", "tier": "LIVE", "outcome": "APPLIED_LIVE", "reason": null }
  ]
}
```

---

## Shared 라이브러리 — 베이스 경로 `/platform/shared-libs`

shared-lib 스토어의 라이브 관리 — 호스트를 재빌드하지 않고 라이브러리(JDBC 드라이버 등)를 모듈 부모 클래스패스에 더하는 드롭인 jar 표면이다([07. 데이터 접근](07-data-access.ko.md), [11. 운영](11-operations.ko.md) 참고). 한 번의 배포 = 하나의 **generation**(세대), 주 전송은 멀티파트다(네이티브 jar 는 1–5 MB).

### GET `/platform/shared-libs`

현재 generation id 와 라이브 저장 라이브러리들.

- 응답 `200`: `SharedLibsView` — `{ "generation": <long>, "libs": [ StoredLib... ] }`. `StoredLib` 는 `{ "name", "version", "sha256", "size", "signerKeyId", "signature" }`.

```json
{
  "generation": 3,
  "libs": [
    { "name": "mysql-connector-j", "version": "8.4.0", "sha256": "…", "size": 2512345, "signerKeyId": null, "signature": null }
  ]
}
```

### GET `/platform/shared-libs/{name}`

단일 저장 라이브러리 메타데이터.

- 응답 `200`: `StoredLib`
- `404`: 저장돼 있지 않음

### POST `/platform/shared-libs`

jar 묶음을 하나의 새 generation 으로 업로드한다. 콘텐츠 타입 `multipart/form-data`. `name` / `version` 폼 필드(및 선택 `signerKeyId` / `signature`)는 `file` 파트와 **평행 배열**이라 개수가 일치해야 한다. 모든 jar 가 이미 스토어와 같으면 멱등이다(새 generation 없음).

- 폼 필드: `name`(반복), `version`(반복), `signerKeyId`(선택, 반복), `signature`(선택, 반복)
- 파일 파트: `file`(반복) — jar 당 하나
- 응답 `201`: `SharedLibsView`(결과 스토어 뷰)
- `400`: name/version/file 개수 불일치, 또는 선택 필드가 `file` 과 평행하지 않음

새 generation 을 발행하면 `SharedLibInvalidator` 가 이전·현재 generation 의 jar 를 diff 하고 — jar→module 역인덱스를 통해 — 바뀌거나 제거된 jar 를 참조하는 ACTIVE 모듈**만** 새 generation 으로 즉시 rebind 한다; 영향 없는 모듈은 그대로 둔다. 이는 `protean.module.eager-shared-lib-invalidation`(기본 `true`; [03. 설정](03-configuration.ko.md) 참고)로 제어된다. 어떤 모듈의 rebind 가 실패하면 그 모듈은 이전 generation 에 머무르며(크게 로깅, 조용히 비활성화하지 않음) — rebind 는 라이브 스왑 이전에 시도되므로 어느 쪽이든 무중단이 유지된다. [07. 데이터 접근](07-data-access.ko.md) 참고.

```
curl -X POST http://localhost:8080/platform/shared-libs \
  -F name=mysql-connector-j -F version=8.4.0 -F file=@mysql-connector-j-8.4.0.jar
```

### DELETE `/platform/shared-libs/{name}`

스토어에서 라이브러리를 제거한다. **미래 generation 에만** 영향을 주며, 이미 사용 중인 generation 은 그대로 유지한다.

- 응답 `204`

## 관련 문서

- [02. 모듈 작성](02-module-authoring.ko.md)
- [03. 설정 레퍼런스](03-configuration.ko.md)
- [06. 승격 게이트](06-promotion-gates.ko.md)
- [08. MCP 연동](08-mcp-integration.ko.md)
- [11. 운영](11-operations.ko.md)
- [13. 트러블슈팅](13-troubleshooting.ko.md)
- [README](../../README.ko.md)
