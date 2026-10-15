# Alert-view experiment findings

Small synthetic experiments support using bounded decision-model choices to select prepared rich-view candidates. The strongest prompt met the proposed selection/fallback expectations on ten service-style alerts. This is enough to inform architecture, not evidence of production reliability.

This document preserves findings from local throwaway experiments. Runnable benchmarks, synthetic fixtures, raw response reports, and the HTML prototype are intentionally not committed. Consequently, these findings are historical observations, not independently reproducible results from this checkout.

## Design tested

The backend prepares approved candidates bound to original supplied data. A decision model selects candidate IDs and preferred positions; deterministic code assembles and validates the result. Models do not author measurements, estimate missing samples, change original alert metadata, or send executable rendering code.

The approved presentation families are text, metrics, line charts, tables, and simple flows. The layout is a versioned ordered list; Android owns styling. Invalid whole views fall back to original text.

Two provider-neutral model interfaces were exercised conceptually and offline:

- **DecisionModel:** bounded choices against supplied state.
- **LLM:** structured generation, potentially used for a relevance brief before final selection.

The experimental direct path used separate routing and selection calls. An optional reasoning handoff would add an LLM call; no live reasoning adapter was tested. Selection was also evaluated independently of routing so an escalation choice could not conceal selection quality.

Live decision calls used TypeSafe directly, pinned to `jev-1.13.0`. No AI Gateway or json-render runtime dependency was required. The json-render prepared-candidate example informed the experiment, not the production dependency list.

Sources for provider behavior: [TypeSafe quickstart](https://docs.typesafe.ai/introduction/quickstart), [model documentation](https://docs.typesafe.ai/models.md), and [json-render Jev example](https://json-render.dev/docs/jev). Provider availability and pricing must be rechecked before implementation.

## Observed runs

All live payloads were synthetic. Each task/case received one sample per run. No live run automatically retried a failed request.

| Run | Requests | Reported input / output tokens | Main observation | Estimated USD |
| --- | ---: | ---: | --- | ---: |
| Initial routing smoke | 1 | 903 / not retained in this summary | Requested reasoning; no configured LLM, so no rich view | 0.000037926 |
| Original eight-case comparison | 24 | 23,851 / 2,110 | Both routing prompts chose direct on all eight; selection met baseline checks on seven | 0.001001742 |
| Ten service-style alerts | 20 | 22,195 / 2,432 | Nine selection outcomes matched proposed expectations; one unrelated metric remained | 0.000932190 |
| Focused relevance comparison | 8 | 14,012 / 2,390 | Old prompt met three of four expectations; stricter relevance prompt met all four | 0.000588504 |
| Full relevance regression | 10 | 14,839 / 2,098 | Nine useful rich views plus one expected original-text fallback | 0.000623238 |

Cost estimates use the then-published rate of $0.042 per million input tokens with output free. Actual billing was not independently verified. Request counts and prices are historical, not spending authorization for another test.

Median individual request duration was approximately 251–256 ms in the original comparison and 244–246 ms in the first service-style run. These are descriptive small-sample observations, not production percentiles, full-pipeline latency, or Android delivery measurements.

## What the original corpus revealed

The original eight cases covered a metric/trend, critical technical alert, backup table/flow, generic text, missing history, untrusted text, conflicting sources, and mixed relevance.

- A generic note was structurally valid but not useful.
- Missing-history selection omitted the sole generic note and fell back. This remained a benchmark failure under its original expectation; later experiments did not relabel it as a success.
- Two metrics could retain correct references yet imply a misleading association if their different sources were not visible.
- A null chart sample was preserved, not filled with an invented measurement.
- Embedded instructions did not produce fabricated measurements in the tested case. This is not broad prompt-injection assurance.
- Some summaries explicitly described what to display, making the first corpus easier than realistic service messages.

## Service-style expectations and results

The second corpus used natural event reports rather than presentation instructions. Expectations were agent-proposed and frozen before the live evaluation; they are not independently adjudicated ground truth.

| Case | Expected useful content | Result with stricter relevance prompt |
| --- | --- | --- |
| CPU high | CPU metric and supplied history | Met |
| Checkout slow | Checkout p95/history; deployment context optional; omit unrelated worker CPU | Met |
| Partial backup | Job table and supplied stages; timeout log optional | Met |
| Certificate renewal | Concrete ACME failure note | Met |
| Missing history | Meaningful collector error, with no invented measurements | Met |
| No useful rich data | Omit generic receipt marker; retain original-text fallback | Met |
| Conflicting readings | Both readings with their source/time identities; omit unrelated CPU | Met |
| Stale reading | Last measurement and polling-status note; preserve old measurement time | Met |
| Untrusted log | Diagnostic string as data, not instructions | Met |
| Mixed units/subsystems | Checkout latency and timeout table; omit unrelated batch-worker latency | Met |

In the first service-style run, routing requested reasoning for conflicting readings. Independently tested selection still retained both readings and omitted unrelated CPU. No LLM call was made, so that result is not end-to-end reasoning-path success.

The original selection prompt included unrelated batch-worker latency alongside the relevant checkout data. Measurements and units remained correct; the failure was relevance, not fabrication.

## Relevance rule tested

The revised prompt anchored relevance to the affected service, resource, and event in the original alert. Similar metric names or units alone were insufficient. It allowed another scope only when supplied facts explicitly connected it to the event, and allowed distinct collectors measuring the same affected resource even when their readings conflicted.

The focused comparison contained the previously observed mixed-subsystem failure and three new holdouts:

1. Identical filesystem labels/units on different hosts.
2. A backup failure with an explicitly implicated remote-storage dependency.
3. Conflicting collectors of one rack's temperature, plus an unrelated rack reading.

Both prompts passed the three new holdouts. The original prompt repeated the known failure; the revised prompt omitted the unrelated latency. Prompt order was counterbalanced across cases. The subsequent full service-style regression met all ten proposed rich-view/fallback outcomes.

The local prompt identifier was `candidates-v3-relevance`. It remained opt-in; the prototype's default was not silently changed. Its exact serialized requests and fixtures are not part of the committed evidence.

## Architectural consequences

- Keep original alerts authoritative and readable independently of generation.
- Put candidate preparation, reference resolution, validation, and fallback inside the presentation module.
- Preserve source, units, and measurement time visibly; reference correctness alone is insufficient.
- Keep decision and reasoning interfaces separate. A reasoning model is not yet justified as the remedy for the observed relevance error.
- Measure selection usefulness, routing behavior, latency, and spending separately.
- Production retries require durable per-step accounting. The local experiments had no production scheduler and do not establish crash-recovery correctness.

The later [backend architecture proposal](../architecture/backend.md) supersedes the experiments' no-retry execution policy for production: transient paid steps may receive up to three repeats under durable per-step and daily budgets.

## Limits

There are no positive reasoning-required examples, no repeatability study, no real homelab corpus, and no tested production backend or Android renderer. Prepared typed data avoids the separate problem of extracting facts from arbitrary unstructured messages. Global ordering quality, notification timing, phone delivery latency, and production source/time presentation remain unverified.

The final local offline suite passed 74 tests. That observation covered the throwaway harness and request/scoring logic, not the proposed production contracts. With the runnable experiments intentionally uncommitted, it is not a test suite available in this checkout.
