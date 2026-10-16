# Backend worker recovery

Approved operational hardening of the existing plain-text backend. This does not implement the other architecture-review candidates.

## Recovery contract

- Classify SQLite errors inside the lifecycle implementation, before sanitizing them. Retry only the `SQLITE_BUSY` primary-code family, including extended results. Do not infer retryability from error text or retry `SQLITE_LOCKED` blindly.
- Retry the whole failed worker transaction only after successful cleanup. If rollback cannot be confirmed, discard the connection and stop; never put an uncertain transaction back into the pool.
- Retry known contention until cancellation. Do not expire retained originals or unfinished work, or impose an attempt-count limit.
- Use exponential backoff with nominal delays of 1, 2, 4, 8, 16 and 30 seconds, capped thereafter. Each actual delay is sampled uniformly from 80–100% of its nominal delay. SQLite's own wait is additional.
- A successful worker transaction ends recovery, even if it finds no eligible work or leaves a backlog. Reset the backoff and recovery counters.
- Non-retryable worker failures, including corruption, permission failures and disk-full failures, remain fatal to the backend. Do not retry every `storage_unavailable` response.
- Cancellation interrupts the backoff immediately. Stop the worker without draining retained work. In-flight SQLite cancellation can take up to its configured wait plus cleanup/scheduling time.

This retry policy belongs only to the fallback worker. HTTP and local operator commands gain no hidden application-level retries. It must not become an unlimited retry policy for future paid execution.

## Request and shutdown budgets

- SQLite waits up to one second for a lock instead of five seconds.
- The HTTP adapter imposes a five-second response/processing budget after headers are accepted. This includes authorization, body reading and connection-pool wait, not just SQL execution.
- Timeouts return safe JSON `503 storage_unavailable`, `Retry-After: 5`, and `Cache-Control: no-store`. A deadline/cancellation race must not produce an empty or HTML error body.
- Preserve the existing outer server network deadlines. A slow receiver can still be constrained by the network write deadline; do not claim delivery over an arbitrary network within five seconds.
- Cancel timed-out work. The driver may need brief cleanup after the response deadline; never interpret a context deadline alone as proof that SQLite stopped immediately.
- A submission timeout does not prove nonacceptance. Retry the same sender event ID; existing duplicate semantics remain authoritative.
- Preserve the five-second HTTP shutdown allowance. Worker backoff must not hold shutdown open, and normal writer contention must not require releasing the external lock before shutdown.

## Diagnostics

Operational events use stderr. Stdout remains reserved for command JSON and the single server readiness record. Credential issuance still appears only in its explicit stdout result.

| Event | Meaning |
| --- | --- |
| `worker_recovery_started` | First known contention failure in this recovery period |
| `worker_recovery_waiting` | Continued failure, at most once per minute after the last recovery report |
| `worker_recovery_succeeded` | First successful worker transaction after contention |
| `worker_stopped` | Non-retryable worker failure |

Events contain only `event`, fixed `stage: fallback_worker`, safe `category`, `retryCount` and `elapsedMilliseconds`. The initial failure has retry count zero. A successful retry is counted. Elapsed time describes worker recovery, not whole-backend health.

Use finite buffering and one continuous event writer per server. Dropping events under backpressure or writer failure is acceptable. Diagnostic output must not block processing or shutdown. An arbitrary `io.Writer` cannot be forcibly interrupted; do not create a goroutine per event or wait indefinitely for a blocked writer. Normal shutdown allows a short best-effort flush within the remaining shutdown allowance. The final sanitized command-error message also has a bounded write attempt, so a full stderr pipe cannot prevent failure exit.

## Approved test seams

1. **Lifecycle interface with real temporary SQLite:** retained work, whole-transaction recovery, fatal classification and cleanup safety. Controlled SQLite faults and deterministic timing are permitted; do not test private helper functions as the behavior interface.
2. **Running executable and real HTTP/TCP:** response timing, queue/body deadlines, JSON safety, recovery without restart, stdout separation and shutdown under contention.
3. **Application runner with a diagnostic writer:** stalled/failed sinks cannot prevent fallback processing or shutdown.

Tests exercise an hour of retry behavior with virtual time, not a wall-clock hour. Real process tests use synthetic credentials kept out of command arguments and synthetic temporary databases. They do not contact a phone, homelab, n8n or paid provider.

## Deliberately excluded

- Startup retries or migration execution-policy changes; the existing initialization budget remains.
- Uninterrupted authenticated reads while another SQLite writer holds a lock, or a new read-connection architecture.
- Backup/restore, production packaging/supervision, Android distribution or original remote HTTPS/n8n acceptance.
- Paid generation, push, typed data, change sync or any new deployment tier.
