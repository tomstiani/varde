# Varde

Varde aims to turn homelab alerts into glanceable notifications and rich native Android views, without requiring each alert source to build its own interface.

This branch is a documentation-only reset. It retains the product vision, requirements and specifications, glossary, and agent instructions. Application code, deployment and CI configuration, investigations, prototypes, and implementation runbooks have been removed. The previous implementation remains in Git history.

## Product vision

- Easy self-hosting with Docker.
- An authenticated endpoint for alerts from homelab services and workflows such as n8n.
- A native Android app with alert history and clear source identity.
- Useful rich views built from approved components, such as text, metrics, charts, tables, and simple flows.
- Original alert text available independently of rich-view generation.
- Model-selected presentations grounded in supplied data, without executable generated code or invented measurements.
- Read-only alerts initially, with interactive actions deferred.

The backend platform, push-delivery mechanism, A2UI adoption, and smallest MVP scope remain open decisions. Discussion of these options does not establish an implementation commitment.

## Retained specifications

These documents preserve the earlier requirements, including implementation-specific contracts and historical status references. They have not been reduced to a new MVP or revalidated as the reset's architecture. Links to removed code, investigations, architecture documents, runbooks, and verification reports are historical references and may no longer resolve on this branch.

- [Android combined inbox](docs/specs/android-combined-inbox.md)
- [Inbox implementation tickets](docs/specs/android-inbox-tickets/)
- [Android alert notifications and recovery](docs/specs/android-alert-notifications.md)
- [Notification implementation tickets](docs/specs/android-notification-tickets/)
- [Backend worker recovery](docs/specs/backend-worker-recovery.md)
- [Backend observability](docs/specs/backend-observability.md)

## Project guidance

- [Domain glossary](GLOSSARY.md)
- [Agent instructions](AGENTS.md)
- [Coding standards](CODING_STANDARDS.md), retained for future implementation; code paths and verification commands describe the previous implementation.
- [Domain documentation guidance](docs/agents/domain.md)
- [Issue tracker guidance](docs/agents/issue-tracker.md)
- [Triage labels](docs/agents/triage-labels.md)
