# Cold context gaps — see context/cold/README.md for what this file is.
# This file names *gaps* in available context, not their history —
# it doesn't have to contain the backstory, just enough to flag that
# a future session should ask rather than assume.

- description: >
    Auth-provider scaffolding (AuthProviderEmail, AuthProviderSignupData in
    src/main/scala/kz/talgat/graphql/models/) is named and structured as if
    more than one signup/auth provider was planned, but only the email path
    is implemented and wired into DAO.createUser. Whether additional
    providers were intended and dropped, or this is just naming carried over
    from a tutorial, is unknown.
  discovery_event: >
    Found while writing docs/context/stack.md during the 2026-10-22
    hot/warm/cold context bootstrap, reading models/AuthProviderEmail.scala
    and models/AuthProviderSignupData.scala.
  refresh_trigger: >
    Re-investigate if a second AuthProvider type is added, or if someone
    asks why AuthProviderSignupData wraps a single-field case class instead
    of being inlined.

- description: >
    Of the four mutations in GraphQLSchema.Mutation (createUser, createLink,
    createVote, login), only createLink carries the `Authorized` tag even
    though a login mutation and currentUser context exist. There's no
    comment or commit explaining whether createUser/createVote are
    intentionally left open (tutorial simplification) or were never
    revisited after AuthMiddleware was added.
  discovery_event: >
    Found while writing CLAUDE.md on 2026-10-22, reading
    GraphQLSchema.scala:136-162 and AuthMiddleware.scala.
  refresh_trigger: >
    Re-investigate before relying on createVote/createUser being protected,
    or if a new mutation is added and it's unclear whether it needs the
    Authorized tag.

- description: >
    The Users/Votes tables and their foreign-key relations were added
    across three commits with the identical message "Add Users and Votes
    Tables" (079fcd6, de678f1, 3afad1d), merged into main via PR #1 from a
    branch named "relations" (6a18133). The commit messages give no
    indication of what changed between the three attempts or why a single
    commit became three.
  discovery_event: >
    Found via `git log --oneline` during the 2026-10-22 context bootstrap.
  refresh_trigger: >
    Re-investigate if the Users/Votes schema or the Fetcher/Relation wiring
    in GraphQLSchema.scala needs to change and the reason for the original
    three-commit rework would inform the approach.
