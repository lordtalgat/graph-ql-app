---
name: sbt-test
description: Run Scala/sbt tests scoped to exactly what was asked for — the whole suite, one test method, or one related suite/package — by choosing the right sbt testOnly invocation instead of always running the full suite. Use whenever the user asks to run, re-run, or check tests in an sbt-based Scala project (e.g. "run the tests", "just run the FooSpec", "run the 'rejects empty input' test", "run tests under the auth package").
---

# sbt-test

Run tests in an sbt-based Scala project, scoped to what was actually requested, instead of defaulting to the full suite every time.

## 1. Determine the scope

Read the request (or ask, if it's genuinely ambiguous) and classify it as one of:

- **Full suite** — "run the tests", "do the tests pass", no target named.
- **One method** — a specific test name/description is named ("the 'returns 404 when missing' test", "testFooBar").
- **One related suite or package** — a test class, spec, or package/module is named, but not a single method ("the AuthSpec tests", "everything under the billing package").

## 2. Check for repo-specific facts first

Before building the command, look for a project context/rules file (e.g. `CLAUDE.md`, `AGENTS.md`, or a linked warm-context doc such as `docs/context/stack.md`) and check whether it documents:

- Whether the build has multiple sbt sub-projects (requiring a `project/` prefix on `testOnly`/`test`).
- The root test package, and where test sources live.
- Any non-default test framework or naming convention (ScalaTest style, MUnit, specs2, uTest, etc.) — the `-z`/`-n` flags below are ScalaTest-specific; MUnit/specs2/uTest use different filter syntax.

If none of that is documented and it isn't obvious from the build file (`build.sbt`) or an existing test, ask rather than guess.

## 3. Build and run the command

- **Full suite**: `sbt test`
- **One related suite/package**: `sbt "testOnly <pattern>"`, where `<pattern>` is a fully- or partially-qualified class name with `*` wildcards, e.g. `testOnly *AuthSpec` or `testOnly com.example.billing.*`.
- **One method** (ScalaTest): `sbt "testOnly <ClassPattern> -- -z \"<substring of the test name>\""` — `-z` does a substring match on the test's description/name. If the project uses ScalaTest tags instead of free-text names, use `-n <TagName>` instead of `-z`.
- Multi-project build: prefix with the sbt project name, e.g. `sbt "core/testOnly *AuthSpec"`.

Run the command and let it finish; don't assume success or failure before the output returns.

## 4. Report results

Report back:
- The exact command you ran (so it can be re-run or adjusted).
- Pass/fail counts and the names of any failing tests, taken from the actual sbt/test-framework output — never invent a result.
- If `testOnly`'s pattern matches no test classes, or the project has no test sources yet, say that plainly instead of reporting a pass.

## Compatibility

- **Loads natively in**: Claude Code (CLI, desktop app, and the web app at claude.ai/code) — place this folder at `.claude/skills/sbt-test/` (project-level) or `~/.claude/skills/sbt-test/` (personal, all projects) and it's auto-discovered and invocable as `/sbt-test` or via the Skill tool.
- **Manual-load fallback**: in any other client (or a Claude Code session that hasn't picked it up — e.g. mid-session after adding the file), paste the body of this file into the chat as an instruction, or reference its path and ask Claude to follow it.
- **Known limitation**: this skill can only scope to tests sbt/the test framework can already discover — if the target project has the test-framework dependency declared but zero test source files (dependency added, no tests written yet), every `testOnly` pattern will legitimately match nothing; that's not a sign the command is wrong.
