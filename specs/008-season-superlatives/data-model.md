# Data model: Season superlatives, so far

One migration, **`V22__season_superlatives.sql`** (V21 is the highest on `main` as of 2026-09-22;
re-check before writing it, since migrations are append-only and concurrent sessions exist).
Three new tables. No existing table is altered.

Everything else is **read** from tables that already exist: `roster_week_points` (scores,
`players_points`, `starters`), `league_matchup` (pairings), `league_transaction` (adds by type),
`player_game` (games played), `league_member` (commissioner), `league.settings` via
`LeagueRepository.playoffFormat` (`playoff_week_start`).

---

## New stored data

### `player_absence` — a scoring period a player's real team played and he didn't

| Column | Type | Notes |
|---|---|---|
| `sport` | text not null | as `player_game.sport` |
| `season` | int not null | |
| `week` | int not null | fantasy week, from the entry's own `week` (as `player_game`) |
| `sleeper_player_id` | text not null | |
| `game_id` | text null | basketball: the missed game's id. Football `None` weeks have none |
| `game_date` | date null | null for a football `None` week |
| `team` | text null | the player's real team that week, from the entry or its neighbours |
| `basis` | text not null | `ENTRY_WITHOUT_PLAY` (entry present, `playedIn` false) or `TEAM_PLAYED_NO_ENTRY` (football `None` week, team seen playing) |

- **Key**: unique `(sport, season, sleeper_player_id, week, coalesce(game_id, ''))`.
- **Why a separate table, not a flag on `player_game`**: research R9. `player_game` keeps meaning
  "games played" for spec 005's readers.
- **Shared across leagues**, like `player_game`: it's a fact about a player, not one league's reading.
- **Written by** `PlayerGameIngestService`, in the same walk as `player_game` rows.
- **Unclassified football `None` weeks** (team unknown) are **not** written. The walk returns their
  count in its `Result`, and the superlatives payload reports it as coverage.

### `status_capture` — "we looked at suspension tags during this week"

| Column | Type | Notes |
|---|---|---|
| `sport` | text not null | |
| `season` | int not null | Sleeper `/state/{sport}` `season` at capture |
| `week` | int not null | Sleeper `/state/{sport}` `week` at capture; 0 in the offseason |
| `captured_at` | timestamptz not null | latest capture that week (upsert) |

- **Key**: `(sport, season, week)`.
- **Written by** `PlayerIngestService.ingest(sport)` on every successful run.

### `player_suspension` — a player tagged suspended in a captured week

| Column | Type | Notes |
|---|---|---|
| `sport`, `season`, `week` | as `status_capture` | FK to `status_capture (sport, season, week)` |
| `sleeper_player_id` | text not null | |

- **Key**: `(sport, season, week, sleeper_player_id)`.
- **Written by** the same run, for every player where `SportRules.isSuspended(player)` is true
  (research R11: football `injury_status = 'Sus'`, basketball `status = 'SUS'`).
- **Never backfilled**: Sleeper only exposes today's tag (FR-019).

### `league_conduct_entry` — the commissioner's list

| Column | Type | Notes |
|---|---|---|
| `id` | bigserial pk | |
| `league_id` | bigint not null → `league(id)` on delete cascade | one league-**season**, so FR-017's "never visible to another league" is structural, and a new season starts with an empty list (spec amendment 9) |
| `sleeper_player_id` | text not null | |
| `reason` | text not null | 1–140 chars, trimmed; shown as plain text |
| `applies_from_week` | int not null | ≥ 1. Counts from this week on (FR-018) |
| `added_by_manager_id` | bigint null → `manager(id)` | null when added by the configured app owner, who has no manager row in the league |
| `created_at` | timestamptz not null default now() | |

- **Key**: unique `(league_id, sleeper_player_id)`. Editing an entry replaces its reason and week.
- **Deleted** on removal (research R12).

---

## Computed, not stored (the superlatives payload)

Everything below is computed per request from the tables above. Nothing is cached, matching every
other analytics page here.

### Season window

- `season`, `sport`
- `throughWeek`: the highest stored regular-season week
- `weeksScored`
- `regularSeasonEnd` (`playoff_week_start - 1`, or null when the league has none)
- `early`: `weeksScored < 4` (research R6)

### Superlative (one per kind)

| Field | Notes |
|---|---|
| `kind` | `HIGHEST_WEEK`, `LOWEST_WEEK`, `BIGGEST_BLOWOUT`, `CLOSEST_GAME`, `CLOSE_WINS`, `CLOSE_LOSSES`, `LUCKIEST`, `UNLUCKIEST`, `MOST_BENCH_POINTS`, `WAIVER_WIRE_WARRIOR`, `JOEL_EMBIID`, `UNETHICAL` |
| `available` | false, with a `reason`, when it can't be computed at all (FR-008) |
| `holders` | every tied team (FR-003); empty with an `emptyReason` when nobody qualifies ("no close games yet", "nobody's been bitten yet") |
| `value`, `unit` | the exact figure (`POINTS`, `WINS`, `GAMES`) |
| `detail` | the rows behind it: games, pickups, absences or conduct entries, each with week(s) (FR-002) |
| `coverage` | `{ weeksCovered, weeksExcluded, reasons[] }` whenever fewer than `weeksScored` weeks were usable (FR-005) |
| `early` | true for the kinds FR-006 names, while the season window is `early` |

### Validation rules carried from the spec

- **Luck matches the table**: `LUCKIEST` and `UNLUCKIEST` `value` are read from the regular-season
  bounded `ExpectedWinsService` result, never recomputed (FR-004, SC-002; research R3).
- **Extremes match the record book**: `HIGHEST_WEEK`, `LOWEST_WEEK`, `BIGGEST_BLOWOUT` and
  `CLOSEST_GAME` come from `LeagueRecordService` with the same explicit ceiling (research R4).
- **Close games**: `CLOSE_WINS` and `CLOSE_LOSSES` count paired games with margin
  `< SportRules.closeGameMargin()`. The margin is echoed in the payload so the page can say "by
  under N points" from the same number that did the counting (FR-011).
- **Waiver pickups**: `WAIVER_WIRE_WARRIOR` counts a starter's points only when his most recent
  arrival on that roster was a completed `WAIVER` or `FREE_AGENT` transaction (FR-012; research R8).
- **Absences**: `JOEL_EMBIID` costs every missed game (a `player_absence` row) of a regular
  contributor while on that roster, each at his estimated mean points per game played, scored by
  `GameScoringService` (FR-013/014/015; research R10, amended 2026-09-23). It never reads
  `player.injury_status`.
- **Unethical Award**: `UNETHICAL` detail rows each carry `source: SUSPENDED | COMMISSIONER`
  (FR-016), plus the weeks that qualified. The payload lists `suspensionWeeksObserved` (FR-019).

## State transitions

Only `league_conduct_entry` has any:

- **absent → present**: commissioner adds.
- **present → present**: commissioner edits the reason or week.
- **present → absent**: commissioner removes.

Every other new table is append/upsert-only ingest output.
