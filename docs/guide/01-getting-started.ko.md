[English](01-getting-started.md) | **한국어**

# 01. 시작하기

Protean 을 기존 Spring Boot 애플리케이션에 의존성으로 넣고, 재기동 없이 Java 소스를 REST 로 배포해
살아있는 엔드포인트로 띄우기까지의 최소 경로를 따라간다.

## 1. 의존성 추가

Protean 좌표는 `org.htcom:protean:0.0.1-SNAPSHOT` 이다(`group = org.htcom`, artifactId = `protean`,
Spring Boot 3.5.x / Java 21). 현재는 로컬 Maven 저장소(`~/.m2`)로 발행되므로 소비자 빌드에
`mavenLocal()` 을 추가한다.

Gradle:

```groovy
repositories {
    mavenCentral()
    mavenLocal()   // Protean 이 publishToMavenLocal 로 올라간 곳
}

dependencies {
    implementation 'org.htcom:protean:0.0.1-SNAPSHOT'
}
```

Maven:

```xml
<dependency>
    <groupId>org.htcom</groupId>
    <artifactId>protean</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### 함께 딸려오는 Spring 의존성

Protean 은 `spring-boot-starter-web`, `spring-boot-starter-aop`, `spring-boot-starter-jdbc` 를
`implementation` 으로 가지므로 발행 POM 에서 runtime scope 로 소비자에게 전이된다. 별도로 web 스택을
선언하지 않아도 컨트롤 플레인(`/platform/*`)이 뜬다. 소비자 앱은 표준 Spring Boot 앱이면 된다
(`@SpringBootApplication` 을 가진 실행 클래스).

JDBC 드라이버(`mysql-connector-j`, `postgresql`)는 발행 POM 에서 `optional` 로 표시되어 전이되지
**않는다**. 워커 DB 프로비저닝 등 실 DB 접근이 필요하면 소비자가 자기 드라이버를 직접 추가한다
(데이터 접근은 [07. 데이터 접근](07-data-access.ko.md) 참조).

### JDK 필요

Protean 은 런타임에 모듈 소스를 컴파일하므로(`RuntimeCompiler`) 시스템 `JavaCompiler` 가 있어야 한다.
JRE 가 아니라 **JDK 로 실행**해야 한다 — JRE 로 띄우면 `시스템 JavaCompiler 없음` 예외로 실패한다.

## 2. 자동 구성

Protean 은 자동 구성 라이브러리다. 소비자 앱의 `@SpringBootApplication`(→ `@EnableAutoConfiguration`)이
`META-INF/spring/...AutoConfiguration.imports` 를 통해 `ProteanAutoConfiguration` 을 로드하면, 그 안의
`@ComponentScan(basePackages = "org.htcom.protean")` 이 격리 전략·모듈 플랫폼·게이트·컨트롤러 등
Protean 빈을 전부 등록한다. **소비자 패키지가 `org.htcom.protean` 을 포함할 필요가 없다** — 추가 설정
없이 컨트롤 플레인이 동작한다.

관리 REST surface 를 노출하고 싶지 않으면 `protean.admin.enabled=false` 로 끈다(기본 `true`). 전체 설정
키는 [03. 설정 레퍼런스](03-configuration.ko.md) 를 본다.

## 3. Hello 모듈 배포 (end-to-end)

배포는 `POST /platform/modules` 에 `ModuleDescriptor` JSON 본문을 보내는 것이다. 아래는 `GET /hello/greet`
가 `hello` 를 반환하는 최소 모듈이다. 승격 게이트 ①이 **테스트 동봉을 강제**하므로 컨트롤러와 함께
JUnit 테스트도 보낸다(무테스트 = 422 거부).

먼저 요청 본문을 `descriptor.json` 으로 저장한다. JSON 문자열 안에서는 소스 줄바꿈을 `\n` 으로
이스케이프한다.

```json
{
  "id": "hello",
  "version": "1.0.0",
  "trustTier": "TRUSTED",
  "desiredState": "ACTIVE",
  "controllerFqcn": "runtime.hello.HelloController",
  "componentFqcns": ["runtime.hello.HelloController"],
  "sources": {
    "runtime.hello.HelloController": "package runtime.hello;\nimport org.springframework.web.bind.annotation.GetMapping;\nimport org.springframework.web.bind.annotation.RestController;\n@RestController\npublic class HelloController {\n  @GetMapping(\"/hello/greet\")\n  public String greet() { return \"hello\"; }\n}\n"
  },
  "tests": {
    "runtime.hello.HelloControllerTest": "package runtime.hello;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\npublic class HelloControllerTest {\n  @Test void greets() { assertEquals(\"hello\", new HelloController().greet()); }\n}\n"
  },
  "needsSharedBeans": false,
  "verification": null
}
```

`sources`·`tests` 는 `FQCN → Java 소스` 맵이다. 필드 이름은 `ModuleDescriptor` record 의 컴포넌트 이름
그대로다(`controllerFqcn`, `componentFqcns`, `needsSharedBeans`, `verification` 등). 필드 하나하나의 의미는
[02. 모듈 작성](02-module-authoring.ko.md) 을 본다.

### 배포 (POST → 201)

```bash
curl -i -X POST http://localhost:8080/platform/modules \
  -H 'Content-Type: application/json' \
  -d @descriptor.json
```

게이트(①테스트 → ②리뷰)를 통과하면 `201 Created` 와 함께 `Location: /platform/modules/hello` 헤더,
그리고 `ModuleStatus` 본문이 온다:

```
HTTP/1.1 201 Created
Location: /platform/modules/hello

{"id":"hello","version":"1.0.0","trustTier":"TRUSTED","desiredState":"ACTIVE",
 "controllerFqcn":"runtime.hello.HelloController","mode":"in-process",
 "needsSharedBeans":false,"bridgedInterfaces":null}
```

테스트를 빼고 보내면 게이트 ①이 거부해 `422 Unprocessable Entity` + `{"error": ...}` 가 온다.

### 서빙 확인 (GET → 200)

배포 직후 엔드포인트가 살아난다:

```bash
curl -i http://localhost:8080/hello/greet
# HTTP/1.1 200 OK
# hello
```

모듈 상태도 조회할 수 있다:

```bash
curl http://localhost:8080/platform/modules/hello   # 단건 상태
curl http://localhost:8080/platform/modules          # ACTIVE 목록
```

### 해제 (DELETE → 204)

```bash
curl -i -X DELETE http://localhost:8080/platform/modules/hello
# HTTP/1.1 204 No Content
```

해제하면 child 컨텍스트가 close 되고 엔드포인트가 사라진다 — 이후 `GET /hello/greet` 는 `404`,
`GET /platform/modules/hello` 도 `404` 다. 이미 없는 모듈을 다시 지우면 `404`.

## 다음 단계

- 디스크립터 필드 전체와 소스 규약, 금지 API, `module.yaml` 매니페스트: [02. 모듈 작성](02-module-authoring.ko.md)
- 모든 `protean.*` 설정 키: [03. 설정 레퍼런스](03-configuration.ko.md)
- REST 엔드포인트 전체(업데이트/롤백/승인 등): [04. REST API 레퍼런스](04-rest-api.ko.md)
- 격리 모드(in-process/worker/container): [05. 격리 모드](05-isolation-modes.ko.md)

## 관련 문서

- [02. 모듈 작성](02-module-authoring.ko.md)
- [03. 설정 레퍼런스](03-configuration.ko.md)
- [04. REST API 레퍼런스](04-rest-api.ko.md)
- [README](../../README.ko.md)
