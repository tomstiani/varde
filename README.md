# Homelab Notifications

A self-hosted backend and native Android app for turning homelab alerts into more glanceable notifications and rich in-app views.

The backend uses Go, standard `net/http`, and SQLite: authenticated plain-text submission, durable originals, duplicate recovery, restart-safe text fallback, and bounded history reads. The native Android app combines alert history from named connections with manual refresh, explicit older-history browsing, source labels, secure settings, plain-text details, and explicit recovery from partial outages. [App-opening missed-alert recovery](docs/how-to/recover-alerts.md) adds source-specific catch-up notifications without downloading original bodies. This is not the complete notification or rich-view experience. [Run the backend](docs/how-to/run-backend.md), [deploy on Linux with Compose and Pangolin](docs/how-to/run-compose.md), [build the Android app](android/README.md), or [try two local backends on a phone over USB](docs/how-to/local-acceptance.md).

[Alert-view experiment findings](docs/research/alert-view-experiments.md) are retained as documentation; runnable experiments and prototypes are intentionally not committed.

The fallback worker recovers automatically from temporary SQLite writer contention. HTTP requests have a five-second processing/response budget after headers; recovery diagnostics cannot block processing or shutdown. See the [worker recovery contract](docs/specs/backend-worker-recovery.md) for precise guarantees and limits.

See the [backend architecture proposal](docs/architecture/backend.md) for the agreed contracts, proposed modules, failure handling, and deferred choices.

## Repository layout

This is a monorepo so backend and Android interface changes can be reviewed together. Each application keeps its own build system and dependencies.

```text
backend/   Go module: executable, lifecycle, HTTP adapter, tests, local demo
android/   Native Kotlin/Compose app: named connections and combined alert history
docs/      Shared architecture, research, and operational documentation
```

Run Go commands from `backend/` and Gradle commands from `android/` using its checked-in wrapper. There is no root Go module or workspace, and no shared runtime library.

See [coding standards](CODING_STANDARDS.md) for the human-readability and package-organization conventions. [Pull-request verification](docs/how-to/check-ci.md) describes automated checks and failure reports; [tracker consistency](docs/how-to/keep-tracker-current.md) explains completion, read-only audits and required-check enforcement.

## Why

Homelab services and agents can send useful alerts, but a stream of plain-text notifications is often hard to scan. This project aims to present alerts with relevant visual information—such as metrics, charts, tables, and diagrams—without requiring every alert source to build its own interface.

## How it should work

1. An alert source, such as an n8n workflow, sends an alert to the self-hosted backend.
2. The backend durably retains the original alert and pending processing before acknowledging acceptance.
3. Optional backend-owned BYOK enables pinned Jev selection over candidates prepared from supplied typed facts. The backend assembles and validates a reference-only rich view, or falls back as a whole to unchanged original text. Plain-text-only alerts bypass generation; models never author measurements or executable code.
4. Best-effort push signals the Android app after durable acceptance. Android immediately shows a generic notification, then fetches original text and silently enriches it; push delivery itself is not implemented yet.
5. Authenticated change sync recovers missed arrivals and later presentation updates. The app loads history on demand and opens a rich view or the original text.

The backend runs in the user's homelab. The app reaches it over the user's chosen network path, such as Pangolin, Tailscale, or the public internet. The project does not depend on a particular network access service.

## Initial scope

- A separate self-hosted backend for each user.
- A native Android client.
- An authenticated alert-ingress endpoint for sources such as n8n.
- A backend-neutral alert interface, so ntfy is not required.
- Model-generated layouts built from reusable, approved components.
- Native Android notifications and richer in-app views.
- Persistent alert history containing both the original alert and generated layout.
- Separate alerts initially; grouping related alerts may be considered later.
- Read-only alerts initially, with room for interactive actions in the future.
- A plain-text notification and view when generation is slow or fails.

Alerts should include an explicit priority or severity. Initially, the model uses the alert payload only; fetching additional context from homelab services is out of scope.

## Current status and remaining work

- **Implemented:** Go/`net/http`/SQLite backend, Kotlin/Compose named-connection inbox, older-history browsing, explicit partial-outage recovery, local alert notifications, foreground change recovery and per-connection catch-up. Linux Compose setup and four-job PR CI are available.
- **Approved rich-view contracts, not runtime implementation:** [#39](https://github.com/tomstiani/varde/issues/39) defines optional typed input, Jev `jev-1.13.0` with backend-owned BYOK, deterministic whole-view validation/fallback and one current backend/Android contract without backward-version support. [#38](https://github.com/tomstiani/varde/issues/38) owns the implementation slices. No live model evaluation or production enablement is established.
- **Agreed notification policy:** FCM first behind replaceable provider Adapters; minimal signals, immediate generic notifications and silent fetched-detail enrichment. Registration/controls, actual FCM delivery, background recovery and integrated acceptance are tracked under [#22–#25](https://github.com/tomstiani/varde/issues/19). Use the linked tracker for current task state and dependency readiness.
- **Still deferred:** rich-view/BYOK implementation, separately authorized live quality/latency evaluation, production generation enablement, supported backup/restore and full production operations. Real two-backend transport tests and physical-phone USB inbox setup/browsing are verified; original remote HTTPS/n8n and full live-phone lifecycle acceptance remain deferred in #16.

See the [architecture](docs/architecture/backend.md), [approved rich-alert contracts](docs/specs/rich-alert-contracts.md), [inbox specification](docs/specs/android-combined-inbox.md), and [notification/recovery specification](docs/specs/android-alert-notifications.md) for contracts and milestone boundaries.
