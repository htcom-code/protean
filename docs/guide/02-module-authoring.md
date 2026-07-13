**English** | [한국어](02-module-authoring.ko.md)

# 02. Module Authoring

A module is the unit of deployment. You declare it with a `ModuleDescriptor` (record) or a `module.yaml`
manifest. This document covers the descriptor fields, the source contract, the constraints the promotion gates
enforce (tests · forbidden APIs), and DI inside the child context.

## 1. `ModuleDescriptor` Fields

`org.htcom.protean.module.ModuleDescriptor` is a record with the following components. When you send it via
REST `POST /platform/modules`, the JSON field names match these component names.

| Field | Type | Meaning |
|------|------|------|
| `id` | `String` | Module identifier (used in the path `/platform/modules/{id}`). |
| `version` | `String` | Version. Serves as the recompile pin for the same version on recovery, and as the history/rollback key. |
| `trustTier` | `TrustTier` | Trust tier — `TRUSTED` \| `UNTRUSTED`. |
| `desiredState` | `DesiredState` | Desired state — `ACTIVE` \| `INACTIVE` \| `PENDING_APPROVAL`. Only `ACTIVE` is reconciled at startup. |
| `controllerFqcn` | `String` | FQCN of the controller whose REST mappings are registered. |
| `componentFqcns` | `List<String>` | FQCNs of the components to register in the child context (including the controller). |
| `sources` | `Map<String,String>` | `FQCN → Java source`. Runtime-compile input. |
| `tests` | `Map<String,String>` | `FQCN → JUnit test source`. Input to promotion gate ①, and **enforced**. |
| `needsSharedBeans` | `boolean` | Whether it depends on shared in-process beans (used to judge isolation-mode compatibility). |
| `verification` | `VerificationPlan` | Promotion gate ③ verification plan. `null` = skip verification. |
| `isolationMode` | `String` | This module's isolation mode — `"in-process"` \| `"worker"`. `null` = the global default (`protean.isolation.mode`). |
| `bridgedInterfaces` | `List<String>` | FQCNs of interfaces a worker calls on the main's shared beans over RPC (worker mode). `null`/empty = none. |
| `signerKeyId` | `String` | Signing key identifier (for the signature gate). `null` = unsigned. |
| `signature` | `String` | Ed25519 signature (Base64) over the normalized content. `null` = unsigned. |
| `resources` | `Map<String,ModuleResource>` | `classpath path → non-Java resource` (mapper XML, etc.). `null` = normalized to an empty map. |

The minimum required fields are `id`, `version`, `trustTier`, `desiredState`, `controllerFqcn`,
`componentFqcns`, `sources`, `tests`, and `needsSharedBeans`. The rest (`verification`, `isolationMode`,
`bridgedInterfaces`, `signerKeyId`, `signature`, `resources`) are `null`/omittable.

### `VerificationPlan` (gate ③)

`verification` is the verification plan against the deployed live endpoint (each field, if `null`, skips that
check):

| Field | Type | Meaning |
|------|------|------|
| `integration` | `List<Probe>` | List of HTTP integration probes. |
| `loadPath` | `String` | Path targeted by load verification. |
| `concurrency` | `Integer` | Number of concurrent threads (`null` = skip load verification). |
| `requestsPerThread` | `Integer` | Requests per thread. |
| `maxAvgLatencyMs` | `Long` | Average-latency ceiling (ms, `null` = skip). |
| `maxHeapGrowthBytes` | `Long` | Heap-growth ceiling before/after load (bytes, `null` = skip). |

A `Probe` is `(String method, String path, int expectedStatus, String bodyContains)`.

### `ModuleResource` (non-Java resource)

The value in the `resources` map is `ModuleResource(String content, boolean base64)`. Load text config
(mapper XML, `.properties`) as plaintext (`base64=false`), and binaries (certificates, keystores) as Base64
(`base64=true`). Paths are normalized and validated to prevent traversal.

## 2. Controller/Component Source Contract

Sources are ordinary Spring stereotype classes. A controller declares its mappings with `@RestController`:

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

- `controllerFqcn` must match the FQCN of the class above exactly (`runtime.hello.HelloController`).
- Put all classes to register in the child context — including the controller — in `componentFqcns`.
- The compiler inherits the current JVM classpath as-is, so you can import platform types such as Spring Web.

## 3. DI Inside the Child Context

Each module is brought up as a **child `ApplicationContext`** using a dedicated `ModuleClassLoader` (parent =
the consumer app's root context). Because the classes listed in `componentFqcns` are registered in this child
context, you can constructor-inject a module-internal `@Service`/`@Repository` into the controller:

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

In this case put both classes in the descriptor's `componentFqcns`:
`["runtime.hello.HelloController", "runtime.hello.GreetingService"]`.

Because the parent is the root context, you can also inject the consumer app's shared beans (mark
`needsSharedBeans=true` when such a dependency exists). On unload, closing the child context makes the entire
`ModuleClassLoader` eligible for GC.

### Managed background executor

Each child context has a managed `ProteanTaskExecutor` registered as a lazy bean (threads are created only on
injection). For async/scheduled work, don't spin threads yourself — inject this instead. On module unload it
auto-shuts-down together with the context close, preventing thread/ClassLoader leaks. Tune the pool size with
`protean.module.executor.pool-size` (default 2) ([03. Configuration](03-configuration.md)).

## 4. Why Tests Are Mandatory

Promotion gate ① (`PromotionPipeline.enforceTestGate`) rejects immediately if `tests` is `null` or empty
("gate ① failed: no unit tests"). The bundled tests are runtime-compiled and run under the module-dedicated
loader, and must be `failed == 0 && succeeded > 0` (all green + at least one passing) to be promoted. In REST
this maps to `422`.

Because tests are compiled together with the target classes under the same module loader, they can reference
each other directly:

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

The `tests` map key is the test class FQCN and the value is its source. This gate can be turned off with
`protean.gate.tests-enabled=false` (depending on your trust level), but by default it is enforced.

## 5. Forbidden-API Constraints (gate ②)

Promotion gate ② (review) does an ASM static scan of the compiled bytecode. `ForbiddenApiRule` rejects the
following calls (a guardrail against accidental hazards — not a security sandbox, but mistake-prevention for a
trusted developer):

| owner | forbidden method |
|-------|------------|
| `java.lang.System` | `exit` |
| `java.lang.Runtime` | `halt`, `exec`, `addShutdownHook` |
| `java.lang.ProcessBuilder` | `start` |

`Runtime.addShutdownHook` is a JVM-global registration that hard-references the module ClassLoader and leaks it
→ forbidden. For background work, use the injectable `ProteanTaskExecutor` above, which is automatically
reclaimed on unload. On violation, gate ② rejects with `422`. This gate can be turned off with
`protean.gate.review-enabled=false`.

## 6. `module.yaml` Manifest

You can also define a module with a declarative manifest instead of JSON. `ModuleManifestLoader` reads the
following keys:

| Key | Required | Default/meaning |
|----|------|-------------|
| `id` | required | Module identifier. |
| `version` | required | Version. |
| `controller` | required | Controller FQCN. |
| `trustTier` | optional | `TRUSTED` (default) \| `UNTRUSTED`. |
| `isolationMode` | optional | `null` (default = global default) \| `in-process` \| `worker`. |
| `needsSharedBeans` | optional | `false` (default). |
| `components` | optional | List of component FQCNs. If empty, `[controller]`. |
| `bridgedInterfaces` | optional | List of RPC-bridge interface FQCNs. |
| `sources` | optional | Inline `FQCN → source` map. |
| `sourceDir` | optional | Directory relative to the manifest. Scans `*.java` underneath (FQCN = `package` declaration + filename). |
| `tests` | optional | Inline `FQCN → test source` map. |
| `testDir` | optional | Test-source directory (scanned). |
| `resources` | optional | Inline `path → plaintext resource` map. |
| `resourceDir` | optional | Resource directory (scans every file underneath as binary). |

`sources`/`tests`/`resources` **merge** the inline map with the directory scan. `sourceDir`/`testDir`/
`resourceDir` can only be used in a file manifest (where there is a directory base) — you cannot use directory
keys in an HTTP body that carries inline content only. A descriptor built from a manifest is fixed to
`desiredState=ACTIVE` and `verification=null`.

Inline example (`hello.yaml`):

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

The endpoint to deploy from a manifest is `POST /platform/modules/from-manifest`, and the body is YAML text
(`Content-Type` is one of `text/plain`, `application/yaml`, `application/x-yaml`):

```bash
curl -i -X POST http://localhost:8080/platform/modules/from-manifest \
  -H 'Content-Type: application/yaml' \
  --data-binary @hello.yaml
```

On success it returns `201 Created` + `Location` + `ModuleStatus`, identical to `POST /platform/modules`.

## 7. Versioning Rule

`version` is the history/rollback key and the recompile pin on recovery. When you swap the source via a canary
update (`PUT /platform/modules/{id}`), assigning a new `version` accrues it to the version history, and
`POST /platform/modules/{id}/rollback?version=...` reverts to a specific version (a `PATCH` that sends only
partial files also requires a new `version`). For the full lifecycle endpoints see
[04. REST API Reference](04-rest-api.md).

## Related Docs

- [01. Getting Started](01-getting-started.md)
- [03. Configuration Reference](03-configuration.md)
- [04. REST API Reference](04-rest-api.md)
- [06. Promotion Gates](06-promotion-gates.md)
- [07. Data Access](07-data-access.md)
- [README](../../README.md)
