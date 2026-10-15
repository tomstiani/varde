# Homelab Notifications

A self-hosted backend and native Android app for turning homelab alerts into more glanceable notifications and rich in-app views.

The project is in architecture planning. This README describes the intended product, not a working backend or Android app. [Alert-view experiment findings](docs/research/alert-view-experiments.md) are retained as documentation; runnable experiments and prototypes are intentionally not committed.

See the [backend architecture proposal](docs/architecture/backend.md) for the agreed contracts, proposed modules, failure handling, and deferred choices.

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

The agreed backend design uses one application and SQLite. The application framework, concrete push provider, production model configuration, notification timing, and detailed wire formats remain undecided. See the architecture proposal before choosing implementation details.
