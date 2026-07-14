**English** | [한국어](12-security.ko.md)

# 12. Security

This covers Protean's trust model and its operational implications. You must understand exactly what it defends and **what it deliberately does not defend** before deploying.

## Core premise: all source = trusted developer

Protean is designed on the premise that **all module source comes from a trusted developer**. Runtime compilation/execution has **no security sandbox, and this is a deliberate non-goal.** Module code runs with full JVM privileges.

`SandboxAbsenceTest` records this absence as evidence — a deployed module runs `System.setProperty` with no restriction and actually changes JVM state:

```java
// runtime.sbx.PrivilegedController#probe
System.setProperty("protean.pwned", "yes");   // not blocked
```

So module code has **every privilege the host JVM holds** — filesystem, network, system properties, other beans, and so on.

## What Protean defends / does not defend

**Defends (via promotion gates):**

- **Integrity, authenticity, authorization** — the signature gate (Ed25519). Only modules signed with a trusted key pass.
- **Accidentally dangerous APIs** — the review gate's `ForbiddenApiRule`. Statically blocks `System.exit`, `Runtime.halt/exec/addShutdownHook`, `ProcessBuilder.start`.
- **Deploying unverified code** — the tests gate (bundled tests enforced and required to pass) and the verification gate (live-endpoint verification).
- **Human authorization** — the approval gate (`PENDING_APPROVAL` → explicit approve).

For how to operate each gate see [06. Promotion Gates](06-promotion-gates.md).

**Does not defend (non-goal):**

- **Isolating malicious code** — there is no security sandbox. The review gate is an ASM static scan, so **reflection bypass is out of scope**; it is a guardrail against mistakes, not a security boundary.
- Safe execution of untrusted source (arbitrary external submitters) — for that use case the consumer must add separate isolation (process separation, bytecode verification, OS security policy, etc.).

The process/container isolation modes are for stability and resource boundaries; they are not designed as a security boundary against adversarial code — see [05. Isolation Modes](05-isolation-modes.md).

## MCP adapter = RCE surface

The MCP adapter is the entry point that lets an agent deploy and execute arbitrary Java source, so it is effectively a remote code execution (RCE) surface. Hence it is **off by default** and starts only when the consumer explicitly enables it (fail-safe):

| Key | Default | Surface |
|----|--------|--------|
| `protean.mcp.enabled` | `false` | MCP HTTP controller, etc. |
| `protean.mcp.stdio` | `false` | stdio JSON-RPC transport |
| `protean.mcp.debug.enabled` | `false` | `debug.*` execution gate (Level 3, more dangerous than deploy — `redefine`/`evaluate` = arbitrary code execution). false = prod posture: tools are exposed in the listing but calls are immediately rejected. Execution is **double-defended** by this gate + `ModuleActionAuthorizer` (DEBUG action). |

### Authentication / authorization

The library does not implement authentication. **Authentication is delegated to the consumer's Spring Security**, and only the "who can do what" policy is plugged in via the `ModuleActionAuthorizer` bean. Every MCP tool call passes through `McpDispatcher`'s common choke point and is checked with `authorize(caller, action, moduleId)`.

- The default implementation `PermissiveModuleActionAuthorizer` **allows everything** (the same posture as the unauthenticated REST admin).
- If the consumer registers a `ModuleActionAuthorizer` bean, `@ConditionalOnMissingBean` replaces the default.
- `ModuleAction` axis: `READ`, `DEPLOY`, `UPDATE`, `DELETE`, `APPROVE`, `DEBUG`, `CUSTOM`.

```java
@Component
public class RoleBasedAuthorizer implements ModuleActionAuthorizer {
    @Override
    public Decision authorize(Principal caller, ModuleAction action, String moduleId) {
        if (caller == null) return Decision.deny("unauthenticated");
        if (action == ModuleAction.DEPLOY && !isAdmin(caller)) {
            return Decision.deny("no deploy permission");
        }
        return Decision.allow();
    }
}
```

For the SPI implementation details see [10. SPI Extension](10-spi-extension.md); for adapter operation see [08. MCP Integration](08-mcp-integration.md).

## Admin REST authentication

The admin surface (`/platform/*`, `protean.admin.enabled=true` by default) is **unauthenticated by default**. Since install/update/approve/uninstall are all exposed here, in production the consumer must protect these paths with Spring Security. If the exposure is unnecessary, set `protean.admin.enabled=false` to turn off controller registration entirely.

### Worker admin-plane authentication (`/__admin/*`, opt-in)

Each `worker`/`container` isolation-mode worker exposes its own control plane at `/__admin/*` (deploy/redeploy/undeploy/shared-libs; only `/__admin/health` stays open, for readiness polling). It is **off by default** — same trusted-developer model as the main admin surface — but matters especially for the **container track**, whose worker publishes its internal port to a host port (`-p 0:8080`, see [05. Isolation Modes](05-isolation-modes.md)): a network-reachable attacker who reaches that host port could otherwise deploy/redeploy/undeploy without holding any secret. Turn it on with `protean.worker.admin-auth.enabled=true` as defense-in-depth.

Two schemes, selected by `protean.worker.admin-auth.mode`:

- `hmac` (default) — per-request HMAC-SHA256 over timestamp + nonce + body (reuses the `BridgeHmac` primitive from the main↔worker RPC bridge, on its own toggle/secret). Rejects requests outside the clock-skew window (`protean.worker.admin-auth.hmac-window-ms`, default 30000ms) and rejects a replayed nonce (tracked in-memory per worker, pruned as the window expires).
- `token` — a static `Authorization: Bearer <secret>` token.

Both modes compare in constant time (`MessageDigest.isEqual`), so the secret cannot leak via timing. The secret (`protean.worker.admin-auth.secret`) is either pinned explicitly (externally managed) or, left blank, auto-generated as a random 256-bit token once per JVM lifetime on the main and injected into each spawned worker/container at launch. Transport confidentiality (TLS) is out of scope — the plane is assumed localhost/host-scoped. See [03. Configuration](03-configuration.md) for the full key table.

## Production deployment recommendations

- **Keep production on in-process + trusted source** by default, and turn on the MCP adapter only when needed.
- **Make gates mandatory**: `protean.gate.signature.required=true` (enforce signing), and if needed `protean.gate.approval.required=true` (human approval). Keep the tests and review gates at their default on.
- Authenticate the admin REST and MCP surfaces with Spring Security, and enforce per-action authorization with `ModuleActionAuthorizer`.
- Keep the signing private key only outside the server (in CI / the signing issuer), and keep only the trust store public key on the server.
- For the `worker`/`container` isolation tracks — chiefly `container`, whose worker control plane is reachable via a published host port — turn on `protean.worker.admin-auth.enabled=true` as defense-in-depth.

## Related docs

- [05. Isolation Modes](05-isolation-modes.md)
- [06. Promotion Gates](06-promotion-gates.md)
- [08. MCP Integration](08-mcp-integration.md)
- [10. SPI Extension](10-spi-extension.md)
- [11. Operations](11-operations.md)
- [README](../../README.md)
