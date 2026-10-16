## Parent

Part of #11: First Android slice: named connections and a combined plain-text inbox. The approved parent specification is authoritative. Extend the working combined, paginated inbox rather than implementing a separate fallback feed.

## What to build

A person can continue reading and browsing healthy backends when another is unavailable, clearly see incomplete/stale results, and explicitly recover without hidden gaps or misleading empty states.

## Acceptance criteria

- [ ] Initial load, refresh and load-older allow successful sources to produce results when another fails or exceeds its deadline. Show a source-specific status and recovery action; do not wait indefinitely or retry automatically.
- [ ] Refresh stages new results while displayed content remains visible. Successful sources get fresh newest-result sessions. Failed sources retain their previously displayed rows and loaded details, visibly marked stale.
- [ ] Sort known stale rows together with current rows by the parent's display ordering, but never treat stale rows as continuation candidates in the fresh merge. Deduplicate displayed connection/alert identities.
- [ ] A continuation failure preserves that source's last successful buffer and cursor without advancing it, pauses further requests to that source, and permits healthy sources to continue. Do not expose paused-source buffer content as if it came from a complete healthy continuation.
- [ ] Distinguish no configured connections, genuinely empty successful history, partial results, all-source failure without retained content, and all-source failure with stale content. Never claim all-connections end-of-history while any source is incomplete.
- [ ] Recover a failed source by explicit full combined refresh, not by splicing recovered pages behind an already advanced continuation. New arrivals and recovered source history follow fresh session rules.
- [ ] Authentication failures preserve saved settings, mark retained content stale and direct the person to correct credentials. TLS, redirect and malformed/invalid cursor failures are actionable connection-specific errors; no credential or private content is echoed.
- [ ] Retain loaded details with a stale/error indicator after a transient failure. Never invent detail content when a fetch has not succeeded. An authenticated detail `404` does not clear unrelated rows.
- [ ] Exercise mixed healthy, slow, revoked and unreachable sources; failure at first page and later pages; concurrent connection edits; recovery; obsolete responses; retained stale rows across repeated refresh; and source exhaustion. Assert merge/cursor invariants through the inbox Interface and screen-visible error states.
- [ ] Preserve bounded concurrency/deadlines, secure settings, source identity, foreground-only requests and the absence of persistent alert caching. All earlier backend, transport and Android tests remain green.

## Scope boundary

No background recovery, automatic retry scheduler, public change sync or new TLS trust mechanism. Stale in-memory content does not promise offline access or access after server revocation across app restarts.
