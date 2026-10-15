# Homelab Notifications

A self-hosted backend and native Android app for turning homelab alerts into more glanceable notifications and rich in-app views.

The project is at the idea stage. This README describes the intended product; it does not describe a working implementation.

## Why

Homelab services and agents can send useful alerts, but a stream of plain-text notifications is often hard to scan. This project aims to present alerts with relevant visual information—such as metrics, charts, tables, and diagrams—without requiring every alert source to build its own interface.

## How it should work

1. An alert source, such as an n8n workflow, sends an alert to the self-hosted backend.
2. The backend uses a hosted model API to create a declarative JSON layout from the alert. The model selects and arranges approved components; it does not send executable JavaScript or native code.
3. The backend stores the original alert and generated layout in alert history.
4. A push service signals the Android app that an alert is ready.
5. The app shows an Android notification with a plain-text fallback. Tapping it opens the rich view, which the app fetches from the backend.

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

The implementation has not chosen specific push or model providers, a backend or Android technology stack, or the JSON component schema. These choices should be made during planning and implementation.
