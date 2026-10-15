# Decision-model APIs for Varde: Jev and OpenAI Decisions

**Research status:** Partial, source-grounded review. The OpenAI product can be identified with high confidence as the **Decisions API**. “Jev” is ambiguous: there are at least two plausible, materially different products, and the user's visual-composition description best matches a third-party demo/integration rather than the Jev model API itself. Do not treat similarly named products as interchangeable.

> Historical survey: this predates confirmation of the intended TypeSafe/json-render example. For the subsequently tested provider and observed behavior, see [alert-view experiment findings](alert-view-experiments.md). Product identity questions below are not current implementation decisions.

## Executive summary

- The user's phrase **“the new OpenAI decision API”** matches OpenAI's official **Decisions API**, a public-beta API at `POST /v1/decisions`. OpenAI documents a single supported model, `gpt-6-luna`, and bounded question types (`predicate`, `choice`, `score`). This is an API for typed judgments, not an automatic model router and not an API for generating Varde's complete component JSON. [OpenAI Decisions guide](https://developers.openai.com/api/docs/guides/decisions)
- **“Jev” is not uniquely identified.** Search surfaced (a) **Jev Model** at `jevmodel.net`, whose owner-published repository says it is independently operated and explicitly not affiliated with TypeSafe; and (b) the **Jev** decision model associated with TypeSafe, whose official site/repo are at `jevmodel.net` / `github.com/jevmodel/typesafe-jev-model` according to that repository. The repo itself points to `jevmodel.net`, not `jevmodel.com`. Separately, the described “demo arranging existing UI components using JSON” resembles an experimental **json-render + Jev** integration; that is an integration/demo, not evidence that Jev itself is a layout API. [Jev Model repository](https://github.com/jevmodel/typesafe-jev-model); [json-render Jev page](https://json-render.dev/docs/jev)
- These can be complementary layers: a decision endpoint can classify/escalate, while a reasoning/generation endpoint can propose a constrained view. But Varde must keep deterministic whole-view validation and original-text fallback as the enforcement boundary. Exact-ID references, no fabricated metrics, and no metadata override cannot be established by a general typed-choice API; encode those as schema constraints and validator checks.
- Do not select a provider based on the name alone. Request the exact Jev URL/repository or screenshot/demo link from the user before evaluating that product. The surfaced Jev Model evidence below is useful but does not prove it is the intended Jev.

## Identity and ownership: evidence versus uncertainty

### OpenAI: identified

OpenAI's official guide names the product “Decisions,” says it is in **public beta**, says only `gpt-6-luna` is currently available, and directs callers to `POST /v1/decisions`. It distinguishes Decisions from using the Responses API with Structured Outputs when an application needs an arbitrary JSON-schema object, and from function calling. [Official guide](https://developers.openai.com/api/docs/guides/decisions)

**Availability caveat:** documentation states public beta; it does not establish account-by-account access, service-level guarantees, regional availability, or that the product will remain unchanged. The official API reference URL surfaced in search but direct content retrieval returned HTTP 404 during this research, so detailed reference-level claims are limited to the guide.

### Jev: ambiguous

The repository `jevmodel/typesafe-jev-model` describes a “Jev Model” for typed decisions, links to `https://jevmodel.net`, its documentation/pricing pages, and a Hugging Face demo. It explicitly states: “Jev AI is an independently operated product and is not affiliated with, operated by, or endorsed by TypeSafe.” This is strong evidence that **the repository's product is not TypeSafe's product**, despite the repository's namespace/name. [Repository README](https://github.com/jevmodel/typesafe-jev-model)

That README describes an API at `POST https://jevmodel.net/v1/systemone`, model name `jev-latest`, supported question types `Choice`, `Score`, and `Noul`, and text/JSON/arrays-of-text input. These are repository-owner claims and identify a plausible API, but they cannot settle whether the user means it. The requested UI-composition clue more directly points to the json-render experimental integration, where Jev is used to select/order pre-existing UI candidates and json-render converts selections into a UI spec. The integration's existence does not make the underlying Jev decision model a general UI-layout generator. [Jev Model README](https://github.com/jevmodel/typesafe-jev-model); [json-render Jev docs](https://json-render.dev/docs/jev)

**Unresolved identity:** Exact canonical owner and current release status for the demo described by the user could not be independently pinned down from the limited official material retrieved. Ask for the precise URL/repository, or the product's exact name as shown in its docs. In particular, clarify whether “Jev” means `jevmodel.net` Jev Model, TypeSafe's Jev, `json-render`'s experimental Jev integration, or another demo. Avoid calling it “TypeSafe Jev” based only on repository naming.

## Capability comparison (conditional on Jev identity)

| Dimension | OpenAI Decisions API (verified) | Jev Model at `jevmodel.net` (plausible match; owner-published repo, identity not confirmed as user's target) |
|---|---|---|
| Decision/routing | Bounded predicate/choice/score answers. App maps a choice to route/action; guide frames classification/routing as application use. It does not itself invoke another model. [OpenAI guide](https://developers.openai.com/api/docs/guides/decisions) | Choice/Score/Noul typed answers; README explicitly positions it for routing/model/workflow selection, while application owns permissions, thresholds, and action. [README](https://github.com/jevmodel/typesafe-jev-model) |
| Component/layout selection | No documented UI component/layout selection primitive. A `choice` can select among supplied categories, but it does not author ordered component trees or exact data bindings. Use separate structured generation if required. [OpenAI guide](https://developers.openai.com/api/docs/guides/decisions) | Typed `Choice` can select a bounded option, but surfaced product docs do not prove support for ordering multiple components, binding exact data IDs, or producing the Varde view schema. `json-render + Jev` is a separate experimental composition path. [README](https://github.com/jevmodel/typesafe-jev-model); [json-render docs](https://json-render.dev/docs/jev) |
| Structured output and reference fidelity | API returns typed answers with supplied question names and, for choices, one supplied choice. This is stronger than free text for routing; it is not a guarantee that arbitrary nested JSON preserves exact IDs. [OpenAI guide](https://developers.openai.com/api/docs/guides/decisions) | README claims typed outputs, question IDs, option probabilities/confidence, and state may be a JSON object. Exact-ID fidelity for arbitrary supplied-data references is not documented in the retrieved excerpt. Validate at Varde backend regardless. [README](https://github.com/jevmodel/typesafe-jev-model) |
| Examples/setup | Official guide provides API examples and documents SDK minimum versions: Python 3.26.0, JS 7.30.0, Go 3.73.0, Ruby 0.101.0, Java 4.78.0. It defines per-request questions and options/levels; no training is documented as required. [OpenAI guide](https://developers.openai.com/api/docs/guides/decisions) | Repository supplies endpoint/request example, says bearer API key, state/model/questions; also links playground/docs/pricing. The inspected source does not establish whether tuning/training is available or needed. [README](https://github.com/jevmodel/typesafe-jev-model) |
| Latency | OpenAI says “up to 10x faster than the Responses API.” This is vendor-stated comparative positioning, not a Varde benchmark, workload-specific percentile, or end-to-end guarantee. No measured Varde latency was run. [OpenAI guide](https://developers.openai.com/api/docs/guides/decisions) | README says response elapsed time includes validation and should not be treated as pure model inference time. No independently measured latency evidence was found. [README](https://github.com/jevmodel/typesafe-jev-model) |
| Pricing | Do not rely on search snippets for prices: no pricing claim is made here because the directly retrieved official guide excerpt did not provide a verified price schedule. Confirm current official pricing before any paid experiment. | Repository links its own pricing page, but direct fetch of `jevmodel.com` failed and the repo points to `jevmodel.net`; therefore current unit prices are unverified here. No comparison can responsibly be made. |
| API/SDK | Dedicated endpoint `POST /v1/decisions`, with official SDK examples in guide. Cloud-hosted OpenAI API. [OpenAI guide](https://developers.openai.com/api/docs/guides/decisions) | Repository documents `POST https://jevmodel.net/v1/systemone`; server-side API key. Exact SDK inventory and operational commitments not verified in retrieved source. [README](https://github.com/jevmodel/typesafe-jev-model) |
| Self-hosting/privacy | OpenAI API is hosted; no self-hosted Decisions deployment is documented in the cited guide. Do not send sensitive alert data until Varde reviews current OpenAI data-use, retention, and regional-processing terms for its account. | The surfaced README describes an API endpoint and hosted playground, not downloadable weights or self-hosting. Self-hosting cannot be assumed. Review the identified owner's current privacy/security terms before sending alert state. |

## Fit to Varde's architecture

Varde needs an ordered, approved list of components, each referencing supplied data by exact ID, with deterministic backend validation and fallback to original text. The Responses-vs-Decisions distinction in OpenAI's guide matters: Decisions is suitable for compact bounded decisions (e.g. `needs_reasoning`, `layout_class`, or `allowed_component_family`); arbitrary schema-conforming view generation belongs to a structured-output/generation capability, not the Decisions API by itself. [OpenAI Decisions guide](https://developers.openai.com/api/docs/guides/decisions)

A safe division of responsibilities to test—not a claim of product guarantees—is:

1. Backend prepares a minimal, typed state with stable IDs and approved component/data catalog.
2. Optional decision call classifies alert or determines whether reasoning is warranted.
3. Reasoning/generation call proposes the complete ordered view against an explicit schema/catalog. The handoff format remains an experiment, as requested.
4. Backend rejects the entire view if any component, ordering/shape, or data reference violates policy; otherwise render. On rejection/error/timeout, use original text.

Neither model should be trusted to enforce “no copied/generated measurements,” “no metadata override,” or “no arbitrary code.” These are deterministic backend contracts. Restricting generated fields to exact IDs and allowed component types reduces the model's degrees of freedom but does not replace validation.

## Small experiment plan (not run)

Use a fixed synthetic corpus of alerts and canonical labels/views; no real user data, credentials, or paid calls without explicit approval. First dry-run fixtures against the local deterministic validator.

1. **Direct selection:** For each alert, ask a single endpoint to select a bounded presentation class/component plan. Compare exact component order, ID binding, fabricated-data rate, validator acceptance, and fallback rate against gold labels.
2. **Escalation routing:** Ask a decision endpoint only whether to escalate to a reasoning model, with a conservative `uncertain/review` route. Measure false negatives (missed complex alerts), false positives (unnecessary reasoning calls), and expected cost/latency as `P(route)*reasoning-call-cost` plus decision-call cost.
3. **Handoff alternatives:** Compare (A) decision first, then pass original alert plus IDs/state to reasoning; (B) reasoning first, then decision selects/validates from a finite candidate set; and (C) one generation call for the view, with deterministic backend validation. Keep the handoff schema identical where possible. Test whether passing a compact decision label loses useful context versus passing the full state.
4. **Multi-call overhead:** Record end-to-end p50/p95 and error/timeout rates. A serial decision→reasoning path necessarily incurs an extra network/model round trip relative to one call; do not infer the delta from vendor marketing. If calls can be parallelized, only do so for independent questions and account for duplicated input/cost.
5. **Approval gate:** Begin offline with fixtures. Only after identity, security, retention, pricing units, and access are verified, request explicit approval before any paid/vendor call. No benchmarks or paid calls were made for this report.

## Recommendation and unresolved questions

**Recommendation:** Treat OpenAI Decisions as a plausible *routing/classification layer*, not a substitute for constrained component-view generation. Keep generation and deterministic validation separate. Do not choose or integrate a Jev product until the user supplies its exact URL/repository, because “Jev” currently resolves to distinct products/integrations and the demo clue does not uniquely identify an owner/API.

Before a trial, resolve: (1) which Jev URL/repository is meant; (2) whether the intended Jev product is available now and its authoritative pricing units; (3) current provider retention, training-use, region, and deletion terms; (4) whether the proposed reasoning model supports the required structured schema and exact-ID constraints; (5) which labels and synthetic corpus define “needs reasoning”; and (6) acceptable latency/cost and fallback thresholds. The reasoning-to-decision handoff should remain undecided until the proposed comparative experiment is run.

## Sources and scope notes

- OpenAI, [Decisions guide](https://developers.openai.com/api/docs/guides/decisions) — primary API documentation; source for beta/model/types/SDK/latency positioning and distinction from structured outputs.
- OpenAI, [Decisions API reference (create)](https://developers.openai.com/api/reference/resources/decisions/methods/create) — surfaced by search, but direct content retrieval returned 404 during research; no unsupported reference-level details inferred from it.
- Jev Model repository, [jevmodel/typesafe-jev-model](https://github.com/jevmodel/typesafe-jev-model) — owner-published README for the `jevmodel.net` product; notable explicit non-affiliation statement and API description.
- Vercel Labs, [json-render experimental Jev integration](https://json-render.dev/docs/jev) — primary project documentation for the demo-like component arrangement clue; not evidence that this integration is the user's intended Jev product.

Research was limited to retrieved first-party documentation/repository and public search. Direct retrieval of `jevmodel.com` failed, and pricing/privacy/self-hosting claims for the ambiguous Jev product therefore remain unverified. No API calls, credentials, paid usage, code changes, or benchmarks were performed.
