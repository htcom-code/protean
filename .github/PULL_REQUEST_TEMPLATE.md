<!--
Thanks for contributing to Protean! Keep the title in Conventional Commits
form, e.g. `fix(gate): reject module when test source fails to compile`.
Delete any section that does not apply.
-->

## What & why

<!-- What does this change do, and what problem does it solve? -->

## Type of change

- [ ] `fix` — bug fix (no new API)
- [ ] `feat` — new capability (dynamic loading, gates, isolation, MCP, debug, data access)
- [ ] `perf` — performance, no observable behaviour change
- [ ] `docs` — documentation only
- [ ] `refactor` / `chore` / `test` / `ci` — no behaviour change

## Correctness & safety

Protean loads, compiles, and unloads code at runtime — the invariants that must
not break are ClassLoader/lifecycle hygiene and promotion-gate integrity.

- [ ] Unload path releases everything it acquired (dynamic endpoint dereg,
      RequestMappingHandlerAdapter per-Class cache purge, child context close,
      `ProteanTaskExecutor` shutdown) — no ClassLoader hard-leak (Metaspace).
- [ ] Added/updated exception-case tests (nil/empty, gate rejection, rollback,
      timeout, double-unload, leak/invariant, concurrency).
- [ ] Promotion gates (tests → review → verify) still enforce; no bypass path.
- [ ] Config gate keys verified through the binding path (not just direct injection).

## Verification

The operations guide (`docs/guide/11-operations.md`, `.ko.md`) is the source of
truth. **Run `bootJar` and `test` as SEPARATE gradle invocations, bootJar first** —
combining them can OOM `LeakDiagnosisTest`, and running `clean test` first deletes
the `-boot.jar` that the container/isolation tests mount, so 16 of them self-skip
and the green covers less than it appears to.

```bash
./gradlew clean bootJar
./gradlew test
```

- [ ] `gradle clean bootJar` assembles
- [ ] `gradle test` passes (full regression, run after the jar exists)
- [ ] Docs updated if behaviour/config changed (README/README.ko, `docs/*`)
- [ ] Container/isolation tests actually ran (not self-skipped). If they skipped,
      say why — no `-boot.jar`, or no Docker.

## Related

<!-- Closes #123, related issues. -->
