## Summary

This PR carries the full SDD kata-practice sequence for one change — scoping the `createVote` mutation to the authenticated session user — plus all of its supporting artifacts: the project's hot/warm/cold context bundle, a CI gate, a portable test-running skill, a greenfield spec (unrelated `bulk-link-import` feature, practice only), and the brownfield delta/plan/tasks/session-log/review for `createVote` itself.

The actual behavior change: `createVote` now requires authentication and derives the voter's identity from the session (`ctx.currentUser`) instead of a client-supplied `userId` argument — closing a gap where any caller, authenticated or not, could previously attribute a vote to an arbitrary user id. It also fixes a pre-existing, unrelated bug in `DBSchema`'s table-creation order that silently blocked every test (including this PR's own) from ever running, and ships two new test suites — one written by the implementer, one written independently from only the spec and public interface signatures — plus a seven-lens + adversarial code review and a tracked follow-up ticket for what that review found.

## Provenance

- **Tool / model:** Claude Code (desktop app, Code tab), Sonnet 5 (`claude-sonnet-5`) driving the session; Claude Opus 5.5 (`claude-opus-5-5`) for the adversarial-pass subagent only — 2026-10-22.
- **Context loaded:** hot file `CLAUDE.md` @ blob `27c5e60e2344cbee094329fcd68ce9eb93af5209` (commit `b61b3ff`); warm file `docs/context/stack.md` (JDK-11 toolchain requirement, single-module sbt layout); Skills: `skills/sbt-test/SKILL.md` referenced as the harness-convention source for `sbt testOnly` scoping — not invoked via the Skill tool itself, since it lives at repo-root `skills/` rather than `.claude/skills/` and so isn't auto-discovered in this session.
- **Verification gates:**
  - Compile/typecheck (Scala compiler; no separate linter is configured in this repo) — **PASS**
  - `sbt testOnly *CreateVoteAuthSpec` — **PASS** (1/1)
  - `sbt testOnly *CreateVoteIndependentSpec` — **PASS** (5/5, after wiring fixes documented in the session log — no implementation bugs found)
  - `sbt test` (full suite, both new spec files together) — **FAIL** — aborts on a cross-suite shared in-memory-DB collision (pre-existing architecture, newly exposed by adding a second spec file); tracked below, not fixed in this PR
  - CI gate `scripts/ci/test-file-deletion-guard.sh` (the required check) — **PASS** (exit 0)
- **Human decisions:** the user chose to run this entire implementation → verification → review → adversarial-pass pipeline under auto-mode delegation rather than reviewing each diff interactively turn-by-turn — a deliberate throughput-over-oversight trade-off for this exercise. Every in-the-moment technical judgment call documented below (scope boundaries, defer-vs-fix decisions, model choice for the adversarial pass) was therefore made by the acting agent, not a human reviewer; this provenance block exists so a human can backfill that review asynchronously, now, before merge.
- **Known limitations:**
  1. A full `sbt test` run currently fails (see Verification gates) — not fixed in this PR.
  2. The `DBSchema.scala` table-ordering fix was necessary (nothing could run without it) but technically exceeds the delta's declared "`GraphQLSchema.scala` only" scope — flagged by two review lenses, not yet resolved by amending the delta or splitting the commit.
  3. The adversarial pass's recommended "different model family" wasn't achievable in this environment (no non-Claude model access) — it ran on Claude Opus instead of Sonnet, which is model-tier diversity, not family diversity.
  4. No durable cross-request session exists in this app for any `Authorized` mutation (pre-existing, extended from one mutation to two by this change) — including a credential-logging exposure and a stale-identity vote-misattribution edge case the adversarial pass found. Tracked, not fixed here.
- **Session duration:** ~15–20 minutes for the supervised T1 implementation session itself (see the 15-minute checkpoint and the session log's ordered action log); the independent-test-writing, seven-lens-review, and adversarial-pass sessions that followed were separate, subsequent sessions (each logged and linked below), not included in this figure.
- **SDD approach:** [changes/scope-createvote-to-session-user/delta.md](https://github.com/lordtalgat/graph-ql-app/blob/task-T1-createvote-auth/changes/scope-createvote-to-session-user/delta.md) — a brownfield delta with an explicit Preserved-behaviour sentence, ADDED/MODIFIED/REMOVED sections, a REMOVED audit that caught a missed finding, and a Risk note.

## Linked evidence

- Spec or delta: [delta.md](https://github.com/lordtalgat/graph-ql-app/blob/task-T1-createvote-auth/changes/scope-createvote-to-session-user/delta.md)
- Plan and tasks: [plan.md](https://github.com/lordtalgat/graph-ql-app/blob/task-T1-createvote-auth/changes/scope-createvote-to-session-user/plan.md), [tasks.md](https://github.com/lordtalgat/graph-ql-app/blob/task-T1-createvote-auth/changes/scope-createvote-to-session-user/tasks.md)
- Session log: [session-log.md](https://github.com/lordtalgat/graph-ql-app/blob/task-T1-createvote-auth/sessions/T1/session-log.md) (also [checkpoint-15min.md](https://github.com/lordtalgat/graph-ql-app/blob/task-T1-createvote-auth/sessions/T1/checkpoint-15min.md), [actions.jsonl](https://github.com/lordtalgat/graph-ql-app/blob/task-T1-createvote-auth/sessions/T1/actions.jsonl))
- Independent tests: [CreateVoteIndependentSpec.scala](https://github.com/lordtalgat/graph-ql-app/blob/task-T1-createvote-auth/src/test/scala/kz/talgat/graphql/CreateVoteIndependentSpec.scala)
- Review (seven lenses + adversarial pass): [review.md](https://github.com/lordtalgat/graph-ql-app/blob/task-T1-createvote-auth/reviews/T1/review.md) (also [risk-register.md](https://github.com/lordtalgat/graph-ql-app/blob/task-T1-createvote-auth/reviews/T1/risk-register.md))
- Architectural debate, if any: none was conducted for this change.
- Replay packet, if any: none was produced for this change.
- Follow-up ticket opened by this PR's review: [DEFERRED-session-persistence.md](https://github.com/lordtalgat/graph-ql-app/blob/task-T1-createvote-auth/changes/scope-createvote-to-session-user/DEFERRED-session-persistence.md)

## Redaction pass

Scanned every linked artefact above plus the diff itself for secrets, credentials, customer data, internal URLs, employee names, and proprietary domain terms. One category of match, reviewed and left as-is with reason:

| artefact | line | matched value | why not redacted |
|---|---|---|---|
| `DBSchema.scala` (pre-existing, not changed by this PR) and every file quoting its seed data (`delta.md:37`, `review.md:123-124`, `DEFERRED-session-persistence.md:19-20`, both new test files) | various | `t***t@mail.com`, `s***v@mail.com`, password value `p******d` | Fake fixture data seeded by this repo's original tutorial commits, years before this session; the password's literal value is the string "password", not a real credential; already fully public in this repo's existing git history. Not a leak this PR introduces. |

No other matches — no real secrets, tokens, internal hostnames/IPs, or third-party customer/employee data found in any linked artefact.

## Read-as-a-stranger self-test

A reviewer who wasn't here could: see exactly what changed and why (delta.md), see that it was implemented and verified (session-log.md's action log + checkpoint), see independent tests pass without having seen the implementation (session-log.md's adjudication table), see a structured seven-lens + adversarial review with a verdict that follows from named findings (review.md), and see every open risk named with a resolution and a place it's tracked (DEFERRED-session-persistence.md, risk-register.md) rather than silently dropped. That review's verdict is **Request changes** — merging anyway is a call for a human reviewer to make now, which is the point of this provenance block existing.
