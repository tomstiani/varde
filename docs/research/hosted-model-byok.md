# Jev-first hosted selection with backend-owned BYOK

Design discussion and primary-source research: **2026-10-19 UTC**. Part of [#6](https://github.com/tomstiani/varde/issues/6). See the [approved feature specification](../specs/typed-rich-alert-views.md), [provider comparison](hosted-model-strategy-d01c3433-5b78-40d5-afcd-467f0d0b97c7.md), [unstructured-alert research](jev-unstructured-alerts.md) and [typed input draft](typed-alert-input-draft.md).

**Status: first-release strategy confirmed by the user on 2026-10-19.** The direction and field-level contracts are approved for planning; runtime implementation and live evidence remain separate. Under subsequent explicit publication approval, the specification and eight child tickets were published as [#38](https://github.com/tomstiani/varde/issues/38) and #39–#46. Contract #39 was later published and closed under separate explicit approval. This is not production enablement, runtime implementation, live-benchmark authorization or permission for further issue mutations. No model calls, credentials, real alert content, provisioning, notification work or additional agents were used.

## 1. First-release direction

- **Jev only, pinned to `jev-1.13.0`.** No moving aliases, OpenAI implementation or general-purpose generation model initially. Model upgrades are deliberate configuration changes evaluated with synthetic fixtures.
- **BYOK per homelab backend.** Its operator supplies the provider account/key. No Android key entry or per-sender model overrides.
- **Optional and disabled by default.** Original-text submission/reading works without a provider account. A key's presence alone does not enable generation.
- **One active provider/model configuration.** No automatic provider failover or silent escalation.
- **Defer the cross-provider contract.** Native decision-model I/O has not been established as a shared standard by this review. Isolate Jev authentication/transport from candidate preparation and validation, but do not introduce a generalized provider API or second adapter speculatively.
- **No backward-version support.** A subsequent user decision removes legacy state projection, negotiated old representations, capability/downgrade paths and mixed-version support. Backend and Android upgrades are coordinated; optional plain-text input remains supported under the current contract. See the [approved #39 contracts](../specs/rich-alert-contracts.md). Under separate approval, the contracts were published, affected issue wording was corrected and #39 was closed; this records contract delivery only.

Earlier proposals to default to Luna or implement both Jev and OpenAI Decisions are superseded by these answers. The external comparison remains evidence, not an additional implementation commitment.

## 2. Typed input prerequisite and presentation

The approved [#39 contracts](../specs/rich-alert-contracts.md) define **optional provider-independent typed input**, reference-only rich views and the native Jev/configuration/execution grammar. Input describes supplied facts, not Jev requests, Android components or layout instructions. The [authoring examples](typed-alert-input-draft.md) provide the simple sender-facing introduction; they do not replace the complete contract.

Confirmed behavior:

- Keep original title, summary, priority and event timestamp unchanged and separate from rich content.
- Use tagged entries in the existing `data` concept: measurements with optional history, records for tables and explicit ordered sequences for simple flows. The complete field names and current wire versions are approved in #39.
- Group one measurement with its history, sharing label/resource/unit context. Supply common source metadata once with entry-level overrides. Relationships are explicit in supplied structure/references, never inferred from matching labels.
- Require IDs only when another entry needs a reference; use alert-local identifiers, not mandatory UUIDs for every value/sample.
- Unknown units, measurement times and sources remain unknown. Never borrow receipt/event time as measurement time.
- History-only entries may support charts and a metric explicitly labelled as the latest supplied reading with its timestamp. If the latest sample is null, omit that latest-reading metric rather than substituting an older number.
- Preserve null samples as gaps. Accept sender sample order but sort presentation candidates chronologically without rewriting originals. Reject duplicate instants and contradictory standalone/history readings at the same instant. An untimed standalone value remains independent of timestamped samples.
- Start with one series per chart candidate. Combined multi-series charts are deferred; separate measurements remain supported.
- Records have explicit columns/rows with scalar string/number/boolean/null cells and optional per-column units. No nested cells, executable formatting or inferred columns.
- Sequences have explicit ordered steps with optional supplied status text. Preserve internal order; no invented steps, branching, cycles or inferred causality/topology.
- Alerts without useful typed data bypass Jev and retain original text. Exact supplied text can accompany structured candidates; automatic prose segmentation and prose-to-measurement extraction are deferred. No hidden extraction LLM or model-based verifier.

### Initial limits confirmed in Q35

| Limit | First-release value |
| --- | ---: |
| Total submitted alert body | 65,536 bytes |
| Typed entries | 8 |
| Samples per measurement | 120 |
| Record-set dimensions | 20 rows × 6 columns |
| Steps per sequence | 8 |
| Selected presentation components | 8 |

Excess typed input is rejected before acceptance, not silently clipped. Invalid/malformed typed input receives precise field-level guidance without echoing private submitted values. It is not accepted as text with the invalid entries discarded. After durable acceptance, invalid model output or any invalid assembled component instead causes whole-view fallback to unchanged original text. Selected-component overflow also causes whole-view fallback.

## 3. One bounded decision step

The backend deterministically prepares candidates with supplied values, strings, units, source and time associations. Jev selects relevance and preferred ordering only.

- Ask one finite choice per candidate: omit or one of the supported preferred positions; batch questions into one provider request per attempt.
- Put the candidate identity in question instructions, not solely question IDs: TypeSafe says its model does not see question IDs. [S2]
- Interpret positions as ordering bands. Sort ties by stable backend candidate order; do not ask another model or issue a repair call.
- Validate exact answer coverage, allowed options, candidate membership, bounds and complete assembled view. No partial streamed publication or salvage of valid-looking components.
- Confidence is not proof of factual correctness; no untested confidence threshold is an approval gate.

Independent choices can still create irrelevant or redundant selections. Deterministic validation preserves supplied facts, not an automatic guarantee of useful presentation. The retained experiments do not establish production reliability or p95 latency.

## 4. Key handling and activation

Confirmed scope is local configuration plus runtime-mounted secret files, activated through controlled restart. No hot reload, remote credential-management endpoint, provider OAuth or client-side keys initially.

Approved safeguards to implement and test:

- Keep provider keys separate from Varde-issued sender/installation credentials. Outbound keys must be recoverable by the backend; storing only their hash cannot authenticate to a provider.
- Store nonsecret configuration separately from secret files. Avoid secret CLI arguments, URLs, checked-in `.env` files, image layers, SQLite records, ordinary backups and raw diagnostics.
- Compose can grant one runtime secret file to the service under `/run/secrets/`; `_FILE` support must be implemented by Varde, not assumed. These are host bind mounts, not an encrypted secret vault. Verify read access for the existing non-root service UID/GID without broad file permissions. [S1]
- Use the selected Jev key explicitly; no scanning ambient credential stores. Keep fixed official HTTPS origins and reject redirects. Custom endpoints/proxies require later trust/compatibility work.
- A local configuration check must not silently make authenticated account/model calls. No authenticated access test is authorized here.
- Ordinary logs/errors contain only safe configuration/model revisions, attempt IDs, outcome categories and usage—not alert bodies, provider response bodies or Authorization headers.

Invalid/unavailable generation configuration must not prevent backend startup, durable alert acceptance or original reads. Report generation unavailable; never silently select another provider.

## 5. Switching, rotation and durable failures

- Pin pending work to its generation-configuration revision before its first paid reservation. Keep revisions nonsecret; do not use raw-key fingerprints.
- Switching/retiring a configuration does not migrate pending alerts to a new provider. Pending old-configuration work finalizes original-text fallback; new work uses the newly activated configuration. Already terminal alerts never automatically regenerate.
- Correcting missing/invalid keys affects new work, not terminal fallback alerts. Same-account key rotation is not a provider switch; changing the destination provider/account requires an explicit configuration revision.
- An expired/old attempt cannot overwrite a later result or configuration. Cancellation cannot prove a remote request stopped; uncertain attempts remain counted.
- Retain **one initial attempt plus up to three durable transient retries**. Approved delays are 5 seconds, 30 seconds and 2 minutes with 80–100% jitter; honor longer `Retry-After` up to 15 minutes, otherwise terminal fallback. No hidden SDK retries, invalid-output repairs or cross-provider retries.
- Retain one locally in-flight request, a 48-KiB request/32-KiB response cap and a 10-second selection-attempt deadline. The worker shares the five-second shutdown allowance. These approved limits are not measured provider latency guarantees.
- **Remove the proposed 1,000-attempt UTC daily model ceiling. No Varde dollar-budget enforcement or estimated-cost reservations.** Financial limits belong to the operator's provider account/key. Controls may be account-level, soft or unavailable; do not promise a hard cap.

The removed daily model-attempt quota explicitly revises the earlier [backend architecture proposal](../architecture/backend.md). Its shared-provider seam remains a future possibility rather than a first-release requirement. The architecture overview has been reconciled to these answers; this research does not change notification quotas, timing, controls or implementation.

## 6. Privacy and cost responsibility

Send only allowlisted original title/summary and bounded candidate context necessary for selection. Do not send credentials, arbitrary payload extras or unrelated history. Source labels can contain private identifiers: allowlisting is disclosure minimization, not guaranteed secret removal.

Retention is the operator's choice using their own provider account/key. Disclose known policies and uncertainty; unknown retention is not a product-enforced prohibition. In particular, the reviewed TypeSafe public terms do not establish a fixed standard prompt/response deletion window. Enterprise ZDR must not be assumed. BYOK changes billing ownership, not retention or account eligibility. [S3]

At the comparison's illustrative 2,000 billed input tokens per Jev attempt and $0.042/million input tokens, 10,000 attempts cost $0.84 before applicable adjustments. Actual batched questions change input usage; this is arithmetic, not a measured bill or guarantee. Timeouts may be charged; unknown usage is not zero.

## 7. Contract delivery and remaining work

1. #39 delivered the approved input/view/candidate/configuration/execution definitions, frozen native request and initial synthetic examples. Do not treat those fields or limits as unresolved design choices.
2. Implement the separately tracked #38 slices using those contracts. Secret loading, state persistence, transport, rendering and recovery tests are not implemented by this research.
3. Expand the initial synthetic examples into reproducible lifecycle, fake-HTTP and Android tests, including option-order sensitivity, adversarial text, irrelevant candidates, ties/overflow, gaps, source/time associations, configuration failure and late results. The local 18-case check verifies fixture consistency only.
4. The later evaluation targets and exact prompt are approved, but the live evaluation has not run. Historical small runs and vendor claims are not production acceptance.
5. Any synthetic live evaluation needs separate explicit authorization for payloads, exact pinned model and bounded execution. No second provider is required; operator-managed financial controls do not constitute permission to make calls.
6. Production enablement remains separate and unapproved. GitHub remains canonical; further issue changes, #6 closure, pushing or PR creation require explicit approval. Keep #11/#16 acceptance and notification work separate.

## Sources

Directly retrieved **2026-10-19 UTC**. These are documented capabilities, not observed integration behavior:

- [S1] Docker, [Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/): per-service grants, runtime files, bind mounts and `_FILE` convention.
- [S2] TypeSafe, [API reference](https://docs.typesafe.ai/api) and [Choice](https://docs.typesafe.ai/primitives/choice): authentication, finite choices, batched questions, question-ID visibility, errors and default SDK retries.
- [S3] TypeSafe, [Models](https://docs.typesafe.ai/models.md), [Legal](https://docs.typesafe.ai/legal), [Data Processing Addendum](https://typesafe.ai/legal/data-processing): version/rate information, training-use statements, enterprise ZDR and retention terms. Detailed comparative evidence is retained in the [provider report](hosted-model-strategy-d01c3433-5b78-40d5-afcd-467f0d0b97c7.md).
