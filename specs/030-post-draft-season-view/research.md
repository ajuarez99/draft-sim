# Research: spec 030, the season after the draft

Every item below says how it's known. **Measured** means a call was run or code
was read this session (2026-10-11). **Assumed** means it wasn't.

## R1. What the NBA season projection payload actually is (measured)

`GET https://api.sleeper.app/projections/nba/2026?season_type=regular&position[]=PG&…C`
returned 200 with 1,482,046 bytes in 0.37 s and 2,120 rows, all `company: "rotowire"`.
**529** rows carry a stat line (`pts` present). That matches `claude/projection-tools.md`'s
2026-10-05 count exactly.

- **Shape**: one row per player, `game_id: "season"`, `week: null`, `stats.gp: 1.0`. The
  stats are **per-game averages**, not season totals. Example: player `1970` (LAL):
  `pts 31.88, reb 7.71, ast 8.17, stl 1.59, blk 0.48, to 3.77, tpm 3.76, dd 0.56, td 0.14`.
- **Keys present** (count out of 529): `pts ast reb fga fgm fta sp` 529, `to dreb gp` 528,
  `stl ftm` 527, `blk` 519, `tpm` 495, `dd` 209, `td` 26. A missing key means a projected
  zero for that player (for example, no `td` row for a non-triple-double player). It doesn't
  mean the stat is unknown.
- **`last_modified`** = 1791719411944 ms, which is 2026-10-11. The projections are being
  updated during the preseason.
- **No projected games-played count for the season.** `sp` (2100.0 for 1970) looks like
  seconds per game (35 min). Not confirmed.

**Decision**: store each player's per-game stat line as returned, keyed
`(sport, season, sleeper_player_id)`, and score it at read time with each league's own
`scoring_json` through `GameScoringService.score`. That's the one implementation of "what
is a game worth" (its javadoc names the two-implementations defect).
**Rationale**: the existing `player_projection` table only holds `pts_ppr/half/std`
(V15). For NBA, `pts_std` matches neither league's scoring (`GameScoringService` javadoc:
Jokić 56.5 vs the league's 58.5). Basketball has to be scored per league, so the raw line
is what must be stored.
**Alternatives rejected**:
- Reusing `player_projection` with `week = 0`. It has no stats column, so it can't be
  scored per league.
- Building roadmap 3.1 (weekly per-game lines for both sports) first. That's a much
  bigger change, and it replaces the NFL column pick, which needs the 2.1 scoring check
  before it's safe. Story 2 only needs one season line per player. This **answers the
  spec's open question: 3.1 isn't a prerequisite.** The new table is shaped so 3.1 can
  sit beside it later.

## R2. Scored categories the projection can't supply (measured)

"Ball Knowers" NBA 2026 (`1339351318115946496`) `scoring_settings`:
`pts 0.5, reb 1, ast 1, stl 2, blk 2, to -1, tpm 0.5, dd 2, td 3, bonus_ast_15p 2,
bonus_pt_40p 2, bonus_pt_50p 2, bonus_reb_20p 2, ff -2, tf -2`.

*Amended after plan review (S4):* at review time `ff` appeared on 1 of 529 lines, so **five** keys appear on no line (the four `bonus_*` keys and `tf`). The count moves with the source, and tests use fixtures. A coverage cut was considered and rejected, because `dd` (209/529) and `td` (26/529) show that missing lines are normally projected zeros. Originally written: six scored keys never appear in the projection: `bonus_ast_15p`, `bonus_pt_40p`,
`bonus_pt_50p`, `bonus_reb_20p`, `ff`, `tf`. `GameScoringService.contributions` already
iterates the scoring keys and skips stats that are absent, so they contribute 0. That's
the right arithmetic, but it's silent.

**Decision**: the strength payload lists `unprojectedCategories` (the scored keys that
appear in no projection row), and the page says so in words (spec edge case).
**Not measured**: how many points those bonuses are worth per week. They're threshold
bonuses on rare events, so the effect is assumed small. Either way it's the same for
every team, so it barely moves the ranking. It's still stated rather than assumed away.

## R3. The weekly crediting rule for basketball (measured earlier, re-read today)

Memory `project-nba-one-game-per-week-is-correct`, verified 540/540 roster-weeks: Sleeper
credits **one game per starter per week**. Among multi-game weeks, the credited game beats
the week's average game by about 14–19%, and the mechanism is unknown.

**Decision**: projected weekly strength is the **sum of the best legal lineup's per-game
projected points**, one game per starter. It's labelled the way spec 018 labels its
stand-in: "one average game per starter. Sleeper credits one game per week, and the
credited game has run about 15% above the average."
**Consequence, stated rather than corrected**: the absolute number will read about 15%
under what the league actually scores. It isn't scaled up, because a scale factor would be
a fitted constant the project has no business fitting from one memory note (AGENTS.md:
never tune a constant to agree with a guess). The ranking is unaffected to first order.
**Rejected**: games-in-week × per-game average. The league never scores that, and it's
about 2.5× too high (memory).

## R4. "Strongest team" already exists for football (measured: code read)

`LeagueAnalysisService.projectionBlocks` (`LeagueAnalysisService.java:425-468`) already
does Story 2 for the NFL. It builds the best legal lineup per roster through
`SportRules.startingLineup` with a value function, then reports `total`, `byPosition`,
`rankByPosition`, `starters`, `bench` and `missing`, ranked through `Ranker`, with `isMe`.
The only thing stopping basketball is the explicit gate at `:446`
(`if (league.sport() != Sport.NFL) return unavailable(…projectionsNotBuiltReason…)`).
`BasketballRules.startingLineup` (`BasketballRules.java:375`) already takes a value
function and handles multi-position eligibility (spec 025).

**Decision**: Story 2 is **a basketball path through the existing projection block**,
not a new ranking. The NBA path supplies `pointsByPlayer` = per-league scored per-game
projections (R1, R3), and everything downstream is reused.
This is the constitution's rule 6 in action: the spec framed Story 2 as new work, and
reading the code found the mechanism already built and tested, just gated.
**Consequence**: the Analysis page (`destinations.ts`, `sports: ['nfl']`) gets offered for
basketball. Its NFL-specific wording (bye weeks, rest-of-season week-by-week, "FLEX")
must be audited. For NBA the `byWeek` series is flat, because the same per-game line
applies every week. It's hidden for NBA rather than drawn as a flat line that implies
it was computed week by week.
**Rejected**: a separate "team strength" endpoint and page. It would be a second
implementation of best-lineup ranking, the exact defect class this repo keeps naming.

## R5. The single season-state rule (measured: code read and live API)

Live, 2026-10-11:
- League `1339351318115946496` reports `status: "in_season"`, `settings.leg: 1` and
  `last_scored_leg: null`.
- `/v1/state/nba` reports `season_type: "pre"`, `week: 2` and
  `season_start_date: "2026-10-20"`.
- All 12 rosters hold 14–16 players with 0 wins.
- Sleeper's `status` already says `in_season`, so **Sleeper's league status can't
  identify this window.**

Code: `LeagueSeasonResolver.Rule.PLAYED_WEEKS` ("at least one stored scored week in
`roster_week_points`") is the existing definition of "played". `LeagueAnalysisService`
also counts scored weeks from `weekPoints.storedWeeks(league.id())` (`:226`), on purpose
(the DB, not Sleeper, decides).

**Decision**: `SeasonState` is computed in one backend place:
- `PRE_DRAFT`: the league's draft isn't complete.
- `DRAFTED_UNSCORED`: the draft is complete and `storedWeeks(league)` is empty.
- `IN_SEASON`: at least one stored week.
- `COMPLETE`: the league is complete.

It's exposed once (contract C1), and the home reads it instead of rebuilding it from
`draft.status` and standings.
**Rejected**: the frontend's existing `preSeason` (`LeagueHome.tsx:144`, draft not
complete) plus `anyGames`. Two inputs combined client-side would be a second
implementation of the rule FR-001 says must live in one place.

**Expected first scored week**: the league's `leg` (1) is the week. The date is the
sport state's `season_start_date` when `previous_season`/`league_season` match the
league's season. Otherwise it's null, and the page says it's not known.
**Assumed**: Sleeper's NBA league week 1 starts on 2026-10-20 (tip-off). Not checked
against how spec 017 maps NBA weeks to dates. The build must reuse spec 017's mapping
(`ScheduleGridService`) instead of inventing one.

## R6. Draft grades can't run before games. The spec gets amended (measured: code read)

`DraftGradesService` (spec 018) grades picks on **realized production**: the league's
scoring applied to the player's real games. It returns `NO_SCORED_WEEKS` until a week is
scored (`DraftGradesService.java:27-28`). So FR-010 ("in the drafted-not-scored state,
show a draft grades summary") **can't be met as written.** The spec assumed grades were
available because they're "already built"; that was wrong.

**Decision**: FR-010 is amended (visibly, in spec.md):
- In `DRAFTED_UNSCORED`, the home doesn't show grades. It links to the completed draft
  board instead, and the strength ranking (Story 2) is the "who won the draft" answer.
- The grades summary block appears from the first scored week, labelled with the
  existing `gradesEarly` badge.

**Rejected**: projection-based "draft grades" (pick value from projections instead of
results). That's what Yahoo shows, but it would be a second grading model beside spec
018's, with the same name and a different basis. That's bug class #5 (a reader would
assume "grade" means one thing). It's possible later, under a different name.

## R7. FR-012 (spotlight on this season once games start) already holds (measured: code read)

`PlayerSpotlightService.forLeague` (`:171-176`) deliberately answers about the league whose
id was given, not the resolver's newest played season. Its javadoc says the spotlight must
answer about 2026 in its preseason state. No change is needed. The verification pass
confirms it on the live league after tip-off. Recorded so the build doesn't "fix" it.

## R8. Home blocks that fall back (measured: code read)

| Block | Fallback source | Action in `DRAFTED_UNSCORED` |
|---|---|---|
| `MatchupBlock` | weekly report resolver: newest played season (`LeagueHome.tsx:337`) | Removed (FR-003) |
| `PowerBlock` | newest season with a vote (`:515-518`) | Removed |
| `AwardBlock` | superlatives fallback (`:544`) | Removed |
| `StandingsBlock` | already empty-state | Replaced by the strength ranking preview |
| `NextMatchupBlock` (NBA) | current season, `leg` 1 | Kept. It's already this season. |
| `SpotlightArea` | current season (R7) | Kept |

## R9. Story 5 pre-checks (measured, for the follow-up's plan)

- **Schedule published**: `/v1/league/1339351318115946496/matchups/{1,2,19}` all return 12
  rows with a `matchup_id`. The whole regular season (weeks 1–19, `playoff_week_start: 20`)
  is paired already.
- **Supported format**: `playoff_teams: 6`, `playoff_seed_type: 0`, `divisions: 0`. That's
  inside what the in-season forecast supports.
- **Spread from history**: the 2025 league (`1229352720222134272`) has 21 stored scored
  weeks (`LeagueSeasonResolver` javadoc), which is enough to measure week-to-week spread.

None of this is built now (spec: Story 5 is a follow-up). It's recorded so the follow-up
doesn't re-derive it, and so this build keeps the strength payload shaped to feed it: a
per-team weekly mean is exactly the Story 5 input.

## R10. Projection freshness (measured: code read)

- Football projections are refreshed only by the admin `POST /api/ingest/projections`,
  never by the daily refresh (`DailyRefreshService` has no projection step).
- Rosters are read **live from Sleeper** on every analysis request
  (`LineupPass` → `sleeper.rosters`). So "rosters last refreshed" is "now", and trades
  after the draft are already counted (spec edge case met by construction).

**Decision**: add an NBA season-projection step to the daily refresh. It's one call,
about 1.5 MB, about 0.4 s. It's skipped when the stored set is younger than the existing
6-hour `STALE_AFTER` judgement, which stays labelled a judgement. There's also an admin
ingest route for a manual run.
