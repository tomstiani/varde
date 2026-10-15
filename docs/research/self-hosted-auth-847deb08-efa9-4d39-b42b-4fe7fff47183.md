# Self-hosted backend authentication and secret management

**Request:** n8n sends alerts to ingress; native Android app fetches alerts over a user-selected network path. Assess rotation, least privilege, per-user deployment, push payload minimization, onboarding, and a first-release boundary.

## Recommendation

For a first release, use **two separate credentials and two deliberately narrow interfaces**:

1. **n8n → ingress:** require a high-entropy, per-deployment shared secret in an HTTPS request header. Configure n8n’s Webhook node with Header Auth when ingress is an n8n webhook; if ingress is a separate backend, enforce equivalent header authentication there. Give this credential only permission to submit alerts. Support replacement/overlap during rotation, and document revocation by removing the old value.
2. **Android → backend:** issue a distinct, per-user or per-installation opaque bearer token, limited to reading that user’s alerts. Send it only over HTTPS in the Authorization header. Store it in app-private storage protected using Android Keystore-backed encryption; provide revoke/re-enrolment. Do not ship a shared client secret in the APK.
3. **Network choice is configuration, not authentication:** let the user select/configure a reachable HTTPS base URL (for example, private VPN/tailnet endpoint or their own public domain), but authenticate and authorize requests at the backend in either case. A private-network overlay can reduce exposure, but is not a replacement for application authorization. Avoid assuming the endpoint is safe merely because it is on a private network.
4. **Push:** if push is used to wake/notify the app, send a generic signal or opaque alert identifier only. Fetch content from the backend after receipt and render a generic fallback when fetch fails. No sensitive alert body in push payload for v1.

This is a recommendation/inference from the source requirements below, not a claim that a particular product stack is mandatory.

## Findings and evidence

### n8n ingress credential

- n8n’s official Webhook documentation describes Header Auth as a configured header name and credential value the caller must send; this is a suitable simple machine-to-machine boundary when the workflow starts at a Webhook node. [n8n Webhook node: authentication](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.webhook/#authentication)
- n8n supports a custom `N8N_ENCRYPTION_KEY` for encrypting credentials in its database; absent a configured key it generates one on first launch. The key must therefore be backed up and protected as deployment secret material. [n8n deployment environment variables](https://docs.n8n.io/deploy/host-n8n/configure-n8n/environment-variables/deployment/#encryption)
- n8n documents encryption-key rotation as a supported process, with a full database backup required first and rotation enabled across main/worker instances. This concerns n8n’s stored credentials, not rotation of the separate ingress API credential. [n8n key rotation](https://docs.n8n.io/deploy/host-n8n/configure-n8n/security/rotate-encryption-keys/)
- Docker Compose secrets are mounted as files only into services granted access, under `/run/secrets/<name>`; Docker recommends this over passing sensitive values as environment variables. [Docker Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/), [Compose secrets reference](https://docs.docker.com/reference/compose-file/secrets/). **Deployment caveat:** a Compose secret sourced from a local file still requires securing and backing up that host file; the mechanism is not by itself a remote vault or automated rotation system.

**Inference:** A single, random, deployment-specific ingress key in n8n’s credential store and the backend’s secret store is materially simpler than standing up OAuth for a single trusted workflow client. A shared secret is a bearer credential: any party possessing it can act with its allowed authority. Use HTTPS, protect both copies, and avoid logs that expose the header. Separate credentials by deployment/user to limit blast radius. Rotation should permit an operator to install a new value in both ends, verify it, then invalidate the old one; overlap is an implementation design recommendation, not an automatic property of Header Auth.

### Android identity, token handling, and network path

- OAuth native-app best practice requires an external user-agent (normally the browser), and public native clients MUST use PKCE. It also explains that a native app cannot keep a client secret confidential. [IETF RFC 8252 §§4–6, 8.5](https://datatracker.ietf.org/doc/html/rfc8252)
- RFC 6750 states that bearer tokens are usable by whoever possesses them and requires TLS and certificate-chain validation when transmitting them. [IETF RFC 6750 §§2, 5](https://datatracker.ietf.org/doc/html/rfc6750)
- Android Keystore protects key material from extraction and can restrict its use; Android app-private internal storage is the appropriate app-scoped storage context. Together these support encrypting a persisted refresh/long-lived token with a Keystore-protected key. This storage pattern is a synthesis, not a verbatim Android prescription. [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [Android data storage](https://developer.android.com/training/data-storage/), [Android network security configuration](https://developer.android.com/privacy-and-security/security-config)
- Tailscale Serve exposes a service to tailnet users/devices under tailnet access-control policy, while Funnel makes a service internet-reachable. These are examples of network-path choices, not requirements and not substitutes for backend authorization. [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve), [Tailscale Funnel](https://tailscale.com/docs/features/tailscale-funnel), [Tailscale grants](https://tailscale.com/kb/1538/grants-syntax)

**Inference:** Support a user-editable endpoint/base URL and validate that it uses HTTPS for non-loopback remote use. Keep the app token audience/use narrow (read alerts only), associate it with a user or installation, and enforce ownership on every alert fetch. Make token expiry/revocation behavior explicit. If issuing opaque tokens, store only a verifier/hash server-side where feasible, show the secret only at enrolment, and provide independent per-installation revocation; these are recommended design choices, not features mandated by the cited RFCs.

**Options and tradeoffs:**

- **Manual API key entered in app:** lowest backend complexity, but onboarding and rotation UX are awkward; avoid one shared key for all app installs.
- **One-time pairing code → device token:** recommended v1 compromise for per-user homelab deployment. The operator creates a short-lived, single-use code out-of-band; app redeems it over HTTPS and receives a revocable per-installation read token. Short expiry, one-use enforcement, rate limiting, and scoped redemption reduce exposure. This flow is a recommendation, not an existing standard defined by the sources cited here.
- **OAuth Authorization Code + PKCE:** appropriate if deployment already has an OAuth/OIDC provider or future multi-user/SSO requirements. It brings browser redirect, provider configuration and lifecycle complexity; never embed a client secret. RFC 8252’s guidance applies if this route is selected.
- **Tailnet-only access:** reduces who can reach the endpoint if tailnet policy is appropriately restrictive, but makes onboarding require joining/configuring that network and does not provide per-app user/alert authorization. A user-selected path might instead be LAN or a public HTTPS hostname, so backend auth must remain path-independent.

### Push payload minimization

Firebase documents that FCM uses TLS point-to-point, but that is not end-to-end encryption. For sensitive content it recommends application-level E2EE or, alternatively, sending an empty/data signal and fetching content from the application server. It also notes that background fetch can be delayed or fail, so a generic notification fallback may be needed. [Firebase: secure message data with E2EE](https://firebase.google.com/docs/cloud-messaging/encryption)

**Recommendation:** v1 push payload contains no alert title/body or private metadata; use a generic “New alert” signal, optionally with a non-secret opaque identifier. Treat that identifier as a locator, not authorization. The app must authenticate independently before retrieving content. E2EE push could be evaluated later if offline detail display is a concrete requirement and the project can safely manage device keys.

## First-release boundary

**Include:** TLS-only remote API; separate n8n-ingress and Android credentials; n8n header auth; scoped backend checks (submit-alert vs read-own-alerts); per-installation Android token and revocation; simple one-time pairing/onboarding; user-configured HTTPS endpoint; documented key backup/rotation/recovery; generic push wake-up payload and authenticated fetch.

**Defer:** general-purpose OAuth authorization server, SSO, cross-user sharing/role matrix, client-certificate PKI, custom E2EE protocol, and automatic secrets-vault integration. Revisit OAuth when there is an existing issuer or genuine multi-user tenancy; revisit E2EE only if alert content must be available without a live authenticated fetch.

## Uncertainties and residual risks

- The exact ingress topology is unspecified. n8n Webhook Header Auth applies directly only when the receiving endpoint is the n8n Webhook node; a separate ingress service must enforce its own auth.
- “User-selected network path” could mean VPN/tailnet, LAN, or arbitrary host. DNS, certificate provisioning, captive portals, and endpoint changes will affect onboarding. Do not disable TLS validation to make self-signed certificates work; establish a deliberate trust/onboarding model instead.
- Per-user isolated deployments simplify data separation but may multiply secret backup, update, and recovery duties. No source here establishes a single best deployment model.
- Device compromise, rooted Android, malicious workflow edits, exposed backups, and operator mistakes remain risks. Keystore protection does not prevent an authorized compromised app/device from using a token.
- No source establishes push delivery timing as guaranteed; design fetch and display behavior for missed/delayed messages.

## Sources (primary)

- [n8n Webhook node](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.webhook/)
- [n8n security overview](https://docs.n8n.io/deploy/host-n8n/configure-n8n/security/)
- [n8n deployment environment variables](https://docs.n8n.io/deploy/host-n8n/configure-n8n/environment-variables/deployment/)
- [n8n rotate encryption keys](https://docs.n8n.io/deploy/host-n8n/configure-n8n/security/rotate-encryption-keys/)
- [Docker Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/)
- [Docker Compose secrets reference](https://docs.docker.com/reference/compose-file/secrets/)
- [IETF RFC 8252 — OAuth 2.0 for Native Apps](https://datatracker.ietf.org/doc/html/rfc8252)
- [IETF RFC 6750 — Bearer Token Usage](https://datatracker.ietf.org/doc/html/rfc6750)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android data storage](https://developer.android.com/training/data-storage/)
- [Android network security configuration](https://developer.android.com/privacy-and-security/security-config)
- [Firebase Cloud Messaging encryption guidance](https://firebase.google.com/docs/cloud-messaging/encryption)
- [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve)
- [Tailscale Funnel](https://tailscale.com/docs/features/tailscale-funnel)
- [Tailscale grants](https://tailscale.com/kb/1538/grants-syntax)
