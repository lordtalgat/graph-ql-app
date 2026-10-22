# Spec: Bulk Link Import

Status: draft (post-audit, see audit.md)
Feature ID: `bulk-link-import`

## 1. Behaviour

A signed-in user who has collected several links elsewhere (a bookmarks export, a chat thread, a reading list) wants to add all of them to this app in one action instead of calling `createLink` once per URL. They submit a GraphQL mutation `importLinks(urls: [String!]!)` with the full list of URLs in a single request. The server validates and inserts a `Link` row for each URL that is syntactically valid and not already present, attributes every created `Link` to the submitting user, and returns both the list of successfully created `Link`s and a list of rejected entries (URL + reason) — a partial success is a normal, non-error outcome, not a failure of the whole request.

Every entry in `created` and `rejected` carries the zero-based index of its corresponding entry in the submitted `urls` list, and `rejected[].url` is always the original literal string the caller submitted (never a normalized form — see §4 "Normalization rule"). This is how a caller correlates each outcome back to the specific row it submitted, regardless of the order in which the server actually processed or inserted the batch internally.

### Acceptance Criteria

**AC1 — Happy path, all valid and unique**
Given an authenticated user submits `importLinks` with 3 URLs that are all syntactically valid and not already present in the `LINKS` table,
When the mutation executes,
Then the response's `created` list contains exactly 3 new `Link` rows, each with `postedBy` set to the submitting user's id.

**AC2 — One duplicate among valid URLs**
Given an authenticated user submits `importLinks` with 3 URLs, one of which already exists as a `Link` row (posted by any user),
When the mutation executes,
Then that one URL appears in `rejected` with reason `duplicate`, and the other 2 URLs are created and appear in `created`.

**AC3 — Unauthenticated request**
Given a request to `importLinks` with no authenticated user in context,
When the mutation executes,
Then the response contains a single top-level error with code `UNAUTHENTICATED` and no `Link` rows are created.

**AC4 — Oversized batch**
Given an authenticated user submits `importLinks` with 201 URLs (one over the 200-URL limit defined in the NFR budget),
When the mutation executes,
Then the response contains a single top-level error with code `BATCH_TOO_LARGE` and no `Link` rows are created.

**AC5 — Unauthenticated and oversized at once**
Given a request to `importLinks` with no authenticated user in context, carrying a batch of 201 URLs,
When the mutation executes,
Then the response contains a single top-level error with code `UNAUTHENTICATED` (not `BATCH_TOO_LARGE`), and no `Link` rows are created.

## 2. Concurrency

Two simultaneous `importLinks` calls — whether from the same user or different users — that each include the same URL must not both succeed in creating a `Link` row for that URL. The current `DAO`/Slick access pattern (`DBSchema.scala`, `DAO.scala`) does a `SELECT`-based existence check before `INSERT`, which is a check-then-act race: two concurrent requests can both pass the duplicate check for the same URL before either has inserted, and both insert, producing two `Link` rows for one URL. This spec requires either (a) a unique constraint on `LINKS.URL` at the database level with the insert path treating a constraint violation as a `duplicate` rejection rather than a 500, or (b) serializing the check-and-insert for a given URL behind a single-writer path. A pre-insert `SELECT` alone does not satisfy this requirement. The uniqueness constraint and the batch-internal duplicate check (§4) must use the same normalization rule (§4, "Normalization rule") — a constraint on the raw string does not satisfy this section if two differently-cased or differently-ported variants of the same URL race each other.

## 3. Errors

- A per-URL validation failure (not parseable as an absolute `http://` or `https://` URL) rejects only that entry, with reason `invalid_url`, in `rejected[]` — it does not fail the batch.
- A malformed request (the `urls` argument missing, `null`, or not a list — i.e. a GraphQL argument-type violation) returns a single GraphQL validation error (equivalent to HTTP 400: empty/absent `data`, non-empty `errors`) and creates nothing.
- Authentication failure (`UNAUTHENTICATED`, see AC3) and batch-size failure (`BATCH_TOO_LARGE`, see AC4) are checked before any URL in the batch is processed — no partial creation can happen ahead of either of these checks. Authentication is checked first: a request that is both unauthenticated and over the batch-size limit returns `UNAUTHENTICATED`, not `BATCH_TOO_LARGE` (see AC5) — consistent with this codebase's existing `AuthMiddleware`, which runs before field-level resolution regardless of arguments.
- A database write failure during a single URL's insert (see §5 Integrations) rejects only that URL with reason `db_error`; it does not abort URLs already committed earlier in the same batch, and does not fail the whole request.

## 4. Boundaries

- **Empty list** (`urls: []`): not an error. Returns `created: []`, `rejected: []`.
- **Oversized list**: a batch of more than 200 URLs is rejected wholesale per AC4 — never silently truncated to the first 200.
- **Malformed single entry**: a URL string that is empty, exceeds 2048 characters, contains control characters, or uses a non-`http(s)` scheme (e.g. `javascript:`, `ftp://`, `data:`) is rejected per-entry with reason `invalid_url`.
- **Duplicate within the same batch**: if the same URL, after normalization (see "Normalization rule" below), appears more than once in one request's `urls` list, only the first occurrence is considered for insertion; subsequent occurrences are rejected with reason `duplicate_in_batch`, even though no pre-existing `LINKS` row conflicts with them against the raw string.

### Normalization rule

Two URL strings are the same URL, for every duplicate check in this spec (batch-internal above, and against existing rows per §2), if they are byte-identical after normalization. Normalization: lowercase the scheme and host; strip a default port (`:80` for `http`, `:443` for `https`); strip a single trailing `/` from the path when the path would otherwise be empty (`https://x.com/` → `https://x.com`); leave the rest of the path, the query string, and the fragment unchanged and case-sensitive. The database-level unique constraint required by §2 must be defined on this normalized form (e.g. a generated column or expression index), not on the raw `LINKS.URL` string, or case/port variants will bypass it. The *original, unnormalized* string the user submitted is always what gets stored in `LINKS.URL` and returned in `created`/`rejected` (§1) — normalization is only a comparison key, never a stored or displayed value.

## 5. Integrations

The only external dependency this feature has is the existing H2/Slick database (`DBSchema`/`DAO`) — no third-party link-preview, metadata-enrichment, or URL-unshortening service is in scope for this version (see audit.md for any such suggestion and its disposition). Assumed failure mode: a per-URL `INSERT` can fail due to connection pool exhaustion, a constraint violation, or a query timeout. On any such failure for a given URL, that URL is rejected with reason `db_error` (see §3); the request as a whole still returns `200`-equivalent GraphQL success with whatever subset of URLs succeeded, rather than aborting the batch.

## 6. NFR budget

| Metric | Target |
|---|---|
| P95 latency, 50-URL batch | ≤ 800 ms |
| P99 latency, 50-URL batch | ≤ 1500 ms |
| Max batch size | 200 URLs per request |
| Max request payload | 64 KB |
| Max single URL length | 2048 characters |
| Sustained concurrent request rate | 20 req/s with P95 within budget above |
