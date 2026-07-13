# Security Policy

## Reporting a vulnerability

Please report security issues **privately** — do not open a public issue for an
unpatched vulnerability.

- Use GitHub's **"Report a vulnerability"** (Security → Advisories) on this
  repository:
  <https://github.com/htcom-code/protean/security/advisories/new>, or
- email the maintainer at **htjulia1@gmail.com**.

Include a description, the affected version/commit, and a minimal reproducer
(a failing test, or a module descriptor + REST/MCP call). We'll acknowledge the
report and work with you on a fix and coordinated disclosure.

## Supported versions

Protean is pre-1.0; fixes land on the active line, and the public API may change
between minor versions.

| Version | Supported |
|---|---|
| 0.0.x   | ✅ |

## Threat model — trusted source by default

Protean compiles and runs **Java source at runtime**. Its baseline trust model is
that **all module source comes from trusted developers** (the in-process
production case). A security sandbox for *untrusted* source is an **explicit
non-goal** — `SandboxAbsenceTest` exists to prove that absence, so nobody mistakes
it for a guarantee. In-process modules run with the host's full authority.

What Protean *does* provide are controls that raise the bar and isolate blast
radius. **The host owns the policy** — these are opt-in or configurable:

- **Promotion gates.** `install` routes every module through tests → review →
  verify before it serves. The **review** gate is an ASM bytecode static scan
  (`ForbiddenApiRule` blocks `System.exit`, `Runtime.exec`,
  `Runtime.addShutdownHook`, …); register a `CodeRule` bean to add rules. These
  gates catch classes of mistakes/abuse but are **not** a substitute for trusting
  your source.
- **Signature gate (opt-in).** `protean.gate.signature.required` verifies an
  Ed25519 signature against a trust store — provenance for who authored a module.
- **Approval gate (opt-in).** `protean.gate.approval.required` holds
  auto-gate-passing modules in `PENDING_APPROVAL` until a human approves; an
  unapproved module is not served even across a restart (no bypass).
- **The MCP adapter is a deployment entry point → an RCE surface.** It is
  **fail-safe off** (`protean.mcp.enabled=false`, debug tools too). Protean does
  **not** implement authentication for it — you must put the MCP endpoint behind
  your Spring Security and implement the `ModuleActionAuthorizer` SPI (the default
  is permissive). Never expose MCP unauthenticated on an untrusted network.
- **Process / container isolation for lower-trust workloads.**
  `protean.isolation.mode=worker` runs a module in a separate JVM; `container`
  runs it in Docker with cgroup memory/PID limits, a read-only filesystem,
  `cap-drop`, and seccomp. Use these when a module should not run with host
  authority — but they bound blast radius, they are not a language-level sandbox.
- **Bounded execution & clean unload.** `protean.module.request-timeout-ms` caps
  module request wall-clock; unload deregisters endpoints, purges the
  `RequestMappingHandlerAdapter` per-Class cache, closes the child context, and
  shuts down the managed `ProteanTaskExecutor` so a module ClassLoader is fully
  collectible (a leak here is a denial-of-service via Metaspace exhaustion).

## Scope

In scope: Protean runtime bugs with security impact — a promotion gate that can
be **bypassed**, the MCP surface active or authorizable in a way it shouldn't be,
worker/container isolation that fails to contain (escaping cgroup/seccomp/FS
limits or the RPC bridge leaking host authority it shouldn't), a signature/approval
gate that accepts what it should reject, or a ClassLoader/thread leak that lets a
module exhaust Metaspace/threads.

Out of scope: a trusted-but-malicious in-process module doing what in-process code
can inherently do (that's the documented trust model — run it in worker/container
or don't load it), and resource exhaustion from a module deliberately granted full
authority (a configuration choice — isolate it).
