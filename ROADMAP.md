**English** | [한국어](ROADMAP.ko.md)

# Protean Roadmap

Forward-looking items for the Protean platform. Because Protean is a **library** —
consumers cannot extend internals at use-time — feature completeness is favored over
short-term ROI. Some items below are therefore **support candidates whose inclusion is
still being decided**, recorded here so the decision is explicit rather than implicit.

This is a living document; it is seeded with the tracks that have concrete, grounded
items today and grows as new ones arise. It is not a release schedule.

## Legend

| Status | Meaning |
|---|---|
| ✅ Shipped | Implemented and on `main`. Listed for context. |
| 🛠 Planned | Committed to ship, not yet scheduled or implemented. |
| 🔎 Candidate | Under evaluation — **support not yet decided**. Captured so the trade-off is on the record. |
| 🚫 Not planned | Considered and deliberately declined (with the reason). |

---

## Platform compatibility (Spring Boot / Java)

Protean runs as a library *inside* the consumer's Spring Boot application, and it
manipulates Spring MVC/context internals (dynamic mapping registration, child
application contexts, handler-adapter cache purging). Those internal touch-points
make the supported Spring Boot line an explicit contract, not an incidental detail.

### ✅ Current baseline

- **Spring Boot 3.5.x on Java 21.** The supported and tested line — Protean is
  compiled against it and CI runs the full suite on it.

### 🛠 Planned — Spring Boot 4.x support

Because Protean is a library (completeness over ROI — consumers cannot patch
internals at use-time), **supporting Spring Boot 4.x is a commitment, not an open
question.** It is simply not implemented yet: Boot 4 (Spring Framework 7) is a major
release with breaking changes, and Protean's deep MVC/context coupling means a
consumer on 4.x *today* would hit runtime linkage failures (e.g. `NoSuchMethodError`),
possibly latent (surfacing only when a specific path such as module unload runs).
Until the migration lands, Protean is **pinned to 3.5.x**.

The migration will be designed and discussed as its own track — a compatibility
surface audit, and likely a startup version guard so an unsupported line fails fast
with a clear message instead of a cryptic crash. Recorded here so the intent is
explicit; the design is deferred to that track.

### 🚫 Not planned

- Auto-accepting Dependabot **major** Spring Boot bumps. A major-version move is a
  deliberate migration (see above), not an automated dependency bump.

---

## Observability

Runtime request tracing and per-module metrics. See the operations guide
([docs/guide/11-operations.md](docs/guide/11-operations.md#request-traces--monitoring))
for the current surface.

### ✅ Shipped

- Runtime request trace ring buffer + query API (`GET /platform/traces`, filters
  `limit`/`moduleId`/`errorsOnly`/`status`/`minLatencyMs`/`since`/`beforeSeq`).
- Opt-in per-module aggregated metrics (`GET /platform/traces/metrics`,
  `protean.trace.metrics.enabled`).
- Correlation id (`traceId`) shared across trace, logs, and RFC 9457 error bodies.
- **Per-module attribution for worker/container routes.** Proxied (worker/container)
  routes now record their `moduleId` in the main-side trace and get their own
  per-module metrics row — previously they were unattributed and folded into the
  `(platform)` bucket.

### 🔎 Candidate — support undecided

The main-side trace for a worker/container module records the **proxy-hop
(client-observed) latency**, not the module's **internal execution time**. Each worker/
container is itself a Protean app with its own `TraceStore`, but it runs under
`@Profile("!worker")`, so it does not expose `/platform/traces`, and its internal
traces are not reachable from the main platform. The following would close that gap;
whether Protean should support them is **to be evaluated** (weigh operational value vs.
coupling, security surface, and overhead):

- **B2 — Pull-back aggregation.** The main platform periodically pulls each worker/
  container's internal trace/metrics (via an internal admin endpoint) and merges them,
  keeping a single query surface. Trade-off: added endpoints on the worker admin plane,
  polling overhead, and merge semantics to define.
- **B3 — Multi-source console.** A client (e.g. the trace console) queries each worker's
  endpoint directly and stitches the views. Trade-off: requires relaxing the worker
  profile to expose trace endpoints, and raises the console's coupling to worker
  topology. *(Currently disfavored — higher coupling than B2.)*

### 🚫 Not planned (for now)

- Long-term trace persistence / external store. The ring buffer is intentionally
  in-memory and bounded; durable retention and aggregation are delegated to logs/APM.

---

*To propose a new track or move a candidate to shipped/declined, open a pull request
editing this file alongside the change.*
