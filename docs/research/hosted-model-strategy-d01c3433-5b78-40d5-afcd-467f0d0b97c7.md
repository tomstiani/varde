# Varde: first-release hosted-model strategy

Request ID: `d01c3433-5b78-40d5-afcd-467f0d0b97c7`

## Recommendation and evidence status

**Benchmark three candidates, without choosing a vendor:** OpenAI **GPT-5 nano**, Google **Gemini 3.1 Flash-Lite** on the paid Developer API, and Anthropic **Claude Haiku 5.5** on its direct API. Add **GPT-5.6 Luna** as an optional contemporary OpenAI comparison.

Use a replaceable backend adapter, a provider-independent view contract, deterministic validation, and original-text fallback. The model should select and order supplied information, not invent an interface or diagnose the homelab.

**Evidence:** model support, rates, and policies below were checked against directly fetched owner documentation. **Inference/proposal:** architectural choices, limits, benchmark design, and selection rationale. **Unknown:** actual access, latency, fidelity, token usage, account-specific retention arrangements, and quotas. No authenticated or paid model calls, credentials, implementation work, or GitHub changes were performed.

**Freshness caveat:** fetched owner pages superseded older search summaries. Google’s stable 3.5 Flash-Lite page is labeled July 2026; Anthropic’s Haiku 5.5 page states October 7, 2026. This report follows the pages actually served, not a remembered model catalog. Recheck links, account access, lifecycle status, and pricing before execution. Documentation-listed availability is not universal account eligibility. [G1][A1]

## 1. Contract and data fidelity

The requested contract is the foundation, not a claim about existing implementation:

- A versioned envelope contains one **ordered component list**. Allowed types: plain text, metrics, line charts, tables, and simple flows.
- Android owns styling, sizing, rendering, and accessibility. No generated layout instructions, executable code, HTML, SVG, Mermaid, expressions, arbitrary diagrams, or nested UI components.
- Original **title, summary, priority, and timestamp stay outside generation**, unchanged and available for fallback.
- Input is the alert payload only: no history lookup, monitoring queries, retrieval, browsing, or tools.
- Use supplied values only. No fabricated measurements, missing-data estimates, inferred timestamps, unit conversions, or derived aggregates in v1.
- An invalid whole view falls back to original text. Never salvage valid-looking components from a failed view or display partial streamed JSON.

### Proposed representation

Use an envelope such as `schemaVersion: "1"` and `components: [...]`. Ordinary typed component data does not constitute nested UI containers.

Prefer **references to supplied fields** over model-copied numbers. A metric selects a supplied value/unit; a chart selects a supplied series; a table selects supplied rows/columns. Resolve references deterministically against a payload-derived allowlist. References are data identifiers, never executable paths or expressions.

Reference existence alone is insufficient: validate value/unit/time/source associations. Identical numbers from different sources are not interchangeable. Use fixed schema structure rather than per-alert schema enums initially; Claude documents compilation/cache effects when schema structure changes. [A2]

For text and labels, prefer supplied strings or extractive spans. Free paraphrases can introduce unsupported causal claims despite perfect numeric fidelity. Do not interpret text as markup.

For charts, preserve supplied samples and gaps; do not interpolate, smooth, or connect across missing measurements as if observed. Omit unsupported charts. A simple flow is an explicitly supplied ordered sequence, not inferred topology or causality; no branching or cycles.

**Proposed benchmark limits, not settled product requirements:** 8 components, 160 characters per text block, 3 chart series with 120 points each, 20 table rows with 6 columns, and 8 sequential flow steps. Enforce locally. If limits require dropping data, use a documented deterministic subset without implying completeness, or fall back. Empty/no-useful views also fall back.

**Inference:** this constrained selection-and-ordering task is a plausible small-model workload. No reviewed source establishes Varde-specific quality or latency.

## 2. Candidate comparison

Prices: **USD per 1,000,000 tokens**, standard synchronous text requests, uncached input, excluding taxes. These are documented rates, not measured costs.

| Candidate | Verified price and capability | Benchmark role / uncertainty |
|---|---|---|
| OpenAI `gpt-5-nano-2025-08-07` | **$0.05 input / $0.40 output**; Structured Outputs, Responses and Chat Completions. [O1] | Cost-floor candidate. Vendor calls it fastest/cheapest within GPT-5; that is not a cross-provider latency result. Reasoning usage may dominate short JSON. |
| Google `gemini-3.1-flash-lite` | Stable; structured outputs. **$0.25 input / $1.50 output**, including thinking tokens. [G2][G3] | Independent cost-efficient extraction candidate. Access, thinking settings and exact schema acceptance need testing. |
| Anthropic `claude-haiku-5-5` | Structured outputs; positioned for latency-sensitive extraction/routing. **$0.10 input / $0.50 output** for prompts up to 100,000 tokens; **$0.50 / $2.50** above that threshold. [A1][A2][A3] | Strong listed small-prompt pricing; schema restrictions and retention wording deserve attention. |
| Optional OpenAI `gpt-5.6-luna` | **$0.20 input / $1.20 output**; Structured Outputs; supports `reasoning.effort: none`. OpenAI recommends it for most new speed/cost-sensitive workloads. [O1][O2] | Contemporary control against older nano; compare cost per accepted view, not token rates alone. |

Google also lists stable `gemini-3.5-flash-lite` with structured outputs at **$0.30 input / $2.50 output**. It is not in the first cost-focused round because no Varde evidence yet establishes a benefit from the higher rates. [G1][G3]

### Schema limitations

- **OpenAI:** use strict Structured Outputs, not plain JSON mode. The root must be an object, not root `anyOf`; all fields must be required, with nullable types for optional values; objects require `additionalProperties: false`. Nested unions can represent components below the envelope. Refusals and incomplete responses require explicit handling. [O3]
- **Google:** supports a JSON Schema subset including objects, arrays, required fields, enums, nullable values and numerical/array bounds. Very large/deep schemas can be rejected. Google explicitly requires application validation for semantically incorrect values. [G4]
- **Anthropic:** JSON output uses `output_config.format`; objects require `additionalProperties: false`. Supports basic types and unions, but not recursive schemas, numerical bounds, string-length bounds, or general array limits beyond `minItems` 0/1. Unsupported constraints can cause HTTP 400; SDK transformations may remove unsupported constraints, so inspect the transmitted schema. [A2]
- Claude documents initial schema-compilation overhead, grammar caching for 24 hours since last use, extra billed schema-guidance input, and refusal/truncation exceptions. It also documents possible enum capitalization differences: Varde should reject noncanonical values rather than silently relax its contract. [A2]

**Proposal:** keep a full canonical local validator and explicit provider schema projections. Verify the exact component-array union against each service. Enforce bounds, reference integrity and cross-field semantics locally even where a provider supports corresponding constraints. Schema conformity is not data fidelity.

## 3. Privacy and deployment suitability

Self-hosting the backend does not keep model-bound alert content local. Alert payloads can contain credentials, hostnames, addresses and log details; hosted generation is a disclosure decision.

| Service | Verified policy | Consequence |
|---|---|---|
| OpenAI API | No training on API data unless opted in. Abuse logs may contain prompts/responses and normally last up to **30 days**, with exceptions. ZDR/Modified Abuse Monitoring require approval. Responses has default application-state storage; `store: false` does not eliminate abuse logs or all caching retention. [O4] | Set `store: false`; avoid background/stateful features, files and tools. Do not promise zero storage. |
| Paid Gemini Developer API | Paid prompts/responses are not used to improve products. Abuse monitoring retains prompts/context/output for **55 days**; authorized review can occur for flagged content. Terms allow transient storage/caching where Google or agents operate. Unpaid services have different data-use terms and regional exceptions. [G5][G6] | Use an active billing-linked project for real data; avoid unpaid/Contributor/data-sharing modes. Terms restrict use to professional/business purposes, not consumer use: personal homelab eligibility is an unresolved gate, not solved by enabling billing. |
| Direct Claude API | Commercial data is not used for training absent permission. Privacy support gives deletion within **30 days**, with feature/agreement/policy/legal exceptions. Platform docs separately say conversation content is not retained by default except Covered Models and describe separately enabled ZDR. Haiku 5.5 is not named among Covered Models. [A4][A5][A6] | Owner wording is not fully reconciled. Conservatively disclose standard 30-day retention plus exceptions until confirmed for the account; do not advertise default ZDR. Schema artifacts have separate retention implications. [A2][A5] |

**Proposal:** explicit opt-in, backend-only API credentials, local field allowlisting/secret stripping, body logging disabled by default, and pseudonymization where practical. Keep identity mappings local. Do not automatically fail over to another provider without separate disclosure/approval.

**Unknown:** personal-user eligibility, ZDR access, region-specific routing, contractual terms and legal obligations. These remain deployment gates.

## 4. Explicit cost assumptions

Assume **2,000 billed input tokens** including instructions/schema/payload, and **500 billed output tokens** per attempt. No cache, tools, retries, batch discounts or extra thinking beyond those 500 billed output tokens. Tokenizers differ; equal payload bytes need not mean equal tokens. Google output prices include thinking, and OpenAI reasoning tokens are billed as output. [G3][O5]

`USD = (input_tokens × input_rate + billed_output_tokens × output_rate) / 1,000,000`

| Model | Assumed cost/attempt | 1,000 attempts | 10,000 attempts |
|---|---:|---:|---:|
| GPT-5 nano | $0.000300 | $0.30 | $3.00 |
| Gemini 3.1 Flash-Lite | $0.001250 | $1.25 | $12.50 |
| Haiku 5.5, small-prompt tier | $0.000450 | $0.45 | $4.50 |
| Optional Luna | $0.001000 | $1.00 | $10.00 |

A retry on 5% of alerts multiplies cost by **1.05** only if token usage is identical. Also report total spend divided by accepted views. Timeout cancellation is not a billing guarantee. These examples exclude backend hosting, taxes, regional premiums, credits and negotiated discounts. Actual reasoning and larger tables/charts can exceed the assumptions; there are no measured costs here.

## 5. Validation, retry and fallback strategy

**Proposed pipeline:** preflight → bounded provider request → terminal-response check → strict parsing/schema validation → fidelity/reference validation → rendering-bound validation → atomic complete-view publication.

- Preserve original text independently of generation. Preflight malformed or oversized input can bypass the provider immediately.
- Treat alert strings as untrusted data, never instructions; enable no tools and follow no embedded URLs.
- Reject duplicate keys, trailing material, extra fields, wrong versions, unsupported components, nested UI, nonfinite values, invalid references, changed units/times/sources, missing-data estimates and invented flow links. Validate prose too.
- Refusal, truncation, authentication/quota/API error, invalid output or deadline expiry returns original text. Never repair a measurement or silently drop an invalid component.
- Begin with **no model repair retry for invalid views**, respecting immediate whole-view fallback and creating a clean first-attempt baseline.
- Separately evaluate at most one transient transport/429/5xx retry when `Retry-After` and remaining deadline permit. No retries for invalid schema, authentication/permission, refusal, malformed source or fidelity failure. Disable or record SDK automatic retries.
- Use one monotonic deadline covering backend queueing, connection, generation, validation and allowed retries. Probe **2, 5 and 10 seconds** as benchmark operating points, not promises. Cancel best-effort on expiry; finalize fallback and ignore late results.
- Bound concurrency/pending work and deduplicate by alert revision, schema, prompt and model configuration. Cache only validated complete views; exclude cache hits from generation percentiles.

The production timeout and retry choice remain open. Token caps and reasoning parameters differ by API; record actual settings rather than assuming interchangeable controls.

## 6. Reproducible benchmark proposal

### Fixed synthetic corpus

Use **40 fixtures**, ten per family, with fixed IDs, seed, canonical bytes/hashes, source-fact oracle, and small/medium/near-limit sizes. Allow multiple valid component layouts.

| Family | Synthetic cases | Oracle |
|---|---|---|
| Metric + trend | CPU 83 `%` and supplied timestamped samples 20, 40, 83; multiple series, gaps, duplicate/unsorted timestamps, mixed units. | Exact source/value/unit/time binding; no fabricated points or interpolation. Any sorting must be deterministic and documented. |
| Table + flow | Service state/latency rows and explicit ordered sequence `sensor → broker → consumer`; absent-relationship variants. | Preserve rows/columns; no invented edges, state or root cause. |
| Sparse | Text-only, missing unit, null value, empty series, unavailable measurement. | Omit unsupported components; never invent history, units or estimates; empty/no-useful output falls back. |
| Malformed/adversarial | Invalid JSON/types, NaN-like strings, oversized arrays, duplicate keys, control characters, Unicode, missing references, embedded instructions to invent data. | Preflight-invalid cases make zero model calls; valid adversarial strings cannot change instructions; invalid output falls back atomically. |

These values define synthetic fixtures, not observed measurements. Include paired cases with the same number under different sources/units to catch naive membership-only fidelity checking. Coverage and usefulness must be scored separately so empty views cannot win.

### Execution protocol

1. Freeze prompt, canonical schema, provider projection, SDK/API versions, exact model ID, reasoning setting, output cap, deadline, concurrency and price snapshot. Record backend hardware/network region, account tier, run time and cache metadata.
2. Run **10 randomized repetitions per fixture per model**: 400 scheduled cases/model and 1,200 across the three main candidates. Keep preflight bypasses in product outcomes but exclude them from provider latency percentiles.
3. Start at concurrency 1; run a separate bounded burst condition, for example concurrency 4. Interleave model order; repeat across time windows where feasible.
4. Separate initial-schema/first-request observations from repeated fixed-schema traffic. A new connection does not prove a cold provider cache; label unknown cache state honestly.
5. Measure one-attempt behavior first. Test transient retries separately and log every attempt. Locally injected network errors are simulations, not measured provider reliability.
6. Obtain approval and a spending ceiling before any future paid execution. At the illustrative usage, **400 actual attempts per main candidate total $0.80**; optional Luna adds **$0.40**. This is not a cap: thinking, longer output and retries can increase cost. Preflight bypasses reduce actual calls.

### Metrics

Use monotonic clocks. **Generation end-to-end latency** starts when the backend accepts work into the generation pipeline and ends with a validated view or finalized fallback. Include queueing, networking, model completion, validation and allowed retries; report provider-call duration separately.

For each model/family/size/concurrency/cache/retry condition, record:

- Generation p50/p95 in milliseconds, sample counts, outcome counts, deadline misses and fallback rate.
- Success-only time-to-valid-view p50/p95, clearly labeled. Timeouts are censored observations, not successful completions at the deadline. Use nearest-rank percentiles (`ceil(p × n)`) and disclose small-sample uncertainty.
- First-attempt schema validity; fidelity validity conditional on schema validity; total accepted-view rate. Separate preflight bypasses, refusals, truncation, HTTP/transport errors and invalid generated views.
- Fidelity errors: fabricated measurements, changed values/units/timestamps, incorrect source associations, unsupported prose, invented edges and missing-data estimates. Score useful coverage separately.
- Provider-reported input/output/thinking/reasoning/cache tokens where exposed, cost per attempt, cost per alert including retries and cost per accepted view. Missing usage after timeout is unknown, not zero; reconcile billing where possible.
- Manual source-oracle review of every accepted view and Android checks for bounds and unchanged original metadata. Zero observed fidelity errors would not establish a universal guarantee.

**Phone-to-backend fetch latency stays separate.** Measure phone request/response timing and server fetch processing later across representative local/remote networks. Do not infer it from provider duration. Notification delivery and Android rendering are also separate spans.

**Notification-before-fetch is explicitly deferred until measured generation and fetch latency are available.** No sequencing decision is made here.

## 7. Compact next steps

1. Approve contract, limits, extractive text, supplied-only flows and fidelity oracle.
2. Recheck account access/prices; resolve Google personal-use eligibility and Anthropic retention wording.
3. Prepare synthetic corpus and provider schema projections without production credentials or real alerts.
4. Approve and run a bounded paid benchmark of the three main candidates; optionally add Luna.
5. Rank fidelity first, then p95/deadline behavior and cost per accepted view; retain original-text fallback regardless of vendor.
6. Measure phone fetch separately before revisiting notification sequencing.

## Primary sources and verification

All sources below were directly fetched and relevant passages inspected. Google pricing was additionally checked in raw HTML because readable extraction omitted model headings. A page-local extraction initially could not attribute prices; raw heading/table inspection resolved that limitation. No model APIs were executed.

- [O1] OpenAI: [GPT-5 nano](https://developers.openai.com/api/docs/models/gpt-5-nano)—snapshot, rates, endpoints, Structured Outputs and Luna recommendation.
- [O2] OpenAI: [GPT-5.6 Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna)—rates, reasoning controls and Structured Outputs.
- [O3] OpenAI: [Structured model outputs](https://developers.openai.com/api/docs/guides/structured-outputs)—schema subset, strictness and exceptions.
- [O4] OpenAI: [Data controls](https://developers.openai.com/api/docs/guides/your-data)—training, abuse logs, application state, caching and retention controls.
- [O5] OpenAI: [Reasoning models](https://developers.openai.com/api/docs/guides/reasoning)—reasoning-token output billing, usage fields and incomplete outputs.
- [G1] Google: [Gemini 3.5 Flash-Lite](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite)—stable ID, capabilities and page date.
- [G2] Google: [Gemini 3.1 Flash-Lite](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite)—stable ID and structured-output support.
- [G3] Google: [Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.1-flash-lite)—Standard paid rates, units and thinking-token inclusion; also checked 3.5 section.
- [G4] Google: [Structured outputs](https://ai.google.dev/gemini-api/docs/structured-output)—supported schema subset and semantic-validation warning.
- [G5] Google: [Gemini API terms](https://ai.google.dev/gemini-api/terms)—professional/business-use restriction, paid/unpaid data use and storage.
- [G6] Google: [Abuse monitoring](https://ai.google.dev/gemini-api/docs/usage-policies)—55-day retention and authorized review.
- [A1] Anthropic: [Haiku 5.5 overview](https://platform.claude.com/docs/en/models/haiku-5-5/overview)—release date, identifier, positioning, prices and thresholds.
- [A2] Anthropic: [Structured outputs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs)—supported models, constraints, compilation/caching, billed guidance and output exceptions.
- [A3] Anthropic: [Model pricing](https://platform.claude.com/docs/en/about-claude/pricing)—rates and qualifications.
- [A4] Anthropic Privacy Center: [Training policy](https://privacy.claude.com/en/articles/7996868-is-my-data-used-for-model-training)—commercial data and consent/feedback exceptions.
- [A5] Anthropic: [API and data retention](https://platform.claude.com/docs/en/manage-claude/api-and-data-retention)—feature retention, ZDR arrangements and Covered Models.
- [A6] Anthropic Privacy Center: [Organization data retention](https://privacy.claude.com/en/articles/7996866-how-long-do-you-store-personal-data)—30-day standard deletion and exceptions.

**Residual risks:** live documentation can change; personal account eligibility and exact schema support remain untested; Anthropic retention wording needs clarification; no measured latency, token costs, data fidelity, quota or privacy guarantees are established. The benchmark and production settings remain proposals, not implementation results.
