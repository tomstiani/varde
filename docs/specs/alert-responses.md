# Alert responses

**Status:** deferred requirement. Not part of the MVP and not published to the tracker. The MVP remains read-only.

**Context:** captures intended interactivity so MVP decisions do not preclude it. Relates to the open A2UI decision; see [A2UI for rich views](../research/a2ui.md).

## Problem Statement

A person reading an alert often wants to act on it: mark it handled, silence it temporarily, trigger a remedy, answer a workflow's question, or jump to the relevant service. Without responses, they must leave Varde and find the right tool themselves.

## Response types

| Type | Handled by | Presentation |
| --- | --- | --- |
| Acknowledge, resolve, snooze | Homelab backend only; no source support needed | Native controls on every alert and on notifications, independent of rich views |
| Trigger a source action | Source-declared action forwarded to the alert source | Rich-view button, with native fallback |
| Answer a question | Source-declared question; answer forwarded to the alert source | Rich-view inputs and submit button, with native fallback |
| Open a declared link | Source-supplied URL | Rich-view link, restricted to declared URLs |

An n8n workflow paused on a Wait node, resumed by webhook, is a likely first source for actions and questions.

## Rules

1. **Sources declare actions.** An alert carries its available actions and questions, each with an identifier, a callback, and a parameter schema. Rich-view generation may place a declared action but never create one.
2. **The backend authorizes every response.** It checks the action identifier, parameters, expiry and prior handling before forwarding.
3. **Responses work without rich views.** An original alert and its declared actions remain usable when rich-view generation fails or is absent.
4. **Destructive actions require native confirmation** that generated layout cannot bypass.

## Design concerns

- Response states: pending, sent, accepted, failed, rejected.
- Idempotency across repeated taps and offline retries.
- Expiry once an alert is resolved or superseded.
- Credentials permitting responses, separate from read-only history access.
- An audit trail of responses.
- Notification quick actions using the same declarations.

## Suggested sequence

1. Native acknowledge, resolve and snooze, establishing delivery, idempotency and authorization.
2. Action and question declarations in the alert contract, with backend validation, callback forwarding and native fallback controls.
3. Interactive rich-view catalog version: Button, inputs and restricted `openUrl`.
4. Notification quick actions.

## MVP constraints

- The alert contract should tolerate a future optional `actions` field rather than rejecting unknown fields.
- The read-only rich-view catalog should be versioned so an interactive version can be added alongside it.
