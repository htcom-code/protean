**English** | [한국어](README.ko.md)

# Protean Quickstart Example

Runs a single consumer app in three scenarios selected purely by **configuration (Spring profiles)**.
It consumes Protean as `project(':')`, so it builds straight from the current sources — no publishing or mavenLocal required.

> Modules are compiled at runtime with `javac`, so you must run on a **JDK 21** (not a JRE).

## Three run modes

| Profile | Scenario | Auto-deploy | Check |
|--------|----------|-----------|------|
| (none) | in-process data access | data-access module | `GET /items/add` |
| `worker-demo` | worker isolation (separate JVM) | compute module | `GET /compute/square` |
| `mcp` | MCP deployment entry point | none (an agent deploys) | `POST /platform/mcp` |

> Worker isolation is turned on by the `protean.isolation.mode=worker` **property**, not by a profile. The example
> profile is named `worker-demo` rather than `worker` because `worker` is a **reserved Spring profile** that Protean
> uses to mark the worker JVMs it spawns (enabling it in the main app makes the orchestration beans disappear).

### ① in-process data access (default)

```bash
gradle :examples:quickstart:bootRun
```

On startup the data-access module is deployed in-process. It inserts a row into H2 (in-memory) and returns the count:

```bash
curl 'localhost:8080/items/add?name=widget'
# → items=1   (increments by 1 on each call)

# Inspect deployment state via the management REST API
curl localhost:8080/platform/modules
```

### ② worker isolation (separate JVM)

```bash
gradle :examples:quickstart:bootRun --args='--spring.profiles.active=worker-demo'
```

The compute module is served from a **separate JVM worker** (the main process spawns the worker → forwards via
`ReverseProxy`). The call site is identical to in-process:

```bash
curl 'localhost:8080/compute/square?n=7'
# → square=49
```

### ③ MCP deployment entry point

```bash
gradle :examples:quickstart:bootRun --args='--spring.profiles.active=mcp'
```

The MCP adapter is enabled (`POST /platform/mcp`) and an agent can deploy modules. There is no auto-deploy.
List the tools first:

```bash
curl -s localhost:8080/platform/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

**Real deployment call** — `deploy_module` carries the sources in `files[]` (source/test/resource).
The FQCN is derived automatically from the package + file name. Save the following as `deploy.json`:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
  "name":"protean.deploy_module",
  "arguments":{
    "id":"mcp-hello","version":"1","controller":"gen.HelloController",
    "files":[
      {"kind":"source","filename":"HelloController.java","content":"package gen;\nimport org.springframework.web.bind.annotation.GetMapping;\nimport org.springframework.web.bind.annotation.RequestParam;\nimport org.springframework.web.bind.annotation.RestController;\n@RestController\npublic class HelloController {\n  public static String up(String s){ return s.toUpperCase(); }\n  @GetMapping(\"/mcp/hello\")\n  public String hello(@RequestParam(defaultValue=\"world\") String name){ return \"hello \"+up(name); }\n}\n"},
      {"kind":"test","filename":"HelloTest.java","content":"package gen;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\nclass HelloTest {\n  @Test void up_uppercases(){ assertEquals(\"HI\", HelloController.up(\"hi\")); }\n}\n"}
    ]
  }
}}
```

Deploy (passing promotion gates ①test · ②review) and call it right away:

```bash
curl -s localhost:8080/platform/mcp -H 'Content-Type: application/json' -d @deploy.json
# → ... "module mcp-hello deployed (ACTIVE)" ...

curl 'localhost:8080/mcp/hello?name=abc'
# → hello ABC
```

For the tool catalog and input formats, see [08-mcp-integration](../../docs/guide/08-mcp-integration.md).

### ④ Combinations — runtime property overrides

Append `--<protean.*>` properties to a profile to combine scenarios. Isolation is turned on via a **property**
(the Spring profile `worker` is a Protean reserved profile and must not be enabled in the main app — see the note above).

**MCP + worker isolation** — a module deployed via MCP runs isolated in a separate JVM worker:

```bash
gradle :examples:quickstart:bootRun \
  --args='--spring.profiles.active=mcp --protean.isolation.mode=worker'
```

After deployment, `list_modules` shows `mode` as `worker`, and the call site is identical to in-process
(the main process forwards to the worker port via `ReverseProxy`). Because of the worker spawn, the first call takes a few seconds.

**MCP + approval gate** — deployment stops at `PENDING_APPROVAL` and only serves once approved:

```bash
gradle :examples:quickstart:bootRun \
  --args='--spring.profiles.active=mcp --protean.gate.approval.required=true'
```

`deploy_module` → `PENDING_APPROVAL` (not serving) → `approve_module {id, approver}` → `ACTIVE` (serving),
or `reject_module {id, approver}` → removed. (Both tools require `id` and `approver`.)

### ⑤ Container (Docker) isolation — OS-level isolation

Runs a module as a **Docker container worker**, confined by cgroup memory, a read-only rootfs, `cap-drop=ALL`,
`no-new-privileges`, and a pids-limit (strong isolation, the baseline for untrusted code). The `container` profile enables
`protean.isolation.mode=container` and the container tuning (`protean.worker.container.*`).

> **Prerequisites**
> 1. The **Docker daemon** must be running (the only external requirement of the container track).
> 2. The embed runtime explodes the host bootJar and mounts it read-only into the container, so **run `gradle bootJar` first**.
> 3. The container track supports **self-contained modules only** — a shared-bean module that receives the host
>    `JdbcTemplate` parent-first (the default data-access module) can't be launched because the RPC bridge doesn't
>    support it. That's why the demo uses the compute/MCP modules.

The `container` profile only turns on the isolation mode, so **combine** it with `worker-demo` (compute module) or the
`mcp` profile, which actually deploy a module. Putting it last (`...,container`) makes `mode=container` override the
preceding profile's mode.

**The compute module in a container:**

```bash
gradle bootJar                                    # prepare the exploded bootJar to mount into the container (once)
gradle :examples:quickstart:bootRun \
  --args='--spring.profiles.active=worker-demo,container'
```

Once deployed, verify the container came up on the host; the call site is identical to in-process (the main process
forwards to the container's published port via `ReverseProxy`):

```bash
docker ps                                         # find the protean-worker-compute-... container
curl 'localhost:8080/compute/square?n=7'          # → square=49  (served by the container worker)
```

**A module deployed via MCP, in a container** — bring it up with `mcp,container`, then deploy a self-contained module
(e.g. `mcp-hello`) via the `deploy_module` from ③ above, and that module runs in its own dedicated container:

```bash
gradle bootJar
gradle :examples:quickstart:bootRun \
  --args='--spring.profiles.active=mcp,container'
# after deploying deploy.json (③):
docker ps                                         # find the protean-worker-mcp-hello-... container
curl 'localhost:8080/mcp/hello?name=abc'          # → hello ABC
```

The default hardening (`--read-only`, `--tmpfs /tmp`, `--cap-drop=ALL`, `no-new-privileges`, `--memory`, `--pids-limit`)
is always applied. Egress blocking (`network`) and the seccomp profile (`seccomp: bundled`) are added via the options in
the `application-container.yml` comments. For the isolation trade-offs, see [05-isolation-modes](../../docs/guide/05-isolation-modes.md).

### ⑥ MCP + worker isolation + Level 3 debugging

A combination that enables the MCP deployment entry point + `debug.*` interactive debugging (JDI) + worker isolation at
once. The `debug` profile activates the set together via `application-debug.yml`.

```bash
gradle :examples:quickstart:runMcpDebug
# or
gradle :examples:quickstart:bootRun --args='--spring.profiles.active=debug'
```

> **Activation-key caution**: the debug surface separates **exposure** from **execution**.
> - **Exposure** is automatic when `protean.mcp.enabled=true` — `DebugMcpConfiguration`'s `@ConditionalOnProperty` is
>   bound to `protean.mcp.enabled` (not a debug key), so whenever MCP is on, the `debug.*` tools always appear in `tools/list`.
> - **Execution** is turned on separately via **`protean.mcp.debug.enabled` (dotted, default false, runtime-togglable)**.
>   When off, debug calls are rejected at the dispatcher with `debug surface disabled`. You must use the dotted form —
>   `protean.mcp.debug-enabled` (hyphenated) is treated as a different key that doesn't bind, leaving the surface off (default false).

First check that the `debug.*` tools (attach · set_breakpoint · await_stop · frames · get_variables · evaluate · step ·
continue · redefine · terminate) are exposed in `tools/list` (they appear whenever MCP is on, regardless of `debug.enabled`):

```bash
curl -s localhost:8080/platform/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | grep -o 'debug\.[a-z_]*' | sort -u
```

> Tools being exposed is separate from being **executable** — confirm the surface is actually active by invoking a debug
> tool and checking there's no `debug surface disabled` rejection.

Typical flow: `debug.launch` (deploy a module to a JDWP worker + auto-attach) → `debug.set_breakpoint` →
`debug.await_stop` → `debug.frames` / `debug.get_variables` / `debug.evaluate` → `debug.step` / `debug.continue` →
`debug.terminate`. Idle sessions are reclaimed automatically when they exceed
`protean.mcp.debug.session-idle-timeout` (default 30m). For a hands-on debugging walkthrough, see
[09-debugging](../../docs/guide/09-debugging.md).

## Run-command summary

Each scenario is registered as a named gradle task (`build.gradle`), so you can run it directly without a long `--args`.
Use the task names in the table below; the raw `--args` commands remain valid too.

| Scenario | Task | Equivalent raw command |
|----------|--------|------------------|
| in-process data access (default) | `runInProcess` | `bootRun` |
| worker isolation + auto-deploy | `runWorkerDemo` | `bootRun --args='--spring.profiles.active=worker-demo'` |
| MCP deployment entry point | `runMcp` | `bootRun --args='--spring.profiles.active=mcp'` |
| MCP + worker isolation | `runMcpWorker` | `bootRun --args='--spring.profiles.active=mcp --protean.isolation.mode=worker'` |
| MCP + approval gate | `runMcpApproval` | `bootRun --args='--spring.profiles.active=mcp --protean.gate.approval.required=true'` |
| MCP + worker isolation + debugging (Level 3) | `runMcpDebug` | `bootRun --args='--spring.profiles.active=debug'` |
| container isolation (compute module) · Docker required | `runContainerCompute` | `bootJar && bootRun --args='--spring.profiles.active=worker-demo,container'` |
| container isolation (MCP deploy) · Docker required | `runContainerMcp` | `bootJar && bootRun --args='--spring.profiles.active=mcp,container'` |

> Prefix the task name with the project path: `gradle :examples:quickstart:runMcpWorker`.
> The `run*` tasks are all short aliases for `bootRun`, and the `bootRun`/`bootJar` in the raw-command column take the same project path.
> The container tasks build `bootJar` first automatically, so no separate `gradle bootJar` is needed.
> To pass several overrides yourself, list them space-separated inside one `--args` (e.g. `--spring.profiles.active=mcp --protean.isolation.mode=worker --protean.gate.approval.required=true`).
> To stop the server once it's up, use `Ctrl-C` (foreground) or `pkill -f QuickstartApplication` (plus the worker: `pkill -f ProteanWorkerLauncher`).

## What this example demonstrates

- Protean auto-wiring into a library-consumer app via **auto-configuration**
- Deploying a module carried as a **source string** through `ModulePlatform.install` (passing promotion gates ①test · ②review)
- Switching the same app between **in-process / worker / MCP** by configuration alone
- A module accessing data by receiving the host's `JdbcTemplate` parent-first

## Related docs

- [User guide](../../docs/guide) — 01 Getting started · 05 Isolation modes · 07 Data access · 08 MCP integration
- [README](../../README.md) — project overview
