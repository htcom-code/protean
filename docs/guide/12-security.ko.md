[English](12-security.md) | **한국어**

# 12. 보안

Protean 의 신뢰(trust) 모델과 그 운영상 의미를 다룬다. 무엇을 방어하고 **무엇을 의도적으로 방어하지 않는지**를 정확히 이해한 뒤 배포해야 한다.

## 대전제: 모든 소스 = 신뢰된 개발자

Protean 은 **모든 모듈 소스가 신뢰된 개발자에게서 온다**는 전제로 설계됐다. 런타임 컴파일·실행에는 **보안 샌드박스가 없으며, 이는 의도적 non-goal 이다.** 모듈 코드는 JVM 전체 권한으로 실행된다.

이 부재는 `SandboxAbsenceTest` 가 증거로 남긴다 — 배포된 모듈이 `System.setProperty` 를 아무 제약 없이 실행해 실제 JVM 상태를 바꾼다:

```java
// runtime.sbx.PrivilegedController#probe
System.setProperty("protean.pwned", "yes");   // 막히지 않음
```

따라서 모듈 코드는 파일시스템·네트워크·시스템 프로퍼티·다른 빈 등 **호스트 JVM 이 가진 모든 권한**을 갖는다.

## Protean 이 방어하는 것 / 안 하는 것

**방어한다(승격 게이트로):**

- **무결성·진본성·인가** — 서명 게이트(Ed25519). 신뢰 키로 서명된 모듈만 통과.
- **사고성 위험 API** — 리뷰 게이트의 `ForbiddenApiRule`. `System.exit`, `Runtime.halt/exec/addShutdownHook`, `ProcessBuilder.start` 정적 차단.
- **미검증 코드 배포** — 테스트 게이트(테스트 동봉·통과 강제), 검증 게이트(살아있는 엔드포인트 검증).
- **사람 인가** — 승인 게이트(`PENDING_APPROVAL` → 명시 approve).

각 게이트의 운영법은 [06. 승격 게이트](06-promotion-gates.ko.md) 참고.

**방어하지 않는다(non-goal):**

- **악의적 코드 격리** — 보안 샌드박스 없음. 리뷰 게이트는 ASM 정적 스캔이라 **리플렉션 우회가 범위 밖**이며, 실수 방지용 레일이지 보안 경계가 아니다.
- 미신뢰 소스(임의의 외부 제출자)의 안전 실행 — 이 용도라면 반드시 별도 격리(프로세스 분리, 바이트코드 검증, OS 보안 정책 등)를 소비자가 추가해야 한다.

프로세스/컨테이너 격리 모드는 안정성·자원 경계용이지 적대적 코드에 대한 보안 경계로 설계된 것이 아니다 — [05. 격리 모드](05-isolation-modes.ko.md).

## MCP 어댑터 = RCE 표면

MCP 어댑터는 에이전트가 임의 Java 소스를 배포·실행하게 하는 입구라 사실상 원격 코드 실행(RCE) 표면이다. 그래서 **기본 off**이며 소비자가 명시적으로 켜야 기동한다(fail-safe):

| 키 | 기본값 | 표면 |
|----|--------|------|
| `protean.mcp.enabled` | `false` | MCP HTTP 컨트롤러 등 |
| `protean.mcp.stdio` | `false` | stdio JSON-RPC 전송 |
| `protean.mcp.debug.enabled` | `false` | `debug.*` 실행 게이트(Level 3, 배포보다 위험 — `redefine`/`evaluate`=임의 코드 실행). false=prod 태세: 툴은 목록엔 노출되나 호출은 즉시 거부. 실행은 이 게이트 + `ModuleActionAuthorizer`(DEBUG 액션) **2중 방어**. |

### 인증·인가

라이브러리는 인증을 구현하지 않는다. **인증은 소비자의 Spring Security 에 위임**하고, "누가 무엇을 할 수 있나"의 정책만 `ModuleActionAuthorizer` 빈으로 꽂는다. 모든 MCP 툴 호출은 `McpDispatcher` 의 공통 choke point 를 지나며 `authorize(caller, action, moduleId)` 로 검사된다.

- 기본 구현 `PermissiveModuleActionAuthorizer` 는 **전부 allow** 다(무인증 REST admin 과 동일 태세).
- 소비자가 `ModuleActionAuthorizer` 빈을 등록하면 `@ConditionalOnMissingBean` 으로 기본이 대체된다.
- `ModuleAction` 축: `READ`, `DEPLOY`, `UPDATE`, `DELETE`, `APPROVE`, `DEBUG`, `CUSTOM`.

```java
@Component
public class RoleBasedAuthorizer implements ModuleActionAuthorizer {
    @Override
    public Decision authorize(Principal caller, ModuleAction action, String moduleId) {
        if (caller == null) return Decision.deny("미인증");
        if (action == ModuleAction.DEPLOY && !isAdmin(caller)) {
            return Decision.deny("배포 권한 없음");
        }
        return Decision.allow();
    }
}
```

구현 SPI 상세는 [10. SPI 확장](10-spi-extension.ko.md), 어댑터 운영은 [08. MCP 연동](08-mcp-integration.ko.md).

## 관리 REST 인증

관리 surface(`/platform/*`, `protean.admin.enabled=true` 기본)는 **기본 무인증**이다. install/update/approve/uninstall 이 모두 여기로 노출되므로, 프로덕션에서는 소비자가 Spring Security 로 이 경로를 반드시 보호해야 한다. 노출이 불필요하면 `protean.admin.enabled=false` 로 컨트롤러 등록 자체를 끈다.

## 프로덕션 배포 권고

- **운영계는 in-process + 신뢰 소스**를 기본으로 두고, MCP 어댑터는 필요할 때만 켠다.
- 게이트를 **필수화**한다: `protean.gate.signature.required=true`(서명 강제), 필요 시 `protean.gate.approval.required=true`(사람 승인). 테스트·리뷰 게이트는 기본 on 을 유지한다.
- 관리 REST 와 MCP 표면을 Spring Security 로 인증하고, `ModuleActionAuthorizer` 로 동작별 인가를 강제한다.
- 서명 개인키는 서버 밖(CI/서명 발급자)에서만 보관하고, 서버에는 trust store 공개키만 둔다.

## 관련 문서

- [05. 격리 모드](05-isolation-modes.ko.md)
- [06. 승격 게이트](06-promotion-gates.ko.md)
- [08. MCP 연동](08-mcp-integration.ko.md)
- [10. SPI 확장](10-spi-extension.ko.md)
- [11. 운영](11-operations.ko.md)
- [README](../../README.ko.md)
