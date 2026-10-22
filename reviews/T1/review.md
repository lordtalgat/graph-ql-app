# Review: T1 (scope-createvote-to-session-user)

Branch: `task-T1-createvote-auth`. Diff reviewed: `src/main/scala/kz/talgat/graphql/{GraphQLSchema,DBSchema}.scala` + the two new test files, `src/test/scala/kz/talgat/graphql/{CreateVoteAuthSpec,CreateVoteIndependentSpec}.scala`.

## Isolation

**Tier: B**, same caveat as prior isolated passes in this project: a fresh subagent with no conversation/memory carryover, instructed not to use any tools or read any files — self-reported "Tool calls made: none." Loaded once with all four artefacts (diff, delta, the independent test file, the test pass/fail record, including its collision history) and ran all seven lens passes plus the verdict within that one retained-context session, per the kata's "load once, re-issue lens name/question each pass" guidance — not seven separate fresh agents, since the isolation requirement is *from the implementation session*, not from the review's own earlier lenses. Did not load: the plan/tasks documents, this session's prior transcript, or the repository on disk.

## Verdict: **Request changes** (unchanged in category after the adversarial pass, but now more heavily justified — see bottom of file)

Driven by three major findings, not any single blocker:

- **Lens 1 (major) + Lens 3 (major), same root cause**: the diff reorders `DBSchema.scala`'s table-creation/seeding statements, but the delta explicitly scopes this change to `"GraphQLSchema.scala ... only"`. There is no AC to check this edit against, and it was never authorised.
- **Lens 2 (major)**: the two new spec files share one fixed in-memory H2 DB name; a full `sbt test` run already aborts because of it (confirmed in the pass/fail record, not hypothetical).

Per the stated rule, ≥2 majors is sufficient to request changes on its own; here there are 3 across lenses 1–3. The core `GraphQLSchema.scala` production change is correctly scoped and well-covered (lenses 3/4), so the request is narrow: justify-or-split the `DBSchema.scala` change, and fix the cross-suite DB collision so `sbt test` passes as a whole. Minor/nit findings below are documented, not blocking.

**Update after the adversarial pass (see bottom of file):** the verdict stays **Request changes** — there is no more severe category in this scheme — but it is no longer only about test/scope hygiene. The adversarial pass found a credential-logging exposure severe enough to call **blocker-grade on its own merits** (deferred rather than blocking only because it is a pre-existing, already-tracked architectural gap this diff extends rather than introduces) and a genuine correctness regression (stale-identity vote misattribution) that both trace back to the same root cause, now captured in [DEFERRED-session-persistence.md](../../changes/scope-createvote-to-session-user/DEFERRED-session-persistence.md).

---

## Lens 1 — Behaviour preservation

*Does the diff change observable behaviour the spec/delta didn't authorise?*

- **major** — `DBSchema.scala`, the `databaseSetup` block (reordering `Links.schema.create`/`Users.schema.create`/`Votes.schema.create` and the three `forceInsertAll` calls, so `Users` now seeds before `Links`). The delta scopes this change to `GraphQLSchema.scala`'s `createVote` field only, but the diff also changes application startup/seeding order — an observable behaviour change the delta never mentions or authorises.
  *Fix: split into its own commit/PR with its own rationale and a test that `databaseSetup` succeeds end-to-end from a clean DB, or amend the delta to cover it.*
- **minor** — `GraphQLSchema.scala`, `resolve = c => c.ctx.dao.createVote(c.arg(LinkIdArg), c.ctx.currentUser.get.id)`. Noted for completeness: the success-path behaviour (no `userId` round-trip) matches the delta's "Preserved behaviour"/"MODIFIED" sections exactly.
  *Fix: none needed.*

## Lens 2 — Hidden assumptions

*What does this diff assume about input, environment, or upstream state that the spec didn't name?*

- **major** — `CreateVoteAuthSpec.scala`'s and `CreateVoteIndependentSpec.scala`'s `DBSchema.createDatabase` call sites. Both assume they're the only consumer of the fixed H2 URL (`jdbc:h2:mem:howtographqldb`, `keepAliveConnection = true`) for the JVM's lifetime — already confirmed broken: a full `sbt test` run aborts one of the two suites.
  *Fix: give each spec its own uniquely-named in-memory DB (e.g. include the suite name in the JDBC URL).*
- **minor** — `GraphQLSchema.scala`, `c.ctx.currentUser.get.id`. Assumes `AuthMiddleware`'s `Authorized`-tag handling always populates `currentUser` before `resolve` runs on every path; if that invariant is ever violated, `.get` throws an unhandled `NoSuchElementException` instead of a controlled GraphQL error.
  *Fix: `c.ctx.currentUser.map(_.id).getOrElse(throw AuthorizationException(...))` or an equivalent typed helper.*
- **minor** — `DBSchema.scala`'s new comment ("USERS table... must exist before Slick's ALTER TABLE... runs") assumes a specific Slick/H2 DDL-generation strategy that no test directly asserts.
  *Fix: add a smoke test that runs `databaseSetup` end-to-end to lock in the assumption.*

## Lens 3 — Spec/ADR drift

*For each spec AC, name the diff line that implements it; flag anything extra or skipped.*

- `tags = Authorized :: Nil` on `createVote` → implements ADDED #1. **Matches.**
- `resolve = ... c.ctx.currentUser.get.id` → implements ADDED #2 / MODIFIED #2. **Matches.**
- `arguments = LinkIdArg :: Nil` → implements MODIFIED #1. **Matches.**
- Removal of `val UserIdArg = ...` → implements REMOVED #1. **Matches.**
- `DAO.createVote`, `MyContext`, `AuthMiddleware`, `GraphQLServer` left untouched → confirmed, as the delta requires.
- **major** — `DBSchema.scala` (the whole reorder hunk). The delta states "Touched file: `GraphQLSchema.scala` ... only" — this diff violates that stated scope, so there is no AC to drift-check this change against at all.
  *Fix: amend the delta to cover it with its own rationale/AC, or land it as a separate change.*

## Lens 4 — Independent tests check

*Which failure modes from lenses 1–3 are not covered by the independent tests?* (`CreateVoteIndependentSpec.scala` only — `CreateVoteAuthSpec.scala` was written by the implementer, not independently.)

- **Covered well**: sibling `login`+`createVote` success for two users, unauthenticated rejection, removed-`userId`-argument rejection both authenticated and unauthenticated — directly exercises the delta's Risk-note scenario and the REMOVED-argument AC.
- **Not covered** — the Lens 1/3 `DBSchema.scala`-reorder finding: no test targets schema-creation ordering directly; the suite only incidentally depends on the reorder by virtue of `DBSchema.createDatabase` succeeding at all.
- **Not covered** — the Lens 2 shared-fixed-DB-name finding: by construction, a single spec file run in isolation (`testOnly *CreateVoteIndependentSpec`) cannot detect a cross-suite collision; the pass/fail record confirms this was found separately, not by either test file.
- **Not covered** — the Lens 2 `.get()`-on-`currentUser` finding: no test forces a path where the `Authorized` check passes but `currentUser` is still `None`.
- **Not testable by any unit test**: the Lens 3 scope-violation finding (editing a file outside the delta's declared scope) is a process/review-time defect, not something a test can catch.

## Lens 5 — Edge cases

*What input shapes does the code not handle? (empty, oversized, malformed, concurrent, malicious)*

- **minor** — `GraphQLSchema.scala`, `c.ctx.dao.createVote(c.arg(LinkIdArg), ...)`. A non-existent `linkId` isn't validated before reaching the DAO; likely surfaces as a raw FK-violation SQL exception rather than a clean GraphQL error. No test in either spec file exercises this.
  *Fix: add a negative test for an unknown `linkId`; have the resolver/DAO translate FK failures into a domain error.*
- **minor** — `CreateVoteAuthSpec.scala` / `CreateVoteIndependentSpec.scala`, both `DBSchema.createDatabase` call sites. Under sbt's default parallel suite execution, the shared fixed-name DB isn't just "second one fails cleanly" (as currently observed) — it's a race that could manifest as nondeterministic corruption instead.
  *Fix: same as the Lens 2 major (unique DB names per suite); `parallelExecution in Test := false` as a stopgap.*
- **nit** — `GraphQLSchema.scala`, `createVote` field. Nothing prevents the same authenticated user voting on the same link multiple times (no uniqueness/idempotency check) — pre-existing, not introduced here, and the delta is silent on whether that's intended.
  *Fix: out of scope for this change; file a follow-up ticket if "one vote per user per link" was ever meant to be an invariant.*

## Lens 6 — Security / tool-call surface

*Does the diff add a new endpoint, tool call, permission boundary, or external service interaction?*

No finding for this lens — diff did not exercise this concern. The change tightens an existing mutation's authorization rather than adding a new endpoint, tool call, or external integration; net effect is a *reduction* in attack surface (unauthenticated/spoofed-identity voting is removed), using the pre-existing, unchanged `Authorized`-tag mechanism.

## Lens 7 — Over-engineering

*What abstraction or extension point in the diff has no current consumer?*

- **nit** — `CreateVoteIndependentSpec.scala`, the `errorHandler`'s `AuthenticationException` branch. No test in this file exercises it (all auth-failure tests hit `AuthorizationException` via `ensureAuthenticated`), so it's a generalization with no current consumer in this suite.
  *Fix: add a test that triggers an `AuthenticationException` (bad login credentials) to justify the branch, or drop it until needed — low priority, since it mirrors the real `GraphQLServer.ErrorHandler` convention it was modeled on.*

No other findings for this lens — the production change (`GraphQLSchema.scala`) is minimal: one argument removed, one tag added, one resolver line changed, no new abstractions introduced.

---

## Adversarial pass

**Model used:** Claude Opus (5.5). Honesty note on isolation: the kata recommends Tier A with a genuinely different model *family* (e.g. Claude reviewed by GPT). That wasn't available in this environment — the only model choices reachable here are within the Claude family (Sonnet/Opus/Haiku/Fable). Opus was picked as the most capability-different option available, run as a fresh, no-context subagent (no conversation/memory, no tool use, self-reported "Tool calls made: none") — isolated from the implementation and from the seven-lens pass, but not cross-family. Loaded: the diff, a summary of the delta, the independent test list (by name + assertion, to exclude), and a summary of the seven-lens findings (to avoid repeats) — not the implementation transcript, not the plan.

### Pre-mortem

Five causes were generated, ordered most → least obvious:
1. *(skipped, as instructed — the delta removed `userId` on purpose; clients still sending it is the expected, mitigated failure.)*
2. The "login now, vote later" two-request client flow fails 100% of the time (GraphQLSchema.scala:151–152) — already covered by [DEFERRED-session-persistence.md](../../changes/scope-createvote-to-session-user/DEFERRED-session-persistence.md) and the delta's own Risk note.
3. **Credentials leak into logs (picked as strongest).**
4. One GraphQL document can brute-force passwords and stuff votes via aliased fields (no query-complexity/alias limiting) — real, but a pre-existing property of this GraphQL server in general, not introduced by this diff; not picked as the headline finding because it isn't new here.
5. Seeded `forceInsertAll` ids colliding with real ids after a hypothetical H2 2.x upgrade — real but speculative (depends on a future, unplanned dependency bump); lowest confidence of the five.

**Finding (pre-mortem) — credentials leak into logs**
- **Trigger:** `createVote` being `Authorized` (GraphQLSchema.scala:151) with no durable session means every legitimate vote must embed `login(email, password)` in the same document. Any layer that logs request URLs, query text, or failing-query error context (including the raw FK-violation path noted in Lens 5) writes a plaintext password to a log.
- **Blast radius:** every voting user's password, for as long as logs are retained, readable by anyone with log access; passwords are stored in plaintext in this app already, so one leaked log line is a full, silent account compromise — and a compliance incident, not just a bug.
- **Mitigation:** token-based auth (`login` returns a signed token; `GraphQLServer` reads it from a header per request) removes the need to send credentials with every mutation. Stopgap: redact `password` from any logged query/variables and never log GraphQL query text verbatim.
- **Resolution: defer with ticket** — [changes/scope-createvote-to-session-user/DEFERRED-session-persistence.md](../../changes/scope-createvote-to-session-user/DEFERRED-session-persistence.md) ("Consequence 1"). Not "fix now": the real fix touches `MyContext.scala`/`GraphQLServer.scala`/`Server.scala`, all explicitly outside this delta's declared scope — the same scope boundary Lens 1/3 already flagged for the smaller `DBSchema.scala` edit. Not "accept with documented risk" silently: this is severe enough (plaintext credential exposure) that it needs a tracked, named owner-of-attention, not just a line in a provenance block. Re-visit condition: before this mutation pattern is exposed to any real client.

### Edge-case hunter

Three-plus candidates were generated, excluding anything the 5 independent tests already cover:
- **A.** `createVote` placed before `login` in the same document — rejected even with valid credentials, since mutation fields run in document order. (GraphQLSchema.scala:151–152.)
- **B.** A client migrating incrementally that stops passing a `userId` *value* but still *declares* the now-unused `$u` variable hits a different validation error (`NoUnusedVariables`) than test 4's inline-literal case. (GraphQLSchema.scala:131, :150.)
- **C.** `login` and `createVote` placed in two different named operations of one document, executed via `operationName`, so the context update from the unexecuted operation never happens.
- **D. (picked as strongest)** A document with two `login` fields where the second fails — see below.

**Finding (edge-case) — stale identity survives a failed second login**
- **Input shape:**
  ```graphql
  mutation {
    a: login(email: "talgat@mail.com", password: "password") { id }
    b: login(email: "shakirov@mail.com", password: "WRONG") { id }
    createVote(linkId: 3) { user { id } }
  }
  ```
- **Observable failure:** `a` succeeds and sets `currentUser = Some(user1)`. `b` throws `AuthenticationException`, which becomes a null field + error but never reaches the context-update step — so the context is still `user1` when `createVote` resolves (GraphQLSchema.scala:152, `c.ctx.currentUser.get.id`). The vote is recorded against user 1, not the user 2 the caller evidently intended partway through the document. The response looks like a partial success (one field errored) but silently misattributes the side effect — a narrower recurrence of exactly the behaviour the delta's REMOVED section says is gone ("the ability for user A to cast a vote attributed to a different user B's id"), reachable by document shape rather than by malice.
- **Mitigation:** have a failed `login` explicitly clear `currentUser` (update the context to `None` on the error path, not just leave it untouched), or add schema/query validation that rejects a document containing more than one `login` field.
- **Resolution: defer with ticket** — same ticket, [DEFERRED-session-persistence.md](../../changes/scope-createvote-to-session-user/DEFERRED-session-persistence.md) ("Consequence 2"). Not "fix now": the fix lives in `login`'s resolver / `MyContext`, both outside this delta's declared scope, and the root cause is identical to Finding 1 (no durable session, only fragile same-document context propagation) — bundling both into one scoped follow-up is more coherent than patching this one symptom in isolation. Not "accept silently": it directly undermines a guarantee the delta explicitly claims to provide (REMOVED: vote-as-another-user), so it needs to be named, not just absorbed.
