**English** | [한국어](README.ko.md)

# Protean User Guide

A collection of practical how-tos for **Spring Boot developers** (library consumers) who use Protean as a dependency.
The focus is on "how to do it".

The documents are numbered in reading order. If you're new, start with [01. Getting Started](01-getting-started.md).

## Core

| # | Doc | Contents |
|---|------|------|
| 01 | [Getting Started](01-getting-started.md) | End-to-end: add the dependency → auto-configuration → deploy a Hello module → verify → uninstall |
| 02 | [Module Authoring](02-module-authoring.md) | Writing a `ModuleDescriptor` / `module.yaml`, sources/tests/resources, controller contract, child-context DI, forbidden APIs |
| 03 | [Configuration Reference](03-configuration.md) | The full `protean.*` properties (key · default · description) |
| 04 | [REST API Reference](04-rest-api.md) | Control-plane endpoint request/response · status codes · errors |

## Feature Guides

| # | Doc | Contents |
|---|------|------|
| 05 | [Isolation Modes](05-isolation-modes.md) | Choosing/setting up in-process / worker / container, hot-swap · pool · supervision · RPC bridge |
| 06 | [Promotion Gates](06-promotion-gates.md) | Operating the tests · review · verify gates, signature/approval workflows |
| 07 | [Data Access](07-data-access.md) | Driver bundling · resource channel · module DataSource · worker DB · transactions · managed execution |
| 08 | [MCP Integration](08-mcp-integration.md) | Remote server security posture, Bearer/OAuth 2.0 setup, connecting & driving a client (HTTP/stdio), tool catalog, auth delegation |
| 09 | [Debugging](09-debugging.md) | Hands-on Level 3 debugging (launch/attach/breakpoint/step/evaluate/redefine) |
| 10 | [SPI Extension](10-spi-extension.md) | Extension points opened via bean registration (`CodeRule` · `DbDialect` · `ModuleStoreDialect` · `ModuleActionAuthorizer` · `WorkerRuntimeProvider` · `ModuleUnloadCallback`) |

## Operations · Auxiliary

| # | Doc | Contents |
|---|------|------|
| 11 | [Operations](11-operations.md) | Persistent store · reconcile · traces · timeouts · leak avoidance · build/publish |
| 12 | [Security](12-security.md) | Trust model from an operations perspective, sandbox non-goal, MCP/REST auth recommendations |
| 13 | [Troubleshooting](13-troubleshooting.md) | Common errors · diagnostics · FAQ |

## Related Docs

- [README](../../README.md) — project overview
