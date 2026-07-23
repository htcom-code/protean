[English](13-troubleshooting.md) | **한국어**

# 13. 트러블슈팅

자주 마주치는 문제와 원인·해결, 그리고 FAQ. 각 항목은 코드/테스트에서 확인한 실제 동작에 근거한다.

## 빌드/테스트 중 OutOfMemoryError

**증상**: `./gradlew clean bootJar test` 같은 결합 실행에서 `LeakDiagnosisTest` 등이 OOM 으로 죽는다.

**원인**: `test` 태스크는 누수 카나리에서 soft reference 를 강제로 비우려고 힙을 `maxHeapSize=512m` 로 의도적으로 제한한다. `bootJar`(fat jar 조립)는 그 자체로 메모리를 먹는다. 둘을 한 gradle 호출에 묶으면 제한된 힙에서 collateral OOM 이 난다.

**해결**: bootJar 와 test 를 분리 호출한다.

```bash
./gradlew clean test
./gradlew bootJar
```

## 모듈 언로드 후 Metaspace 누수

**증상**: 모듈을 반복 배포/해제하면 Metaspace 사용량이 계속 늘어 결국 `OutOfMemoryError: Metaspace`.

**원인**: 각 모듈은 자기 `ModuleClassLoader` 로 로드된다. 언로드해도 그 ClassLoader 를 가리키는 강한 참조가 하나라도 남으면 GC 되지 않고 Metaspace 가 회수되지 않는다. 대표 참조원:

- MVC 인프라의 per-Class 캐시. 핸들러를 **호출**하면 컨트롤러 Class 를 키로 캐시가 채워지는데, 매핑 해제만으로는 비워지지 않는다.
- 모듈이 raw `new Thread`/외부 실행기로 띄운 스레드가 죽은 ClassLoader 를 물고 있는 경우.
- 공유 스레드에 남긴 ThreadLocal, static 캐시 등록, JMX MBean 등 컨텍스트 밖 자원.

**해결 / 방지**:

- 플랫폼은 `DynamicEndpointRegistrar.unregister`/`swap` 에서 매핑 해제와 함께 per-Class 캐시(`RequestMappingHandlerAdapter`·`ExceptionHandlerExceptionResolver`·argument-resolver·`@ControllerAdvice` 캐시)를 컨트롤러 Class 키로 evict 한다. 이건 자동이다.
- 모듈 코드는 async/scheduled 작업에 관리형 실행기 `ProteanTaskExecutor` 를 주입받아 쓴다. 언로드 시 child 컨텍스트 close 로 `close()`(→ `shutdownNow`) 되어 스레드가 정리된다.
- 컨텍스트 밖 자원은 `ModuleUnloadCallback` 빈에서 스스로 청소한다(child close 직전 호출됨).

## 승격 게이트 실패

`install`/`update` 가 게이트에서 막히면 REST 응답은 RFC 9457 problem detail 형태의 `422`(`code: "GATE_FAILED"`, 실패한 게이트를 지목하는 `gate` 멤버 포함)다.

### 게이트 ① — 테스트 없음/실패

- **테스트 미동봉**: `descriptor.tests` 가 비면 `unit tests are required (tests must be bundled)` (`tests` 게이트 실패로 표면화). 모듈에 JUnit 테스트를 반드시 넣어야 한다.
- **테스트 실패**: 동봉 테스트가 그린이 아니면 `unit tests failed N/M ...` 로 실패 메시지가 포함된다. stdout/stderr 진단이 필요하면 `protean.mcp.capture-test-output=true` 로 켜서(전역 System.out 가로채기라 opt-in) 실패 출력을 응답에 포함시킬 수 있다.

### 게이트 ② — 금지 API / 코드 룰 위반

바이트코드 정적 스캔(ASM)에서 금지 API 사용 등 룰 위반이 잡히면 `review` 게이트가 `code check violations: [...]` 로 거부한다. 메시지의 위반 목록을 보고 해당 호출을 제거한다.

### 게이트 ③ — 검증 실패

`verification`(VerificationPlan)이 있으면 배포된 살아있는 엔드포인트를 검증한다(통합 프로브·부하/지연·힙 증가). 실패 시:

- `install`: undeploy + store 삭제 → 롤백(모듈이 남지 않음).
- `update`: 이전 버전으로 hot-swap 자동 롤백 후 예외.

통합 프로브(`expectedStatus`·`bodyContains`) 불일치, `maxAvgLatencyMs`·`maxHeapGrowthBytes` 초과가 흔한 원인이다. 부하 기준은 노이즈가 있으니 상한을 지나치게 빡빡하게 잡지 않는다.

### 게이트 완화

신뢰 수준에 맞춰 개별 게이트를 끌 수 있다(기본 전부 on). 끄면 무음 우회 방지를 위해 `WARN` 로그가 남는다.

```yaml
protean:
  gate:
    tests-enabled: true      # false 면 테스트 강제 생략
    review-enabled: true     # false 면 코드 체크 생략
```

## 동일 path 충돌(ambiguous mapping) 거부

**증상**: 이미 서빙 중인 경로와 같은 매핑을 가진 두 번째 모듈 배포가 예외로 실패한다.

**동작**: 같은 path 를 등록하려 하면 Spring `RequestMappingHandlerMapping` 이 ambiguous mapping 으로 등록을 거부한다. `PathConflictModuleTest` 로 특성화된 정책:

- 두 번째 모듈 배포는 거부되고 예외가 난다.
- **첫 모듈은 그대로 살아 있다** — 충돌이 기존 서빙을 깨뜨리지 않는다.
- 두 번째 모듈은 배포되지 않은 상태로 남는다(`isDeployed=false`).

**해결**: 모듈 간 경로가 겹치지 않게 설계한다(모듈별 path prefix 권장). 참고로 같은 모듈 `id` 를 다시 `register` 하는 것도 `module already deployed: <id>` 예외다 — 교체는 `update`(hot-swap `swap`)로 한다.

## 컨테이너/OS 격리 테스트가 안 돈다

**증상**: worker/container 격리, DB 스코프 프로비저닝 관련 테스트가 스킵되거나 실패한다.

**원인**: 이 테스트들은 Testcontainers(`testcontainers:mysql`/`postgresql`)로 실 DB 엔진을 띄우고 GRANT 격리를 충실히 검증하므로 **Docker 가 필요**하다. 또 container track 워커는 build/libs 의 `-boot.jar` 를 explode 해 쓰므로 **bootJar 산출물이 선행**돼야 한다.

**해결**: Docker 데몬을 띄운 뒤, 컨테이너 테스트 전에 bootJar 를 먼저 만든다. bootJar 와 test 결합 OOM 을 피하려면 여전히 분리하되 bootJar 를 앞세운다.

```bash
./gradlew bootJar      # 워커 실행형 산출물 먼저
./gradlew test         # (Docker 가 떠 있어야 컨테이너 테스트 통과)
```

## FAQ

**Q. `/platform/*` 엔드포인트가 404 다.**
관리 surface 가 꺼졌거나 등록되지 않은 것이다. `protean.admin.enabled` 가 `false` 가 아닌지, 워커 프로파일(`worker`)로 뜨지 않았는지 확인한다(두 컨트롤러 모두 `@Profile("!worker")`).

**Q. 재기동하면 모듈이 사라진다.**
디스크립터 저장소가 휘발성 경로일 수 있다. filesystem 백엔드의 기본 `dir` 은 `java.io.tmpdir` 하위다 — 영속 경로로 바꾼다. 또 reconcile 은 `ACTIVE` 만 복구하므로 `PENDING_APPROVAL` 모듈은 재기동 후 서빙되지 않는다(의도된 우회 차단).

**Q. `module-store.backend=jdbc` 로 기동이 실패한다(bad SQL, 또는 "No ModuleStoreDialect …").**
JDBC store 백엔드는 H2/MySQL/PostgreSQL 을 지원한다. 벤더를 인식 못 하면 엔진에서 깨질 H2 DDL 을 쓰지 않고 fail-fast 한다 — `protean.module-store.dialect`(`h2`|`mysql`|`postgresql`)를 설정하거나, 다른 벤더는 `ModuleStoreDialect` 빈을 등록한다([10. SPI 확장](10-spi-extension.ko.md) 참고). 에러가 기동 self-check 의 truncation 보고면, (커스텀) dialect 의 `descriptor_json` 컬럼이 대용량 문자 타입이 아니다 — bounded `VARCHAR` 말고 `CLOB`/`TEXT`/`LONGTEXT` 를 쓴다.

**Q. `/platform/traces` 가 항상 비어 있다.**
`protean.trace.enabled=false` 면 기록되지 않는다. 또 trace 조회 엔드포인트 자신(`/platform/traces` 요청)은 자기-소음 방지로 기록되지 않는다. 링버퍼(`capacity`, 기본 200)를 넘긴 오래된 trace 는 폐기된다.

**Q. 타임아웃을 걸었는데 폭주 모듈이 안 멈춘다.**
`request-timeout-ms` 의 interrupt 는 협조적이라 블로킹만 끊는다. CPU 스핀은 못 막는다. 하드 격리가 필요하면 worker/container 모드를 쓴다.

**Q. PUT 이 400 이다.**
카나리 업데이트는 경로 `id` 와 본문 `id` 가 일치해야 한다. 불일치면 400.

**Q. `list_modules`(또는 `/platform/modules`)엔 `ACTIVE` 인데 모듈 경로가 404 다.**
상태 목록은 스토어의 `desiredState`(선언)를 보여줄 뿐, 라우트가 실제 등록됐다는 뜻이 아니다. 재기동 복구 시 재컴파일 실패 등으로 런타임 등록이 0건이면 "ACTIVE 인데 서빙 안 됨"이 된다. 실제 등록 라우트는 MCP 리소스 `protean://modules/{id}/routes` 로 실측한다(`resources/read`) — **빈 배열이면 등록된 라우트가 없다는 확증**이다. 서버 로그의 `reconcile: recovered N/M modules`·컴파일 오류를 함께 확인한다. 자세한 조회는 [08. MCP 연동](08-mcp-integration.ko.md#리소스-템플릿-resourcestemplateslist) 참고.

**Q. 상태코드 의미?**
`422`=게이트 거부, `409`=격리 모드 미지원/상태 충돌(설치 안 된 모듈 롤백 등), `400`=잘못된 입력/매니페스트·id 불일치, `404`=대상 없음.

## 관련 문서

- [04. REST API 레퍼런스](04-rest-api.ko.md)
- [06. 승격 게이트](06-promotion-gates.ko.md)
- [09. 디버깅](09-debugging.ko.md)
- [11. 운영](11-operations.ko.md)
- [README](../../README.ko.md)
