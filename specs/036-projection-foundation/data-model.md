# Data model: spec 036

## E1. `projection_snapshot` (new, V36)

One frozen pre-game projection: one player in one game (NBA), or one player-week's game
(NFL). Shared by every league in the sport. Points are never stored. Scoring happens at read
time under the asking league's settings.

| Column | Type | Null | Notes |
|---|---|---|---|
| id | bigserial PK | no | |
| sport | text | no | `nba` / `nfl` |
| season | int | no | |
| week | int | no | the projection row's own `week` |
| sleeper_player_id | text | no | Sleeper id, as V15/V20 |
| game_id | text | no | Sleeper game id; joins `player_game.game_id` (research R2) |
| game_date | date | no | the projection row's `date` (assumed Eastern, R3) |
| opponent | text | yes | |
| team | text | yes | the player's team at fetch time |
| stats | jsonb | no | the line as served, minus `adp_*` / `pos_adp_*` keys |
| season_avg_stats | jsonb | yes | NBA only: the `player_season_projection.stats` row at fetch time (R6) |
| source | text | yes | `company` (`rotowire`) |
| source_modified_at | timestamptz | yes | Sleeper's `last_modified` |
| fetched_at | timestamptz | no | when this line was fetched |
| cutoff_at | timestamptz | no | game_date at the sport's hand-set ET cutoff, fixed at first insert |

Constraints:
- `unique (sport, season, sleeper_player_id, game_id)`.
- `check (fetched_at < cutoff_at)`: a row can't exist that was fetched after its own
  cutoff. That's a second wall behind the guarded upsert (FR-003/004).
- Index `(sport, season)` for the accuracy read.

**State transitions:** absent → **open** (inserted, `fetched_at < cutoff_at`) → **open,
refreshed** (a later pre-game fetch replaces stats, `fetched_at`, `source_modified_at` and
`season_avg_stats`; `cutoff_at` never changes) → **frozen** (the wall clock passes
`cutoff_at`; nothing changes the row again). Frozen isn't a stored flag. It's `now() >=
cutoff_at`, so it can't be left stale.

**Why not reuse `player_projection`:** that table is a mutable cache keyed by week with a
six-hour staleness rule (V15 header, `ProjectionIngestService.STALE_AFTER`), and refreshing
it is its whole job. A frozen pre-game record is the opposite contract.

## E2. `player_projection.stats` (V36 alter)

`alter table player_projection add column stats jsonb` (nullable). Rows written before V36
have it null. The football exact-scoring path (US3) falls back to the column pick for any
row without stats, row by row, so a half-refreshed window still produces a number.

## E3. Accuracy (derived, not stored)

`ProjectionAccuracy` (Java record, mirrored in `api.ts`):

| Field | Type | Notes |
|---|---|---|
| sport | string | |
| season | int | |
| unit | `GAME` \| `PLAYER_WEEK` | NBA grades games, NFL grades player-weeks. Deliberately not `PER_WEEK`, which already means "one NBA game a week" in `Projections.unit` (review N2). |
| status | `TOO_EARLY` \| `MEASURED` \| `NO_SNAPSHOTS` | |
| minSamples | int | the N it was judged against (labelled arbitrary) |
| minCell | int | |
| series | `Series[]` | NBA: `GAME`, `SEASON_AVERAGE`; NFL: `GAME` only |
| snapshotsFrom | date \| null | the earliest game_date with a snapshot |
| scoredThrough | date \| null | the latest realized game_date among samples |
| caveats | string[] | fixed sentences, see plan.md US2 |
| source | string \| null | `rotowire` |

`Series`: `kind`, `label`, `overall: Cell`, `byPosition: Cell[]`, `byBand: Cell[]`,
`didNotPlay: int`, `pending: int`.

`Cell`: `key` (position code or band label like `"20–35"`), `n`, `mae: number | null`,
`lean: number | null` (mean projected − actual; positive means projections ran high).
`mae`/`lean` are null when `n < minCell`, or when status isn't `MEASURED`.

`AccuracySummary` (rides on `LeagueAnalysisService.Projections.accuracy`, nullable): `status`,
`n`, `minSamples`, `mae`, `lean`, `unit`. It's built from the same `ProjectionAccuracy`
`GAME` series overall cell. It's never computed separately.

## Validation rules

- Scored sample: a `player_game` row exists for (player, game_id).
- Did-not-play: no such row, but a `player_game` row exists for that game_id.
- Pending: no `player_game` row for that game_id. It's excluded from n, MAE, lean and DNP.
- Bands are judged on the **projected** value (a band is "games projected 20–35"), never on
  the actual. Banding on the actual would build regression to the mean into the figure.
