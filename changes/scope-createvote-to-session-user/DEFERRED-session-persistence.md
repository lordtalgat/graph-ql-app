# Deferred ticket: no durable cross-request session for GraphQL mutations

Created by: T6 (planned in [tasks.md](tasks.md)), populated now because the adversarial pass on T1 (see [reviews/T1/review.md](../../reviews/T1/review.md), "## Adversarial pass") turned the original architectural gap into two concrete, severity-rated findings.

**Architectural decision available:** the JWT-vs-server-side-sessions fork for the real fix below has been debated — see [docs/decisions/session-auth-debate.md](../../docs/decisions/session-auth-debate.md). Decision: server-side sessions, with named reversal-evidence.

## The gap

`GraphQLServer.scala` builds a fresh, session-less `MyContext(dao)` for every HTTP `POST /graphql` request. `login`'s `UpdateCtx` only updates the context for *sibling fields resolved later in the same GraphQL document* — it has no effect across requests, because nothing (no cookie, token, or session store) persists `currentUser` anywhere in this app. The only way an `Authorized` field (`createLink`, and now `createVote`) can ever succeed for a real caller is a single document containing both `login` and the mutation together.

This was first identified in [delta.md](delta.md)'s "REMOVED audit" (T1's change doubled this defect's blast radius from one mutation to two) and its "Risk note." The adversarial pass on T1 found it is worse than "inconvenient":

## Consequence 1 — plaintext credentials leak into logs (pre-mortem, cause 3)

Because `login(email, password)` must be embedded in the same document as every vote, every vote request carries a plaintext password in its query text — which commonly ends up in access logs, proxy/CDN logs, browser history, APM traces, and error logs that echo a failing query. Seeded/stored passwords are plaintext, so one leaked log line is a full account compromise. This already applied to `createLink`; T1 extends it to `createVote`.

## Consequence 2 — a failed second `login` in one document doesn't clear identity, and the next mutation runs as the previous user (edge-case-hunter finding D)

```graphql
mutation {
  a: login(email: "talgat@mail.com", password: "password") { id }
  b: login(email: "shakirov@mail.com", password: "WRONG") { id }
  createVote(linkId: 3) { user { id } }
}
```

`a` succeeds and sets `currentUser = Some(user1)`. `b` throws `AuthenticationException`, which becomes a null field + error, but never runs the context-update function — so the context is still `user1`. `createVote` then resolves against that stale context and attributes the vote to user 1, even though the caller's evident intent was to act as user 2. This is a narrower instance of exactly the behaviour the delta's REMOVED section says is gone ("the ability for user A to cast a vote attributed to a different user B's id") — reachable here by document shape rather than by malice, but the end state is the same: a vote lands on an identity the caller didn't intend at that point in the document.

## Why deferred rather than fixed in T1

The real fix for both consequences is the same: give `login` a durable token (e.g. a signed JWT) and have `GraphQLServer` populate `currentUser` from a request header, replacing the single-document `UpdateCtx` pattern entirely. That touches `MyContext.scala`, `GraphQLServer.scala`, and `Server.scala` — all explicitly out of [delta.md](delta.md)'s declared scope ("`MyContext`... `GraphQLServer`... are not edited") — and is a change of its own scale, not a line-level fix that belongs inside T1.

## Re-visit condition

Before this mutation pattern (or `createLink`'s identical pattern) is exposed to any real client — in particular before any client implements the ordinary "log in once, act later" flow, or before this app accepts traffic outside this kata's own testing — implement token-based authentication and remove the single-document login-then-act requirement. Until then, treat every `Authorized` mutation as carrying the two risks above.
