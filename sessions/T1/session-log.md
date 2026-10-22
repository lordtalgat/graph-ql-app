# Session log — T1

## Task spec (as executed)

From [changes/scope-createvote-to-session-user/tasks.md](../../changes/scope-createvote-to-session-user/tasks.md), task T1 ("first slice — one AC end-to-end, < 100 lines"):

- **Input:** current `GraphQLSchema.scala` (`createVote` has `arguments = LinkIdArg :: UserIdArg :: Nil`, no `Authorized` tag, resolver reads `c.arg(UserIdArg)`).
- **Output:** `createVote` updated to `tags = Authorized :: Nil`, `arguments = LinkIdArg :: Nil`, resolver `c.ctx.dao.createVote(c.arg(LinkIdArg), c.ctx.currentUser.get.id)`; one new ScalaTest spec (`CreateVoteAuthSpec`) proving the login+createVote same-document happy path.
- **Done:** `sbt "testOnly *CreateVoteAuthSpec"` passes — response has no top-level `errors` and `createVote.user.id` equals the logged-in user's id.

Baseline stated to self before starting: *"Implement T1 — require authentication on `createVote`, derive the voter's id from the session instead of a client-supplied argument, and prove the one supported call pattern (login + createVote in one document) still works end-to-end."*

## Context loaded

- Hot file: [CLAUDE.md](../../CLAUDE.md) — Fetcher/Relation rule, `Authorized`-tag convention, "resolve any new relationship through the batching mechanism" rule (not triggered by this task, but checked).
- Warm files: [docs/context/stack.md](../../docs/context/stack.md) — specifically the JDK 17/zinc incompatibility note (this repo needs JDK 11 to compile/test) and the sbt-test scoping facts.
- Skills/rules: [skills/sbt-test/SKILL.md](../../skills/sbt-test/SKILL.md) — used its `testOnly` scoping guidance to run only `CreateVoteAuthSpec` during iteration instead of the (nonexistent, at the time) full suite.
- Spec/delta: [changes/scope-createvote-to-session-user/delta.md](../../changes/scope-createvote-to-session-user/delta.md), [plan.md](../../changes/scope-createvote-to-session-user/plan.md), [tasks.md](../../changes/scope-createvote-to-session-user/tasks.md).

## Ordered action log

1. Loaded context bundle above; confirmed T1's Input/Output/Done against the current state of `GraphQLSchema.scala`.
2. Created branch `task-T1-createvote-auth` off `claude-integration`.
3. Edited `GraphQLSchema.scala`: added `tags = Authorized :: Nil` to `createVote`, removed `UserIdArg` from its `arguments` (and deleted the now-unused `val UserIdArg` declaration, confirmed unused elsewhere by grep), changed the resolver to `c.ctx.dao.createVote(c.arg(LinkIdArg), c.ctx.currentUser.get.id)`.
4. Wrote `src/test/scala/kz/talgat/graphql/CreateVoteAuthSpec.scala` — one test running `login` + `createVote` as sibling fields in one document against `Executor.execute(GraphQLSchema.SchemaDefinition, ...)` directly (per plan.md Component 4), deliberately not referencing `GraphQLServer`.
5. Ran `sbt -java-home ".../jdk11.0.27_6" "testOnly *CreateVoteAuthSpec"` → **failed**: `org.h2.jdbc.JdbcSQLException: Table "USERS" not found` while adding FK constraint `postedBy_FK` during `Links.schema.create`.
6. Diagnosed root cause: `DBSchema.databaseSetup` creates and seeds `LINKS` before `USERS`, even though `LinksTable` declares a `foreignKey(..., Users)`; Slick runs that FK's `ALTER TABLE` as part of `Links.schema.create` itself, so it always fails on a fresh database — a pre-existing defect, unrelated to this task's edit, that nothing had exercised before (zero tests existed in this repo before today).
7. Fixed `DBSchema.scala`: reordered both the schema-creation block and the seed-insert block to `Users → Links → Votes`, and added a short comment explaining why the order matters (see Rejected Alternatives for scope boundary on this fix).
8. Re-ran `sbt -java-home ".../jdk11.0.27_6" "testOnly *CreateVoteAuthSpec"` → **passed** (1 succeeded, 0 failed).
9. Ran `sbt -java-home ".../jdk11.0.27_6" test` (full suite) as a regression check → **passed** (1 succeeded, 0 failed — no other tests exist yet).
10. Ran `bash scripts/ci/test-file-deletion-guard.sh claude-integration HEAD` against the branch diff → exit 0 ("no test files deleted").
11. Wrote the 15-minute checkpoint: [sessions/T1/checkpoint-15min.md](checkpoint-15min.md).
12. Wrote this session log.

## Rejected alternatives (with reasons)

- **Defensive re-check of `currentUser` inside the resolver** (`ctx.ctx.currentUser.getOrElse(throw AuthorizationException(...))`) instead of plain `.get`. Rejected: `AuthMiddleware.beforeField` already guarantees `currentUser.isDefined` before this resolver runs, via the `Authorized` tag — the same contract `createLink` already relies on. A second check duplicates a guarantee the framework already enforces rather than trusting it, and adds error handling for a scenario the tag already makes unreachable.
- **Referencing `GraphQLServer.ErrorHandler`/middleware directly in the test**, for maximum fidelity to the real request path. Rejected: merely touching the `GraphQLServer` object triggers its own singleton initialization, which calls `DBSchema.createDatabase` a second time against the same fixed H2-mem URL (`jdbc:h2:mem:howtographqldb`, kept alive by `keepAliveConnection = true`) — colliding with the test's own freshly created database. The test instead re-declares just what it needs (`AuthMiddleware`, explicit `variables = JsObject.empty`) directly against `Executor.execute`.
- **Fixing the "no cross-request session persistence" risk** flagged as the top risk in delta.md, while already touching this area. Rejected/deferred: that is explicitly T6's scope (a tracked deferred-risk ticket), not T1's — pulling it into this task would blow past the "<100 lines, one afternoon" boundary tasks.md set for T1, and conflate two different changes in one diff.
- **Adding a DB-level uniqueness constraint** (e.g. on `VOTES(user_id, link_id)`) while already editing `DBSchema.scala` for the ordering fix. Rejected: out of scope for this change entirely — duplicate-vote prevention isn't part of `scope-createvote-to-session-user`'s ADDED/MODIFIED/REMOVED list, and it's a concern already called out separately in the unrelated `bulk-link-import` spec's concurrency section.

## Verification gates run

- `sbt -java-home ".../jdk11.0.27_6" "testOnly *CreateVoteAuthSpec"` — **PASS** (1 succeeded, 0 failed) — this is T1's stated Done signal.
- `sbt -java-home ".../jdk11.0.27_6" test` (full suite) — **PASS** (1 succeeded, 0 failed, no regressions; only one test exists in the repo so far).
- `bash scripts/ci/test-file-deletion-guard.sh claude-integration HEAD` (the K 5.D.3 CI gate) — **PASS** (exit 0; no test files were deleted by this diff, one was added).
- Type/compile check: covered implicitly by the `sbt test` run above (Scala's compiler is the only type check configured in this repo; no separate linter is configured).

## Outcome (T1 implementation)

- **Status: complete.** T1's stated Done signal is met, and the diff includes one necessary, out-of-plan fix (DBSchema creation/seed ordering) without which no test — this one or any future one — could ever pass.
- **Next step (superseded by the section below):** hand off to T2.

---

# Independent verification (isolated test session)

## Isolation statement and tier

Stated in writing before pasting anything: *"This session has access to: the change delta (ADDED/MODIFIED/REMOVED/Preserved-behaviour) + public interface signatures (no bodies) + test-harness conventions. It does NOT have access to: the implementation code (`GraphQLSchema.scala`'s actual `createVote` field or `VoteType` derivation), the implementation transcript, the plan, or this session's prior conversation."*

**Tier: B**, with a caveat. A fresh subagent (Agent tool, `general-purpose`) was spawned with no conversation/memory carried over and an explicit instruction not to use any tools (Read/Grep/Glob/Bash/Write/etc.) or explore the repository — pure text synthesis from only what was pasted into the prompt. This is a genuinely separate context, which is why it's Tier B and not Tier C (same conversation, memory off). It is **not** Tier A: the subagent technically retained the same filesystem/tool access as any other subagent in this environment — isolation here rests on instruction-following and self-report, not a hard sandbox boundary. The agent self-reported "Tool calls made: none" at the end of its response; that self-report was the only verification available in this environment and is named here as the limitation, per the kata's "limited isolation" guidance.

**Corroborating evidence beyond the self-report:** the test file's own content is itself evidence isolation held. It assumed `Vote`'s GraphQL type exposes `userId`/`linkId` fields directly (inferred from the `Vote` case class signature given) and explicitly flagged this as uncertain. `VoteType`'s actual derivation (`ExcludeFields("userId", "linkId")`, replaced with nested `user`/`link` object fields) is a fact only visible in `GraphQLSchema.scala`, which the session never saw — a session that had read the implementation would not have made (or needed to flag) this mistake.

## What was pasted (spec + signatures + harness only — no implementation)

- The change delta (`Preserved behaviour`, `ADDED`, `MODIFIED`, `REMOVED` sections of [delta.md](../../changes/scope-createvote-to-session-user/delta.md); the `REMOVED audit`/`Risk note` process sections were summarized to one line, not pasted in full, since they're process notes rather than behavioural requirements).
- Public interface signatures only: the GraphQL contract shape (`createVote(linkId: Int!): Vote!`, `login(...)`, `votes(...)`, `link(...)`), and Scala-level signatures for `GraphQLSchema.SchemaDefinition`, `GraphQLSchema.Resolver`, `DBSchema.createDatabase`, `MyContext`, `AuthMiddleware`, the `User`/`Link`/`Vote` case classes, and the two exception types — all signatures, no bodies.
- Test-harness conventions: ScalaTest `FunSuite` naming/path convention, the run command, a fixture pattern for executing a GraphQL document via `Executor.execute`, and the seed-data facts (user/link/vote ids and credentials).
- **Not pasted:** `GraphQLSchema.scala`'s actual `createVote` field body, `VoteType`'s actual field derivation, `GraphQLServer.scala`, `AuthMiddleware.scala`'s body, `MyContext.scala`'s body, or anything from this session's prior conversation (T1's implementation, checkpoint, or session log).

## Test run 1 — wired in verbatim, no changes

Wrote the agent's output verbatim to `src/test/scala/kz/talgat/graphql/CreateVoteIndependentSpec.scala` and ran `sbt -java-home ".../jdk11.0.27_6" "testOnly *CreateVoteIndependentSpec"`:

| Test | Result |
|---|---|
| 1. login + createVote, positive | **FAILED** — `ValidationError`: "Cannot query field 'userId' on type 'Vote'. Did you mean 'user'?" (and same for `linkId`) |
| 2. second user, positive | **ERROR** — `org.h2.jdbc.JdbcSQLException: Table "USERS" already exists` |
| 3. unauthenticated, negative | **ERROR** — same `Table "USERS" already exists` |
| 4. removed `userId` arg, authenticated, negative | **ERROR** — same `Table "USERS" already exists` |
| 5. removed `userId` arg, unauthenticated, edge case | **ERROR** — same `Table "USERS" already exists` |

Total: 0 passed, 5 failed/errored.

## Adjudication (one verdict per test, before any fix)

1. **Ambiguity in spec** — the public interface signatures given described the Scala `Vote` case class (`userId`, `linkId` fields) but not `VoteType`'s actual GraphQL shape, which excludes those two fields in favour of nested `user { }`/`link { }` objects (`ExcludeFields` + `AddFields` in the untouched `VoteType` derivation). Not a code bug: this exclusion pre-dates and is unrelated to T1's change. Not really a test-authoring mistake either — the test explicitly flagged this exact uncertainty in advance.
2. **Bug in test** — the test called `DBSchema.createDatabase` independently inside `run()`, and with 5 tests in one suite each doing so, tests 2–5 collided with test 1's still-alive database (the DB targets a fixed name kept alive for the JVM's lifetime). Root cause traces back to my own harness note under-specifying this ("once per test" without saying "at most once per suite run") — but the fix lives entirely in the test file (share one `dao` for the whole suite), not in the delta or the code.
3. **Ambiguity in spec/harness** (re-adjudicated after fixing #2, see below) — once the DB collision was fixed, this test failed cleanly on a message-content assertion: the response contained a generic `"Internal server error"` rather than `AuthorizationException`'s real message. The harness conventions given never mentioned that reproducing `GraphQLServer`'s friendly-message behaviour requires also supplying its `exceptionHandler`; without it, Sangria's default masks any unhandled resolver exception. Not a code bug — the real HTTP path (`GraphQLServer.scala`) already wires this handler and does produce the friendly message.
4. **Ambiguity in spec/harness** (re-adjudicated after fixing #2) — once past the DB collision, this test errored with an uncaught `sangria.execution.ValidationError` thrown synchronously by `Executor.execute` itself, not returned inside a successful `Future` as a response with an `"errors"` entry. My harness notes incorrectly implied all rejections return a JsObject; argument/schema-validation failures instead throw before a `Future` is even produced. The test's own `FLAG` had explicitly anticipated this exact possibility.
5. **Ambiguity in spec/harness** (re-adjudicated after fixing #2) — same synchronous-`ValidationError` gap as test 4; the test's own `FLAG` here also explicitly anticipated uncertainty about ordering, which the fix resolves (validation runs before any field resolution, so it always fires first, regardless of auth state).

None of the five failures were a **bug in the implementation** — `createVote`'s behaviour matched the delta in every case once the tests were wired correctly.

## Wiring fixes applied (behaviour unchanged, only how the tests attach to the interface)

- Hoisted `val dao = DBSchema.createDatabase` to the suite body (constructed once, shared by all 5 tests) instead of once per test.
- Changed field selections from `userId`/`linkId` to `user { id }`/`link { id }` in tests 1 and 2; assertions still check the same thing (vote attributed to the correct user/link id), just read through the correct field path.
- Added an inlined `exceptionHandler` (mirroring `GraphQLServer.ErrorHandler`'s two cases, not referencing `GraphQLServer` itself, to avoid the double-DB-init issue from T1) so `AuthorizationException`'s real message surfaces, matching production behaviour.
- Changed tests 4 and 5 from asserting on a returned `JsObject` to `intercept[sangria.execution.ValidationError]`, since that's how an unknown-argument failure actually manifests.

## Test run 2 — after wiring fixes

`sbt -java-home ".../jdk11.0.27_6" "testOnly *CreateVoteIndependentSpec"` → **5 succeeded, 0 failed.** All five tests pass against the real T1 implementation, with the delta's expected behaviour unchanged throughout — only the test wiring was corrected.

## New discovery: full-suite collision (not one of the 5 adjudicated failures)

Running the whole suite (`sbt test`, both `CreateVoteAuthSpec` and `CreateVoteIndependentSpec` in one JVM) **aborts** `CreateVoteIndependentSpec` entirely — its class-level `dao` construction collides with `CreateVoteAuthSpec`'s own `DBSchema.createDatabase` call, since both target the same fixed in-memory DB name. This confirms, now empirically, the exact risk flagged in T1's checkpoint ("future tasks must not each independently call `DBSchema.createDatabase`..."). `testOnly *CreateVoteIndependentSpec` alone (the command a reviewer would run to verify this specific file) passes cleanly; `sbt test` as a whole currently does not once a second spec file exists. Fixing this properly means `DBSchema.createDatabase` taking a unique DB name per call — a production-code change out of scope for this verification task — so it is logged here as a new, confirmed, deferred risk rather than fixed on the spot.

## Outcome (independent verification)

- **Status: complete** for the verification goal (isolated tests written, wired in, run, every failure adjudicated, converged to a passing state without changing expected behaviour) — but it surfaced a **new blocking issue for the test suite as a whole** (full-suite collision above), which is **not yet fixed**.
- **Next step:** before T2–T4 (which each plan to add their own test(s)) are implemented, `DBSchema.createDatabase` needs a non-fixed database name (or an equivalent fixture reset mechanism) so multiple spec files can coexist in one `sbt test` run — recommend this become its own task before T2 starts, rather than letting each subsequent task rediscover the same collision independently.
