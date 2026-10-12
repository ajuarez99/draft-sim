# Data model: spec 030

## E1. `player_season_projection` (new table, migration `V31__player_season_projection.sql`)

The highest applied migration on `main` (943b303) is `V30__draft_target.sql`. Before
writing V31, check that no other open branch has already taken it.

| Column | Type | Notes |
|---|---|---|
| `id` | bigserial PK | |
| `sport` | text not null | `nba` for now. The table is sport-generic. |
| `season` | int not null | |
| `sleeper_player_id` | text not null | Sleeper's id, the same keying as `player_projection` (V15). A player this app hasn't ingested is still stored. |
| `stats` | jsonb not null | The **per-game** stat line exactly as returned (R1), minus `adp_*`. Scored at read time per league. |
| `basis` | text not null | `PER_GAME` (R1: `gp` = 1.0). Stored so a later source that sends season totals can't be read as per-game by accident. |
| `source` | text | `rotowire`, as measured. |
| `source_modified_at` | timestamptz | Sleeper's `last_modified` |
| `fetched_at` | timestamptz not null default now() | |
| unique | `(sport, season, sleeper_player_id)` | |

**Validation on ingest**:
- Keep only rows whose `stats` holds `pts` (529 of 2,120 measured). Same discipline as
  `ProjectionIngestService.toRows`: a stub row and a projected zero mustn't look alike.
- Reject (log, don't store) a whole fetch if `gp` is present on a row and isn't 1.0. That
  would mean the basis changed underneath us.
- Store no row for a player without a stat line. Absence means "no projection", which is
  what `missing` counts.

**Replacement**: a fetch upserts every returned row. Rows for players no longer
returned are **deleted** for that `(sport, season)` in the same transaction, so a player
who drops out of the projections stops counting instead of keeping a stale line.

## E2. `SeasonState` (computed, not stored)

```
enum SeasonState { PRE_DRAFT, DRAFTED_UNSCORED, IN_SEASON, COMPLETE }
```

The rule (R5) is evaluated in one backend method, in this order:

1. `league.complete()` → `COMPLETE`
2. the league's draft isn't `complete` → `PRE_DRAFT`
3. `weekPoints.storedWeeks(league.id())` is empty → `DRAFTED_UNSCORED`
4. otherwise → `IN_SEASON`

| Transition | What triggers it |
|---|---|
| PRE_DRAFT → DRAFTED_UNSCORED | Draft status turns `complete` (poller or ingest) |
| DRAFTED_UNSCORED → IN_SEASON | The first scored week is stored by the history ingest (bounded by `last_scored_leg`) |
| IN_SEASON → COMPLETE | The league is complete |

A league with no draft row (an imported season with no draft) counts as drafted. Its
state is decided by stored weeks alone, because "draft not complete" can't be asserted
without a draft.

## E3. Projection set (read model)

For one `(sport, season)`: `{ source, sourceModifiedAt, fetchedAt, players: count }`.
It's carried as provenance on every strength response (FR-006a).

## E4. Strength entry: reuses `LeagueAnalysisService.RosterProjection`

No new record. For basketball the existing fields mean:

| Field | NFL meaning (today) | NBA meaning (new) |
|---|---|---|
| `total` | rest-of-season projected points | projected points **per week**: the best lineup's summed per-game projections, one game per starter (R3) |
| `byPosition` | starters' points by the starter's own position | same, by the starter's **primary** position (spec 026) |
| `rankByPosition` | rank in league per position group | same |
| `starters` / `bench` | rest-of-season value | per-game value |
| `byWeek` | per remaining week | **empty list** for NBA (R4: a flat series would imply week-by-week computation) |
| `missing` | rostered players with no projection | same |

New fields on `LeagueAnalysisService.Projections`. They're added to the Java record **and**
`web/src/api.ts` in the same change (constitution rule 2):

| Field | Type | Notes |
|---|---|---|
| `unit` | `"REST_OF_SEASON" \| "PER_WEEK"` | NFL `REST_OF_SEASON`, NBA `PER_WEEK`. The page labels every number with it. |
| `basisNote` | string, nullable | NBA: the one-credited-game stand-in sentence (R3). The client owns the words; this is a code (`ONE_AVERAGE_GAME_PER_STARTER`). |
| `provenance` | `{ source, sourceModifiedAt, fetchedAt }`, nullable | E3 |
| `unprojectedCategories` | string[] | Scored keys that appear in no projection row (R2). Empty for NFL. |

## E5. Preseason favorite (computed)

From the ranked `rosters` of E4:

- `leaders`: every roster tied for rank 1 **or** within the near-tie margin of rank 1.
- `marginPoints` = `total(rank 1) − total(first roster not in leaders)`, in E4's `unit`.
  For NFL it's divided by the window's remaining weeks so the headline is always
  "pts/week" (FR-007).
- `marginPct` = `marginPoints / total(that roster) × 100`, one decimal.
- **Near-tie**: `marginPct` below `preseason.near-tie-pct` in `config/weights.yml`,
  default **1.0**. Labelled in the file as hand-set and **arbitrary**, not fitted. When
  there's more than one leader, `marginPoints`/`marginPct` describe the gap from the
  leader group to the next team, and the headline names a tie (FR-009).
- `null` when projections are unavailable. The reason comes from E4's `reason`.

## E6. Draft board link (FR-010, amended)

In `DRAFTED_UNSCORED`: `{ draftId }` of the completed draft, so the home can link its
board. No grades are shown (R6).
In `IN_SEASON`: the existing draft-grades read (spec 018), condensed to best and worst
team grade plus `gradesEarly`.

## E7. First scored week (FR-002, FR-011)

`{ week: league.leg or null, startDate: sport-state season_start_date or null,
gamesThisWeek: { mine: int, byRoster: {rosterId: int} } or null }`

`gamesThisWeek` comes from the existing NBA schedule grid (spec 017) for that week. It's
null for NFL, and null when the schedule isn't loaded (then FR-014's "say what's
missing" applies).
