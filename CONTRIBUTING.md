# Contributing to Protean

Thanks for your interest in improving **Protean**. This guide covers the
conventions this project follows. The operations guide
([`docs/guide/11-operations.ko.md`](docs/guide/11-operations.ko.md)) is the source of
truth for build/test/publish commands.

## Getting started

There is **no `gradlew` wrapper** — use a system `gradle` (Java 21 toolchain).

```bash
git clone https://github.com/htcom-code/protean
cd protean
gradle clean test    # full regression
gradle bootJar       # assemble the plain jar + fat bootJar
```

Requires JDK 21 and Spring Boot 3.5.x. Some tests (container isolation,
Testcontainers MySQL/Postgres, seccomp/OS isolation) need **Docker**; without it
they skip themselves.

Protean is a **library**: consumers cannot patch it at their point of use, so we
favor **feature completeness** over ROI shortcuts, and open a bean-registered SPI
wherever an axis needs to vary rather than hard-coding a policy.

## Architecture at a glance

Knowing where a change lands helps reviewers and keeps tests next to their subject.

| Package | Responsibility |
|---|---|
| `autoconfigure/` | `ProteanAutoConfiguration`, `ProteanProperties` (`protean.*` config surface) |
| `compiler/` | JSR-199 in-memory compile (`RuntimeCompiler`), `ModuleClassLoader`, `ModuleSharedLibs` |
| `dynamic/` | Dynamic RequestMapping register/deregister (`DynamicEndpointRegistrar`) |
| `module/` | `ModulePlatform` (facade), `ModuleDescriptor`, store (FS/JDBC), reconciler, `ProteanTaskExecutor`, `ModuleUnloadCallback` |
| `gate/` | Promotion gates ①tests ②review(bytecode) ③verify, signature, `rules/` (`CodeRule` SPI) |
| `isolation/` | `IsolationStrategy` SPI + in-process / worker / container |
| `worker/`, `bridge/`, `proxy/` | Worker management, worker→main RPC bridge, reverse-proxy routing |
| `db/` | DB-scope auto-provisioning, `DbDialect` SPI (built-in MySQL/Postgres) |
| `mcp/` | MCP adapter (dispatcher / tools / transport / debug) |
| `runtime/`, `web/`, `boot/` | Timeout watchdog & request trace, control-plane REST, worker/stdio launchers |

The facade is `module/ModulePlatform` — install / update / rollback / approve /
reject / uninstall / reconcile all delegate here, and gate + verify + isolation
routing happen inside it.

**Invariants a change must not break:** the unload path releases everything it
acquired (endpoint dereg, `RequestMappingHandlerAdapter` per-Class cache purge,
child context close, executor shutdown) so a module ClassLoader is fully
collectible (no Metaspace leak); and the promotion gates cannot be bypassed
(no path serves a module that skipped tests → review → verify).

## Development workflow

Run the full verification before opening a PR. **`test` and `bootJar` must be
separate gradle invocations** — combining them (`clean bootJar test`) can trigger
a collateral OOM in `LeakDiagnosisTest`, whose test task caps the heap at 512m to
force soft-reference eviction.

```bash
# good — separate
gradle clean test
gradle bootJar

# bad — combined, OOM risk
gradle clean bootJar test
```

| command | what it runs |
|---|---|
| `gradle clean test` | full test suite (maxHeap 512m) |
| `gradle test --tests 'org.htcom.protean.XxxTest'` | a single class |
| `gradle bootJar` | assemble the fat bootJar (`-boot`) |
| `gradle publishToMavenLocal` | publish to `~/.m2` (POM / consumability check) |

## Code & test conventions

These keep the platform's safety claims honest.

1. **Test the behavior, at the right level.** Real-JVM e2e for the dynamic
   lifecycle; MCP behavior at the tool-dispatcher level; config gate keys through
   the **binding path** (a directly-injected value hides the binding blind spot).
2. **Always include exception cases.** Each area exercises edge cases — empty /
   missing input, gate rejection, rollback, timeout, double-unload,
   concurrency, and **leak / invariant** checks (ClassLoader / thread /
   Metaspace). Exercise the actual branch — don't stop at the happy path.
3. **Guard against ClassLoader / thread leaks.** Any change to load/unload,
   isolation, or the executor must prove the module ClassLoader is collectible
   after unload. `LeakDiagnosisTest` is the canary.
4. **Don't weaken the gates.** Changes to `gate/` must keep tests → review →
   verify enforced; a "convenience" bypass is a correctness bug.
5. **Keep the config surface typed.** New `protean.*` keys go through
   `ProteanProperties` (configuration-processor metadata → consumer IDE
   completion), and are verified via binding, not direct injection.

## Documentation

Update docs when behaviour or config changes. Docs are bilingual: the guides live
in [`docs/guide/`](docs/guide), and the README ships in English (`README.md`) and
Korean (`README.ko.md`) — keep both in sync. Record notable changes in
`CHANGELOG.md` / `CHANGELOG.ko.md`.

## Commit & branch conventions

- **Commits** follow Conventional Commits: `type(scope): subject` (`feat`, `fix`,
  `docs`, `refactor`, `perf`, `test`, `chore`, `ci`).
- **Branches** are `<type>/<short-description>` in kebab-case.
- The `main` branch is protected — open a PR rather than pushing directly, and
  satisfy the PR template checklist.

## License

By contributing, you agree that your contributions are licensed under the
project's [Mozilla Public License 2.0](LICENSE).
