# Contract: `MockSessionState.sourceSleeperLeagueId`

**Feature**: `003-easier-navigation` | **Phase**: 2

The only API change in this feature. One additive, optional field on an existing response.

> **Amended after implementation (2026-09-17).** This document originally said `MockSessionState`
> already carried `sourceLeagueName` and that the change sat beside it. That was wrong, and it
> changed the work:
>
> - `sourceLeagueName` lives on **`MockSessionSummary`** (the `/api/mocks` list row, mirroring
>   `MockDraftRepository.SessionSummary`), not on `MockSessionState`.
> - `MockSessionState` — what `/mock/:id` actually fetches — had **no source-league field at all**,
>   and `MockDraftRepository.SessionRow` did not carry one either.
>
> So the change is wider than one column read back on an existing field: `SessionRow` gained the
> column and both of its `select`s, `SessionSummary` gained it too, and `MockSessionState` gained a
> genuinely new field rather than a sibling of an existing one. The diff below shows the summary
> row; the session state gains the same field with no `sourceLeagueName` beside it.

## Affected endpoints

Every endpoint returning `MockSessionState`:

- `POST /api/mocks`
- `POST /api/mocks/from-draft/{sleeperDraftId}`
- `GET /api/mocks/{id}`
- `GET /api/mocks` (list)

## The change

```diff
 {
   "id": 4,
   "sport": "nfl",
   "teams": 12,
   "rounds": 15,
   "userSlot": 5,
   "currentPickNo": 4,
   "createdAt": "2026-09-11T02:14:51Z",
-  "sourceLeagueName": "(Foot) Ball Knowers"
+  "sourceLeagueName": "(Foot) Ball Knowers",
+  "sourceSleeperLeagueId": "1346366555759341568"
 }
```

**Type**: `string | null`. **Additive**: no existing field changes meaning, type or nullability.
`sourceLeagueName` stays — it is the display label and remains the only thing old rows have.

## Semantics

| Case | `sourceLeagueName` | `sourceSleeperLeagueId` |
|---|---|---|
| Mock created from a league, after V16 | the name | the id |
| Mock created from a league, **before** V16 | the name | `null` |
| Mock created with no league in mind | `null` | `null` |
| Forked from a live draft (`/from-draft`) | per existing behaviour | the forked draft's league id where known, else `null` |

The second row is the one that matters: **old mocks are not backfilled.** Matching a stored display
name back to a league would be ambiguous across users and would bypass the membership check that the
id path performs at creation. A pre-V16 mock correctly shows no league context.

## Client requirements

The field is **optional on the client type**, not merely nullable:

```ts
sourceSleeperLeagueId?: string | null
```

The frontend and backend are separate Railway services that deploy independently, so a new frontend
talking to an old backend is a state every rollout passes through — not a hypothetical. `api.ts:579`
records the 2026-09-14 incident where a missing field reached `.toUpperCase()` during render and took
the entire home screen to a white page. The rule that came out of it applies here unchanged:

> A missing field should degrade one badge, never the page.

Absent or `null` → the rail renders no League section for that mock. It never throws, and it never
falls back to matching on `sourceLeagueName`.

**Deploy order**: backend first. A new backend serving an old frontend is harmless — the extra field
is ignored.

## Persistence

`V16__mock_source_league_id.sql`:

```sql
alter table mock_draft_session add column source_sleeper_league_id text;
```

Nullable, no default, no backfill, no index — it is read by primary-key session lookup, never
searched. Current migration head is `V15__player_projection.sql`.

## Verification

- Integration test: a mock created with `sourceSleeperLeagueId` round-trips it; one created without
  reads back `null`.
- **Check the skip count.** The backend suite reports `BUILD SUCCESSFUL` with roughly 52 tests
  silently skipped when Postgres is not running, so a green build alone does not mean the `*IT`
  above actually ran.
