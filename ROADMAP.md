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
| 🔎 Candidate | Under evaluation — **support not yet decided**. Captured so the trade-off is on the record. |
| 🚫 Not planned | Considered and deliberately declined (with the reason). |

---

## Observability

Runtime request tracing and per-module metrics. See the operations guide
([docs/guide/11-operations.ko.md](docs/guide/11-operations.ko.md#요청-트레이스--모니터링))
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
