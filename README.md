# Homelab Notifications

A self-hosted backend and native Android app for turning homelab alerts into more glanceable notifications and rich in-app views.

The first backend slice uses Go, standard `net/http`, and SQLite: authenticated plain-text submission, durable originals, duplicate recovery, and restart-safe text fallback. It is not the complete backend or Android experience. [Run and test the local backend](docs/how-to/run-backend.md).

[Alert-view experiment findings](docs/research/alert-view-experiments.md) are retained as documentation; runnable experiments and prototypes are intentionally not committed.

See the [backend architecture proposal](docs/architecture/backend.md) for the agreed contracts, proposed modules, failure handling, and deferred choices.

## Repository layout

This is a monorepo so backend and Android interface changes can be reviewed together. Each application keeps its own build system and dependencies.

```text
backend/   Go module: executable, lifecycle, HTTP adapter, tests, local demo
android/   Native Android app (not implemented yet)
docs/      Shared architecture, research, and operational documentation
```

Run Go commands from `backend/`; there is no root Go module or workspace. No shared runtime library or Android build scaffolding is introduced yet.

See [coding standards](CODING_STANDARDS.md) for the human-readability and package-organization conventions.

## Why

Homelab services and agents can send useful alerts, but a stream of plain-text notifications is often hard to scan. This project aims to present alerts with relevant visual information—such as metrics, charts, tables, and diagrams—without requiring every alert source to build its own interface.

## How it should work

1. An alert source, such as an n8n workflow, sends an alert to the self-hosted backend.
2. The backend durably retains the original alert and pending processing before acknowledging acceptance.
3. Hosted models select supplied data for approved components. The backend assembles and validates a declarative JSON layout, retaining it or falling back to the original text. Models do not send executable JavaScript or native code.
4. Best-effort push signals the Android app; timing relative to generation remains undecided.
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

## Not decided yet

The backend uses one Go application, standard `net/http`, and SQLite. Concrete push delivery, production model configuration, notification timing, typed-data/layout wire formats, and Android implementation details remain undecided. See the architecture proposal before extending the first slice.
