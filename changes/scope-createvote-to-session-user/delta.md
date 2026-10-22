# Change: scope-createvote-to-session-user

Change description: Require authentication for the `createVote` mutation and derive the voter's identity from the authenticated session (`ctx.currentUser`) instead of an explicit `userId` GraphQL argument — by adding `tags = Authorized :: Nil` to `createVote` (mirroring the existing `createLink`) and removing `UserIdArg` from its argument list.

Touched file: `src/main/scala/kz/talgat/graphql/GraphQLSchema.scala` (the `createVote` `Field` definition only — `DAO.createVote`, `MyContext`, `AuthMiddleware`, and `GraphQLServer` are not edited).

## Preserved behaviour (one sentence)

After this change, a single GraphQL request containing both `login` and `createVote` as sibling mutation fields (`mutation { login(email: ..., password: ...) { id } createVote(linkId: ...) { id } }`) must still succeed exactly as it does today for the equivalent `login` + `createLink` document, since that remains the only mechanism by which an authenticated `currentUser` can reach a resolver tagged `Authorized`.

## ADDED

- `createVote` gains the `Authorized` field tag (mirroring `createLink`), so `AuthMiddleware.beforeField` now calls `ctx.ensureAuthenticated()` before the resolver runs, throwing `AuthorizationException("You do not have permission. Please sign in.")` for any call with no `currentUser` in context.
- The voter's identity is now derived server-side, from `ctx.ctx.currentUser.get.id`, instead of being trusted as client-supplied input.

## MODIFIED

- `createVote`'s GraphQL argument list changes from `(linkId: Int!, userId: Int!)` to `(linkId: Int!)` — `UserIdArg` is dropped from its `arguments` list.
- The resolver body changes from `c.ctx.dao.createVote(c.arg(LinkIdArg), c.arg(UserIdArg))` to `c.ctx.dao.createVote(c.arg(LinkIdArg), c.ctx.currentUser.get.id)`. `DAO.createVote(linkId: Int, userId: Int)`'s own signature is untouched — only what gets passed as its second argument changes.

## REMOVED

- The `userId` GraphQL argument on `createVote`. Any existing caller sending `createVote(linkId: ..., userId: ...)` now gets a GraphQL argument-validation error ("Unknown argument 'userId' on field 'createVote'") instead of a successful mutation — there's no deprecation window and no backward-compatible acceptance of the now-unused argument.
- The ability to call `createVote` with no prior authentication at all. Before this change, any caller — logged in or not — could create a vote; after, an unauthenticated call is rejected outright.
- The ability for an authenticated user A to cast a vote explicitly attributed to a different user B's id. Before this change `userId` was caller-supplied and never checked against `currentUser`, so a client could vote "as" any user id it chose; after, a vote can only ever be attributed to the caller's own session identity.

## REMOVED audit

- **Generator missed:** the only file this diff touches is `GraphQLSchema.scala` — but the regression that matters most isn't local to that file. `GraphQLServer.scala` constructs a brand-new `MyContext(dao)` (default `currentUser = None`) for *every* HTTP `POST /graphql` request, and nothing anywhere in this app (`Server.scala`'s route, `MyContext`) persists `currentUser` across requests — there is no cookie, token, or session store. `login`'s `UpdateCtx` only replaces the context used by *sibling fields resolved later in the same GraphQL document* (Sangria's sequential mutation-field-execution semantics); it has no effect on a later, separate HTTP request. So the realistic two-step client pattern — call `login` once, then call `createVote` sometime later in a separate request — was never actually supported by this auth model for *any* `Authorized`-tagged field, including the pre-existing `createLink`. This change doesn't introduce that defect; it silently **doubles its blast radius from one mutation (`createLink`) to two** (`createLink`, `createVote`). The original three-bullet REMOVED list, drafted by reading only the touched field, had no way to surface this, because the missing guarantee — "a normal login-then-act-later client flow works" — lives in the *interaction* between the touched file and two untouched ones (`GraphQLServer.scala`, `MyContext.scala`), not in the diff itself.
- **Secondary miss:** no transition period. The old `userId` argument isn't accepted-but-ignored during a migration window — it fails immediately and loudly for any caller still sending it. Worth confirming with whoever owns existing API consumers before merging, not assuming.
- **Genuine complete entries:** the three original REMOVED bullets (dropped `userId` argument, dropped unauthenticated access, dropped vote-as-another-user) are all still accurate as far as they go — they just weren't the whole list. One sentence per touched/relevant file: `GraphQLSchema.scala` (touched) — correctly identified as where the argument/tag/resolver changes live, but reading it alone can't reveal that the `Authorized` tag it now shares with `createLink` has a pre-existing, undocumented reachability problem; `GraphQLServer.scala` (untouched) — holds the actual cause (fresh context per request) and must be read together with the diff, not instead of it; `MyContext.scala` (untouched) — defines `currentUser` and `ensureAuthenticated()` but says nothing about cross-request lifetime, which is exactly the undocumented part a reader has to infer rather than find written down.

## Risk note

- Highest-risk preserved behaviour: the behaviour named above — that `login` + `createVote` in one request still works via Sangria's `UpdateCtx` / sequential-field-execution semantics.
- Why at risk: it is now the *only* path by which `createVote` can ever succeed for a real caller; it rests on an implicit, undocumented ordering/propagation guarantee of a third-party library rather than anything this codebase asserts or tests; and there are zero tests in this repository (`src/test` is currently empty) exercising it even for the pre-existing `createLink` case — so a future Sangria upgrade, or a refactor that reorders `Mutation`'s field list or changes how `Executor.execute` resolves sibling mutation fields, could silently break it with nothing anywhere to catch the regression.
- One test or check that proves it survives: add a ScalaTest spec that runs a single GraphQL document — `mutation { login(email: "talgat@mail.com", password: "password") { id } createVote(linkId: 1) { id user { id } } }` — against `GraphQLSchema.SchemaDefinition` via `Executor.execute`, and assert the response has no top-level `errors` and that `createVote.user.id` equals the logged-in user's id. This is the one scenario that must keep passing for `createVote` to be reachable at all after this change.
