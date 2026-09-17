# Data Model: Navigation that doesn't strand you

**Feature**: `003-easier-navigation` | **Date**: 2026-09-17

Almost everything here is a **client-side shape**, not a database table. Navigation is derived from
data the app already fetches; the only persisted change in this feature is one nullable column.

---

## 1. `LeagueDestination` (new, client)

The declared table that fixes research.md R1 and R5. One row per page a league offers.

| Field | Type | Notes |
|---|---|---|
| `key` | `'board' \| 'live' \| 'history' \| 'power' \| 'analysis' \| 'mock'` | stable identity, used by the switcher to preserve the current page across leagues |
| `label` | `string \| (season) => string` | a function for `board`, whose label is already status-dependent ("Draft room" while pre-draft or drafting, "Draft board" when complete, "Mock draft" otherwise) |
| `glyph` | `string` | the existing rail glyphs — `▦ ◷ ▲ ◫ ▶` |
| `href` | `(ctx: LeagueContext) => string` | built from `sleeperLeagueId` or `sleeperDraftId` as that destination requires |
| `match` | `RegExp` | identifies the destination from a pathname; **the single source** `leagueRefFromPath` composes |
| `sports` | `Sport[]` | which sports offer it. `analysis` is `['nfl']` only |
| `requiresStatus` | `DraftStatus[] \| null` | `live` only appears while `pre_draft` or `drafting` |
| `isAction` | `boolean` | `mock` is a button that opens a modal, not a link — it has no `match` |

**Validation rules**

- Every `match` must be reachable by some `href` in the same row. A route pattern with no builder,
  or a builder with no pattern, is the exact defect this feature exists to fix and must fail a test,
  not a review.
- `sports` may not be empty, and may not be defaulted. A defaulted sport list asserts a rule rather
  than a value — the same shape that has shipped a multi-sport bug three times in this project.
- `key` values are unique.

**Relationships**: consumed by `leagueRefFromPath` (matcher), `LeagueRailSection` (rendering), the
palette's index builder, and the league switcher's fallback. Four consumers, one declaration.

---

## 2. `LeagueContext` (existing, extended)

Today's `RailLeague` in `railLeague.ts`: `{ lineage, season }`. Unchanged in shape; extended in
where it can come from.

| Source of the league | Today | After |
|---|---|---|
| `/leagues/:id/{history,power}` | path | path |
| `/leagues/:id/analysis` | **nothing** | path |
| `/drafts/:id{,/board,/live}` | path | path |
| `/managers/:id/history` | **nothing** | navigation state from the standings row that linked here; absent on a direct visit |
| `/mock/:id` | **nothing** | session's `sourceSleeperLeagueId` (Phase 2) |

**State note**: manager history and mock context are *best-effort*. A direct visit to
`/managers/:id/history` with no referring league legitimately has no league, and the rail must render
no League section rather than guess one. This is a deliberate asymmetry with the `/leagues/` routes,
where the league is always knowable from the URL.

---

## 3. `SearchDestination` (new, client)

One flattened, searchable row. Built by `searchIndex.ts`; never persisted.

| Field | Type | Notes |
|---|---|---|
| `id` | `string` | stable key for list rendering |
| `kind` | `'league-page' \| 'season-board' \| 'manager'` | drives grouping in the overlay |
| `label` | `string` | what the user reads — "History", "2025 board", a manager's name |
| `context` | `string` | which league or sport it belongs to. Required, because five leagues each have a "History" (FR-005) |
| `href` | `string` | resolved, not a builder |
| `sport` | `Sport` | for the sport pill and for filtering |
| `terms` | `string[]` | lowercased haystack: league name, page label, season year, manager name |

**Derivation**

```
for each lineage in leagueLineages(getDrafts()):
  for each destination in LEAGUE_DESTINATIONS where sport is offered and status permits:
    → one 'league-page' row against lineage.current
  for each season in lineage.seasons where seasons.length > 1:
    → one 'season-board' row, labelled by year
for each sport in ['nfl','nba']:
  for each manager in getManagers(sport):
    → one 'manager' row, merged on manager identity across sports
```

**Validation rules**

- Manager rows merge on **manager id**, not display name. Ten of twelve Ball Knowers managers are the
  same Sleeper user in both sports (`api.ts:426`) and must appear once, carrying both sport pills.
- A destination whose sport does not offer it is never emitted (FR-006).
- `context` is never empty.

**Lifetime**: rebuilt when the cached draft list changes; held in memory only. Opening the overlay on
a warm cache issues no request (NFR-001).

---

## 4. `mock_draft_session.source_sleeper_league_id` (new, persisted)

The only database change in this feature.

| Column | Type | Null | Default |
|---|---|---|---|
| `source_sleeper_league_id` | `text` | yes | none |

- **Migration**: `V16__mock_source_league_id.sql` (head is currently `V15__player_projection.sql`).
- **Written**: at mock creation, from the value `MockDraftService` already resolves and validates
  (membership + sport) before discarding it today.
- **Never backfilled**: rows written before V16 have only `source_league_name`, and matching leagues
  by display name would be both ambiguous and a way around the membership check. Old mocks keep no
  league context; that is the correct answer, not a gap.
- **Nullable forever**: a mock started with no league in mind has no source league.

**State transitions**: none. Written once at creation, read thereafter.

---

## 5. What is deliberately *not* modelled

- **Players.** The palette indexes navigation, not the player pool. Draft rooms have their own player
  search over a different index with a different lifetime.
- **A navigation history or "recently visited" store.** No requirement asks for it, and it would be
  the first thing in this feature to need persistence.
- **Per-user navigation preferences.** The rail's collapsed state already persists in `localStorage`
  under `bk-rail`; nothing here adds a second preference.
