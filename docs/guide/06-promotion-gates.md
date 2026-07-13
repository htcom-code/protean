**English** | [한국어](06-promotion-gates.ko.md)

# 06. Promotion Gates

When installing/updating a module, Protean promotes it to the `ACTIVE` state — where it takes traffic — only after it passes several gates. Each gate can be turned on/off with `protean.gate.*`, and the safe defaults (tests and review on) can be relaxed to match your trust level.

## Pipeline order

`ModulePlatform.install(descriptor)` runs the gates in this order.

```
(signature) → ① tests → ② review → (awaiting approval) → deploy → ③ verify → ACTIVE
```

- Signature, ①, and ② are **automatic gates** that `PromotionPipeline.runGates()` runs at the compile stage before deploy.
- If the approval gate is on, what passes the automatic gates is saved only as `PENDING_APPROVAL` and stops here (no deploy, no verify).
- ③ verify runs against the live endpoint after the module is actually deployed. On failure it auto-rolls back (install → undeploy + delete from store; update → hot-swap back to the previous version).

Each gate failure throws `PromotionPipeline.GateFailedException`. If it fails at an automatic gate, the module is not even saved.

## Gate-toggle config keys

| Key | Default | Meaning |
|----|--------|------|
| `protean.gate.tests-enabled` | `true` | ① tests gate |
| `protean.gate.review-enabled` | `true` | ② review (code check) gate |
| `protean.gate.signature.required` | `false` | Signature gate (opt-in) |
| `protean.gate.signature.keys.<keyId>` | (none) | trust store: keyId → Base64(X.509 Ed25519 public key) |
| `protean.gate.approval.required` | `false` | Approval gate (opt-in) |
| `protean.mcp.capture-test-output` | `false` | On ① failure, include test stdout/stderr in the diagnostic message |

Turning a gate off is treated not as a bypass but as an **explicit omission**, leaving a `WARN` log (to prevent silent bypass).

## ① Tests gate

Bundling JUnit tests is **mandatory** for a module. If `ModuleDescriptor.tests()` is empty, it is rejected immediately.

```
Gate ① failure: no unit tests (test bundling is mandatory).
```

Behavior:

1. Compiles `sources` + `tests` together under a single module `ClassLoader` (the test references the target class).
2. Runs each class in `tests()` with the JUnit Platform Launcher.
3. The pass condition is `0 failures && at least 1 success`. If not a single test succeeds, it is not green.

On failure, the exception message contains `failures/total` and the **full stack trace** of each failure. If `protean.mcp.capture-test-output=true`, it also captures stdout/stderr during execution and appends them to the message (opt-in, since it intercepts the global `System.out`).

## ② Review gate (code check)

Statically inspects the compiled bytecode with every registered `CodeRule` bean. The built-in rule `ForbiddenApiRule` rejects *accidental* dangerous API calls.

| owner | forbidden method |
|-------|-------------|
| `java.lang.System` | `exit` |
| `java.lang.Runtime` | `halt`, `exec`, `addShutdownHook` |
| `java.lang.ProcessBuilder` | `start` |

`addShutdownHook` is forbidden because a JVM-global registration causes a hard-reference leak of the module `ClassLoader`. If you need background work, use the injected `ProteanTaskExecutor`, which is auto-reclaimed on unload.

Violation example:

```
Gate ② failure: code check violation [runtime.x.Foo#bar forbidden call: java.lang.System.exit]
```

This rule is **not a security sandbox.** Being an ASM bytecode scan, reflection bypass is out of scope; it is a guardrail against a trusted developer's mistakes (for the security model, see [12. Security](12-security.md)). If you need extra rules, register a `CodeRule` bean and `RuleSystem` auto-collects and enforces it — see [10. SPI Extension](10-spi-extension.md).

## ③ Verify gate (VerificationPlan)

If `ModuleDescriptor.verification()` is `null`, it is a no-op (skipped). If a plan is present, it verifies over HTTP against the **actually deployed server port** (fails if the port is undetermined).

`VerificationPlan` fields:

| Field | Type | Meaning |
|------|------|------|
| `integration` | `List<Probe>` | Integration probes (HTTP checks). `null` = skip |
| `loadPath` | `String` | Target path for load (multi-request / speed / memory) |
| `concurrency` | `Integer` | Number of concurrent threads. `null` = skip the entire load verification |
| `requestsPerThread` | `Integer` | Requests per thread (default 10) |
| `maxAvgLatencyMs` | `Long` | Average-latency ceiling (ms). `null` = skip |
| `maxHeapGrowthBytes` | `Long` | Heap-growth ceiling before/after load (bytes). `null` = skip |

`Probe(method, path, expectedStatus, bodyContains)` — fails on a status-code mismatch or when `bodyContains` is not present.

Authoring example:

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
        16L * 1024 * 1024   // maxHeapGrowthBytes (16MB, lenient)
);
```

Load-verification rules:

- **multi-request**: fails if there is any non-2xx response. Times out and fails if not completed within 60 seconds.
- **speed**: fails if the average latency exceeds `maxAvgLatencyMs`.
- **memory**: fails if the heap growth before/after load exceeds `maxHeapGrowthBytes`. Because of JVM GC noise, a **lenient value** is recommended (not a precise measurement).

On ③ failure, `ModulePlatform` auto-rolls back the module it just deployed.

## Signature gate

If `protean.gate.signature.required=true`, it runs at the very front of the pipeline to guarantee **integrity, authenticity, and authorization**. It uses JDK-native Ed25519 with no extra dependency.

### 1. Generate a key pair · configure the trust store

```java
KeyPair kp = ModuleSigning.generateKeyPair();
String publicB64 = ModuleSigning.publicKeyToBase64(kp.getPublic());
// register publicB64 in the server config
```

```yaml
protean:
  gate:
    signature:
      required: true
      keys:
        ci-key: "<publicB64>"   # keyId → Base64(X.509 Ed25519 public key)
```

### 2. Sign · attach the descriptor

```java
String sig = ModuleSigning.sign(descriptor, kp.getPrivate());
ModuleDescriptor signed = descriptor.withSignature("ci-key", sig);
platform.install(signed);
```

The signing target is the deterministic canonicalization (`canonicalBytes`, sorted map keys) of the module content **excluding** `signerKeyId`/`signature`. Therefore, changing any content — sources, verification plan, etc. — after signing breaks the signature.

Rejection cases (all `GateFailedException`, module not saved):

- No signature (`signerKeyId`/`signature` missing)
- An untrusted `keyId` not in the trust store
- Content tampering after signing (signature mismatch)

## Approval gate

If `protean.gate.approval.required=true`, it passes only the automatic gates (signature, ①, ②), saves as `PENDING_APPROVAL`, and **does not deploy or verify.** An unapproved module is not served, and since `reconcile` only restores `ACTIVE`, it stays unserved even after a restart (bypass blocked).

REST workflow:

```
POST /platform/modules              # install → PENDING_APPROVAL
POST /platform/modules/{id}/approve?approver=alice   # ③verify + deploy → ACTIVE
POST /platform/modules/{id}/reject?approver=alice    # remove
```

- `approve`: reaches `ACTIVE` only if ③ verify + deploy succeed. On failure it reverts to `PENDING_APPROVAL` (not served).
- `reject`: removes the module awaiting approval.
- Both actions record the `approver` identity in the audit log. **Identity verification itself is the consumer's job via Spring Security / `ModuleActionAuthorizer`** ([12. Security](12-security.md)).

The same is exposed over MCP as `ApproveModuleTool`/`RejectModuleTool` (action `APPROVE`) — see [08. MCP Integration](08-mcp-integration.md).

## Related docs

- [02. Module Authoring](02-module-authoring.md)
- [03. Configuration Reference](03-configuration.md)
- [10. SPI Extension](10-spi-extension.md)
- [12. Security](12-security.md)
- [README](../../README.md)
