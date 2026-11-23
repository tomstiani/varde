# Structured backend observability

Approved implementation spec for [issue #33](https://github.com/tomstiani/varde/issues/33). Requirements, test boundaries and implementation decisions are approved. Nothing in this document claims implementation or verification.

## Problem Statement

The operator can inspect Docker logs but cannot currently diagnose rejected malformed alerts from them. HTTP failures are returned to callers without a corresponding request log. Existing worker recovery events cover only part of the backend and lack a common timestamp/severity envelope.

The operator needs safe, correlated logs that an external service can collect alongside other services, without deploying Varde-specific observability infrastructure or risking alert processing when log output stalls.

## Solution

Emit structured, best-effort JSON diagnostics to stderr. Summarize HTTP responses, correlate them with a response request ID, explain validation failures safely, and normalize server/worker events. Preserve existing API semantics, stdout consumers, worker recovery policy and request/shutdown budgets.

## User Stories

1. As an operator, I want backend logs in Docker output, so that my existing collection service can ingest them.
2. As an operator, I want one JSON object per line, so that collection does not depend on parsing prose.
3. As an operator, I want timestamps and severity, so that I can order events and prioritize failures.
4. As an operator, I want successful request summaries, so that I can distinguish successful handling from requests that never arrived.
5. As an operator, I want rejected requests logged, so that malformed alerts do not disappear from diagnostics.
6. As an operator, I want safe field/rule explanations, so that I can correct a sender without exposing its payload.
7. As an operator, I want safe malformed-JSON explanations, so that parsing failures remain diagnosable without recording raw input.
8. As an API caller, I want a response request ID, so that I can identify the matching backend log.
9. As an operator, I want status and duration from the actual response, so that timeout logs do not incorrectly report late success.
10. As an operator, I want unexpected failures distinguished from request rejection, so that internal faults are visible at error level.
11. As an operator, I want background failures and recovery events in the same format, so that I can diagnose processing independently of HTTP acceptance.
12. As an operator, I want startup and shutdown events, so that I can recognize process lifecycle changes.
13. As an operator, I want credentials and alert content excluded, so that ordinary log collection does not become a private-data archive.
14. As an operator, I want logging buffers bounded, so that a stalled collector cannot exhaust memory.
15. As an alert sender, I want log backpressure isolated from alert processing, so that accepted work continues when logs cannot be written.
16. As an operator, I want bounded diagnostic shutdown, so that a full stderr pipe cannot prevent process exit.
17. As an operator, I want documented loss semantics, so that missing logs are not mistaken for proof that no event occurred.
18. As an operator using local commands, I want existing stdout results preserved, so that scripts and secret issuance remain compatible.

## Implementation Decisions

### Ownership and interfaces

- Keep validation facts and safe rule explanations inside the lifecycle module. Reuse its existing sanitized failure categories, known field names and rule messages; do not infer rules by reparsing request bodies in logging code.
- The HTTP adapter owns request correlation, route classification, response status/duration and HTTP failure metadata.
- The application owns one shared bounded diagnostic writer for server execution and adapts existing worker events into it. Use Go's standard structured logging facilities where helpful, behind the bounded emission boundary; do not add an external logging dependency or framework.
- Allow the HTTP adapter to receive a narrow diagnostic emitter while keeping ordinary construction convenient for existing callers. Do not introduce a database abstraction or expose diagnostic machinery through lifecycle business operations.
- No persistence schema changes, alert retries or business-policy changes.

### Structured output

- Emit UTF-8 JSON objects separated by newlines to stderr. Each structured entry has `timestamp` (UTC RFC 3339 with fractional precision), `level` (`info`, `warn`, or `error`), and `event` (fixed name).
- Keep existing command-result JSON and the single `listening` readiness record on stdout unchanged. Emit a separate `server_started` stderr event after successful binding; it supplements rather than replaces readiness.
- HTTP summaries use event `http_request_completed`, with `requestId`, `method`, `route`, `status`, and nonnegative `durationMilliseconds`. Successful responses use `info`; 4xx use `warn`; 5xx use `error`. This includes expected 503 capacity/timeouts: severity signals server inability to serve, not necessarily a software defect.
- Known failures add `category`, and, where available, `field` and `reason`. Reasons come from safe policy explanations, not arbitrary error strings. A generic fixed JSON-validation explanation is sufficient; finer parser diagnostics are not required.
- Unknown failures use a fixed sanitized category/reason. Panic values, stack traces, SQL errors and provider error text are never logged.
- Existing worker event names and `stage`, `category`, `retryCount`, `elapsedMilliseconds` fields remain compatible; add the common envelope. Recovery started/waiting are `warn`, recovery succeeded is `info`, and worker stopped is `error`. Preserve existing recovery cadence and counter semantics.
- Emit `server_stopping` and `server_stopped`, with a fixed safe stop reason. Normal shutdown uses `info`; unexpected server termination uses `error`. Startup errors before readiness get a best-effort `server_start_failed` entry with a fixed safe category.
- Retain the existing bounded, sanitized final CLI failure message as a compatibility exception to JSON stderr. Document that collectors may encounter this text and Docker may combine it with stdout readiness. Do not change credential/operator command output in this milestone.

### Correlation and privacy

- Generate a fresh, opaque server-owned request ID for every request entering the HTTP adapter. Return it as `X-Request-ID`, including error and timeout responses. Ignore inbound request-ID headers; do not trust or echo client-controlled identifiers.
- Use the same ID for any related request diagnostics. Do not log alert IDs, credential IDs, client addresses or user agents merely because they are available.
- Record only registered route patterns, never raw paths, path parameters or query strings. Unmatched routes use `unmatched`; unsupported methods use a safe route classification or `unmatched`. Nonstandard methods are represented by a fixed `OTHER` label rather than echoed input.
- Use known, allowlisted failure fields/categories only. Unknown user-supplied JSON keys must not appear as field names. Omit unavailable fields rather than fabricating precision.
- Never record authorization headers, credentials, bodies, rejected values, cursors, destinations, provider responses or arbitrary internal error text. Do not inspect response/request bodies to manufacture summaries.

### Response and concurrency correctness

- Emit exactly one completion summary per request that completes through the HTTP adapter, not separate access and rejection entries. Exclude routine health routes if any exist; adding one is out of scope.
- Measure elapsed time with a monotonic clock and summarize the response actually committed by the outer response boundary, including implicit 200 and router-generated errors.
- Keep request-ID headers and summary recording outside the existing timeout response boundary. Deadline responses must log the timeout 503 even if inner work later finishes successfully.
- Make metadata transfer from timeout-managed handler work race-safe. Freeze the summary at outer completion and ignore late metadata; do not wait for inner work just to log it.
- Preserve safe panic handling. Record a fixed panic category without the recovered value. A panic before commitment retains the existing sanitized failure response; a panic after commitment records error-level failure with the status already sent rather than claiming it changed to 503.
- Preserve response-controller unwrapping, cancellation, existing JSON error bodies, security headers and five-second request budgets. A timeout still does not prove submission failed to commit.
- HTTP-server internal diagnostics must not bypass sanitization. Do not enable the default raw server error logger; any bridge must emit fixed safe events without forwarding arbitrary text.
- Connections rejected before a request reaches the adapter are not guaranteed a request summary. Full wire-level access logging is not part of this milestone.

### Bounded output and loss

- Use a fixed-capacity queue of 256 bounded event records and one continuous serialization/output writer per server. Request producers never wait for queue space or sink I/O. Do not spawn a goroutine per event.
- When full, drop the incoming record; do not retain bodies or unbounded strings. Once the writer reports an output error, stop attempting sink output and discard subsequent records without blocking producers.
- Normal shutdown permits at most 100 milliseconds of best-effort flushing, within the existing shutdown allowance. A permanently blocked arbitrary writer cannot be interrupted; do not wait for it indefinitely or repeatedly create replacement writers.
- Synchronize emitter shutdown with concurrent producers; closing must not panic even if timeout-managed work finishes late. No durable log-delivery or complete-audit guarantee is made. Drop counters/metrics and priority queues are not required for this milestone.

## Testing Decisions

The user approved two primary boundaries. Tests assert externally observable behavior, not private queue layout or helper calls.

### Running backend over HTTP with real temporary SQLite

- Follow existing submission/validation, real HTTP deadline, process recovery and stdout-separation test patterns.
- Verify valid submissions and reads produce compact summaries with matching response IDs and distinct IDs across requests. An incoming forged request-ID header must not be echoed.
- Cover invalid JSON, missing/invalid known fields, unknown user-controlled keys, authentication/authorization failures, conflicts, router 404/405 and server failures. Verify statuses and existing API contracts remain unchanged.
- Verify fixed safe explanations without raw parser/storage errors; use synthetic sentinel secrets in bodies, query strings, paths, headers, unknown keys and inbound IDs, then assert they never appear in diagnostics.
- Verify actual timeout responses yield one 503 summary and no late duplicate success entry. Exercise panic safety and no raw panic output. Use existing safe fault-injection seams where necessary, not a production fault endpoint.
- Verify worker recovery event compatibility, startup/shutdown ordering where observable, parseable structured records and unchanged stdout results/readiness.
- Run the actual container and inspect Docker/Compose logs using only synthetic data. Confirm a malformed authenticated submission yields the correlated warning record. Record this operator acceptance separately from automated test results; lack of Docker access leaves it explicitly unverified.

### Application runner with an injected diagnostic writer

- Extend existing stalled/failed-writer tests to include request summaries and shared worker diagnostics.
- Generate more events than queue capacity while the sink is blocked; assert request completion, fallback progress and bounded shutdown still occur. Release test writers during cleanup.
- Assert output failure does not stop accepted work, new submissions or shutdown. Exercise producer/shutdown races under the race detector.
- Do not test exact scheduling, require every best-effort event under saturation, or expose private buffering just to count allocations. Verify the fixed bound by implementation review alongside behavioral saturation tests.

Run the backend's normal tests, race tests, vet and demo workflow. Update operator documentation with the delivered schema, stdout/text exceptions, correlation policy, privacy guarantees and loss limitations. Do not replace real container acceptance with unit-test success.

## Out of Scope

Metrics endpoints, dashboards, tracing, collectors/exporters, external-service configuration, Android logging, user-submitted bug reports, new health endpoints, full connection-level access logging, durable audit logs, configurable logging subsystems, paid processing changes, retry-policy changes and storage migrations.

## Further Notes

This spec refines the approved observability requirements. GitHub issue #33 remains the progress authority; tracked documents contain contracts, not duplicated completion checklists. Existing worker recovery contracts must be updated alongside implementation to describe the expanded envelope without changing retry behavior. This milestone does not claim broad production readiness or delivery guarantees for logs.
