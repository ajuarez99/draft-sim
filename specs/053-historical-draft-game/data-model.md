# Data Model: Historical Draft Game (spec 053)

> **Amended after review (2026-11-02)**: plan.md's "Amended after review" table
> supersedes the tables below wherever they disagree. The changes are:
> - `positions` moves to `historical_player_season` (F5).
> - `historical_player_game.stats jsonb` becomes typed nullable smallint columns
>   (`pts reb ast stl blk to tpm fgm fga ftm fta`) plus derived `dd td bonus_pt_40p
>   bonus_pt_50p bonus_reb_20p bonus_ast_15p tf ff`, with an integer `game_id`
>   (F15). DNPs are not stored (F11).
> - `historical_player` is upserted, never deleted (F9).
> - `historical_game` gains `reversal_round int not null` (0) (F8) and `board
>   jsonb not null`, a rank-ordered snapshot of `{playerId, adp, positionalRank}`
>   (F9).
> - `historical_season` gains `stat_coverage jsonb`, meaning `{stat: share of
>   played rows}` (FR-007).
> - A new `historical_import_job` table holds `id, part, status, started_at,
>   finished_at, report jsonb` (F14), plus an `UNLOGGED historical_kaggle_staging`
>   table (F3). It is declared in V43, not created at runtime, so all DDL stays in
>   migrations, and the import truncates it at both start and end.
> - Standings carry `ties` (F16).
> - Bust and hindsight are redefined per F1/F4.

> **Amended 2026-11-05 (renumbered V43 → V45):** spec 055 (official week scores) shipped `V44__roster_fpts_snapshot.sql`
> to main and prod first, under a deadline. Flyway runs with `outOfOrder` off, so a V43 merged after V44
> was applied would stop prod from starting. Allan chose to renumber this migration to **V45**; the SQL is unchanged.
> Any scratch DB that already applied it as V43 (e.g. 053's on 5438) needs a fresh DB or a `flyway repair`.

Migration: **`V43__historical_draft_game.sql`** (now `V45__historical_draft_game.sql`, see the amendment above). The highest migration on
`origin/main` and every local branch was V42 on 2026-11-02. Re-check this before
the migration is applied (constitution rule 1). All tables are new and nothing
existing is altered.

## Reference data (written by the import, read-only afterwards)

### `historical_season`
One row per offered NBA regular season.

| column | type | notes |
|---|---|---|
| season_start | int PK | start year: 1995 means 1995-96 |
| label | text not null | "1995-96" |
| source | text not null | `KAGGLE` or `SLEEPER` (FR-015). Exactly one per season |
| opening_night | date not null | first game date. Week 1 is the Mon–Sun week containing it |
| last_counted_date | date not null | last game date that counts (2019-20: 2020-03-11) |
| week_count | int not null | |
| game_count | int not null | distinct regular-season games loaded (SC-002) |
| counted_stats | text[] not null | scoring keys that pass the era rule, including derived keys (research R2) |
| excluded_stats | jsonb not null | `{key: reason}` for each league-default key that doesn't count. Shown by FR-008 |
| ranking_source | text not null | `PRIOR_SEASON_POINTS` for every season in v1 (research R4). `ADP` reserved |
| imported_at | timestamptz not null | |

Offered seasons: `season_start` 1970..2025, pending review item O2, which could
move the floor.

### `historical_player`
One real person (FR-014).

| column | type | notes |
|---|---|---|
| id | bigserial PK | |
| source | text not null | `KAGGLE` / `SLEEPER` |
| source_key | text not null | Kaggle: normalised name, or the curated disambiguated key (`george-johnson-2`). Sleeper: `sleeper_player_id` |
| name | text not null | display name |
| positions | text[] not null | engine positions (PG/SG/SF/PF/C). Kaggle `G` → {PG,SG}, `F` → {SF,PF}, `G-F` → {PG,SG,SF,…} per a mapping table in code |
| unique (source, source_key) | | |

A person who spans the 2016-17 / 2017-18 source seam exists twice, once per
source. That is deliberate: no season ever reads both rows (research R3).

### `historical_player_season`
The prior-season card line. Also used as the drafted season's own line in results.

| column | type | notes |
|---|---|---|
| player_id | bigint FK → historical_player | |
| season_start | int | the season this line describes. It is not necessarily an offered season: 1969 exists so 1970 has cards |
| team | text | last team, or `TOT`-derived team list joined by "/" |
| age | int null | Kaggle `Age`. Sleeper: null (not in the totals payload) **(assumed)** |
| games | int not null | |
| totals | jsonb not null | raw counting stats **as recorded**. A missing key means unrecorded (Kaggle) or zero (Sleeper), per research R2 |
| PK (player_id, season_start) | | |

### `historical_player_game`

| column | type | notes |
|---|---|---|
| player_id | bigint FK | |
| season_start | int not null | from `Game_ID` digits for Kaggle, never the CSV's calendar-year `Season` column |
| game_id | text not null | |
| game_date | date not null | |
| week | int not null | Mon–Sun bucket from `opening_night` (research R8) |
| team | text | |
| stats | jsonb not null | recorded raw stats plus derived dd/td/bonus keys when computable (R2). An empty-string CSV cell is **absent**, never 0 |
| PK (player_id, game_id) | | index (season_start, week) |

Rows after `last_counted_date` are not loaded, so the bubble games are never stored.

## User data

### `historical_game`
One user's play of one season (FR-013).

| column | type | notes |
|---|---|---|
| id | bigserial PK | |
| sleeper_user_id | text not null | owner. Same identity header the mock room uses (`X-Sleeper-User`) |
| season_start | int FK → historical_season | |
| teams, rounds | int not null | 12, 14 in v1 (research R7) |
| roster_positions | text[] not null | snapshot |
| scoring | jsonb not null | snapshot of the **era-filtered** scoring used, so a later config change never alters a finished game |
| user_slot | int not null | |
| rng_seed | bigint not null | |
| current_pick_no | int not null | |
| status | text not null | `DRAFTING` → `COMPLETE` (results written) or `ABANDONED` |
| result | jsonb null | frozen results (weekly scores, standings, comparisons). Written once at completion and never recomputed on read (FR-013) |
| created_at, completed_at | timestamptz | |

State transitions: `DRAFTING` → (last pick made → replay runs in the same
transaction) → `COMPLETE`. `DRAFTING` → `ABANDONED` is a user action. Nothing
leaves `COMPLETE`.

### `historical_game_pick`

| column | type | notes |
|---|---|---|
| game_id | bigint FK → historical_game on delete cascade | |
| pick_no, round, draft_slot | int not null | |
| seat_type | text not null | `USER` / `BOT` |
| player_id | bigint FK → historical_player | |
| PK (game_id, pick_no) | | unique (game_id, player_id) |

## Derived (computed, stored only inside `historical_game.result`)

- **Player-week score** = max over the player's games that week of
  `GameScoringService.score(eraScoring, stats)`. 0 when he has no games that week.
- **Team-week score** = the total of `startingLineup` over player-week scores (the
  extracted `RealizedLineupService` overload).
- **Schedule**: seeded round-robin over `week_count` weeks (12 teams → 11-week
  cycle, repeated).
- **Standings**: W-L first, points-for as the tiebreaker.
- **Season value** (for comparisons) = the sum of a player's player-week scores
  over the season.
- **Hindsight draft**: bots' picks held fixed. At each user pick, take the
  available player with the highest season value, then replay. It is labelled
  "greedy hindsight, bots unchanged", not "optimal".
- **Biggest bust**: the user's pick with the largest (pick number − season-value
  rank among all drafted players). **Best steal passed on**: across the user's
  picks, the available player whose season value most exceeds that of the player
  taken.
