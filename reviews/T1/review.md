# Review: T1 (scope-createvote-to-session-user)

Branch: `task-T1-createvote-auth`. Diff reviewed: `src/main/scala/kz/talgat/graphql/{GraphQLSchema,DBSchema}.scala` + the two new test files, `src/test/scala/kz/talgat/graphql/{CreateVoteAuthSpec,CreateVoteIndependentSpec}.scala`.

## Isolation

**Tier: B**, same caveat as prior isolated passes in this project: a fresh subagent with no conversation/memory carryover, instructed not to use any tools or read any files — self-reported "Tool calls made: none." Loaded once with all four artefacts (diff, delta, the independent test file, the test pass/fail record, including its collision history) and ran all seven lens passes plus the verdict within that one retained-context session, per the kata's "load once, re-issue lens name/question each pass" guidance — not seven separate fresh agents, since the isolation requirement is *from the implementation session*, not from the review's own earlier lenses. Did not load: the plan/tasks documents, this session's prior transcript, or the repository on disk.

## Verdict: **Request changes**

Driven by three major findings, not any single blocker:

- **Lens 1 (major) + Lens 3 (major), same root cause**: the diff reorders `DBSchema.scala`'s table-creation/seeding statements, but the delta explicitly scopes this change to `"GraphQLSchema.scala ... only"`. There is no AC to check this edit against, and it was never authorised.
- **Lens 2 (major)**: the two new spec files share one fixed in-memory H2 DB name; a full `sbt test` run already aborts because of it (confirmed in the pass/fail record, not hypothetical).

Per the stated rule, ≥2 majors is sufficient to request changes on its own; here there are 3 across lenses 1–3. The core `GraphQLSchema.scala` production change is correctly scoped and well-covered (lenses 3/4), so the request is narrow: justify-or-split the `DBSchema.scala` change, and fix the cross-suite DB collision so `sbt test` passes as a whole. Minor/nit findings below are documented, not blocking.

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
