# Stack reference (warm context)

Reference detail for this repo. Rules derived from these facts live in [CLAUDE.md](../../CLAUDE.md); this file is where to look up or correct the underlying facts.

## Language

Scala 2.12.3 (`build.sbt:7`).

## Build tool

sbt, pinned to 1.10.6 (`project/build.properties`). Plugin: `sbt-revolver` 0.10.0 (`project/plugins.sbt`), enabled via `Revolver.settings` in `build.sbt` — this is what gives `sbt run` auto-recompile/restart on source changes.

**JDK requirement (verified 2026-10-22):** this sbt 1.10.6 / Scala 2.12.3 combination fails to compile under JDK 17 — `sbt compile` throws `java.io.IOError: java.lang.RuntimeException: /packages cannot be represented as URI` from zinc's incremental compiler, even on a clean compile with no source changes. JDK 11 compiles and runs tests cleanly. If the default `java` on `PATH`/`JAVA_HOME` is 17, pass `sbt -java-home "<path-to-a-JDK-11-install>" ...` explicitly.

## Framework / libraries

- [Sangria](https://sangria-graphql.github.io/) 1.3.0 + `sangria-spray-json` 1.0.0 — GraphQL schema definition and query execution.
- Akka HTTP 10.0.10 (+ `akka-http-spray-json`) — the HTTP server; see `src/main/scala/kz/talgat/graphql/Server.scala`.
- Slick 3.2.1 (+ `slick-hikaricp`) — database access layer; see `DAO.scala` / `DBSchema.scala`.
- H2 1.4.196, in-memory, config in `src/main/resources/application.conf` (`h2mem` block). Schema and seed data (`DBSchema.databaseSetup`) are recreated on every server start — nothing persists across restarts.

## Test runner

ScalaTest 3.0.4 is declared as a `Test`-scope dependency in `build.sbt`, but no test sources currently exist under `src/test`. Run with `sbt test` once tests are added.

### Scoping a single test run (for the `sbt-test` skill)

This is a single-module build (`build.sbt` defines one project, no `.dependsOn`/sub-project structure), so `testOnly`/`test` commands never need a project-name prefix — plain `sbt "testOnly <pattern>"` is enough. When test sources are added, they should live under `src/test/scala/kz/talgat/graphql/`, mirroring the main package, so a suite-level `testOnly` pattern can target `kz.talgat.graphql.*` or a specific class like `*DAOSpec`. ScalaTest is the only test framework configured, so the `-z` (substring name match) and `-n` (tag match) `testOnly` flags apply as-is — no translation needed for a different framework.

## Project layout

Single module, package `kz.talgat.graphql`, under `src/main/scala/kz/talgat/graphql/`:

- `Server.scala` — Akka HTTP route binding and app entry point.
- `GraphQLServer.scala` — parses the HTTP request into a GraphQL query/operation/variables and wires the Sangria `Executor` (schema, context, deferred resolver, exception handler, middleware).
- `GraphQLSchema.scala` — the GraphQL schema: object types derived from `models/` case classes via `sangria-derive` macros, plus `Query`/`Mutation` root types and the `Fetcher`/`Relation` definitions.
- `DAO.scala` — Slick query layer (`Future`-returning methods) over the tables in `DBSchema.scala`. The only place that talks to the database.
- `DBSchema.scala` — Slick table definitions, foreign keys, and startup seed data.
- `MyContext.scala` — per-request Sangria context (`dao`, `currentUser`); `login()` authenticates and throws on failure.
- `AuthMiddleware.scala` — Sangria `Middleware` enforcing the `Authorized` field tag.
- `models/` — case classes (`Link`, `User`, `Vote`), the `Identifiable` interface, auth value types, and auth-related exceptions.

## Architectural constraint

**All cross-entity relationship resolution must go through Sangria's `Fetcher`/`Relation` deferred-resolution mechanism, registered once as `GraphQLSchema.Resolver` and passed to the `Executor` in `GraphQLServer.scala`.** This is the project's N+1 query avoidance strategy: `linksFetcher`, `usersFetcher`, and `votesFetcher` batch-load related rows by plain ID or by a named `Relation` (`linkByUserRel`, `voteByLinkRel`, `voteByUserRel`). A field resolver that calls `ctx.ctx.dao.getX(...)` directly for a to-one/to-many relationship (instead of `fetcher.defer(...)` / `fetcher.deferRelSeq(...)`) bypasses batching and reintroduces N+1 queries. See `GraphQLSchema.scala:34-93` for the existing pattern to follow.
