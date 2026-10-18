## Parent

Part of #19: Android alert notifications and missed-delivery recovery. The approved parent specification is authoritative. This slice can proceed independently of the real FCM Adapter once registration and controls are complete.

## What to build

Recover missed alerts without opening the app, using battery-conscious Android background scheduling and the same durable coordinator, notification controls and real backend change stream as foreground recovery.

## Acceptance criteria

- [ ] Schedule unique, network-constrained Android background work rather than exact alarms or a permanent foreground service. Document a supported periodic interval and best-effort OS/power-management/force-stop limitations, without promising exact delivery times.
- [ ] Worker, app-opening recovery and push follow-up use the same per-connection coordinator and serialized/coalesced state. Repeated scheduling and concurrent triggers cannot create parallel cursor advancement or duplicate interruptions.
- [ ] Each execution processes a finite bounded number of pages/requests with existing transport deadlines and concurrency bounds, then persists unfinished fixed-horizon progress and resumes later. Cancellation, process death or retry cannot lose unrecorded changes.
- [ ] A completed recovery pass posts one correctly counted catch-up summary per eligible connection; presentation changes and previously announced/covered alerts stay silent. Do not prefetch the archive or all recovered originals.
- [ ] Muted or permission-denied connections retain recovery/read availability without notifications or accumulating a later audible backlog. Activation/re-enable still uses the current silent registration baseline.
- [ ] One unavailable backend does not block healthy recovery. Background network/authentication/configuration failures update safe connection-specific app status and retry appropriately without posting outage notifications or tight retry storms.
- [ ] Connection rename/removal, endpoint/credential changes, notification toggles and destination/session updates cancel or fence obsolete workers/results. Removed access cannot be resurrected after process restart or a delayed scheduled run.
- [ ] Background processing does not mutate an active older-history snapshot, reset browsing position or weaken release transport/cache/credential protections.
- [ ] Deterministic scheduler/coordinator tests cover unique work, bounded runs, resumption, concurrent triggers, multiple connections, muted/denied settings and stale-generation cancellation. Real Go transport tests verify missed alerts after outage/restart through the same recovery contract.
- [ ] Add platform worker tests using supported Android test facilities and document an opt-in device recovery procedure. Report unavailable genuine background/power-management observations separately from fake-trigger tests; ordinary tests require neither homelab access nor Google credentials.
