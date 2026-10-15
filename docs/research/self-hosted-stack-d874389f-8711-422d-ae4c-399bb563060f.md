# Self-hosted homelab backend + native Android app: stack options

## Scope and assumptions

This compares practical first-release choices for one user, a backend hosted on a homelab machine, and a native Android client. It does not assume a VPN, tunnel, reverse proxy, or other network-path provider. “Self-hosted” describes the application backend; push delivery may still depend on a hosted platform. External reachability, TLS, authentication, backup destination, and threat model remain deployment decisions rather than properties of a chosen framework.

## Short recommendation

Start with **FastAPI + SQLite, packaged as a single Docker Compose service** and a **native Kotlin Android app** (a conventional UI architecture, local Room storage only if offline/cache needs justify it, and ordinary Android notifications). Let the app poll/synchronize when foregrounded or use WorkManager for deferrable reliable synchronization; do not promise immediate remote push in v1. Ship a signed APK for direct single-user installation. This minimizes moving parts and keeps the server-side state local to the one host. SQLite is explicitly suitable for server applications when the database is local and clients talk to the application server, not directly to the database file [1].

This is a recommendation by inference from the one-user/single-host constraints, not a claim that SQLite or FastAPI is universally best. If concurrent writers, multiple backend replicas, or richer relational operations become real requirements, move to PostgreSQL and maintain explicit database backups. If the backend must initiate prompt notifications while the app is backgrounded, decide whether reliance on Firebase Cloud Messaging (FCM) is acceptable; that is not fully self-hosted push [6].

## Viable combinations

| Backend / packaging | Deployment and maintenance | Testability | Fit |
|---|---|---|---|
| **FastAPI + SQLite + Docker Compose** | One application container plus a durable host-mounted database file; Compose supports production overrides/restart policies and health checks [2]. SQLite guidance supports a local database behind an application server [1]. | FastAPI's TestClient exercises the app without a live network socket, using HTTPX and pytest [3]. | Best first-release default for one host/user, assuming modest write concurrency and disciplined backup of the data file. |
| **FastAPI + PostgreSQL + Docker Compose** | Adds a database service and operational work (persistent volume, upgrades, credentials, backup/restore). PostgreSQL's `pg_dump` produces database dumps; cluster-wide roles require `pg_dumpall` [4]. | Same FastAPI TestClient path; integration testing must additionally run PostgreSQL to validate actual SQL/schema behavior [3][4]. | Choose if data/concurrency needs justify a separate client/server database. A Compose health check/startup dependency is useful but does not replace backup or recovery [2]. |
| **Ktor server + SQLite/PostgreSQL + Compose** | Kotlin on both sides may enable language familiarity, but does not remove database/runtime/container operations. Ktor documentation supports server configuration/deployment; the selected DB determines most state operations [5]. | Ktor `testApplication {}` runs the app in-process and issues client requests without starting a real server [5]. | Credible when Kotlin is already the operator's stronger backend language; no evidence here that it is inherently lower-maintenance than FastAPI. |

A raw systemd process is also possible, but Compose is a compact packaging boundary for a homelab deployment. Docker documents production-specific Compose configuration and restart policies [2]. A health check reports health; it is not a substitute for monitoring/recovery, nor does it alone restart an unhealthy process [2]. Persist application data outside the disposable container and test restoration; this operational recommendation follows from container replacement and database durability requirements, rather than a claim that Compose itself provides backups.

## Android client options and notifications

**Native Kotlin (recommended).** Android's architecture guidance describes UI and data layers, repositories, and unidirectional data flow [7]. Compose has official UI testing guidance [8], while Room is Android's SQLite abstraction and includes database testing guidance [9]. Use Room only where local persistence/offline behavior warrants it; it is not required merely because the server uses SQLite. WorkManager is intended for persistent background work across app restarts/reboots [10], but it is not a precise-time scheduler. For time-critical local reminders use Android alarms; Android recommends inexact alarms unless exact timing is necessary, with exact-alarm access restrictions on newer Android versions [11]. Android 13+ notification runtime permission affects whether app notifications can be shown [12].

**Notification approaches:**

- **Local notifications:** app schedules a notification on-device. This needs no server push service, but the server cannot directly deliver a new event to a sleeping/offline app. WorkManager is suitable for deferrable work, not immediate delivery [10].
- **FCM remote push:** feasible with a self-hosted application server, but the server sends through Firebase/Google's FCM infrastructure [6]. This introduces Google service/project credentials and an external dependency, so it should be an explicit product choice, not described as fully self-hosted.
- **Foreground polling/sync:** simplest to reason about and test; updates arrive only when the app next checks. This latency tradeoff is an inference from polling behavior, not a platform guarantee.

For distribution, a signed APK can be installed outside Google Play, though users may need to permit installs from that source. An Android App Bundle is a publishing format, not directly installable; `bundletool` can generate APKs from bundles [13]. For a single owner, a signed APK is the simplest initial path; document update/signing-key retention.

## Recommendation rationale and decision gates

1. Keep the backend as a small HTTP API and keep the database inaccessible directly from the phone. SQLite's owner documentation endorses local server-side use and warns against multiple computers directly sharing a network SQLite file [1].
2. Begin with one Compose-managed service and one durable database file. Add a production override/restart policy and health endpoint; health checks are diagnostic, not backup or full supervision [2].
3. Use FastAPI TestClient for fast API tests and maintain at least one persistence/restore-oriented integration test. If PostgreSQL is selected later, test against PostgreSQL rather than assuming SQLite exercises the same database behavior [3][4] (the latter is an engineering inference).
4. Build a native Kotlin app, use local notifications for locally known events and foreground sync for server state. Add FCM only after deciding the Google dependency and background-delivery expectations [6][10].
5. Revisit SQLite when there is demonstrated need for concurrent writers, multi-instance deployment, or database features. Revisit push when notification latency is a requirement. Choose reachability/TLS/authentication based on the actual homelab exposure model; this report does not prescribe a network-path provider.

## Evidence versus inference / limits

- **Source-backed:** Compose supports production overrides/restart policies and service health checks; FastAPI and Ktor offer in-process HTTP testing; Android documents persistent work, notification permissions, alarms, app architecture/testing, and APK/AAB distinctions; FCM uses Google's service; SQLite advises local server-side access rather than sharing its DB file over a network.
- **Inference:** FastAPI + SQLite + Compose is likely the lowest operational burden for a modest single-user, single-host first release. Relative maintenance costs are contextual, not quantified by the cited sources. APK distribution and foreground sync are recommendations based on the single-user assumptions.
- **Unresolved:** workload/write concurrency, data sensitivity, recovery objectives, Android device/Google-services availability, offline requirements, update mechanism, remote access, and notification latency. These can change the recommendation. No network-path provider was assumed.

## Primary sources

1. SQLite, “Appropriate Uses For SQLite” and “SQLite Over a Network”: https://www.sqlite.org/whentouse.html and https://www.sqlite.org/useovernet.html
2. Docker Docs, “Use Compose in production”; Compose services and startup order: https://docs.docker.com/compose/how-tos/production/ ; https://docs.docker.com/reference/compose-file/services/ ; https://docs.docker.com/compose/how-tos/startup-order/
3. FastAPI, Testing and TestClient: https://fastapi.tiangolo.com/tutorial/testing/ ; https://fastapi.tiangolo.com/reference/testclient/
4. PostgreSQL, `pg_dump` and backup/restore: https://www.postgresql.org/docs/current/app-pgdump.html ; https://www.postgresql.org/docs/19/backup-dump.html
5. Ktor, server documentation and testing: https://ktor.io/docs/ ; https://ktor.io/docs/server-testing.html
6. Firebase Cloud Messaging, server environment and HTTP v1: https://firebase.google.com/docs/cloud-messaging/server-environment ; https://firebase.google.com/docs/cloud-messaging/send/v1-api
7. Android Developers, app architecture: https://developer.android.com/topic/architecture
8. Android Developers, Compose testing: https://developer.android.com/develop/ui/compose/testing
9. Android Developers, Room overview and database testing: https://developer.android.com/training/data-storage/room ; https://developer.android.com/training/data-storage/room/testing-db
10. Android Developers, persistent background work: https://developer.android.com/develop/background-work/background-tasks/persistent
11. Android Developers, alarms and exact-alarm changes: https://developer.android.com/develop/background-work/services/alarms ; https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
12. Android Developers, notification permission: https://developer.android.com/develop/ui/compose/notifications/notification-permission
13. Android Developers, app bundle FAQ, alternative distribution, bundletool: https://developer.android.com/guide/app-bundle/faq ; https://developer.android.com/distribute/marketing-tools/alternative-distribution ; https://developer.android.com/tools/bundletool
