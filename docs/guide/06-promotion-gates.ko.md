[English](06-promotion-gates.md) | **한국어**

# 06. 승격 게이트

모듈을 설치/업데이트할 때 Protean 은 여러 게이트를 통과시킨 뒤에만 트래픽을 받는 `ACTIVE` 상태로 승격한다. 각 게이트는 `protean.gate.*` 로 켜고 끌 수 있으며, 안전 기본값(테스트·리뷰 on)을 신뢰 수준에 맞춰 완화할 수 있다.

## 파이프라인 순서

`ModulePlatform.install(descriptor)` 은 다음 순서로 게이트를 실행한다.

```
(서명) → ① 테스트 → ② 리뷰 → (승인 대기) → 배포 → ③ 검증 → ACTIVE
```

- 서명·①·②는 `PromotionPipeline.runGates()` 가 배포 전에 컴파일 단계에서 실행하는 **자동 게이트**다.
- 승인 게이트가 켜져 있으면 자동 게이트 통과분을 `PENDING_APPROVAL` 로만 저장하고 여기서 멈춘다(배포·검증 안 함).
- ③ 검증은 실제로 배포된 뒤 살아있는 엔드포인트에 실행한다. 실패하면 자동 롤백(설치는 언디플로이+저장소 삭제, 업데이트는 이전 버전으로 hot-swap 복귀)된다.

각 게이트 실패는 `PromotionPipeline.GateFailedException` 을 던진다. 자동 게이트에서 실패하면 모듈은 저장조차 되지 않는다.

## 게이트 토글 설정키

| 키 | 기본값 | 의미 |
|----|--------|------|
| `protean.gate.tests-enabled` | `true` | ① 테스트 게이트 |
| `protean.gate.review-enabled` | `true` | ② 리뷰(코드 체크) 게이트 |
| `protean.gate.signature.required` | `false` | 서명 게이트(opt-in) |
| `protean.gate.signature.keys.<keyId>` | (없음) | trust store: keyId → Base64(X.509 Ed25519 공개키) |
| `protean.gate.approval.required` | `false` | 승인 게이트(opt-in) |
| `protean.mcp.capture-test-output` | `false` | ① 실패 시 테스트 stdout/stderr 를 진단 메시지에 포함 |

게이트를 끄면 우회가 아니라 **명시적 생략**으로 처리되어 `WARN` 로그를 남긴다(무음 우회 방지).

## ① 테스트 게이트

모듈은 JUnit 테스트 동봉이 **강제**된다. `ModuleDescriptor.tests()` 가 비어 있으면 즉시 거부된다.

```
게이트 ① 실패: 단위 테스트가 없습니다(테스트 동봉 강제).
```

동작:

1. `sources` + `tests` 를 하나의 모듈 `ClassLoader` 로 함께 컴파일한다(테스트가 대상 클래스를 참조).
2. JUnit Platform Launcher 로 `tests()` 의 각 클래스를 실행한다.
3. 통과 조건은 `실패 0건 && 성공 1건 이상`. 성공 테스트가 하나도 없으면 그린이 아니다.

실패 시 예외 메시지에 `실패건수/전체건수` 와 각 실패의 **풀 스택트레이스**가 담긴다. `protean.mcp.capture-test-output=true` 면 실행 중 stdout/stderr 도 함께 포집해 메시지에 붙인다(전역 `System.out` 가로채기라 opt-in).

## ② 리뷰 게이트 (코드 체크)

컴파일된 바이트코드를 등록된 모든 `CodeRule` 빈으로 정적 검사한다. 내장 룰 `ForbiddenApiRule` 은 *사고성* 위험 API 호출을 거부한다.

| owner | 금지 메서드 |
|-------|-------------|
| `java.lang.System` | `exit` |
| `java.lang.Runtime` | `halt`, `exec`, `addShutdownHook` |
| `java.lang.ProcessBuilder` | `start` |

`addShutdownHook` 은 JVM 전역 등록이 모듈 `ClassLoader` 하드 참조 누수를 일으키기 때문에 금지된다. 백그라운드 작업이 필요하면 주입형 `ProteanTaskExecutor` 를 쓰면 언로드 시 자동 회수된다.

위반 예:

```
게이트 ② 실패: 코드 체크 위반 [runtime.x.Foo#bar 금지 호출: java.lang.System.exit]
```

이 룰은 **보안 샌드박스가 아니다.** ASM 바이트코드 스캔이라 리플렉션 우회는 범위 밖이며, 신뢰된 개발자의 실수를 막는 레일이다(보안 모델은 [12. 보안](12-security.ko.md) 참고). 추가 룰이 필요하면 `CodeRule` 빈을 등록하면 `RuleSystem` 이 자동 수집해 강제한다 — [10. SPI 확장](10-spi-extension.ko.md) 참고.

## ③ 검증 게이트 (VerificationPlan)

`ModuleDescriptor.verification()` 이 `null` 이면 no-op(스킵)이다. 계획이 있으면 **실제 배포된 서버 포트**에 HTTP 로 검증한다(포트 미확정 시 실패).

`VerificationPlan` 필드:

| 필드 | 타입 | 의미 |
|------|------|------|
| `integration` | `List<Probe>` | 통합 프로브(HTTP 체크). `null`=스킵 |
| `loadPath` | `String` | 부하(멀티request/속도/메모리) 대상 경로 |
| `concurrency` | `Integer` | 동시 스레드 수. `null`=부하 검증 전체 스킵 |
| `requestsPerThread` | `Integer` | 스레드당 요청 수(기본 10) |
| `maxAvgLatencyMs` | `Long` | 평균 지연 상한(ms). `null`=스킵 |
| `maxHeapGrowthBytes` | `Long` | 부하 전후 힙 증가 상한(byte). `null`=스킵 |

`Probe(method, path, expectedStatus, bodyContains)` — 상태코드 불일치나 `bodyContains` 미포함 시 실패.

작성 예:

```java
VerificationPlan plan = new VerificationPlan(
        List.of(
            new VerificationPlan.Probe("GET", "/orders/health", 200, "UP"),
            new VerificationPlan.Probe("GET", "/orders/1", 200, "\"id\":1")
        ),
        "/orders/health",   // loadPath
        8,                  // concurrency
        20,                 // requestsPerThread
        50L,                // maxAvgLatencyMs
        16L * 1024 * 1024   // maxHeapGrowthBytes (16MB, 관대)
);
```

부하 검증 규칙:

- **멀티request**: 2xx 아닌 응답이 하나라도 있으면 실패. 60초 내 미완료 시 시간 초과 실패.
- **속도**: 평균 지연이 `maxAvgLatencyMs` 초과 시 실패.
- **메모리**: 부하 전후 힙 증가가 `maxHeapGrowthBytes` 초과 시 실패. JVM GC 노이즈가 있으므로 **관대한 값**을 권장한다(정밀 측정 아님).

③ 실패 시 `ModulePlatform` 이 방금 배포한 모듈을 자동 롤백한다.

## 서명 게이트

`protean.gate.signature.required=true` 면 파이프라인 맨 앞에서 실행되어 **무결성·진본성·인가**를 보장한다. JDK 네이티브 Ed25519 를 쓰며 별도 의존성이 없다.

### 1. 키쌍 생성 · trust store 설정

```java
KeyPair kp = ModuleSigning.generateKeyPair();
String publicB64 = ModuleSigning.publicKeyToBase64(kp.getPublic());
// publicB64 를 서버 설정에 등록
```

```yaml
protean:
  gate:
    signature:
      required: true
      keys:
        ci-key: "<publicB64>"   # keyId → Base64(X.509 Ed25519 공개키)
```

### 2. 디스크립터 서명 · 부착

```java
String sig = ModuleSigning.sign(descriptor, kp.getPrivate());
ModuleDescriptor signed = descriptor.withSignature("ci-key", sig);
platform.install(signed);
```

서명 대상은 `signerKeyId`/`signature` 를 **제외한** 모듈 내용의 결정적 정규화(`canonicalBytes`, 맵 키 정렬)다. 따라서 서명 후 소스·검증계획 등 어떤 내용을 바꿔도 서명이 깨진다.

거부 케이스(모두 `GateFailedException`, 모듈 미저장):

- 서명 없음(`signerKeyId`/`signature` 누락)
- trust store 에 없는 미신뢰 `keyId`
- 서명 후 내용 변조(서명 불일치)

## 승인 게이트

`protean.gate.approval.required=true` 면 자동 게이트(서명·①·②)만 통과시켜 `PENDING_APPROVAL` 로 저장하고 **배포·검증하지 않는다.** 미승인 모듈은 서빙되지 않고, `reconcile` 이 `ACTIVE` 만 복구하므로 재기동 후에도 서빙되지 않는다(우회 차단).

REST 워크플로:

```
POST /platform/modules              # install → PENDING_APPROVAL
POST /platform/modules/{id}/approve?approver=alice   # ③검증+배포 → ACTIVE
POST /platform/modules/{id}/reject?approver=alice    # 제거
```

- `approve`: ③ 검증 + 배포에 성공해야 `ACTIVE`. 실패하면 다시 `PENDING_APPROVAL` 로 원복(서빙 안 됨).
- `reject`: 승인 대기 모듈을 제거.
- 두 동작 모두 `approver` 신원을 감사 로그로 남긴다. **신원 검증 자체는 소비자의 Spring Security/`ModuleActionAuthorizer` 몫**이다([12. 보안](12-security.ko.md)).

MCP 로도 동일하게 `ApproveModuleTool`/`RejectModuleTool`(action `APPROVE`)로 노출된다 — [08. MCP 연동](08-mcp-integration.ko.md).

## 관련 문서

- [02. 모듈 작성](02-module-authoring.ko.md)
- [03. 설정 레퍼런스](03-configuration.ko.md)
- [10. SPI 확장](10-spi-extension.ko.md)
- [12. 보안](12-security.ko.md)
- [README](../../README.md)
