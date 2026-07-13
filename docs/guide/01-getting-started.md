**English** | [한국어](01-getting-started.ko.md)

# 01. Getting Started

This walks the shortest path to adding Protean as a dependency to an existing Spring Boot application and deploying Java source over REST as a live endpoint — without a restart.

## 1. Add the Dependency

The Protean coordinate is `org.htcom:protean:0.0.1-SNAPSHOT` (`group = org.htcom`, artifactId = `protean`,
Spring Boot 3.5.x / Java 21). It is currently published to the local Maven repository (`~/.m2`), so add
`mavenLocal()` to the consumer build.

Gradle:

```groovy
repositories {
    mavenCentral()
    mavenLocal()   // where Protean was published via publishToMavenLocal
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

### Bundled Spring dependencies

Protean carries `spring-boot-starter-web`, `spring-boot-starter-aop`, and `spring-boot-starter-jdbc` as
`implementation`, so they transit to the consumer at runtime scope in the published POM. You don't need to
declare a web stack separately for the control plane (`/platform/*`) to come up. The consumer app just needs
to be a standard Spring Boot app (an entry class with `@SpringBootApplication`).

The JDBC drivers (`mysql-connector-j`, `postgresql`) are marked `optional` in the published POM and do **not**
transit. If you need real DB access (e.g. worker DB provisioning), the consumer adds its own driver directly
(for data access see [07. Data Access](07-data-access.md)).

### JDK required

Because Protean compiles module source at runtime (`RuntimeCompiler`), a system `JavaCompiler` must be present.
Run on a **JDK, not a JRE** — launching under a JRE fails with a "no system JavaCompiler" exception.

## 2. Auto-configuration

Protean is an auto-configuration library. When the consumer app's `@SpringBootApplication`
(→ `@EnableAutoConfiguration`) loads `ProteanAutoConfiguration` through
`META-INF/spring/...AutoConfiguration.imports`, its `@ComponentScan(basePackages = "org.htcom.protean")`
registers all Protean beans — isolation strategy, module platform, gates, controllers, etc. **The consumer
package does not need to include `org.htcom.protean`** — the control plane works with no extra configuration.

If you don't want to expose the admin REST surface, turn it off with `protean.admin.enabled=false` (default
`true`). For the full set of config keys see [03. Configuration Reference](03-configuration.md).

## 3. Deploy a Hello Module (end-to-end)

Deploying means sending a `ModuleDescriptor` JSON body to `POST /platform/modules`. Below is a minimal module
where `GET /hello/greet` returns `hello`. Because promotion gate ① **enforces bundled tests**, you send a
JUnit test alongside the controller (no tests = 422 rejection).

First save the request body as `descriptor.json`. Inside the JSON string, escape source line breaks as `\n`.

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

`sources` and `tests` are `FQCN → Java source` maps. The field names are exactly the component names of the
`ModuleDescriptor` record (`controllerFqcn`, `componentFqcns`, `needsSharedBeans`, `verification`, etc.). For
the meaning of each field see [02. Module Authoring](02-module-authoring.md).

### Deploy (POST → 201)

```bash
curl -i -X POST http://localhost:8080/platform/modules \
  -H 'Content-Type: application/json' \
  -d @descriptor.json
```

On passing the gates (① tests → ② review) you get `201 Created` with a `Location: /platform/modules/hello`
header and a `ModuleStatus` body:

```
HTTP/1.1 201 Created
Location: /platform/modules/hello

{"id":"hello","version":"1.0.0","trustTier":"TRUSTED","desiredState":"ACTIVE",
 "controllerFqcn":"runtime.hello.HelloController","mode":"in-process",
 "needsSharedBeans":false,"bridgedInterfaces":null}
```

Send it without tests and gate ① rejects it with `422 Unprocessable Entity` + `{"error": ...}`.

### Verify serving (GET → 200)

The endpoint goes live immediately after deploy:

```bash
curl -i http://localhost:8080/hello/greet
# HTTP/1.1 200 OK
# hello
```

You can also query module status:

```bash
curl http://localhost:8080/platform/modules/hello   # single status
curl http://localhost:8080/platform/modules          # ACTIVE list
```

### Uninstall (DELETE → 204)

```bash
curl -i -X DELETE http://localhost:8080/platform/modules/hello
# HTTP/1.1 204 No Content
```

Uninstalling closes the child context and the endpoint disappears — afterward `GET /hello/greet` is `404`,
and `GET /platform/modules/hello` is `404` too. Deleting an already-absent module again returns `404`.

## Next Steps

- The full descriptor fields, source contract, forbidden APIs, and the `module.yaml` manifest: [02. Module Authoring](02-module-authoring.md)
- All `protean.*` config keys: [03. Configuration Reference](03-configuration.md)
- The complete REST endpoints (update/rollback/approve, etc.): [04. REST API Reference](04-rest-api.md)
- Isolation modes (in-process/worker/container): [05. Isolation Modes](05-isolation-modes.md)

## Related Docs

- [02. Module Authoring](02-module-authoring.md)
- [03. Configuration Reference](03-configuration.md)
- [04. REST API Reference](04-rest-api.md)
- [README](../../README.md)
