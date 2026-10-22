# Audit: Bulk Link Import

Spec audited: [spec.md](spec.md) (pre-audit draft, before the patches described below)

## Isolation tier: B

The audit was run by spawning a fresh subagent (via the Agent tool, `general-purpose`) with no access to this conversation's history, no project memory, and explicit instructions not to use any tools, read any files, or search the repository — it saw only the literal spec text and the audit prompt pasted into its task. This is "Tier B" on the isolation scale: a genuinely separate context/session with nothing shared, but still running inside the same sandboxed environment/account as the main session (not a wholly separate device or account, which would be Tier A). It is explicitly above the Tier C floor (same conversation, memory disabled), since there was no shared conversation at all.

## Findings and resolutions

### Finding 1 — Undefined URL normalization undermines the concurrency guarantee

> §4 "Duplicate within the same batch" cites §6 for the definition of normalization, but §6 is only the NFR latency/size table — no normalization rule exists anywhere in the spec. Nothing states whether comparison is case-folded (scheme/host), strips trailing slashes, collapses default ports, reorders query params, or drops fragments. This gap also undermines §2's concurrency guarantee: that section assumes a DB-level unique constraint enforces "the same URL," but if the constraint is a raw string unique index while the application's batch-dedup logic applies some (undefined) normalization, `http://Example.com/x` and `http://example.com/x` will both pass the duplicate check and both insert successfully as two distinct `Link` rows — the exact double-insert bug §2 was written to prevent, just relocated to a case/whitespace variant instead of an exact string match.

**INCORPORATE.** Added a "Normalization rule" subsection to §4 defining the comparison key (lowercase scheme/host, strip default port, strip a redundant trailing slash on an otherwise-empty path, leave path/query/fragment untouched and case-sensitive), required the §2 DB-level unique constraint to be built on that normalized form, and clarified that the stored and returned value is always the original unnormalized string. Fixed the dangling §4→§6 reference to point at the new subsection instead. See spec.md §2 and §4.

### Finding 2 — No tie-break between UNAUTHENTICATED and BATCH_TOO_LARGE

> §3 states that `UNAUTHENTICATED` and `BATCH_TOO_LARGE` are "checked before any URL in the batch is processed," but never specifies the order between these two checks relative to each other. A request that is both unauthenticated *and* carries 201 URLs has an ambiguous expected response — the spec gives no acceptance criterion covering that combination, only AC3 (unauth, implicitly valid-size) and AC4 (oversized, implicitly authed) in isolation. Two conforming implementations (or two versions of the same one, after a refactor that reorders the guard clauses) could legitimately return different error codes for the identical request, breaking any client that branches on error code... in a way no test in this spec would catch.

**INCORPORATE.** Added an explicit ordering rule to §3 (auth is checked first, matching this codebase's existing `AuthMiddleware`, which already runs before field resolution) and a new AC5 covering the combined case. See spec.md §3 and AC5.

### Finding 3 — No correlation contract between submitted URLs and result entries

> Nowhere does the spec require that `created`/`rejected` preserve the input order or otherwise let the caller correlate each result back to the specific URL (and position) it submitted. AC1/AC2 only assert set membership and counts... not ordering or index-correspondence. For a 200-URL batch imported from a bookmarks export, the client almost certainly wants to render per-row status next to each original entry... An implementation that returns results in insertion-completion order... would pass every stated AC while making it impossible for the client to reliably map outcomes back to the rows the user actually submitted.

**INCORPORATE.** Added a requirement to §1 that every entry in `created`/`rejected` carries the zero-based index of its corresponding `urls` entry, and that `rejected[].url` is always the caller's original literal string (never the normalized form from Finding 1's fix). This resolves correlation without constraining the server to process the batch in input order internally. See spec.md §1.

## Self-check (step 6)

Re-read as a stranger: the smallest AC (AC1 — happy path, 3 valid unique URLs) is now implementable end-to-end without a clarifying question — auth check, size check, per-URL validation, normalization-based duplicate check (against both existing rows and the rest of the batch), insert, and a `created`/`rejected` response shape with index + original string are all specified with no open term left dangling. No further tightening needed before implementation.
