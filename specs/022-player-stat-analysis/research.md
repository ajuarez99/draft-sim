# Research: Player stat analysis (spec 022)

Written 2026-10-07. Every claim below carries one of three labels:

- **Measured**: run against the local DB (`localhost:5433`, which can lag production), the code on
  `origin/main` at `2c72ead`, or a live endpoint.
- **Read**: taken from a document.
- **Decision**: a choice made here, with the reason.

> **Amended after review, 2026-10-07** ([plan-review.md](plan-review.md); dispositions in
> [plan.md](plan.md)). The original text is kept below wherever an amendment replaces it, so the
> correction stays visible. Amended items: R1 (N1), R6 (F7, F8, N2), R7 (F4, N17), R8 (F2, F5,
> F6, N6), R10 (F12, N11), R12 (F9), and R13 (F3, N10).

## R1. The box score data is enough for everything in scope

**Measured** (NBA 2025): 29,143 rows across 1,232 games, plus 2,462 `TEAM_xxx` rows. That is two
per complete game: `sport_schedule` has 1,231 complete games, 1 canceled and 3 postponed.

> **Amended (N1)**: 29,143 is the **total**, and it includes 2,463 team rows: 2,462 coded plus the
> All-Star game's bare `TEAM_`. Player rows are 26,680 for 2025 and 26,335 for 2024. A player row
> averages 31.0 stat keys, with a maximum of 51 (N2).

Team rows carry every input the rates need: `pts reb oreb dreb ast stl blk to pf fgm fga tpm tpa
ftm fta sp plus_minus`. They also carry `bonus_*` keys. That is one more reason they must never be
scored or ranked as players.

Player rows omit zero-valued keys. A row with `sp = 0` does not occur (0 rows).

**Decision**: the feature adds no new source and no migration.

## R2. One shared game-line join, extracted from Player Trends

**Measured**: `PlayerTrendsService.prepare` (`engine/PlayerTrendsService.java:414-470`) already
encodes four rules this feature needs:

- **Team rows**: excluded from player lists, and the bare `TEAM_` row is the All-Star game.
- **All-Star filter**: a game whose opponent is not one of the season's team codes is dropped.
- **The player's own team row**: the team row in the same game whose code is **not** his
  opponent.
- **Chronology**: games are ordered by date, then game id.

**Decision**: extract these into one class, `engine/NbaGameLines`, which both Trends and this
feature call. Copying them into a second service would be the "two implementations of one rule"
class (AGENTS.md hard rules; memory "Multi-sport landmines"). The extraction is step 1 of the build,
and Trends' existing tests must pass unchanged.

The opponent's team row is also needed, for the rebound, steal and block rates and for
possessions. It is the row whose code **equals** the player's opponent. `NbaGameLines` exposes
both rows.

## R3. Usage rate already exists. Extract it, don't copy it

**Measured**: `PlayerTrendsService.usage` (`:515`) is the Basketball Reference formula, pooled over
a window's games (spec 019 F12). Spot check, run 2026-10-07: the 2025 usage leaders were Dončić at
38.4 and Brown at 36.4.

**Decision**: move `usage` into a new pure class, `engine/AdvancedStats`, next to every other
rate. Trends calls it there. Its spec 019 tests move with it, unchanged.

## R4. Fantasy figures and the breakdown share one implementation

**Measured**: `GameScoringService.score` (`engine/GameScoringService.java:38`) loops over the
**scoring** keys, multiplies each by the stat, and rounds the total to 2 decimals. Nothing returns
the per-stat parts.

**Decision**: add `GameScoringService.contributions(scoring, stats)`, returning
`Map<String, Double>` (one entry per scored key the game produced). Rewrite `score` as the rounded
sum of `contributions`. A test asserts the two agree on every 2025 game.

The season breakdown sums `contributions` over the season's games. Its parts sum to the season
total within ±0.01 × games before display rounding. FR-029 is met with no second scoring rule.

**Read and measured**: the 2026-10-07 SQL in spec.md's context multiplied each stat by its weight
outside the scorer. It showed how far the rankings move. It is not the number this feature shows.
SC-008 compares against `GameScoringService`, and nothing else.

## R5. Which formulas, and where they deviate from Basketball Reference

**Read**: the Basketball Reference glossary (`/about/glossary.html`). These formulas are facts,
reproduced here as the definitions FR-002 requires. In the table, `Tm` is the player's team,
`Opp` is the opponent, and `MP` is minutes played.

| Stat | Definition |
|---|---|
| TS% | PTS / (2 × (FGA + 0.44 × FTA)) |
| eFG% | (FGM + 0.5 × 3PM) / FGA |
| FTr | FTA / FGA |
| 3PAr | 3PA / FGA |
| USG% | 100 × (FGA + 0.44 FTA + TOV) × (TmMP/5) / (MP × (TmFGA + 0.44 TmFTA + TmTOV)) |
| AST% | 100 × AST / ((MP / (TmMP/5)) × TmFGM − FGM) |
| ORB% | 100 × ORB × (TmMP/5) / (MP × (TmORB + OppDRB)) |
| DRB% | 100 × DRB × (TmMP/5) / (MP × (TmDRB + OppORB)) |
| TRB% | 100 × TRB × (TmMP/5) / (MP × (TmTRB + OppTRB)) |
| STL% | 100 × STL × (TmMP/5) / (MP × OppPoss) |
| BLK% | 100 × BLK × (TmMP/5) / (MP × (OppFGA − Opp3PA)) |
| TOV% | 100 × TOV / (FGA + 0.44 × FTA + TOV) |
| Poss (team) | 0.5 × (T(Tm, Opp) + T(Opp, Tm)), where T(A, B) = A.FGA + 0.4 × A.FTA − 1.07 × (A.ORB / (A.ORB + B.DRB)) × (A.FGA − A.FGM) + A.TOV |
| Game score | PTS + 0.4 FGM − 0.7 FGA − 0.4 (FTA − FTM) + 0.7 ORB + 0.3 DRB + STL + 0.7 AST + 0.7 BLK − 0.4 PF − TOV |
| Per 36 | stat × 36 / MP |

**Measured**: a team row's `sp/60` is 240 in regulation, so `TmMP` is the team row's `sp/60`
directly.

**Decision: pooling.** Each rate is pooled over the window. Numerators and denominators are summed
across the player's games in the window, each game paired with that game's own team and opponent
rows. A rate is never an average of per-game rates (spec 019 F12).

**Known deviation, recorded for FR-002.** Basketball Reference computes season rates from the
team's whole-season totals. Ours pair each of his games with that game's totals. The two agree when
a player's minutes are spread evenly. They can differ for a player who missed many games, or who
was traded. This is the most likely reason a stat falls outside SC-003's tolerance. If it does,
the stat's on-page definition says so, and the formula is **not** changed to chase the number.

**Decision**: a zero denominator gives `null` on the wire, with a reason code (`NO_ATTEMPTS`,
`NO_MINUTES` or `NO_TEAM_ROW`). It never gives 0 (FR-006).

## R6. A full-season read costs about 0.65 s and 14.6 MB, so cache a compact form per season

**Measured** (local DB, psql, 2 runs): selecting a whole NBA season's player rows took 632 ms and
668 ms, producing 14,599,780 bytes of text across 29,143 rows. That is before Java parses the JSON.
Trends already pays this on every page load.

The leaderboard, the percentiles, the league ranks and replacement level all need every player's
season. Paying it per request on three new pages would put SC-006's 2-second target at risk.

**Measured**: the backend has no cache anywhere. The production JVM is set to
`-XX:MaxRAMPercentage=75` (`Dockerfile:26`). The container's memory is a Railway plan setting,
which is not recorded in the repo. That is unmeasured, and quickstart V9 reads it.

**Decision**: add a `SeasonBoxCache`, one entry per (sport, season):

- **Storage**: each game holds its non-zero stats as interned key indexes plus `float` values, and
  exposes a read-only `Map` view so `GameScoringService` can score it unchanged. That is about 25
  values per game. **Estimated**, not measured: about 5 MB per season.
- **Validity**: an entry is checked on each read with
  `select count(*), max(fetched_at) from player_game where sport = ? and season = ?`. Its cost is
  unmeasured; V9 times it. When the token changes, the season is reloaded.
- **Single flight**: one reload per season at a time.

Trends switches to the cache as well, so its page load gets faster too.

**Rejected alternatives**:

- **Aggregating in SQL.** Fantasy points would then be computed in SQL, which is a second scoring
  implementation (FR-029), and the last-5 and last-10 windows need per-game order anyway.
- **A materialised table.** A migration and a write path for data that can be derived, and a stat
  correction would make it stale.

> **Amended after review (F7, F8, N2)**. This replaces the storage and validity bullets above.
>
> - **What is cached**: the **raw** rows, `SeasonGame` + `TeamGame`, not the `NbaGameLines`
>   result. `NbaGameLines` is a pure function applied over them. The reason is that Trends'
>   `oneGameShare` reads every non-team row, including the All-Star game, which
>   `NbaGameLines` drops (measured: 15 All-Star player rows in week 17, and 5 of those players
>   become multi-game weeks only because of it).
> - **`oneGameShare`** keeps its current input in this spec, so V1 stays byte-identical. Excluding
>   the All-Star game there is a named follow-up, not a quiet change.
> - **`SeasonGame` gains `isAway`**, from `player_game.is_away`, which is populated on 100% of
>   2024–25 rows except the bare All-Star row. Trends' test helpers are updated to match.
> - **Values** are stored as `double`, not `float`. `pts_std`/`pts_std_dfs` hold tenths.
> - **Concurrency**: a lock-free single flight on the `refresh/SingleFlight` pattern, as its own
>   instance (its javadoc explains why instances aren't shared). Never `synchronized`: on Java 21 a
>   virtual thread waiting on a monitor pins its carrier.
> - **Read order**: the token is read **before** the rows.
> - **Invalidation**:
>   - `PlayerGameIngestService.refreshSportSeason` calls `SeasonBoxCache.invalidate(sport, season)`
>     when it finishes. Every refresh re-upserts every non-final row with `fetched_at = now()`, so a
>     token-only design reloads continuously during a refresh.
>   - While a refresh of that season is in flight, reads serve the existing entry, and the token
>     remains the safety net.
> - **Immutability**: cached lists and maps are unmodifiable. Trends' `prepare` sorts in place
>   (`:446`), so it sorts a copy.
> - **The token query** costs 20 ms, using `player_game_week_idx` (measured with
>   `explain analyze`).

## R7. The season picker is the existing league chain, not a new parameter

**Measured**: each season of a league is its own `league` row and Sleeper league id. The rows are
linked by `previous_league_id`. The NBA chain is 2024 (`1141438340626231296`), 2025 and 2026. The
rail already carries `lineage.seasons`, and spec 011 made one-season pages use `ctx.season`.

**Decision**: the picker switches the route between those league ids:
`/leagues/{seasonLeagueId}/players/{sleeperPlayerId}`, `.../stats` and `.../nightly`.

This gives "that season's scoring, rosters, draft and ADP" (FR-037) for free. A season that has no
stored league is never offered.

**Decision: the default season.** It uses `LeagueSeasonResolver`, extended with a second rule,
"has stored player games". The current rule is "has scored weeks". On 10-21, games exist before
week 1 is scored, and the nightly report must show them.

The resolver gets a method with an explicit rule argument (an enum, `PLAYED_WEEKS` or
`STORED_GAMES`). It does not get a defaulted parameter (memory: "Optional params that encode
rules"). A moved season is signalled with `requestedSeason`, as today.

> **Amended after review (F4, N17, N15, N16)**:
>
> - **`chainBySleeperId` walks backwards only** (`previous_league_id`), so from 2024 the picker
>   could never reach 2025 or 2026. `seasons` is now built from the chain head: a new
>   `LeagueRepository.successorOf(sleeperId)` (`where previous_league_id = ?`) walks forward to the
>   head, and the existing walk then goes back from there.
> - **Seasons with no games**: each season is marked `hasGames`. A season without them (2026
>   before 10-20) is listed as "no games yet", and picking it shows the fallback note.
> - **One fallback**: with URL-only seasons, an explicit pick of the current season and the default
>   link are the same URL, so the fallback note *is* the spec's "that statement" (N15).
> - **Different season from Trends**: the note says Trends may show a different season, because it
>   switches at "half the teams have played 5" (N16).
> - **One token query per season**: the resolver's `STORED_GAMES` check reuses the cache's
>   per-season token rather than querying twice (N17).

## R8. Ownership, current and past

**Measured**:

- **Current season**: `roster_season.players` (V28, spec 019) is stored. NBA 2026 has 12 rows, but
  each `players` value is `{}` because the league is still `pre_draft`.
- **2024 and 2025**: `roster_season.players` is null.
- **Every past week**: `roster_week_points.players_points` holds every rostered player's points.
  The JSON keys are the roster. That is 252 roster-weeks across 21 weeks for 2025, about 15.5
  players each. `RosterWeekPointsRepository.breakdownsFor(leagueId, season)` already reads it.

**Decision**:

- **Current season**: ownership reads `RosterSeasonRepository.rosteredPlayers` (V28), gated on
  `league.status` exactly as Trends does (spec 019 F5). It reports `NOT_DRAFTED` while the league
  is `pre_draft` or `drafting`.
- **Completed season**: ownership is the roster of the **last stored week**. It is labelled "end
  of regular season, week N".
- **The nightly report on a past night**: ownership is the roster of the fantasy week that night
  belongs to, from the game row's own `week`.
- **No data for a week**: ownership is `UNAVAILABLE`. It is never today's rosters (FR-037).

> **Amended after review (F2, F5, F6, N6)**. This replaces the three bullets above.
>
> - **Completed season**: ownership is the roster of week `playoff_week_start − 1`, from
>   `settings_json`. That is week 18 for 2025 and week 21 for 2024. It is labelled "end of regular
>   season (week N)". The last stored week was wrong: it is a playoff week, measured at 2025's week
>   21 with playoffs from week 19, and eliminated teams' rosters drift.
> - **A night in a scored week**: that week's `players_points` keys, as `asOf WEEK`.
> - **A night in the league's current, unscored week**: the V28 current rosters, as `asOf CURRENT`,
>   with the same draft gating. That week has no `roster_week_points` row until it is scored
>   (measured: max stored week = `last_scored_leg`). Without this rule, last night would always be
>   `UNAVAILABLE`.
> - **No roster at all**: only nights after the league's last week are `UNAVAILABLE`. For 2025
>   that is game weeks 22–25, and the page says no roster covers them.
> - **Empty roster (N6)**: if any roster's `players_points` is `{}` for a week, the whole week is
>   `UNAVAILABLE`. Otherwise its players would read as free agents, which is the partial-map trap
>   `rosteredPlayers` already guards against.
> - **A fallback season (F6)**: when the resolver moved to an earlier season, the response also
>   carries `currentOwnership` for the requested season, separately labelled. That way the 10-10 to
>   10-20 window shows who owns him now, and `ownership` (I8) still never borrows it.

The owner's name and avatar come from `RosterOwners.ownerNames`, the single naming rule since
spec 019.

## R9. Draft position comes from the stored draft; value vs. draft cost reuses spec 018

**Measured**: NBA drafts `complete` for 2024 and 2025 with 168 picks each, and NBA 2026 is
`pre_draft`. `DraftRepository.forLeague` and `picks` read them.

`DraftGradesService` already produces, per pick:

- `valueOverSlot`: production against a log fit of pick number, for the whole draft in basketball;
- `positionDrafted` and `positionFinish`.

**Decision: plan-stage amendment, shown in spec.md as well.** The spec's "league rank against
draft position" would have been a second "how did this pick do" rule beside spec 018's. The two
would disagree, because draft grades credit what the league actually counted each week, while a
rank by FP/G counts every game.

So the leaderboard's value-vs.-draft-cost column **is** draft grades' `valueOverSlot`, read from
`DraftGradesService` for the season's draft. It shows the pick, round and manager beside it, and
links to that draft's grades. Undrafted players have none. The leaderboard's own league rank stays
a separate column, labelled "rank by fantasy points per game, all games".

This meets FR-034's "reuse" clause. It changes FR-034's wording, so spec.md gets a dated amendment
note.

## R10. ADP: the snapshots have no season, so the season is the draft's date

**Measured**:

- `adp_snapshot` has no season column. NBA has 4 captures, 2026-09-08 to 09-28, from two sources:
  `blend` (2,158 rows) and `sleeper_search_rank` (3,833).
- `SimulationService.java:173` labels the blend "sleeper_search_rank + observed drafts (blend)".
  So even the blend is partly derived from search rank.
- No NBA `draft_pick.adp_at_time` is set.

**Decision**: the "preseason ADP" for a season is `BoardRepository.asOf(NBA, "blend", draftDate)`,
the latest blend capture on or before that season's draft start.

- **2025**: no capture exists before its draft, so the column reads `NO_ADP_STORED`.
- **2026**: it reads the 09-28 capture, unless a later one lands before 10-10.

> **Amended after review (F12, N11)**:
>
> - `BoardRepository.asOf` has **no callers** and never returns the capture date. The read is
>   therefore a new `BoardRepository.latestBefore(sport, source, date)`, returning
>   `{capturedOn, rows}`, with an IT that runs it on 2025 (empty) and 2026 (09-28).
> - The draft date is `draft.start_time`. `DraftRepository.DraftRow` doesn't carry it today, so it
>   gains `startTime`.
> - `start_time` is the **scheduled** time and is nullable. A null start time gives
>   `NO_DRAFT_DATE`, not a guess.
> - `captured_on` is a date, so a capture made on draft day counts as "on or before". That is
>   stated, not hidden.

**Decision: the label.** The column is labelled "Board ADP (blend of Sleeper search rank and
observed mock drafts), captured {date}". Raw search rank is never shown as ADP, which meets FR-032.
The spec's "a search rank MUST NOT be labelled as ADP" holds, and the blend's composition is stated
rather than hidden.

## R11. Replacement level uses the existing slot-eligibility rule

**Measured**: `BasketballRules.isEligible(Player, String rosterSlot)` (`sport/BasketballRules.java:418`)
already maps PG, SG, SF, PF, C, G, F and UTIL. The NBA league's `roster_positions` are
`PG SG G SF PF F C UTIL UTIL` plus 5 BN.

**Decision: the rule, labelled a simplification (FR-033).**

1. Take players meeting the rank qualification (R12), sorted by season FP/G with deterministic
   ties.
2. Fill `teams` copies of each starting slot in `roster_positions`, most restrictive first: single
   positions, then G and F, then UTIL. Each copy takes the best remaining eligible player. The
   order of the slots within a level is the league's own.
3. A position's replacement level is the FP/G of the best player not placed in step 2 who is
   eligible at that position.
4. A player's value over replacement uses the most favourable of his eligible positions.

It is computed per window. It is not a claim about who is actually on waivers: rostered bench
players count as "not starting".

**Rejected**:

- **A single league-wide replacement**: this ignores the C and PG scarcity the league's slots
  create.
- **Using real waiver availability**: that depends on ownership, which is unavailable for most
  past weeks, and it changes daily.

## R12. Hand-set numbers (all ARBITRARY, in `config/weights.yml` under `draftsim.player-stats`)

| Key | Value | Used for |
|---|---|---|
| `rank-min-games-share` | 0.5 | A player counts for ranks, percentiles and replacement when his games ≥ this share of the most games any team has played so far in the window. On opening week that is one game, so there is no empty group. |
| `rank-min-minutes-per-game` | 15 | The same qualification, by minutes |
| `small-sample-minutes` | 100 | A window below this many minutes is labelled small-sample |
| `standout-min-prior-games` | 5 | A season-high flag needs this many earlier games |
| `standout-ts-delta` | 15 | TS% points above or below the season figure that flag a shooting night |
| `standout-ts-min-attempts` | 10 | The minimum FGA + 0.44 × FTA for the shooting flag |
| `standout-minutes-jump` | 10 | Minutes above the player's last-5 average that flag a free agent |
| `leaders-size` | 5 | Rows per stat-leaders category |
| `recency-days` | 14 | **Added after review (F9).** In LAST_N windows, a player whose last game is more than this many days before the season's latest stored game is excluded from ranks, percentiles and leaders, and labelled "hasn't played since {date}". LAST_N qualification means playing all N games and meeting the minutes rule; `rank-min-games-share` applies to SEASON only |

A missing block reports `NOT_CONFIGURED`, and `/api/health` reports `playerStatsLoaded`. This
follows the Trends pattern, and nothing is defaulted.

## R13. Data timing for the nightly report

**Measured**:

- **Refresh on visit**: a season is refreshed when a page is opened and its data is more than an
  hour old (`RefreshProperties.STALE_AFTER`, `refresh/RefreshProperties.java:41`).
- **Daily refresh**: a GitHub Action calls `/api/refresh/daily` at 11:00 UTC
  (`.github/workflows/daily-refresh.yml:10`).
- **Upstream delay**: how soon Sleeper's box score is complete after a game goes final is
  **unmeasured**. The regular season starts 10-20, and our ingest reads regular-season games only.

**Decision**:

- **Completeness**: a night is complete when every scheduled game on that date in `sport_schedule`
  has status `complete` and has both team rows stored. Otherwise it is `INCOMPLETE`, with the
  count of games still missing. That count is a stored fact, not a guess, which meets FR-008 and
  US3 scenario 5.
- **FR-036**: quickstart V12 measures the upstream delay on the first regular-season nights. US3
  ships after that number exists.

> **Amended after review (F3, N10)**. This replaces the completeness bullet above. That rule was a
> second implementation of a rule Spotlight already has, and it broke on stored data:
>
> - 2024 has no `sport_schedule` rows;
> - three postponed games (01-08, and two on 01-25) would leave their nights incomplete forever;
> - the All-Star game is stored as `canceled`.
>
> The amended rules:
>
> - **Night completeness and default night**: one rule, extracted from
>   `PlayerSpotlightService.isComplete`/`choosePeriod` (`:474-525`, spec 014 R6), and shared by
>   Spotlight and the nightly report. A night is complete once every week its rows belong to was
>   fetched at or after D+1 10:00 UTC. The default night is the latest complete night, so
>   Spotlight's "Top of the night" link always lands on a night the report also calls complete.
> - **Game list**: built from the stored team rows, already All-Star-filtered by `NbaGameLines`.
>   That works for 2024 too.
> - **`missingGames`**: real scheduled games on that date with no stored team rows. "Real" uses
>   `ScheduleGridService`'s existing rule (postponed, canceled and exhibition excluded), called,
>   not copied. With no schedule for that season (2024), `missingGames` is null with
>   `NO_SCHEDULE`.
> - **Opening night (N10)** is **2026-10-20** (3 games, measured), not 10-21.

## R14. Payload and placement in the web client

**Decision**:

- **One response per page.** The player page carries every window, both percentile groups and the
  game log, about 82 games. The leaderboard carries one window at a time, all qualified and
  unqualified players (about 550), with counting stats as totals, per game and per 36. All of
  these are computed server-side, so the TypeScript side never re-derives a rate.
- **Client-side work**: sorting, filtering, column groups and the stat-leaders top 5, all from one
  comparator in `web/src/statLeaderboard.ts` (deterministic ties: value, then games, then name,
  then id). The size is unmeasured; V9 records the JSON size, and the server adds compression only
  if that is large.
- **Placement**:
  - Two new rail destinations, `nightly` and `stats`, basketball only, in `thisWeek` and
    `season`. They follow spec 011's one-season rule.
  - One shared `PlayerLink` component wraps every player name in the five pages named by FR-014.
  - The spotlight's "Top of the night" header links to `/nightly?date=…`.

## R15. Basketball Reference and ESPN are not runtime dependencies

**Read** (2026-10-07):

- Sports Reference's data use policy prohibits tools built on scraped data.
- ESPN's public endpoints returned full box scores, but their terms have not been reviewed.

**Decision**: the build fetches from neither. SC-003 is a manual check (quickstart V11) that reads
Basketball Reference's 2025-26 season, at `NBA_2026` because of the end-year naming, in a browser.
