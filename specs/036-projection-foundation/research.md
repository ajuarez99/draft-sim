# Research: spec 036, projection snapshots and accuracy

Everything here was measured on 2026-10-14 against live Sleeper endpoints or the local
Postgres (5433, a copy of the shared dev data). "Assumed" means not measured, and says so.

## R1. Sleeper serves past projections, but not provably pre-game

- `GET /projections/nba/2025/5`: 1,044 rows, 583 with stats. **583 of 583 have
  `last_modified` after their own `date`.** Example: player 1535, game 1261814656679280641
  on 2025-11-17, last_modified 2025-11-24 07:40 UTC, pts 20.73.
- `GET /projections/nfl/2025/5`: 1,719 rows with stats. The latest `last_modified` is
  2025-10-07 04:00 UTC, after that week's Monday game.

**Decision:** accuracy uses only snapshots this app took itself before each game (US1). The
2025 rows are not used (see the spec's "Not being built").
**Rationale:** a post-game `last_modified` doesn't prove the stats were revised, but nothing
proves they weren't, and a number graded with hindsight would look measured when it isn't.
**Alternative rejected:** use 2025 rows labelled "not verified pre-game". That gets a number
before 2026 samples build up, but it's exactly the kind of more-certain-than-correct output
AGENTS.md says not to ship.

## R2. Row shape, and joining to realized games

NBA 2026 week 1 (`/projections/nba/2026/1`, five positions): 964 rows, 936 with `pts`.
Every row has `game_id`, `date`, `opponent`, `team`, `last_modified`, `company: "rotowire"`.
There are 0 duplicate (player, game_id) pairs and 0 null game_ids. Dates span 2026-10-20 to
10-25. Stat keys: `pts reb ast stl blk to tpm tpa dd td sp fgm fga ftm fta dreb ...`, with
`gp: 1`. There is **no start time**, only a date.

NFL 2026 week 7: 3,302 rows, 424 with `pts_ppr` (the rest are ADP stubs). Each row has a
`game_id` (e.g. `202610706`) and a `date`, and its stats include `pts_ppr`, `pts_half_ppr`
and `pts_std` beside the raw stats.

**Join check (measured):**
- NBA: player 1535's projected game ids on 2025-11-17 and 11-19 (`1261814656679280641`,
  `1261814666607198208`) equal the `player_game.game_id` of his realized games on those dates.
- NFL: players 7527 and 421, 2025 week 5, projected `game_id 202510532` = realized
  `player_game.game_id 202510532`, week 5.

**Decision:** a projection is paired with a realized game by `(sleeper_player_id, game_id)`,
never by week or date. That's correct for NBA multi-game weeks and postponed games alike.

## R3. What "before the game" means without a start time

`/schedule/{sport}/regular/{season}` gives each game a `status`. Values seen: `pre_game`,
`complete`, `postponed`, `canceled` (NBA 2025: 1,231 complete, 3 postponed, 1 canceled; NFL
2026 today: weeks 1–5 complete, everything later `pre_game`). It has no start time either.

**Decision:** a fetched line counts as pre-game only if **both** of these hold at fetch time:
1. The schedule fetched in the same run says that game's status is `pre_game`.
2. The fetch is before a hand-set per-sport cutoff on the game's date, in
   America/New_York: NBA 12:00, NFL 09:00. These are labelled arbitrary in `weights.yml`.
   They're set before the earliest regular tip-off or kickoff each sport has (NBA holiday
   noon games, NFL London 09:30 ET).

**Rationale:** neither check is enough alone. The status flip's lag at tip-off is
**unmeasured** (no game has been played since we started looking), and whether `date` is the
Eastern or the UTC date is **assumed** Eastern, not measured. Requiring both makes either
assumption being wrong fail safe: we lose a sample rather than recording a post-tip line as
pre-game.

**Consequence, measured against the existing job:** the daily refresh runs at 11:00 UTC
(07:00 EDT / 06:00 EST, `.github/workflows/daily-refresh.yml`), when no NBA or NFL game is
live. One run a day captures every game that day before both cutoffs. The line stored is the
morning-of line. Late inactives (NFL ~11:30 ET, NBA ~30 min before tip) are **not** in it, and
the accuracy view says so.

**Alternative rejected:** a second, afternoon cron for fresher lines. Under the cutoffs above
it could only refresh games dated later than today, so it adds nothing until the cutoffs can
be replaced with a measured start time.

## R4. Freezing

**Decision:** the snapshot row stores its own `cutoff_at` (computed once from the game date
and the sport's hand-set time). The upsert only updates when the incoming fetch is pre-game
**and** `excluded.fetched_at < projection_snapshot.cutoff_at`. A line fetched after the cutoff
is never written, so there's no post-cutoff column to keep apart. The spec allowed recording
post-cutoff lines separately ("may be"); this plan doesn't, because nothing reads them.

> **Amended after plan review (S5, B2):** the next paragraph is wrong. Measured: a
> rescheduled game gets a new game id, so an old-id snapshot is voided (plan amendments), not
> paired. The freeze is also a table trigger now, not only the upsert's WHERE.

A postponed game whose old cutoff has passed keeps its old line. It was still fetched before
the game was played, so it's still a pre-game line, just an older one. The date it was
projected for and the date it was played can differ, and the accuracy view reads the pair by
`game_id`, so nothing breaks.

## R5. Where the capture runs

`DailyRefreshService.runAll()` already runs per sport from the GitHub cron, with best-effort
steps that report without failing the run (`TRENDING`, `SEASON_PROJECTIONS`).

**Decision:** add a `PROJECTION_SNAPSHOT` step per sport, best-effort (outcome
`DONE_PROJECTION_SNAPSHOT_FAILED_BEST_EFFORT`), run **after** `SEASON_PROJECTIONS` so the
NBA season-average line it copies is that morning's. It only runs for a sport with a
current-season, not-complete league (the same gate `seasonProjections` uses). Each run:
1. Fetches the sport's schedule (one call).
2. Picks the weeks holding any `pre_game` game dated today through today+7 (Eastern).
   That's usually 1–2 weeks.
3. Fetches those weeks' projections (one call each).
4. Upserts under R3/R4.

The detail string reports `rowsWritten`, `rowsFrozenSkipped`, `rowsNotPreGame` and `weeks`,
so FR-005's "visible in refresh status" uses the existing response.

**Measured cost:** NBA week fetch ~1 MB; NFL week ~2 MB, 270–320 ms (spec 030 / league-analysis
measurements). That's two or three calls per sport per day.

## R6. The NBA season-average line is what the page actually shows

The Analysis page and the preseason strength block rank NBA rosters with
`player_season_projection` (the season per-game average, spec 030), **not** the weekly
per-game rows. Measuring only the per-game rows would grade a number the page doesn't show.

**Decision:** each NBA snapshot row also copies the player's then-current season-average stat
line (`season_avg_stats`, nullable). The accuracy report shows two NBA lines:
- **"Game projection"**: the per-game row.
- **"Season average (what the rankings use)"**: the copied line, against the same realized
  games.

Football has no season-average line, so the column is null.

## R7. Realized side, and "did not play"

- `player_game` holds only games played. NBA 2025: 29,143 rows, 0 with `sp = 0`. Absences go
  to `player_absence` (spec 008).
- NFL 2025 coverage by position (measured): WR 2,807, RB 1,826, TE 1,724, QB 675, DEF 544,
  K 543. Every projected position has realized rows.
- `player_game` is refreshed by spec 009's on-visit `LeagueRefreshService`, sport-wide and
  incremental, so the realized side keeps itself current. The snapshot is the only part with
  a deadline.

**Decision:** a snapshot is one of three things:
- **Scored**: there's a `player_game` row for (player, game_id).
- **Did not play**: no row for the player, but at least one `player_game` row exists for
  that `game_id`, so the game was played.
- **Pending**: no row for that game_id at all. This covers not yet played, postponed and
  canceled, and pending rows are excluded from everything.

This doesn't depend on `sport_schedule` being fresh.

## R8. Scoring both sides

`GameScoringService.score(Map scoring, Map stats)` is the one implementation, verified for NBA
(spec 017/022) and for NFL realized games (spec 018: 5,334/5,334 starter-weeks). Both sides of
every sample are scored with it under the asking league's `scoring_settings`. Projection-only
keys (`pts_*`, `adp_*`, `pos_adp_*`, `gp`) are stripped before scoring. The scoring map has no
entries for them, but stripping keeps a future scoring key from colliding.

Position comes from `player.positions[1]` for the sport.

## R9. Football exact scoring (US3) needs stat lines for the whole window

Football's Analysis page values the rest of the season from `player_projection` (V15, all
remaining weeks, points columns only). The snapshot only holds near weeks.

**Decision:** V36 also adds `stats jsonb` (nullable) to `player_projection`.
`ProjectionIngestService` stores the stat line it already receives and currently drops.
`LeagueAnalysisService`'s football valuation uses `GameScoringService` over `stats` **only
when** `draftsim.projections.nfl-exact-scoring` is true, which defaults to false. The build
flips it only if the parity check (contracts/parity.md) reports 0 mismatches or every
mismatch is explained, and records the count in this spec either way.

**Full-PPR scoring for the parity check:** Sleeper's own `pts_ppr` uses Sleeper's default PPR
settings. The check uses the `scoring_settings` of a local full-PPR NFL league (`rec = 1.0`)
if one exists, and otherwise Sleeper's documented defaults. Which one is used is printed in
the check's output. **Unmeasured**: whether any league here is a pure default-PPR league.

## R10. Hand-set numbers (all labelled arbitrary in `config/weights.yml`)

| Key | Value | Why this value, honestly |
|---|---|---|
| `projection-accuracy.min-samples.nba` | 500 player-games | ~2 nights of a full slate. Below it, a by-position MAE swings on a few blowouts. A guess. |
| `projection-accuracy.min-samples.nfl` | 300 player-weeks | ~1 NFL week of real projections (424 in week 7). A guess. |
| `projection-accuracy.min-cell` | 50 | A breakdown row (position or band) with fewer samples shows its count and "too few", not a figure. A guess. |
| `projection-accuracy.bands.nba` | 0–20, 20–35, 35–50, 50+ (league points) | Chosen to split a typical slate into roughly quarters. Not measured. |
| `projection-accuracy.bands.nfl` | 0–5, 5–10, 10–17, 17+ | Same, not measured. |
| `projection-snapshot.cutoff-et.nba` | 12:00 | Before NBA holiday matinees. |
| `projection-snapshot.cutoff-et.nfl` | 09:00 | Before NFL London 09:30 ET kickoffs. |

None of these is fitted to the accuracy result (FR-014). If a band split turns out lopsided,
the fix is to say so, not to retune it to look even.

## R11. Migration number

> **Amended after plan review (B1).** This section originally said 033 = V32, 035 = V34, and
> "this spec is V35". All three were wrong by the time it was written. 033 was renumbered to
> `V34__trade_assets.sql` (commit ab5dbc8), and 035's uncommitted tree holds
> `V35__espn_platform.sql`. The shared local DB already has V34 installed (rank 32, before
> V33 at rank 33). **This spec is V36.**

`outOfOrder` is unset (false). Two consequences:
- **Local:** running 036's bootRun against the shared `draftsim` database on 5433 would install
  V36 before 035's V35 and break 035's session. 036 verifies against its own database,
  `draftsim_036`, on the same cluster (`DB_URL` override).
- **Prod:** before the US1 deploy, read prod's `flyway_schema_history`. If V34 (033) or V35
  (035) isn't there yet, V36 going out first blocks them. Either they deploy first, or the
  number is changed at merge time.
