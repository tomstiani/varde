# Typed rich alert views with Jev and backend-owned BYOK

**Status: approved feature specification, published as [#38](https://github.com/tomstiani/varde/issues/38).** The concrete [#39 contracts](rich-alert-contracts.md) were approved, published and delivered under explicit closure approval. They define the fields, bounds, native Jev request, configuration/execution rules and evaluation targets; this document provides the user stories and test scope. No rich-view implementation, live evaluation or production enablement is claimed.

Related decisions: [#6 hosted-model strategy](https://github.com/tomstiani/varde/issues/6), [#4 approved component contract](https://github.com/tomstiani/varde/issues/4#issuecomment-6188080441), [#1 release map](https://github.com/tomstiani/varde/issues/1). The creation and later contract reconciliation affected only the new rich-view parent/children. Research #6, release map #1, acceptance and notification issues were left unchanged.

## Problem Statement

A homelab operator can already submit durable plain-text alerts and read them in the native Android inbox. Alerts containing measurements, histories, records or explicit processing stages are harder to scan as prose. Operators need an easy way to supply those facts and obtain useful native rich views without inventing measurements, losing original alerts or making a hosted provider mandatory.

Senders should not construct UI components, prompts or a graph of globally identified facts. Operators should use their own hosted-model account/key, understand what data leaves the homelab and retain a working original-text experience during configuration failures or provider outages.

## Solution

Add optional, provider-independent typed facts to ordinary alert submission. Start with tagged measurements and optional histories, explicit records and explicit ordered sequences. The backend prepares bounded presentation candidates from supplied facts; one Jev decision request selects candidates and preferred positions. Deterministic code orders, assembles and validates the entire rich view before publication.

The Android app renders only the approved versioned ordered text, metric, single-series line-chart, table and simple-flow components. It owns styling, sizing and accessibility. Original alert metadata remains separate, unchanged and readable. Invalid selection or any invalid assembled component produces whole-view fallback to unchanged original text, never a partial rich view.

Hosted generation is optional and disabled by default. Each homelab backend operator supplies a TypeSafe key locally; initial access is pinned to `jev-1.13.0`. No shared provider contract, second provider, routing model or generative extraction model is included initially. Keep Jev-specific I/O separate from presentation decisions so a later provider can be evaluated without changing the typed fact or Android component contracts.

This is a multi-session build proposal. A bounded contract-definition prerequisite precedes implementation; the confirmed direction does not turn unresolved field-level choices into implicit requirements.

## User Stories

### Sender authoring

1. As an alert sender, I want to continue submitting ordinary title/summary alerts without typed data, so that existing simple integrations remain useful.
2. As an alert sender, I want to add one labelled measurement and optional unit without IDs or rendering instructions, so that a simple metric is easy to author.
3. As an alert sender, I want to add timestamped samples to that measurement, so that a trend can be shown without separate reference wiring.
4. As an alert sender, I want to submit history without repeating the latest value, so that I do not maintain duplicate facts unnecessarily.
5. As an alert sender, I want to supply common source metadata once and override it per entry, so that multi-measurement alerts avoid repetition.
6. As an alert sender, I want to omit unavailable units, sources and measurement times, so that I do not fabricate metadata merely to satisfy a schema.
7. As an alert sender, I want to send samples in the order my monitoring tool provides, so that I do not need an extra sorting step.
8. As an alert sender, I want to represent a missing timestamped reading explicitly, so that a chart preserves gaps rather than inventing data.
9. As an alert sender, I want to supply records as columns and rows, so that tabular information does not require component definitions.
10. As an alert sender, I want to supply an explicit ordered sequence with optional status text, so that processing stages can be presented without inferred topology.
11. As an alert sender, I want identifiers only where cross-entry references actually need them, so that I do not generate UUIDs for every entry or sample.
12. As an alert sender, I want precise, non-sensitive field-level validation errors before acceptance, so that I can correct malformed typed data instead of silently losing it.
13. As an alert sender, I want existing stable event-ID duplicate handling to continue, so that retrying an uncertain submission does not create another alert or another generation job.

### Faithful presentation

14. As an Android user, I want a rich view selected from supplied facts, so that I can scan an alert without receiving fabricated measurements or explanations.
15. As an Android user, I want the original title, summary, priority and event timestamp available unchanged, so that generated content cannot replace the authoritative alert.
16. As an Android user, I want source, unit and measurement-time distinctions preserved, so that similar labels or numbers do not imply an unsupported association.
17. As an Android user, I want each measurement history shown as a single-series chart initially, so that chart meaning does not depend on inferred cross-entry relationships.
18. As an Android user, I want a historical metric labelled with its supplied sample time, so that an old reading is not presented as current merely because an alert arrived now.
19. As an Android user, I want no latest-reading metric when the newest sample is missing, so that an older numeric value does not conceal a subsequent gap.
20. As an Android user, I want a standalone untimed value to remain untimed, so that the app does not borrow receipt, event or history time as its measurement time.
21. As an Android user, I want flows to preserve the supplied step order, so that presentation does not manufacture dependencies or change the sequence.
22. As an Android user, I want invalid or unavailable rich output to fall back as a whole, so that plausible-looking fragments of a rejected view are not shown.
23. As an Android user, I want native styling, accessible labels and bounded layouts, so that rich views remain usable on a small phone screen.
24. As an Android user, I want original-text reads and existing inbox/recovery behavior preserved while generation is pending or unavailable, so that a model outage does not make alerts inaccessible.

### Backend operation and privacy

25. As a homelab operator, I want generation disabled unless I explicitly configure and enable it, so that running Varde does not require a model account or cause unexpected disclosure.
26. As a homelab operator, I want to use my own TypeSafe key on my backend, so that I control the provider account and billing relationship without putting credentials in Android.
27. As a homelab operator, I want local nonsecret configuration and separately mounted secret files, so that keys are not embedded in images, ordinary database records or source control.
28. As a homelab operator, I want configuration activation controlled by restart, so that the active model and disclosure destination do not change through hidden hot reload.
29. As a homelab operator, I want generation configuration failure reported without preventing backend startup or original-text operation, so that a bad key does not disable my alert service.
30. As a homelab operator, I want known provider retention policies and unknowns described honestly, so that I can decide whether my own account/key is suitable.
31. As a homelab operator, I want only allowlisted title/summary and bounded necessary candidate context sent to Jev, so that arbitrary payload extras, credentials and unrelated history are not disclosed.
32. As a homelab operator, I want no cross-provider failover or account migration of pending alerts, so that configuring another destination does not silently authorize sending old content there.
33. As a homelab operator, I want same-account key rotation without automatic regeneration of completed alerts, so that replacing a credential does not repeat paid work.
34. As a homelab operator, I want retained attempt accounting and bounded transient retries across restart, so that uncertain requests do not become unlimited hidden paid execution.
35. As a homelab operator, I want no model repair calls for invalid output, so that an unusable view reliably becomes original text rather than starting an opaque call chain.
36. As a homelab operator, I want financial limits managed through my provider account/key rather than a Varde budget subsystem, so that the project does not claim billing guarantees it cannot enforce.
37. As a homelab operator, I want safe operational diagnostics without keys, source bodies or raw provider responses, so that troubleshooting does not become another disclosure path.
38. As a homelab operator, I want terminal fallback to stay terminal after configuration is corrected, so that new keys do not silently trigger historical model calls.

### Verification and future change

39. As a maintainer, I want deterministic offline fixtures and fake Jev transport tests, so that implementation and failure behavior can be verified without live calls or real alerts.
40. As a maintainer, I want one explicitly documented current backend/Android contract and coordinated upgrades, so that I do not maintain legacy state projections, downgrade paths or mixed-version support. Plain-text inputs remain supported under the current contract.
41. As a maintainer, I want reproducible relevance, fidelity, ordering and fallback evaluation, so that historical small experiments are not mistaken for production reliability.
42. As a maintainer, I want model-version changes evaluated deliberately, so that a moving alias does not silently alter selection behavior.
43. As a maintainer, I want any live synthetic benchmark separately authorized, so that writing tests or supplying a key is not mistaken for permission to spend or disclose data.
44. As a maintainer, I want a later provider contract based on actual alternative I/O, so that release one does not depend on an assumed decision-model standard.

## Implementation Decisions

### Confirmed module responsibilities

- Keep the existing single Go application, standard HTTP server and SQLite lifecycle ownership. This does not introduce another service, generic persistence layer or mandatory AI gateway.
- The lifecycle Module owns durable acceptance, duplicate identity, pending/terminal state, paid-attempt reservation/accounting, retry scheduling, configuration revision association and atomic publication.
- The presentation Module owns deterministic candidate preparation, relevance-question construction, supplied-fact associations, answer interpretation, ordering, complete rich-view assembly and validation.
- A native Jev transport implementation owns authentication, provider request/response grammar, bounded transport, provider-error translation and sanitized usage metadata. It performs at most one provider request per invocation and no hidden SDK retries.
- The application startup/configuration path loads the selected local nonsecret configuration and secret file. Provider unavailability or invalid generation configuration is isolated from core alert startup; this does not make fatal storage or unrelated startup failures nonfatal.
- Android reads and renders the approved component vocabulary and preserves unchanged original metadata. Notification Modules, registration, delivery and recovery scheduling are not redesigned here.
- Keep Jev-specific code isolated, but defer a generalized provider Interface and additional Adapters until a second provider is actually evaluated. Standardized decision-model I/O is not an established premise.

### Optional typed facts

- Preserve plain-text submission with omitted or empty typed data. No model call is needed for accepted alerts without useful typed data.
- Use a tagged entry array and a simple measurement shape: labelled supplied numeric value, optional unit/source/measurement time, and optional timestamped samples. A measurement can contain only history instead of a separate standalone value.
- Grouping establishes the measurement/history relationship and shares resource/label/unit context. Distinct collectors belong in distinct entries; label equality alone establishes no relationship.
- Common explicitly supplied source metadata can be inherited and overridden per entry. Defaults do not derive measurement time from receipt time, event time or a sample.
- Missing units, times and provenance remain unknown. Omit unsupported presentation candidates rather than inventing missing facts.
- IDs are alert-local and only necessary for actual cross-entry references. This does not require implementing unused cross-entry relationship kinds in release one.
- Record sets contain explicit columns and rows. Cells are supplied scalar strings, numbers, booleans or null; units may be supplied per column. No inferred columns, nested arbitrary JSON cells or executable formatting.
- Sequences contain explicit ordered steps with optional supplied status text. Preserve internal order; no inferred topology, branches, cycles or causal edges.
- Automatic prose segmentation, prose-to-measurement extraction and generative verification are deferred. Exact supplied text may accompany structured candidates without authoring new prose.

### Measurement fidelity

- Preserve samples as submitted in the original. Sort only derived presentation candidates by measurement instant.
- Reject duplicate instants within a history, including equivalent instants written with different offsets. Do not pick a winner or silently deduplicate.
- A null sample is an explicit gap. Never fill it with zero, interpolate it or connect across it as if missing observations existed.
- History can support a latest-supplied-reading metric that retains the sample timestamp. It is not implicitly a current reading. If the newest sample is null, omit that metric rather than substituting an older numeric sample.
- A separately supplied standalone value remains independent. Without a measurement time, do not assume it corresponds to the final sample.
- A standalone value and a history sample at the same measurement instant must agree. Reject contradictions before acceptance rather than repairing them.
- One series per chart candidate initially; combined multi-series charts are deferred.
- No fabricated measurements, missing-data estimates, unit conversion, smoothing, derived aggregates or inferred times. Supplied facts are not independently verified claims about the physical homelab.

### Confirmed bounds and validation stages

| Resource | Limit |
| --- | ---: |
| Total submitted alert body | 65,536 bytes |
| Typed entries | 8 |
| Samples per measurement | 120 |
| Record-set dimensions | 20 rows × 6 columns |
| Steps per sequence | 8 |
| Selected rich-view components | 8 |

- Malformed typed input or input exceeding its limits is rejected before durable acceptance with safe field-level guidance. Do not silently clip it, discard an invalid entry or accept only the text portion.
- Preserve existing authentication, duplicate-key/encoding safeguards, immutable-original and stable sender/event duplicate semantics. Source/history arrays in retained originals are not reordered as a side effect of presentation.
- Candidate preparation is deterministic and bounded. Entry count and component count are separate: one measurement can offer both metric and chart candidates. A total prepared-candidate cap remains a contract-gate decision.
- After acceptance, any invalid selection or assembled component invalidates the whole rich view. Selecting more than eight components causes original-text fallback, not retaining the first eight.

### Jev selection

- Initial model identifier is exactly `jev-1.13.0`, not a moving stable/preview alias.
- Make one batched finite-choice request per attempt: each prepared candidate is omitted or assigned a preferred position.
- Candidate identity must appear in question instructions/context, not only in provider question IDs. Treat supplied strings as untrusted data, not instructions.
- Interpret preferred positions as ordering bands; ties retain stable backend candidate order. The approved contract fixes catalog enumeration, at most 17 candidates and omit/p0–p7 choices, with a frozen native request example.
- Require complete, allowed, unambiguous answers for the requested questions. Unknown/missing/duplicate answers, out-of-domain choices or invalid output cause whole-view fallback.
- Intentional empty selection is original-text fallback, distinct diagnostically from malformed output. Neither is a useful-rich-view benchmark success.
- No routing call, reasoning handoff, extraction LLM, model repair, external tools or embedded URL fetches. Do not publish streamed/partial selection output.
- Jev decides relevance/order, not values, units, source/time relationships or validity. Model confidence is not a substitute for validation or an already-approved quality threshold.

### BYOK, disclosure and configuration

- One active selection configuration per homelab backend. Key ownership, billing relationship and retention acceptance belong to its operator, not an Android installation or sender.
- Generation is disabled by default. Possession or discovery of a key is not activation consent.
- Use local nonsecret configuration plus a runtime-mounted key file, activated by controlled restart. No Android key input, remote key-management endpoint or hot reload initially.
- Outbound provider secrets remain separate from inbound sender/installation verification hashes. The backend must be able to read its provider key to authenticate; hashing alone cannot supply that credential.
- Keep keys out of ordinary database/config snapshots, image layers, source control, command arguments, URLs, diagnostics, traces and raw errors. Runtime secret files are not a guarantee against host/root access or encrypted-at-rest storage.
- Use the selected native Jev key explicitly and fixed official HTTPS destinations. Do not discover unrelated ambient keys, forward authorization through redirects or accept endpoint overrides from alerts/Android.
- Local configuration checks do not silently call provider account/model endpoints. Synthetic transport tests use fake secrets, not real credentials.
- Send only allowlisted title/summary and bounded candidate context needed for selection. Do not send credentials, arbitrary extras or unrelated alert history. This minimizes disclosure but cannot guarantee that supplied free text/source labels contain no secrets.
- Disclose documented retention and uncertainty honestly. The reviewed TypeSafe terms do not establish a fixed standard prompt/response deletion window; enterprise ZDR is not assumed. An operator may explicitly choose to enable despite that uncertainty.
- No project-enforced dollar budget, estimated-cost reservations or daily spending-oriented request quota. Provider account/key controls are the operator's responsibility and may be account-level, soft or unavailable.

### Durable work, failure and switching

- Original acceptance/reads do not wait for successful Jev generation. Generation is asynchronous and must not change acceptance-triggered notification timing.
- Retain bounded operational concurrency, initially one locally in-flight model request, with independently bounded request/response sizes and deadlines.
- Pin eligible generation work to a nonsecret configuration revision before reserving its first paid attempt. Persist reservations/uncertain attempts and relevant results across restart; never derive the revision from raw key material.
- Each step allows one initial attempt plus at most three transient repeats, with approved 5-second/30-second/2-minute nominal backoff and 80–100% jitter; honor a longer provider wait up to 15 minutes, otherwise finalize fallback.
- Retry temporary transport/timeouts/rate/provider failures only. Do not retry authentication/permanent request/configuration errors, refusal or invalid output; do not repair generated data.
- A request whose send/completion is uncertain still counts. Cancellation and local concurrency limits do not prove a timed-out remote request stopped or was not billed.
- Late responses from an expired or superseded attempt cannot overwrite a later result/configuration. Persist and check attempt/claim identity at publication.
- Missing/invalid generation configuration produces explicit generation-unavailable diagnostics and terminal original-text fallback without preventing original-text service. Correcting it affects new work; terminal alerts do not automatically regenerate.
- Switching/retiring a configuration does not migrate old pending alerts to a new provider/account. Finalize their original-text fallback; new work uses the newly activated revision. No automatic cross-provider failover.
- Same-account key rotation is not a provider switch. Follow the approved configId/accountRef/nonsecret manifest rules; changing key material alone cannot silently redefine the disclosure destination.
- Remove the older architecture proposal's 1,000 model-attempt UTC daily ceiling. This does not remove operational bounds, durable accounting or bounded retries, and does not change notification policies/limits.
- Keep paid-attempt limits separate from the existing fallback-worker storage-contention recovery policy; SQLite recovery must not become unlimited paid retries.

### Approved contract gate

[#39](https://github.com/tomstiani/varde/issues/39) delivered the [concrete contracts](rich-alert-contracts.md). They are the authoritative field-level specification for:

- Current input/read/component grammar, exact decimal and timestamp handling, metadata inheritance and unknown/null semantics.
- Label/cell/input/provider bounds, deterministic candidate enumeration and the frozen Jev omission/position request.
- Local configuration/secret-file validation, account/configuration identity, activation/rotation and retired-pending-work behavior.
- Ten-second selection attempts, five-second shutdown allowance, durable reservations, retry classification and publication guards.
- Synthetic acceptance examples and later evaluation targets: zero observed fidelity/fallback violations, at least 95% useful eligible selections and success-only p95 at most 5 seconds, with outcome counts and censored timeouts reported separately.

These are approved requirements and evaluation targets, not measured runtime guarantees. No live evaluation is authorized. There is one current backend/Android wire contract with coordinated upgrades and no backward-version support; plain-text input remains valid because typed data is optional. Dependent implementations must follow these contracts, not reopen them or guess alternative policy.

## Testing Decisions

The user approved these three highest practical testing seams. Tests should establish externally observable behavior and invariants, not reproduce private helper structure.

### 1. Existing lifecycle Interface with real temporary SQLite

Exercise authenticated submit/read/change behavior, accepted versus rejected typed input, retained originals, duplicate identity, atomic publication, restart recovery and paid-attempt accounting. Inject deterministic Jev outcomes and controlled storage/time faults where necessary.

Prior art includes existing lifecycle fixtures, real temporary databases, duplicate/read/change tests, transaction/crash tests and virtual-time worker recovery coverage. Retain narrow storage fault hooks only where cleanup/transaction safety is not otherwise observable.

Required cases include:

- Plain-text/disabled/no-useful-data work makes zero provider calls and preserves existing fallback semantics.
- Every supported typed family reaches retained originals and deterministic candidates; malformed/bound-exceeding data never becomes accepted partial input.
- History-only values, unknown time/source/unit, null newest/inner samples, arbitrary sample ordering, duplicate instants/equivalent offsets and contradictory standalone/history values obey the confirmed rules.
- Same-number/different-source cases do not merge associations. Sorting candidates never mutates original arrays or submitted strings.
- Valid complete selection publishes atomically; empty, missing/unknown/duplicate answer, overflow and invalid component cases yield whole-view fallback without partial publication.
- Four-attempt maximum, delayed retries, restart, uncertain reservations, permanent errors, expired responses and configuration retirement cannot create hidden extra attempts or overwrite terminal results.
- Key correction does not regenerate completed work; same-account key rotation and destination changes follow their approved activation contract.
- Existing storage-contention recovery, duplicate acceptance, history/change ordering and authenticated original reads remain regression-tested.

### 2. Running backend with a fake Jev HTTP server

Use real local HTTP/TCP and synthetic secrets/payloads, not an authenticated model endpoint. Test native request grammar, exact pinned model, batched question identity/options, selected-key isolation, bounded bodies/deadlines, sanitized responses/errors and single-request-per-attempt behavior.

Prior art includes existing HTTP validation/authorization tests and application process tests for diagnostics, cancellation, readiness and shutdown. Add configuration/secret file fixtures scoped to temporary directories.

Required cases include missing/unreadable/invalid configuration, disabled configuration with a present key, safe generation-unavailable startup, provider authentication/validation/429/temporary overload, refusal/malformed/truncated/oversized responses, delayed completion and redirect refusal. Prove no raw source/provider bodies or secrets leak through errors, stdout/stderr, traces or diagnostic sinks. Test non-root runtime secret access in a local packaging fixture without provisioning a homelab.

Do not treat fake-server latency/errors as measured provider performance or reliability. No live access check is implicit in these tests.

### 3. Android read/render Interface

Use synthetic current-contract backend responses and native rendering tests. Exercise approved components and whole-view fallback through the same read/render path used by the app, not a separate preview-only schema.

Prior art includes existing inbox HTTP fixtures, connection/source labelling, detail/recovery tests and small-screen Compose coverage. Add rich-view fidelity, bounds, source/time labels and accessibility checks while preserving originals and supported-version behavior.

Required cases include unknown/malformed rich versions/components, late presentation updates, missing metadata, null chart gaps, record scalar types, sequence order and bounded small-screen layouts. A rejected whole view must not leave individually rendered components behind. Preserve combined inbox ordering, connection labels and authenticated original reads.

Do not change or take over notification controls, registration, provider delivery or background recovery in these tests. Current-contract regression coverage may observe existing behavior without redefining those contracts.

### Synthetic evaluation, separate from implementation tests

Prepare a committed offline corpus with canonical inputs, candidate/selection revisions and source-fact/usefulness oracles. Cover metric/trend, records/sequence, sparse/no-useful-data and malformed/adversarial families. Include source/time conflicts, option-order sensitivity, ties/overflow, gaps, stale samples, Unicode and embedded instructions. Manual quality review must distinguish faithful values from misleading selection/omission.

The historical experiments do not provide a runnable benchmark in this checkout. No live evaluation is authorized. A later explicitly approved Jev-only synthetic evaluation must record the exact pinned model, questions, configuration, sample counts, complete-view/fallback outcomes, end-to-end and success-only timing, censored timeouts, usage and unknown costs. Provider-account financial controls remain operator-managed. Zero observed fidelity errors does not establish a universal guarantee.

## Out of Scope

- A generalized cross-provider contract, OpenAI/Claude/Gemini integration, custom compatible endpoints, gateways or automatic failover.
- Free-form text generation, arbitrary prose extraction/segmentation, numeric reconstruction through model choices, reasoning routing or model-based validation/repair.
- Multi-series combined charts, arbitrary diagrams, branching/cyclic flows, nested layouts, generated HTML/SVG/code/markup or sender-controlled styling.
- Unit conversion, interpolation, smoothing, derived aggregates, guessed measurement timestamps and fetching external homelab context.
- Mandatory typed input, mandatory model accounts, Android-entered provider keys, per-sender model overrides, remote provider credential management or hot reload.
- Varde financial-spend enforcement, estimated-cost reservation ledgers, daily model-spending quotas or guaranteed provider billing caps.
- Historical regeneration, automatic migration of queued content to another provider/account, alert grouping or remediation actions.
- Notification registration/controls, FCM implementation, background recovery or changes to notification timing/privacy. Their separate work and acceptance remain untouched.
- Real alert uploads, live/paid model calls, secret uploads, homelab access/deployment, Google provisioning, push delivery or physical-phone/n8n/remote-HTTPS acceptance under this draft.
- Closing #11/#16 or their acceptance gates, claiming the full first release is delivered, and GitHub publication/closure without explicit approval.

## Further Notes

### Decision authority and known documentation differences

The user-confirmed strategy is recorded in the [Jev-first BYOK discussion](../research/hosted-model-byok.md). The [input authoring draft](../research/typed-alert-input-draft.md) illustrates the approved simple measurement shape; it is not a complete executable contract. The [unstructured-alert investigation](../research/jev-unstructured-alerts.md) explains why Jev reading prose does not imply arbitrary extraction support.

The [provider comparison](../research/hosted-model-strategy-d01c3433-5b78-40d5-afcd-467f0d0b97c7.md) contains primary sources retrieved 2026-10-19, documented rates/retention/schema limits and the distinction between documented claims, historical observations and unknowns. It is not evidence of production latency or correctness.

The [backend architecture](../architecture/backend.md) has been reconciled to the approved Jev-only selection path, deferred generic provider contract and absence of a daily model/spending quota. Notification policy is not reopened; concurrent notification/observability work remains separate.

### Delivery and approvals

GitHub remains canonical for current progress and acceptance. This file retains the specification reasoning, not a replacement progress tracker or implementation-completion record.

Publication receipt: under explicit approval, created parent [#38](https://github.com/tomstiani/varde/issues/38) and children [#39](https://github.com/tomstiani/varde/issues/39)–[#46](https://github.com/tomstiani/varde/issues/46), with eight native parent links and eleven blocking edges. Read-only verification confirmed submitted bodies matched the approved drafts and native relationships matched the approved graph. This receipt records publication, not live delivery status.

The approved slices are contracts (#39), metric (#40), BYOK (#41), durable Jev (#42), history (#43), records (#44), sequences (#45) and integrated verification (#46). The user subsequently approved the concrete contract-gate outputs, their publication and closure of #39. Publication receipt: updated #38/#39/#40/#42/#44/#46 bodies and closed only #39, with read-only verification of the submitted bodies and native blocker effects. Further issue edits/closure, pushing or PR creation require separately shown content/commands and explicit approval. #6 was left unchanged.
