# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**This is the hot file — rules only.** For language/framework/build-tool/architecture reference, see [docs/context/stack.md](docs/context/stack.md). For known context gaps, see [context/cold/gap-log.md](context/cold/gap-log.md).

## Commands

- Run the server: `sbt run` (auto-recompiles/restarts on source changes)
- Run tests: `sbt test`
- Compile only: `sbt compile`
- GraphiQL UI: `http://localhost:8080` once running; POST queries to `http://localhost:8080/graphql`

## Rules

- Use the build tool, test runner, and dependency versions specified in the warm context (`docs/context/stack.md`) — don't introduce a new one.
- Resolve any new relationship between GraphQL types (e.g. a type gaining a field that references another type) through the Fetcher/Relation batching mechanism described in the warm context — never call the DAO directly from inside a field resolver.
- To require authentication on a new mutation or field, add the `Authorized` tag to its `Field` definition (`tags = Authorized :: Nil`). Don't add ad-hoc auth checks elsewhere.
- Keep `Server.scala` and `GraphQLServer.scala` thin (HTTP wiring and executor setup only) — schema/business logic belongs in `GraphQLSchema`/`DAO`.
- The database is in-memory and reset on every server start — don't write code or tests that assume data persists across restarts.
- Before treating a factual claim in `docs/context/stack.md` as current, spot-check it against the code (versions and file layout can drift from the doc).
- Don't overwrite this file or `docs/context/stack.md` silently — if restructuring hot/warm content, preserve anything not superseded, or move it to `docs/context/legacy-instructions.md`.
