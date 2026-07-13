[English](02-module-authoring.md) | **한국어**

# 02. 모듈 작성

모듈은 배포 단위다. `ModuleDescriptor`(record) 또는 `module.yaml` 매니페스트로 선언한다. 이 문서는
디스크립터 필드, 소스 규약, 승격 게이트가 강제하는 제약(테스트·금지 API), child 컨텍스트 안에서의 DI 를
다룬다.

## 1. `ModuleDescriptor` 필드

`org.htcom.protean.module.ModuleDescriptor` 는 다음 컴포넌트를 가진 record 다. REST `POST /platform/modules`
로 보낼 때의 JSON 필드 이름은 이 컴포넌트 이름과 같다.

| 필드 | 타입 | 의미 |
|------|------|------|
| `id` | `String` | 모듈 식별자(경로 `/platform/modules/{id}` 로 쓰인다). |
| `version` | `String` | 버전. 복구 시 동일 버전 재컴파일용 핀이자 히스토리/롤백 키. |
| `trustTier` | `TrustTier` | 신뢰 등급 — `TRUSTED` \| `UNTRUSTED`. |
| `desiredState` | `DesiredState` | 원하는 상태 — `ACTIVE` \| `INACTIVE` \| `PENDING_APPROVAL`. `ACTIVE` 만 기동 시 reconcile 대상. |
| `controllerFqcn` | `String` | REST 매핑을 등록할 컨트롤러 FQCN. |
| `componentFqcns` | `List<String>` | child 컨텍스트에 등록할 컴포넌트 FQCN 들(컨트롤러 포함). |
| `sources` | `Map<String,String>` | `FQCN → Java 소스`. 런타임 컴파일 입력. |
| `tests` | `Map<String,String>` | `FQCN → JUnit 테스트 소스`. 승격 게이트 ① 입력이며 **강제**된다. |
| `needsSharedBeans` | `boolean` | 공유 in-process 빈 의존 여부(격리 모드 양립성 판단). |
| `verification` | `VerificationPlan` | 승격 게이트 ③ 검증 계획. `null` = 검증 스킵. |
| `isolationMode` | `String` | 이 모듈의 격리 모드 — `"in-process"` \| `"worker"`. `null` = 전역 기본(`protean.isolation.mode`). |
| `bridgedInterfaces` | `List<String>` | 워커 모드에서 메인 공유 빈을 RPC 로 호출할 인터페이스 FQCN 목록. `null`/빈 = 없음. |
| `signerKeyId` | `String` | 서명 키 식별자(서명 검증 게이트용). `null` = 미서명. |
| `signature` | `String` | 정규화 콘텐츠에 대한 Ed25519 서명(Base64). `null` = 미서명. |
| `resources` | `Map<String,ModuleResource>` | `classpath 경로 → 비-Java 리소스`(mapper XML 등). `null` = 빈 맵으로 정규화. |

최소 필수는 `id`, `version`, `trustTier`, `desiredState`, `controllerFqcn`, `componentFqcns`, `sources`,
`tests`, `needsSharedBeans` 다. 나머지(`verification`, `isolationMode`, `bridgedInterfaces`, `signerKeyId`,
`signature`, `resources`)는 `null`/생략 가능하다.

### `VerificationPlan` (게이트 ③)

`verification` 은 배포된 살아있는 엔드포인트에 대한 검증 계획이다(각 항목 `null` 이면 그 검사 스킵):

| 필드 | 타입 | 의미 |
|------|------|------|
| `integration` | `List<Probe>` | HTTP 통합 프로브 목록. |
| `loadPath` | `String` | 부하 검증 대상 경로. |
| `concurrency` | `Integer` | 동시 스레드 수(`null`=부하 검증 스킵). |
| `requestsPerThread` | `Integer` | 스레드당 요청 수. |
| `maxAvgLatencyMs` | `Long` | 평균 지연 상한(ms, `null`=스킵). |
| `maxHeapGrowthBytes` | `Long` | 부하 전후 힙 증가 상한(byte, `null`=스킵). |

`Probe` 는 `(String method, String path, int expectedStatus, String bodyContains)` 다.

### `ModuleResource` (비-Java 리소스)

`resources` 맵의 값은 `ModuleResource(String content, boolean base64)` 다. 텍스트 설정(mapper XML,
`.properties`)은 평문(`base64=false`)으로, 바이너리(인증서·keystore)는 Base64(`base64=true`)로 싣는다.
경로는 traversal 방지를 위해 정규화·검증된다.

## 2. 컨트롤러/컴포넌트 소스 규약

소스는 평범한 Spring 스테레오타입 클래스다. 컨트롤러는 `@RestController` 로 매핑을 선언한다:

```java
package runtime.hello;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
    @GetMapping("/hello/greet")
    public String greet() { return "hello"; }
}
```

- `controllerFqcn` 은 위 클래스의 FQCN(`runtime.hello.HelloController`)과 정확히 일치해야 한다.
- `componentFqcns` 에는 컨트롤러를 포함해 child 컨텍스트에 등록할 클래스를 모두 넣는다.
- 컴파일러는 현재 JVM 클래스패스를 그대로 물려주므로 Spring Web 등 플랫폼에 있는 타입을 import 할 수
  있다.

## 3. child 컨텍스트 안에서의 DI

각 모듈은 전용 `ModuleClassLoader` 를 쓰는 **자식 `ApplicationContext`** 로 올라간다(부모 = 소비자 앱의
루트 컨텍스트). `componentFqcns` 에 나열한 클래스가 이 child 컨텍스트에 등록되므로, 모듈 내부의
`@Service`/`@Repository` 를 컨트롤러에 생성자 주입할 수 있다:

```java
package runtime.hello;

import org.springframework.stereotype.Service;

@Service
public class GreetingService {
    public String greet(String who) { return "hello " + who; }
}
```

```java
package runtime.hello;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
    private final GreetingService svc;
    public HelloController(GreetingService svc) { this.svc = svc; }

    @GetMapping("/hello/{who}")
    public String greet(@PathVariable String who) { return svc.greet(who); }
}
```

이때 디스크립터의 `componentFqcns` 에 두 클래스를 모두 넣는다:
`["runtime.hello.HelloController", "runtime.hello.GreetingService"]`.

부모가 루트 컨텍스트이므로 소비자 앱의 공유 빈도 주입받을 수 있다(그런 의존이 있으면
`needsSharedBeans=true` 로 표시한다). 언로드 시 child 컨텍스트를 `close` 하면 그 `ModuleClassLoader`
전체가 GC 대상이 된다.

### 관리형 백그라운드 실행기

각 child 컨텍스트에는 관리형 `ProteanTaskExecutor` 가 lazy 빈으로 등록되어 있다(주입 시에만 스레드
생성). async/scheduled 작업은 스레드를 직접 만들지 말고 이걸 주입받아 쓴다 — 모듈 언로드 시 컨텍스트
close 와 함께 자동 shutdown 되어 스레드/ClassLoader 누수를 막는다. 풀 크기는
`protean.module.executor.pool-size`(기본 2)로 조정한다([03. 설정 레퍼런스](03-configuration.ko.md)).

## 4. 테스트가 필수인 이유

승격 게이트 ①(`PromotionPipeline.enforceTestGate`)은 `tests` 가 `null` 이거나 비어 있으면 즉시
거부한다("게이트 ① 실패: 단위 테스트가 없습니다"). 동봉된 테스트는 모듈 전용 로더에서 런타임 컴파일·실행되며,
`failed == 0 && succeeded > 0` (전부 그린 + 최소 1개 통과)이어야 승격된다. REST 에서는 `422` 로 매핑된다.

테스트는 대상 클래스와 같은 모듈 로더로 함께 컴파일되므로 서로를 직접 참조할 수 있다:

```java
package runtime.hello;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class HelloControllerTest {
    @Test
    void greets() {
        assertEquals("hello world", new GreetingService().greet("world"));
    }
}
```

`tests` 맵의 키는 테스트 클래스 FQCN, 값은 소스다. 이 게이트는 `protean.gate.tests-enabled=false` 로
끌 수 있으나(신뢰 수준에 따라), 기본은 강제다.

## 5. 금지 API 제약 (게이트 ②)

승격 게이트 ②(리뷰)는 ASM 으로 컴파일 산출 바이트코드를 정적 스캔한다. `ForbiddenApiRule` 은 다음
호출을 거부한다(사고성 위험 방지용 레일 — 보안 샌드박스가 아니라 신뢰 개발자의 실수 방지):

| owner | 금지 메서드 |
|-------|------------|
| `java.lang.System` | `exit` |
| `java.lang.Runtime` | `halt`, `exec`, `addShutdownHook` |
| `java.lang.ProcessBuilder` | `start` |

`Runtime.addShutdownHook` 은 JVM 전역 등록이라 모듈 ClassLoader 를 하드 참조로 붙잡아 누수를 낸다 →
금지. 백그라운드 작업은 위의 주입형 `ProteanTaskExecutor` 를 쓰면 언로드 시 자동 회수된다. 위반 시 게이트
②가 `422` 로 거부한다. 이 게이트는 `protean.gate.review-enabled=false` 로 끌 수 있다.

## 6. `module.yaml` 매니페스트

JSON 대신 선언적 매니페스트로도 모듈을 정의할 수 있다. `ModuleManifestLoader` 가 다음 키를 읽는다:

| 키 | 필수 | 기본값/의미 |
|----|------|-------------|
| `id` | 필수 | 모듈 식별자. |
| `version` | 필수 | 버전. |
| `controller` | 필수 | 컨트롤러 FQCN. |
| `trustTier` | 선택 | `TRUSTED`(기본) \| `UNTRUSTED`. |
| `isolationMode` | 선택 | `null`(기본=전역 기본) \| `in-process` \| `worker`. |
| `needsSharedBeans` | 선택 | `false`(기본). |
| `components` | 선택 | 컴포넌트 FQCN 리스트. 비면 `[controller]`. |
| `bridgedInterfaces` | 선택 | RPC 브리지 인터페이스 FQCN 리스트. |
| `sources` | 선택 | 인라인 `FQCN → 소스` 맵. |
| `sourceDir` | 선택 | 매니페스트 기준 상대 디렉터리. 하위 `*.java` 스캔(FQCN = `package` 선언 + 파일명). |
| `tests` | 선택 | 인라인 `FQCN → 테스트 소스` 맵. |
| `testDir` | 선택 | 테스트 소스 디렉터리(스캔). |
| `resources` | 선택 | 인라인 `경로 → 평문 리소스` 맵. |
| `resourceDir` | 선택 | 리소스 디렉터리(하위 전 파일을 바이너리로 스캔). |

`sources`/`tests`/`resources` 는 인라인 맵과 디렉터리 스캔을 **병합**한다. `sourceDir`/`testDir`/
`resourceDir` 은 파일 매니페스트(디렉터리 기준이 있는 경우)에서만 쓸 수 있다 — 인라인만 담은 HTTP
본문에서는 디렉터리 키를 쓸 수 없다. 매니페스트로 만든 디스크립터는 `desiredState=ACTIVE`,
`verification=null` 로 고정된다.

인라인 예시(`hello.yaml`):

```yaml
id: hello
version: 1.0.0
controller: runtime.hello.HelloController
sources:
  runtime.hello.HelloController: |
    package runtime.hello;
    import org.springframework.web.bind.annotation.GetMapping;
    import org.springframework.web.bind.annotation.RestController;
    @RestController
    public class HelloController {
      @GetMapping("/hello/greet")
      public String greet() { return "hello"; }
    }
tests:
  runtime.hello.HelloControllerTest: |
    package runtime.hello;
    import org.junit.jupiter.api.Test;
    import static org.junit.jupiter.api.Assertions.assertEquals;
    public class HelloControllerTest {
      @Test void greets() { assertEquals("hello", new HelloController().greet()); }
    }
```

매니페스트로 배포하는 엔드포인트는 `POST /platform/modules/from-manifest` 이며, 본문은 YAML 텍스트다
(`Content-Type` 은 `text/plain`, `application/yaml`, `application/x-yaml` 중 하나):

```bash
curl -i -X POST http://localhost:8080/platform/modules/from-manifest \
  -H 'Content-Type: application/yaml' \
  --data-binary @hello.yaml
```

성공 시 `POST /platform/modules` 와 동일하게 `201 Created` + `Location` + `ModuleStatus` 를 반환한다.

## 7. 버전 규칙

`version` 은 히스토리/롤백 키이자 복구 시 재컴파일 핀이다. 카나리 업데이트(`PUT /platform/modules/{id}`)로
소스를 갈아끼울 때 새 `version` 을 부여하면 버전 히스토리에 쌓이고, `POST /platform/modules/{id}/rollback?version=...`
으로 특정 버전으로 되돌릴 수 있다(부분 파일만 보내는 `PATCH` 도 새 `version` 이 필수다). 자세한
라이프사이클 엔드포인트는 [04. REST API 레퍼런스](04-rest-api.ko.md) 를 본다.

## 관련 문서

- [01. 시작하기](01-getting-started.ko.md)
- [03. 설정 레퍼런스](03-configuration.ko.md)
- [04. REST API 레퍼런스](04-rest-api.ko.md)
- [06. 승격 게이트](06-promotion-gates.ko.md)
- [07. 데이터 접근](07-data-access.ko.md)
- [README](../../README.md)
