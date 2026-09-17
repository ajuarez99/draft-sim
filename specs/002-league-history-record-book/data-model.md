# Phase 1 Data Model: League history record book

**Branch**: `002-league-history-record-book` · **Date**: 2026-09-16

No new tables for Slices A–C. Everything is derived from rows that already exist. One nullable
column is added only if Slice D (team names, [research.md](./research.md) D4) is taken.

---

## Existing storage this feature reads

| Table | Key | What this feature takes from it |
|---|---|---|
| `league` | `id` PK, `sleeper_id` UNIQUE, `previous_league_id` | The season chain. One row **per season** — `league.id` is a season, not a franchise |
| `roster_season` | `(league_id, roster_id)` UNIQUE | `manager_id`, W/L/T, PF/PA, `final_placement` (champion = 1) |
| `roster_week_points` | `(league_id, week, roster_id)` UNIQUE | `starters_points` — the scored total. Source of every score record |
| `league_matchup` | `(league_id, season, week, roster_id)` UNIQUE | `matchup_id` — the pairing key. `null` = no game that week |
| `power_ranking` | `(league_id, season, week, kind)` UNIQUE | Snapshot header. `kind ∈ {COMPUTED_REALIZED, COMMISSIONER}` |
| `power_ranking_entry` | `(ranking_id, roster_id)` UNIQUE | `rank`, `score`, `note` |
| `manager` | `id` PK, `sleeper_user_id` UNIQUE | `display_name`, `avatar_id` |

**The single most important structural fact**: `league.id` identifies a *league-season*, not a
league. Every query below is therefore scoped to a **set** of league ids — the chain — rather than
one. This is the difference between "league history" and "this season".

---

## Derived entities

### WeeklyScoreRecord

One roster's scored total in one (season, week). The unit of the high/low lists.

| Field | Type | Source | Notes |
|---|---|---|---|
| `season` | int | `league.season` | |
| `week` | int | `roster_week_points.week` | |
| `rosterId` | int | `roster_week_points.roster_id` | |
| `managerId` | long \| null | `roster_season.manager_id` | Null for an unowned roster-season |
| `manager` | string \| null | `manager.display_name` | Null when `managerId` is null |
| `avatarId` | string \| null | `manager.avatar_id` | |
| `points` | decimal | `roster_week_points.starters_points` | |

**Derivation**: for the chain's league ids, order by `starters_points` desc (high) / asc (low), take
the first *N*. Joined to `roster_season` on `(league_id, roster_id)` for manager identity.

**Rules**
- R1 — A roster-season with no `manager_id` still produces a record; it renders as the roster
  rather than being dropped (spec US1 scenario 2).
- R2 — Ties at the boundary must not silently truncate. Ordering includes a deterministic
  tiebreak (`season`, `week`, `roster_id`) so repeat loads agree, and an exact tie *within* the
  returned set renders both rows.
- R3 — A season with zero stored weeks contributes nothing. It must never contribute a `0.00`.
- R4 — The high list and the low list return the same *N*.

### MarginRecord

Two rosters sharing a `matchup_id` inside one (league_id, season, week). The unit of the closest/
blowout cards. **Blocked until the ingest repair lands** (research D2).

| Field | Type | Source | Notes |
|---|---|---|---|
| `season`, `week` | int | `league_matchup` | |
| `margin` | decimal | \|A.points − B.points\| | The sort key |
| `winner` / `loser` | side | see below | By points, not by `roster_season.wins` |

Each side carries `rosterId`, `managerId`, `manager`, `avatarId`, `points`.

**Derivation**: self-join `league_matchup` on `(league_id, season, week, matchup_id)` where
`roster_id` differs, join each side to `roster_week_points` on `(league_id, week, roster_id)`, then
order by margin asc (closest) / desc (blowouts).

**Rules**
- R5 — `matchup_id IS NULL` means no game. Those rows are excluded, so a bye contributes no margin
  (spec US3 scenario 3).
- R6 — Each pair is emitted **once**. The self-join must be constrained (e.g. `a.roster_id <
  b.roster_id`) or every matchup appears twice, mirrored.
- R7 — A pairing with no stored score on one side is excluded, not treated as zero. `league_matchup`
  holds future fixtures (2026 weeks 1–14 are scheduled and unplayed), so this case is guaranteed to
  occur, not hypothetical.
- R8 — A `matchup_id` group with more than two rosters is data this schema does not promise; it is
  skipped rather than arbitrarily paired.

### SeasonFinalRank

A roster's end-of-season power rank, attached to its standings row.

| Field | Type | Source |
|---|---|---|
| `rank` | int \| null | `power_ranking_entry.rank` |
| `rankWeek` | int \| null | the `power_ranking.week` it came from |
| `rankStatus` | enum | derived — see below |

**Selection**: for a season's `league_id`, the `power_ranking` row with `kind =
'COMPUTED_REALIZED'` and the greatest `week` **strictly greater than 0**; then its entries by
`roster_id`.

**Rules**
- R9 — `week > 0` is mandatory. Week 0 is the preseason baseline and 2026 has *only* that, so a
  plain `max(week)` would present a preseason guess as a final standing (research D3).
- R10 — `kind = 'COMPUTED_REALIZED'` is mandatory. 2026 week 1 is a `COMMISSIONER` row; a
  kind-agnostic query returns an opinion as a result.
- R11 — `rankStatus` distinguishes three cases, because FR-005 forbids a bare `—`:
  - `RANKED` — a qualifying snapshot exists.
  - `IN_PROGRESS` — the season is the chain's newest and is not finished. No final rank is
    *expected*; this is not an error state.
  - `NOT_COMPUTED` — the season is complete and has no qualifying snapshot, but its
    `roster_week_points` are present, so it *is* computable. This is the state that earns the
    button (FR-006).
  - A fourth, `UNAVAILABLE`, covers a complete season with no stored week points — nothing to
    compute from.
- R12 — "Season is finished" must not be decided by a hardcoded week number. It is derived from the
  league's own settings and the sport's state, never from `week >= 17` (FR-011; NBA runs 24).

---

## Backfill semantics (Slice B)

The backfill computes and stores a final `COMPUTED_REALIZED` snapshot for a completed season using
`PowerRankingService#computeRealized(leagueId, season, week)`, where `week` is that season's last
week present in `roster_week_points`.

- It writes through the existing `PowerRankingRepository#save`, whose `ON CONFLICT (league_id,
  season, week, kind)` makes it idempotent — re-running replaces rather than duplicates.
- It requires **no Sleeper call**: `computeRealized` reads only `weekPoints.through(...)`.
- It must refuse a season with no stored week points rather than writing an empty snapshot.

## Ingest repair semantics (Slice C)

`LeagueHistoryIngestService#ingestWeeklyPoints` currently skips a week when its points are cached,
which also skips the fixture upsert sharing that loop. The repair makes the two conditions
independent: a week is fetched when **points are missing OR pairings are missing**, with pairings
tested via the existing `LeagueMatchupRepository#scheduledWeeks` (which already declines to count an
all-null week as cached). `league_matchup.upsert` is `ON CONFLICT … DO UPDATE`, so re-running is
safe.

## Slice D storage change (only if taken)

`V16__roster_season_team_name.sql` — `alter table roster_season add column team_name text;`
Nullable, populated from Sleeper's league-users `metadata.team_name` during ingest. Display rule:
team name, falling back to `manager.display_name`. Per-season by construction, since
`roster_season` is per-season.

## Entity relationships

```text
league (one row PER SEASON) ──< roster_season ──> manager
   │  previous_league_id ──┐                         │
   │                       └── the chain             │
   ├──< roster_week_points ──────> WeeklyScoreRecord ┘
   ├──< league_matchup ──┐
   │                     └─ joined to roster_week_points ──> MarginRecord
   └──< power_ranking ──< power_ranking_entry ──> SeasonFinalRank
```
