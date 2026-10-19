# Varde: first-release hosted-model strategy

Request ID: `d01c3433-5b78-40d5-afcd-467f0d0b97c7`

Reconciled and primary sources retrieved: **2026-10-19 UTC**. Related: [#6](https://github.com/tomstiani/varde/issues/6), [#1](https://github.com/tomstiani/varde/issues/1).

## Recommendation and subsequent design discussion

**Use one bounded Jev selection call over backend-prepared candidates, not model-authored component data.** The user's subsequent design answers select backend-owned BYOK, **Jev only initially, pinned to `jev-1.13.0`**, and defer a generalized provider contract or second adapter. The user confirmed this first-release strategy on 2026-10-19; see the [consolidated BYOK design](hosted-model-byok.md) for decisions and explicit prerequisites. No production enablement or live benchmark is authorized.

Jev has the only retained Varde-specific selection observations and the lowest listed input-only price in this comparison. Its unresolved standard retention duration must be disclosed, but the user explicitly leaves acceptance of provider retention to the operator rather than making uncertainty a product-enforced prohibition. Synthetic quality evaluation remains necessary. Finite per-candidate omit/position choices let deterministic code assemble/order the view; test relevance, ties and overflow. [T1–T4]

OpenAI `gpt-6-luna` Responses with strict Structured Outputs remains an alternative for a single global ordered-ID list, **not the recommended default**. Its convenient ordering does not establish enough quality benefit to justify its higher assumed cost. If evaluated, start with `reasoning.effort: none`, `store: false`, no tools or routing/brief call; exact settings and quality remain untested. [O1][O2]

**The strategy and concrete contracts are approved; runtime implementation and production enablement are not established.** [#39](https://github.com/tomstiani/varde/issues/39) delivered the approved [typed-data/view/Jev/configuration/execution contracts](../specs/rich-alert-contracts.md), including no backward-version support. Implementation and reproducible evaluation remain necessary; no provider has demonstrated production suitability here. Hosted generation stays disabled by default. Retention acceptance belongs to the operator; BYOK does not establish zero retention or account eligibility.

No live or paid model calls, private credentials, real alerts or deployment were used. Public-source research was followed by separately approved GitHub publication of #38/#39–#46 and contract reconciliation/closure of #39; those are documentation/decision delivery, not model integration. Issue #6 remains open and unchanged. Notification and acceptance issues were not modified.

## 1. Reconciliation with existing work

| Evidence | What it establishes | What it does not establish |
| --- | --- | --- |
| [#4 resolution](https://github.com/tomstiani/varde/issues/4#issuecomment-6188080441), read live 2026-10-19 | Approved versioned ordered text/metrics/line-chart/table/simple-flow components; Android owns style; originals separate; supplied measurements only; invalid whole-view fallback | Exact field schemas, limits, visual design or a model choice |
| [Alert-view experiments](alert-view-experiments.md) | Historical synthetic TypeSafe `jev-1.13.0` observations: stricter relevance prompt met ten proposed selection/fallback outcomes | Reproducibility from this checkout, repeatability, independently adjudicated quality, production p95 or privacy suitability |
| [Backend architecture](../architecture/backend.md) | Prepared candidates, native Jev separation, durable attempt accounting and bounded retries; reconciled to #39 | Implemented rich generation or measured production suitability |
| [Approved #39 contracts](../specs/rich-alert-contracts.md) | Concrete input/view/selection/configuration grammar, resource bounds, execution rules and later evaluation targets | Runtime implementation, completed model evaluation or production enablement |
| Earlier version of this report | Useful fidelity rules, source survey and synthetic benchmark design | Its three-vendor benchmark-only conclusion was not a settled strategy |
| [Decision-model API survey](decision-model-apis-6548f0ea-c233-4d8c-a5b3-4c5220874f90.md) | Historical investigation of typed decisions | Its Jev identity ambiguity is not a current blocker: later experiments explicitly used TypeSafe, whose owner docs are reviewed here |

This revision extends the existing report rather than creating a competing decision record. It adds TypeSafe and current OpenAI Decisions to the comparison and narrows generation to prepared-candidate selection. The subsequent user discussion selected Jev-only BYOK initially; other providers remain comparative research, not implementation targets. The former optional GPT-5.6 Luna comparison is replaced by GPT-6 Luna, now documented at lower rates; that does not prove better Varde quality. [O1][O5]

Two stale statements in the earlier report must not guide implementation:

- **Notification timing is settled separately**, not deferred by model benchmarking. Live #1 confirms the #19 policy: do not wait for rich generation; silent enrichment follows authenticated fetch. This research neither reopens that policy nor changes #22–#25.
- The earlier suggestion of at most one transient retry is **not the selected policy**. Retain one initial paid-step attempt plus at most three durable transient repeats. The user explicitly removed the architecture proposal's daily model-attempt ceiling and rejected Varde financial-budget enforcement; operators manage financial controls through their provider account/key. Baseline benchmarking can use one attempt without changing retry policy. Invalid output is not retried.

## 2. Approved output strategy and remaining evidence gates

The backend prepares approved candidates from optional supplied typed facts. Jev selects relevance/order only: one finite omit/p0–p7 choice per candidate, batched into a single request per attempt. Stable catalog order breaks position ties. Candidate descriptions are untrusted data, never instructions. Empty selection is original-text fallback; invalid selection is a distinct diagnostic outcome.

The model response is **not** the Android rich-view contract. Deterministic assembly publishes a versioned ordered list of reference-only metric, lineChart, table, flow and exact-summary text components. Every reference must identify an offered candidate bound to the retained original; invalidity anywhere rejects the whole view. There is no generated code, markup, arbitrary positioning, nesting, extraction LLM or homelab lookup.

[#39](../specs/rich-alert-contracts.md) now defines the exact fields and bounds: optional measurements/history, records and sequences; explicit source/resource/collector inheritance; exact decimal values and typed timestamps; 17 candidate maximum; 8 selected components; 48-KiB request/32-KiB response caps; 10-second selection attempt and five-second shutdown allowance. One series per chart initially, no backward-version support, no Varde financial/daily model quota. Plain-text/no-useful-data alerts bypass Jev.

The remaining gates are **implementation, deterministic validation through the approved test seams, useful-selection evaluation and explicit production enablement**. The 18 synthetic contract examples and frozen request artifact were checked locally for consistency; they are not a production validator, SQLite/Android test run or model-quality result. A future requirement for generated prose or fact extraction would require revisiting this strategy.

## 3. Current capability and cost comparison

Documented claims below were retrieved from owner pages on 2026-10-19, not inferred from model names or search summaries. Rates are standard synchronous text, **USD per million tokens**, uncached, excluding taxes, regional premiums and negotiated discounts. Availability in docs does not establish access or quota for this user's account.

| Candidate | Structured approach / constraints | Input / output rate | First-release assessment |
| --- | --- | --- | --- |
| **OpenAI `gpt-6-luna`, Responses** | Strict JSON Schema subset; ordered candidate-ID array in one object. Supports `reasoning.effort: none`. [O1][O2] | **$0.10 / $0.50**, prompts ≤272K input tokens. [O1] | Alternative if global ordering justifies higher cost; no measured Varde quality/latency. |
| OpenAI `gpt-5-nano-2025-08-07` | Structured Outputs; reproducible dated snapshot; reasoning tokens can add billed output. [O3] | **$0.05 / $0.40** | Cheaper token-rate control, not excluded as obsolete; compare accepted-view cost and reasoning overhead rather than rates alone. |
| **TypeSafe `jev-1.13.0`** | `choice` (≤255 options), `noul` and `score` answers, not arbitrary JSON Schema generation. 64K request / 32K per question by default. [T1][T2] | **$0.042 / free** | Selected initial provider in the design discussion; production enablement still pending. Per-candidate choices require tested relevance/order handling. |
| OpenAI Decisions, `gpt-6-luna` | Public beta; predicate/choice/score endpoint, distinct from Responses. Application assembles the view and interprets probabilities. [O4] | **$0.10 / free** for Decisions, before regional/long-context adjustments | Possible bounded-choice challenger, not needed as a preliminary routing call. No Varde measurements; beta adds lifecycle risk. |
| Anthropic `claude-haiku-5-5` | JSON output via `output_config.format`; limited schema constraints and compilation behavior below. [A1][A2] | **$0.10 / $0.50** for prompts ≤100K tokens; $0.50 / $2.50 above | Competitive documented price; no Varde quality evidence. Retention wording requires clarification. Adaptive thinking defaults on. |
| Paid Gemini Developer API `gemini-3.1-flash-lite` | JSON Schema subset including array bounds; large/deep schemas can be rejected. [G1][G2] | **$0.25 / $1.50**, output includes thinking. [G3] | Technically plausible; higher listed rates and unresolved professional/business-only eligibility for a personal homelab. |

TypeSafe lists `jev-1.14.0` as newest (October 14), but `jev-latest` still points to 1.13 and `jev-preview` to 1.14. Pin 1.13 to reproduce prior observations; evaluate 1.14 separately rather than silently attributing old results to it. [T1]

This is a focused comparison of small-model and bounded-decision options, not an exhaustive catalog. Recheck lifecycle, account access and rates immediately before any authorized execution.

### Schema constraints and failure exceptions

- **OpenAI:** root must be an object, not root `anyOf`; all fields required (nullable for optional values); objects need `additionalProperties: false`. Documented maxima include 5,000 object properties, 10 nesting levels and 1,000 enum values across the schema. Refusals and incomplete responses are outside the successful-schema path. Strict mode is not ordinary JSON mode, and does not prove semantic fidelity. [O2]
- **Claude:** requires `additionalProperties: false`; supports basic types/unions but not recursive schemas, numerical bounds, string-length bounds or array constraints beyond `minItems` 0/1. Unsupported constraints can return 400; SDKs may transform them into descriptions rather than wire constraints. Initial schema compilation adds latency; grammars cache for 24 hours since last use. Injected schema guidance adds billed input tokens. Refusal or `max_tokens` can bypass schema conformity; docs also warn about enum casing differences. Reject noncanonical IDs locally rather than guessing corrections. [A2]
- **Gemini:** supports basic object/array types, required fields, nullable values, enums and min/max item constraints. Docs explicitly require application validation for semantically incorrect output. Exact schema compatibility still needs a future conformance probe. [G2]
- **TypeSafe:** returns typed question answers/probabilities, not the final component envelope. Request limits include 32 MiB and 64 nested levels; reject invalid/missing answers and out-of-catalog choices locally. Provider limits are ceilings, not sensible Varde payload limits. SDKs automatically retry rate limits/overload unless controlled. [T1][T2]

Maintain a canonical local validator and explicit native Jev translation. Generalized cross-provider schema projections are deferred. Never weaken local validation to fit a provider.

### Illustrative cost, not measured spending

Assume 2,000 billed input tokens and 500 billed output tokens per attempt for generative endpoints; TypeSafe/Decisions bill input only. Schema guidance and reasoning must fit those totals for the example to apply. Tokenizers differ. Formula: `(input × input_rate + billed_output × output_rate) / 1,000,000`.

| Candidate | USD / attempt | USD / 10,000 attempts |
| --- | ---: | ---: |
| GPT-6 Luna Responses | 0.000450 | 4.50 |
| GPT-5 nano | 0.000300 | 3.00 |
| TypeSafe Jev | 0.000084 | 0.84 |
| OpenAI Decisions | 0.000200 | 2.00 |
| Haiku 5.5, small-prompt tier | 0.000450 | 4.50 |
| Gemini 3.1 Flash-Lite | 0.001250 | 12.50 |

Independent per-candidate questions can change input size; these equal-token examples are not equal-work benchmarks. Retries, reasoning, schema guidance and timeout charges can increase cost. Four equal-cost attempts multiply per-alert spend by four. Report total spend / accepted useful views as well as cost / attempt. A timed-out call with missing usage has unknown cost, not zero.

## 4. Privacy: hosted selection still discloses alert data

No-training policies are not no-storage policies. Even selecting IDs requires sending alert context and candidate descriptions. Self-hosting the homelab backend does not keep that context local.

| Service | Owner-documented handling | Unresolved deployment consequence |
| --- | --- | --- |
| OpenAI API | Not used for training unless opted in; abuse logs normally up to 30 days, with legal/harm-prevention exceptions. Responses stores application state for 30 days by default; use `store: false`. Prompt caching can separately retain encrypted KV tensors up to 24 hours; `store: false` does not disable that. GPT-5.6+ cache TTL controls minimum lifetime, not maximum retention. ZDR/MAM need approval. Structured-output schemas count as system data, outside residency coverage. [O6] | Accept disclosure/retention explicitly; no default ZDR or EU-only-processing promise. Do not put alert-specific facts into schemas. Account/region arrangements remain unverified. |
| TypeSafe | Docs say requests/responses are not used for training and enterprise ZDR is available. The DPA says personal data is retained as long as necessary for processing purposes and applicable law; reviewed public materials do not establish a fixed standard prompt/response deletion window. [T1][T3][T4] | Disclose the unresolved standard retention window and region handling; operator acceptance governs enablement. Enterprise ZDR cannot be assumed for a personal account. |
| Direct Claude API | Commercial data not trained on absent permission/other stated exceptions. Privacy support says automatic deletion within 30 days, with policy/legal/feature exceptions; flagged content can persist up to two years. Platform docs instead say conversation content is not retained by default except Covered Models; structured-output docs say prompts/responses use ZDR while schema artifacts cache up to 24 hours. Haiku is not in the reviewed Covered Models list. [A2–A5] | Wording is not fully reconciled. Do not advertise default ZDR or an unconditional 30-day ceiling; clarify account/model/feature terms before use. |
| Paid Gemini Developer API | Paid prompts/responses not used to improve products; abuse monitoring retains prompts/context/output 55 days with possible authorized review. Terms allow transient caching where Google/agents operate. Unpaid services have different data-use terms and regional exceptions. [G4][G5] | Paid billing-linked project only for any eventual real-data use; no Contributor/data-sharing mode. Professional/business-only terms make personal-homelab eligibility a separate unresolved gate. |

Proposed deployment safeguards: explicit hosted-generation opt-in, minimal field allowlist, local pseudonymization and secret stripping, backend-only credentials, no body logging or raw response telemetry, and no automatic cross-provider failover. Secret detection is imperfect: allowlisting and user consent still matter. Never send credentials, unrestricted logs, real host identifiers or unrelated alert history by default. Keep identity mappings local. No provisioning or provider contact is authorized by this report.

## 5. Latency evidence and deterministic failure handling

**Measured in this revision: nothing.** The historical TypeSafe experiments report median individual-request durations around 244–256 ms on small synthetic runs. Raw reports/fixtures are not retained here. Those observations do not establish p95, full-pipeline latency, production reliability, or speed relative to OpenAI/Claude/Gemini.

**Documented claims:** OpenAI describes Decisions as about 10× faster than Responses; Claude describes Haiku as fastest relative to its lineup and documents initial grammar-compilation overhead; Gemini describes Flash-Lite as cost-efficient. These are vendor claims with different workloads, not comparable Varde latency results. No numeric release latency SLO is justified. [O4][A1][A2][G1]

Proposed pipeline: bounded preflight → candidate preparation → one bounded provider step → check terminal status → strict parse → selection validation → deterministic assembly → complete rich-view validation → atomic publication.

- Originals remain readable unchanged throughout. No model output overwrites title, summary, priority or event timestamp.
- Reject malformed/oversized submitted typed input before acceptance; never silently discard invalid entries. Accepted alerts without useful typed data bypass Jev and retain original text. Treat embedded instructions as data; enable no tools or external URL fetches.
- Reject duplicate JSON keys, trailing data, unknown fields/versions/IDs, duplicate selection, invalid order, nonfinite values, changed source/unit/time associations, invented measurements or flow links, and any invalid assembled component. Never repair a value or salvage a valid-looking subset.
- Refusal, truncation, invalid selection or invalid rich view produces deterministic **whole-view fallback to unchanged original text**. Do not publish partial streamed JSON. Intentional empty selection is valid fallback but not a useful-rich-view success.
- Permanent configuration/auth/schema errors and exhausted transient attempts produce terminal fallback. Existing text remains available while transient work is pending.
- Follow the backend proposal's durable per-step retry policy: one initial attempt plus up to three repeats, approximately 5 s / 30 s / 2 min delays with jitter; honor longer `Retry-After` up to 15 min, otherwise terminal fallback. Invalid generated output has no repair retry. Persist reservations/uncertain attempts and successful steps; disable hidden SDK retries so they cannot bypass budgets.
- Keep bounded operational concurrency and request sizes; initially one locally in-flight request. **The user removed the daily model-attempt ceiling and rejected application dollar-spend enforcement.** Provider account/key financial controls remain the operator's responsibility. Paid execution is not implemented by the current worker.
- Bound each request's queue/network/generation/validation time; cancellation is best effort and not a billing guarantee. Ignore late results from an expired attempt. An overall presentation-age deadline, if desired, needs a separate policy consistent with durable transient retries.

Isolate native Jev access from presentation logic, but defer a generalized `DecisionModel` contract and other adapters until another provider is considered. No optional `LLM` call initially. The presentation module owns candidates, reference resolution, validation and fallback; provider adapters own wire schemas, errors and transport, not alert history. Record model/prompt/schema/candidate revision and non-content outcome/usage metadata. Model changes require re-evaluation, not automatic promotion. None of this requires an AI Gateway or json-render runtime dependency.

## 6. Future opt-in synthetic benchmark — not authorized or run

First prepare an offline, committed corpus and validator tests. The throwaway experiments are useful evidence but cannot substitute for reproducible fixtures. No real alert, provider credentials or live model call is needed for this preparation.

1. Freeze 40 synthetic fixtures (ten per family), canonical bytes/hashes and source-fact oracles. Families: metric/trend; table/explicit flow; sparse/no-useful-data; malformed/adversarial. Include small/medium/near-limit sizes, null chart gaps, conflicting collectors, stale times, mixed units/subsystems, Unicode, embedded instructions and identical values from unrelated sources. Preflight-invalid fixtures must make zero calls.
2. Freeze candidate preparation, schema projections and relevance expectations before execution. Recreate service-style cases, including unrelated worker latency, but keep unseen holdouts. Accept multiple legitimate orders; score useful coverage and misleading associations separately from exact schema validity. Human-review every accepted view against source facts.
3. Test locally: duplicate keys/IDs, stale references, missing answers, refusal/truncation wrappers, response-size caps, transport errors, late results and atomic fallback. Injected errors are simulations, not measured provider reliability. Exercise Android rendering later without changing notification sequencing.
4. Obtain separate user approval for pinned Jev 1.13, synthetic payload disclosure and a bounded request/stop plan; financial controls belong to the operator's provider account/key, not a Varde budget subsystem. Evaluate Jev only initially. Other-provider comparisons and a generalized contract are deferred. Do not silently switch to TypeSafe preview 1.14.
5. The approved initial plan is Jev-only: ten randomized repetitions per fixture, at most 400 scheduled cases; preflight bypasses reduce actual calls. Randomize case order and time windows. Measure concurrency 1 first; any burst condition needs separate approval. Record unknown cache state honestly; a new HTTP connection does not prove a cold provider cache.
6. Start with one attempt per case, disabling SDK retries; evaluate durable transient retries separately. Record exact model ID, prompt and schema hashes, SDK/API versions, reasoning settings, token/output limits, account tier, backend/network region, timestamps and price snapshot without recording secrets or real alert content.
7. Report end-to-end generation p50/p95 from backend queue acceptance through validated view or finalized fallback, plus provider duration and success-only time-to-valid-view. Report nearest-rank percentiles, counts and censored timeouts; do not treat deadline expirations as successful views. Phone fetch, Android rendering and push delivery are separate measurements.
8. Report schema/fidelity/usefulness acceptance, omission errors, unsupported associations, fallback reasons, HTTP/refusal/truncation rates, deadline misses, usage including reasoning/cache tokens, cost/alert and cost/accepted useful view. Missing timeout usage is unknown; reconcile billed spend. Zero observed fidelity failures is not a universal guarantee.

At the illustrative assumptions, 400 actual Jev attempts cost about **$0.0336**. This is an estimate, **not a spending cap**: actual questions/input and retries differ. Agree a bounded execution/stop plan and operator-managed provider financial controls before any separately authorized trial.

Approved later-evaluation targets in #39: zero observed supplied-fact/fallback violations, at least 95% useful selections among eligible cases and successful valid-completion p95 at most 5 seconds at concurrency one, with 10-second attempt cancellation. Always report full outcome/fallback/deadline counts and censored timeouts alongside success-only percentiles. These are targets, not measured results or permission to execute the trial. Small-sample uncertainty, relevance, privacy and cost must be reviewed together; an empty selector or one that times out difficult cases must not win. If the targets are unmet, retain original-text-only operation rather than escalating to another provider.

## 7. Decisions and next actions

1. Preserve the user-confirmed [Jev-first BYOK design](hosted-model-byok.md): Jev 1.13 only, local backend-owned keys, optional typed input and no Varde financial-budget enforcement. A shared provider contract is deferred. Production generation stays disabled and no paid benchmarking is authorized.
2. Implement the approved #39 contracts through the #38 slices, preserving #4's component constraints. The wire schemas, configuration/execution rules and evaluation targets are no longer unresolved choices.
3. Disclose TypeSafe retention uncertainty and verify account access/eligibility/model lifecycle before any separately authorized use. Acceptance of retention remains the operator's decision. Other providers' unresolved policy wording matters only if they are reconsidered later.
4. Expand the initial contract examples into reproducible lifecycle/fake-transport/Android tests; then seek separate explicit approval for any synthetic live trial and bounded execution plan. Financial controls remain operator-managed.
5. Only after schema validation and evidence gates are satisfied, seek production configuration approval. Record an approved decision separately if granted. Publishing this recommendation to #6, changing its state, pushing or creating a PR all require separate approval. Keep #11/#16 acceptance and notification tickets unchanged.

## Primary sources

All external sources below were directly retrieved **2026-10-19 UTC**. Rates/capabilities/policies are documented claims, not measured results or contractual guarantees for this account. Google pricing was also inspected under its raw model heading because readable extraction omitted headings. Live docs may change; model access and exact wire schemas remain untested.

- [O1] OpenAI, [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna): supported features, identifier, effort controls, rates and long-context/regional qualifications.
- [O2] OpenAI, [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs): strict schema subset, limits, refusals and incomplete outputs.
- [O3] OpenAI, [GPT-5 nano](https://developers.openai.com/api/docs/models/gpt-5-nano): snapshot, reasoning, Structured Outputs and rates.
- [O4] OpenAI, [Decisions](https://developers.openai.com/api/docs/guides/decisions): public beta, supported model/types, endpoint-specific input-only pricing, latency claim and Structured Outputs distinction.
- [O5] OpenAI, [GPT-5.6 Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna): previous comparison's rates and effort controls.
- [O6] OpenAI, [Data controls](https://developers.openai.com/api/docs/guides/your-data): training, abuse logs, Responses storage, prompt caching, ZDR and residency limitations.
- [T1] TypeSafe, [Models](https://docs.typesafe.ai/models.md): versions/aliases, rates, context, training-use statement and enterprise ZDR link.
- [T2] TypeSafe, [API reference](https://docs.typesafe.ai/api): typed questions/answers, option/request limits, errors and automatic SDK retries.
- [T3] TypeSafe, [Legal](https://docs.typesafe.ai/legal): owner policy links and enterprise ZDR availability.
- [T4] TypeSafe, [Data Processing Addendum](https://typesafe.ai/legal/data-processing) and [Privacy Policy](https://typesafe.ai/legal/privacy-policy): processing/retention terms; no fixed standard prompt/response TTL established by this review.
- [A1] Anthropic, [Haiku 5.5 overview](https://platform.claude.com/docs/en/models/haiku-5-5/overview): identifier, prices/threshold, default thinking and relative latency positioning.
- [A2] Anthropic, [Structured outputs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs): syntax/subset, grammar caching, schema billing, refusal/truncation/casing exceptions and retention claims.
- [A3] Anthropic, [API data retention](https://platform.claude.com/docs/en/manage-claude/api-and-data-retention): default-content statement, ZDR arrangements and Covered Models.
- [A4] Anthropic Privacy Center, [Organization retention](https://privacy.claude.com/en/articles/7996866-how-long-do-you-store-personal-data): standard 30-day deletion and policy/legal/feedback exceptions.
- [A5] Anthropic Privacy Center, [Training policy](https://privacy.claude.com/en/articles/7996868-is-my-data-used-for-model-training): commercial data use and permission/feedback exceptions.
- [G1] Google, [Gemini 3.1 Flash-Lite](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite): capability and model identity.
- [G2] Google, [Structured outputs](https://ai.google.dev/gemini-api/docs/structured-output): JSON Schema subset, constraints and semantic-validation warning.
- [G3] Google, [Gemini API pricing — 3.1 Flash-Lite](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.1-flash-lite): standard paid rates and thinking-token inclusion.
- [G4] Google, [Gemini API terms](https://ai.google.dev/gemini-api/terms): eligibility, paid/unpaid data use, billing linkage, regional exceptions and storage.
- [G5] Google, [Abuse monitoring](https://ai.google.dev/gemini-api/docs/usage-policies): 55-day retention and authorized review.
