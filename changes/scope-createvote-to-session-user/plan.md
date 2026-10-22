# Plan: scope-createvote-to-session-user

Source: [delta.md](delta.md). One component per section; each section names the interface it shares with the next.

## Component 1 — `createVote` schema field (GraphQLSchema.scala)

The public entry point. Today: `Field("createVote", VoteType, arguments = LinkIdArg :: UserIdArg :: Nil, resolve = c => c.ctx.dao.createVote(c.arg(LinkIdArg), c.arg(UserIdArg)))`, no `Authorized` tag. After this change: `tags = Authorized :: Nil`, `arguments = LinkIdArg :: Nil`, `resolve = c => c.ctx.dao.createVote(c.arg(LinkIdArg), c.ctx.currentUser.get.id)`.

**Interface to Component 2 (auth/session):** this field's resolver only runs after `AuthMiddleware.beforeField` has called `ctx.ensureAuthenticated()` (because of the `Authorized` tag) and therefore requires `ctx.ctx.currentUser: Option[User]` to be `Some` — i.e. it depends on, but does not itself populate, `currentUser`.

## Component 2 — Auth/session establishment (`login` mutation + `MyContext.currentUser`, unchanged)

Existing, untouched code. `login(email, password)` resolves via `UpdateCtx`, replacing the `MyContext` used for *later fields in the same GraphQL document* with one where `currentUser = Some(user)`. No cookie, token, or cross-request store exists — this is a same-document-only mechanism.

**Interface to Component 1:** exposes `ctx.currentUser: Option[User]`, populated only for sibling fields resolved after `login` within one document — Component 1's resolver is only reachable within that same constraint.

## Component 3 — Data access (`DAO.createVote`, unchanged)

`def createVote(linkId: Int, userId: Int): Future[Vote]` — signature and behavior are not touched by this change; only what Component 1 passes as `userId` changes (from a client-supplied argument to `currentUser.get.id`).

**Interface to Component 1:** accepts `(linkId: Int, userId: Int)`, returns `Future[Vote]` on success or fails the `Future` on a DB/constraint error (propagated by the executor as a generic `ErrorWithResolver`, unaffected by this change).

## Component 4 — Test coverage (new, under `src/test`)

A new ScalaTest spec exercising the public schema via `Executor.execute(GraphQLSchema.SchemaDefinition, ...)` — never calling `DAO`/resolvers directly — covering: the login+createVote same-document happy path, unauthenticated rejection, legacy `userId` argument rejection, and vote-attribution-to-self.

**Interface to Components 1–3:** consumes only the public GraphQL document/response contract (query text in, JSON-shaped response out) — stays correct across internal refactors of Components 1–3 as long as their *external* contract is unchanged.

## Component 5 — Documentation and deferred-risk tracking (docs + changes/ artifacts)

A migration note recording the breaking change (dropped `userId` argument, no deprecation window) and a deferred-ticket artifact carrying forward the delta's critical audit finding (no cross-request session persistence; `login`+mutation only works combined in one document) so it isn't silently lost.

**Interface to Components 1 & 4:** read-only with respect to code — consumes Component 1's final field shape (to describe accurately) and Component 4's test names (to reference as regression coverage); produces no interface anything else depends on.
