# Debate: cross-request authentication for this GraphQL API

Feeds: [changes/scope-createvote-to-session-user/DEFERRED-session-persistence.md](../../changes/scope-createvote-to-session-user/DEFERRED-session-persistence.md) — this debate exists to give that deferred ticket's eventual implementer a real decision to start from, instead of another blank architecture discussion.

## The fork

**Should this app's cross-request authentication be implemented as stateless signed JWTs (client holds the token, sent via an `Authorization` header, verified per-request with no server-side lookup), or as server-side sessions (server holds session state, referenced by an opaque session id, backed by a cache/DB)?**

Context: today there is zero cross-request session mechanism at all. `login` exists but nothing persists `currentUser` across HTTP requests — every `Authorized` mutation only works if `login` and the mutation are sent together in one GraphQL document (see the linked ticket). Either option replaces that with real, durable, cross-request authentication.

## Decision criteria

1. **Request latency overhead** for auth verification, per authenticated request.
2. **Implementation complexity** added to this specific codebase and its current dependency set (Akka HTTP, Sangria, Slick/H2 — no existing session or crypto/token libraries today).
3. **New operational infrastructure required** — zero new infrastructure vs. standing up and operating a new stateful service.
4. **Revocation**: ability to immediately invalidate a single compromised credential before its natural expiry.
5. **Team/stack fit** — this is a small, single-developer, kata-scale app, not a team with existing auth infrastructure or a horizontal-scaling requirement today.

## Isolation

Two fresh subagents (Tier B: no shared conversation/memory, no tool use, self-reported "Tool calls made: none" on every turn), one assigned to each side, run for Round 1 (independent case) and Round 2 (cross-pollinated objection) in the same per-side session so each side's Round 2 is a genuine rebuttal to the other's actual words, not a guess. Neither side saw the other's case until Round 2, and neither saw this document being written.

## Round 1 — each side's strongest case

**For JWT:** Ed25519/HMAC signature verification costs low single-digit microseconds in-process, versus a session lookup that adds a network round-trip (1–5ms) — 10–100x slower. Implementation is minimal: an Akka HTTP directive extracts the header, a JWT library verifies it inside the existing `AuthMiddleware`/context resolution — no new Slick tables, no cache client. Zero new infrastructure to provision or operate, which directly serves a solo developer who gains nothing from running a stateful service. This is the same precedent as OAuth2/OIDC access tokens (RFC 7519); short-lived `exp` already bounds exposure without server-side state.

**For sessions:** An in-process/embedded session lookup is sub-millisecond — below what the JWT pitch needs to beat, and the "stateless" advantage only pays off at a scale this app will never reach. Implementation is ~50 lines reusing Slick/H2, already proven in this codebase, versus pulling in an unfamiliar signing library and building expiry/refresh logic from scratch. "Zero new infrastructure" for JWT is a myth once revocation is handled properly — you end up needing a denylist or refresh flow, i.e. the same stateful lookup, just worse-designed. Sessions make revocation trivial: delete the row. On team fit, extending already-proven Slick/H2 beats bolting on new crypto dependencies for a single developer.

**What each side foregrounds / downplays:** JWT foregrounds criteria 1 and 3 (latency, zero infra) and only gestures at criterion 4 ("`exp` bounds exposure" — not actual revocation). Sessions foregrounds criteria 2, 4, and 5 (reuse, revocation, fit) and only gestures at criterion 1 (asserts "sub-millisecond" with no benchmark). The downplay on both sides is the same field: **criterion 4, revocation** — JWT's round 1 case never really engages it.

## Round 2 — strongest dissent after cross-pollination

**JWT's objection to sessions:** the "~50 lines, reuse Slick/H2" estimate hides a correctness-critical cost: under Akka HTTP's actor-per-request model, a session-touch-on-every-request pattern needs explicit concurrency handling (row locking / optimistic versioning) that Slick/H2 doesn't give for free — get it wrong and the failure mode is silent auth bypass or lost logouts, not a clean error. H2 was never designed or benchmarked as a concurrent, write-heavy session store, so "sub-millisecond" is a claim about today's empty table, not about contended writes. Framed as: a bounded, well-understood risk (stale token until expiry) traded for an unbounded, untested one (datastore contention under concurrent writes).

**Sessions' objection to JWT:** JWT's round 1 case never actually addresses revocation — "short-lived expiry" is not revocation. If `exp` is long, a stolen/leaked token stays valid for its full lifetime with no kill switch (a textbook OWASP JWT risk). If `exp` is short enough to matter, you need a refresh-token flow — a second token type, with its own storage/rotation, and typically a server-side denylist for refresh tokens anyway. That's the exact stateful lookup JWT's criterion-3 pitch dismissed, just relocated and more complex, not eliminated. The latency comparison in Round 1 becomes irrelevant once that lookup is added back in.

Neither side rebutted the other's Round 2 objection (the two Round 2 passes ran in parallel, not as a full back-and-forth chain) — noted as a limitation of this debate, not resolved by it.

## Claims, marked

| Claim | Status |
|---|---|
| Ed25519/EdDSA verify costs tens of microseconds in-process | **Evidence-backed** (standard, widely-published crypto benchmarks) — but not measured in this codebase |
| A session lookup over network to Redis/Postgres costs 1–5ms | **Assumption, and mismatched to this app** — this app's only datastore is an in-process, embedded H2 instance, not a networked cache; the "network round-trip" premise doesn't apply as stated here |
| Sessions implementation is "~50 lines" reusing Slick/H2 | **Assumption** (plausible order of magnitude, not demonstrated) |
| JWT library integration is "a few lines" in `AuthMiddleware` | **Assumption** (plausible, not demonstrated) |
| JWT needs zero new infrastructure | **Evidence-backed as stated, but incomplete** — true only if revocation is out of scope; Round 2 shows it isn't free once criterion 4 is taken seriously |
| JWT cannot revoke before expiry without extra machinery (denylist/refresh) | **Evidence-backed** (OWASP JWT cheat sheet; this is JWT's well-documented, named weakness) — and unrebutted by the JWT side in this debate |
| H2 under Akka HTTP's actor-per-request model needs explicit concurrency handling for session-touch writes | **Assumption, but well-grounded in general database-concurrency principles** — no benchmark cited, and no citation that H2 specifically fails here, but the general claim (concurrent writers need locking/versioning or they race) is standard |
| This app will never need horizontal scaling (criterion 5's premise) | **Assumption** — stated as context, not proven; it's the single biggest "unknown that could flip the decision," which is exactly what the reversal-evidence below is for |

## Decision

**Decision: server-side sessions.**
**Driver: criterion 5 (team/stack fit) and criterion 3 (operational cost), together.** This is a single-developer, single-process kata app with an already-proven Slick/H2 stack and no crypto/token library today. JWT's headline advantages (criteria 1 and 3) are built for a horizontally-scaled, multi-instance deployment this app doesn't have and isn't planned to have; its one load-bearing weakness — revocation (criterion 4) — went unaddressed in Round 1 and, per Round 2, can't be fixed without re-introducing the exact stateful lookup it claims to avoid. Sessions' own weakness (write-contention under Akka's actor-per-request model) is real but scale-dependent, and this app's actual expected concurrency (a handful of requests, ever) makes it theoretical here, whereas JWT's revocation gap is architectural and doesn't go away at small scale.

This is not "defer pending evidence" — the unknowns (exact H2 contention behavior, exact JWT library line count) are real but small enough, in either direction, to not change which option fits *this* app *today*.

## Reversal-evidence

**Revisit if:** this app moves off a single embedded H2 instance / single process — e.g. adopts a networked datastore (Postgres) and runs more than one app instance behind a load balancer, which is exactly the condition under which JWT's statelessness (criterion 1/3) stops being theoretical and sessions' single-instance simplicity stops being free. Also revisit if a real requirement emerges for immediate, remote multi-device session revocation at a scale where "delete the row" needs to happen across untrusted or distributed clients faster than this kata app currently needs. **Timeframe:** since this is a personal/kata project without a fixed roadmap, there's no calendar deadline — revisit within the same work session that either signal is first observed, rather than letting it sit alongside the already-growing deferred-ticket backlog.

## Self-check

Re-reading only the rejected side's (JWT's) case and its Round 2 objection on this page: yes, it's convincing on its own terms — the latency numbers are real, the "zero infrastructure" pitch is genuinely attractive, and the H2-concurrency objection to sessions is a legitimate, specific risk, not a strawman. What tips the decision is narrow and named above (criterion 5's single-process premise plus the unrebutted revocation gap), not a weak opposing case — if this app's deployment shape changes, the JWT case on this page is already strong enough to flip the decision without needing to be rewritten.
