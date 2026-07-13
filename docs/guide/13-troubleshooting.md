**English** | [한국어](13-troubleshooting.ko.md)

# 13. Troubleshooting

Common problems with their causes and fixes, plus an FAQ. Each item is grounded in actual behavior confirmed from the code/tests.

## OutOfMemoryError during build/test

**Symptom**: In a combined run like `./gradlew clean bootJar test`, `LeakDiagnosisTest` and others die with OOM.

**Cause**: The `test` task deliberately caps the heap at `maxHeapSize=512m` to force soft references to clear in the leak canary. `bootJar` (fat jar assembly) is itself memory-hungry. Binding the two into one gradle invocation causes a collateral OOM on the constrained heap.

**Fix**: Invoke bootJar and test separately.

```bash
./gradlew clean test
./gradlew bootJar
```

## Metaspace leak after module unload

**Symptom**: Repeatedly deploying/unloading a module makes Metaspace usage keep climbing, eventually `OutOfMemoryError: Metaspace`.

**Cause**: Each module is loaded by its own `ModuleClassLoader`. Even after unload, if even one strong reference to that ClassLoader remains, it is not GC'd and Metaspace is not reclaimed. Common reference sources:

- Per-Class caches in the MVC infrastructure. **Invoking** a handler fills a cache keyed by the controller Class, and unmapping alone does not clear it.
- Threads a module started with raw `new Thread`/an external executor pinning a dead ClassLoader.
- Out-of-context resources such as a ThreadLocal left on a shared thread, a static cache registration, or a JMX MBean.

**Fix / prevention**:

- The platform evicts, in `DynamicEndpointRegistrar.unregister`/`swap`, the per-Class caches (`RequestMappingHandlerAdapter`, `ExceptionHandlerExceptionResolver`, argument-resolver, `@ControllerAdvice` caches) keyed by the controller Class along with unmapping. This is automatic.
- Module code should inject and use the managed executor `ProteanTaskExecutor` for async/scheduled tasks. On unload the child context close calls `close()` (→ `shutdownNow`), cleaning up the threads.
- Out-of-context resources should clean themselves up in a `ModuleUnloadCallback` bean (called just before the child close).

## Promotion gate failure

When `install`/`update` is blocked at a gate, the REST response is `422` (`{"error": ...}`).

### Gate ① — no tests / test failure

- **No tests bundled**: if `descriptor.tests` is empty, the gate rejects with `unit tests are required (tests must be bundled)` (surfaced as a `tests` gate failure). A JUnit test must be included in the module.
- **Test failure**: if the bundled tests are not green, `unit tests failed N/M ...` with the failure message included. If you need stdout/stderr diagnostics, turn on `protean.mcp.capture-test-output=true` (opt-in, since it intercepts the global System.out) to include the failure output in the response.

### Gate ② — forbidden API / code rule violation

When the bytecode static scan (ASM) catches a rule violation such as a forbidden API use, the `review` gate rejects with `code check violations: [...]`. Look at the violation list in the message and remove the offending calls.

### Gate ③ — verification failure

If a `verification` (VerificationPlan) is present, the deployed live endpoint is verified (integration probe, load/latency, heap growth). On failure:

- `install`: undeploy + store delete → rollback (no module remains).
- `update`: automatic rollback via hot-swap to the previous version, then an exception.

Common causes are an integration-probe (`expectedStatus`/`bodyContains`) mismatch or exceeding `maxAvgLatencyMs`/`maxHeapGrowthBytes`. Load thresholds have noise, so do not set the upper bounds excessively tight.

### Relaxing gates

You can turn off individual gates to match the trust level (all on by default). Turning one off leaves a `WARN` log to prevent a silent bypass.

```yaml
protean:
  gate:
    tests-enabled: true      # false forcibly skips the tests requirement
    review-enabled: true     # false skips the code check
```

## Same-path conflict (ambiguous mapping) rejection

**Symptom**: Deploying a second module with the same mapping as an already-serving path fails with an exception.

**Behavior**: When trying to register the same path, Spring's `RequestMappingHandlerMapping` rejects the registration as an ambiguous mapping. Policy characterized by `PathConflictModuleTest`:

- The second module deploy is rejected and an exception is thrown.
- **The first module stays alive** — the conflict does not break the existing serving.
- The second module remains not deployed (`isDeployed=false`).

**Fix**: Design paths so modules don't overlap (a per-module path prefix is recommended). Note that re-`register`ing the same module `id` also throws a `module already deployed: <id>` exception — do replacements with `update` (the hot-swap `swap`).

## Container/OS isolation tests don't run

**Symptom**: Tests related to worker/container isolation and DB-scope provisioning are skipped or fail.

**Cause**: These tests spin up a real DB engine with Testcontainers (`testcontainers:mysql`/`postgresql`) and faithfully verify GRANT isolation, so they **require Docker**. Also, the container-track worker explodes and uses the `-boot.jar` in build/libs, so the **bootJar artifact must exist first**.

**Fix**: Start the Docker daemon, then build the bootJar before the container tests. To avoid the bootJar+test combined OOM, still separate them but run bootJar first.

```bash
./gradlew bootJar      # the worker-runnable artifact first
./gradlew test         # (Docker must be up for the container tests to pass)
```

## FAQ

**Q. `/platform/*` endpoints return 404.**
The admin surface is off or not registered. Check that `protean.admin.enabled` is not `false`, and that the process did not start under the worker profile (`worker`) (both controllers are `@Profile("!worker")`).

**Q. Modules disappear after a restart.**
The descriptor store may be on a volatile path. The filesystem backend's default `dir` is under `java.io.tmpdir` — change it to a persistent path. Also, reconcile restores only `ACTIVE`, so a `PENDING_APPROVAL` module is not served after a restart (an intentional bypass block).

**Q. `/platform/traces` is always empty.**
If `protean.trace.enabled=false`, nothing is recorded. Also, the trace-query endpoint itself (a `/platform/traces` request) is not recorded, to avoid self-noise. Traces older than the ring buffer (`capacity`, default 200) are discarded.

**Q. I set a timeout but a runaway module won't stop.**
The `request-timeout-ms` interrupt is cooperative and only cuts off blocking. It can't stop a CPU spin. If you need hard isolation, use worker/container mode.

**Q. PUT returns 400.**
A canary update requires the path `id` and the body `id` to match. A mismatch is a 400.

**Q. `list_modules` (or `/platform/modules`) shows `ACTIVE` but the module path is 404.**
The status list only shows the store's `desiredState` (declaration); it does not mean the route is actually registered. During restart recovery, if runtime registration is 0 due to a recompile failure etc., you get "ACTIVE but not serving". Measure the actually-registered routes with the MCP resource `protean://modules/{id}/routes` (`resources/read`) — **an empty array is proof that no routes are registered**. Also check the server log's `reconcile: recovered N/M modules` and compile errors. For detailed lookup see [08. MCP Integration](08-mcp-integration.md#resource-templates-resourcestemplateslist).

**Q. What do the status codes mean?**
`422` = gate rejection, `409` = isolation mode unsupported / state conflict (rollback of a not-installed module, etc.), `400` = bad input/manifest or id mismatch, `404` = target not found.

## Related docs

- [04. REST API Reference](04-rest-api.md)
- [06. Promotion Gates](06-promotion-gates.md)
- [09. Debugging](09-debugging.md)
- [11. Operations](11-operations.md)
- [README](../../README.md)
