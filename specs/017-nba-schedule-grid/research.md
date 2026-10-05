# Research: NBA schedule grid and next opponent for basketball

Every item says whether it was **measured** (run on 2026-10-05, output recorded) or
**reasoned** (read from code, not executed).

## R1. What Sleeper's schedule actually contains (measured)

`GET https://api.sleeper.app/schedule/nba/regular/2026`, fetched 2026-10-05:

| Fact | Value |
|---|---|
| Games / unique `game_id`s | 1,200 / 1,200 |
| Keys per row | `game_id, week, date, home, away, status` (home/away are `{team}` objects) |
| Statuses | `pre_game` × 1,200 |
| Weeks | 1–25. First 2026-10-20, last 2027-04-11 |
| Games per team | **80 for all 30 teams** |
| Week 1 distribution | 2 games: 5 teams, 3: 24, 4: 1 (matches the design doc) |
| Odd weeks | wk 7 Nov 30–Dec 3 (28 teams × 2, 2 × 1). Wk 8 Dec 12–13 (28 teams × 1). Wk 18 Feb 15–18 and wk 19 Feb 25–28 (All-Star break). |

`GET /schedule/nba/regular/2025` (finished season): 1,235 games. Statuses: 1,231
`complete`, 3 `postponed`, 1 `canceled`. Raw entries per team: 82 for most, **83 for
CHI, MIA, MEM, DEN, MIL, DAL** (the three postponed games, with their makeups as
separate rows) and **NYK, SAS**. Plus `STP` and `STR` with 1 each. That's the All-Star
exhibition, dated 2026-02-15 and `canceled`.

Inferences, labelled as such: the 80 → 82 gap fits the NBA scheduling each team's last
two games after the Cup group stage (the empty Dec 4–11 window). NYK/SAS at 83 fits
the Cup final being in Sleeper's schedule. Neither changes the design. Both mean
**the stored schedule must be replaceable, and counts must come from statuses, not raw
rows.**

**Decision**: the design doc's "every team = 82" acceptance is replaced by SC-001
(stored = fetched) and SC-003 (2025 counted totals). The doc gets an amended note.

## R2. Where to store it, and when (reasoned)

`PlayerGameIngestService.doRefresh` step 1 already calls
`stats.schedule(code, season)` once per sport-season refresh. That covers every
visit-triggered chain refresh (`LeagueRefreshService.refreshChain` → `refreshSportSeason`)
and the admin `POST /api/ingest/player-games/{id}`. It's single-flight per
`sport:season`.

**Decision**: write the parsed schedule right there, after a successful parse and
before the week loop. No new Sleeper call and no new scheduler step.

- **Replace, not upsert.** A delete + insert of the `(sport, season)` rows in one
  transaction. Games can disappear from Sleeper's list (a rescheduled game may get a
  new id), and an upsert would keep the ghost forever.
- **Empty guard (FR-002).** `mapsOf` turns a non-list body into `[]`. An empty parse
  must not wipe a stored season. Log it and keep the old rows. This is the same "a bad
  answer isn't a quiet week" rule the per-game loop already applies (`:196-201`).
- **A storage failure fails the run** (same as the fetch failing today). A league
  refresh that silently skipped the schedule would leave the grid stale with no
  signal. *Alternative considered:* best-effort like trending. Rejected, because the
  schedule isn't decoration: the grid's whole content is the schedule.
- **Both sports** are stored, since the fetch already happens for NFL. Nothing reads
  NFL rows yet. That costs ~285 rows per NFL season and keeps one code path.

Completed seasons are `loaded_complete` and never refreshed again. So NBA 2025 gets a
stored schedule only through the admin route
(`POST /api/ingest/player-games/{id}?season=2025`). That's fine: the grid is for the
current season, and SC-003 is also asserted from a fixture.

## R3. One parser, one counting rule (reasoned; the two-implementations class)

`PlayerGameIngestService.Schedule` (a private nested class) already parses rows,
resolves the home/away shape (`sideTeam`), and owns the status vocabulary (`SETTLED`).
A second parser in a new service would be the "two implementations of one rule" trap
that memory records three times.

**Decision**: lift `Schedule` to a package-level top-level class
(`ingest/SportSchedule.java`) with `Game` as a public record. The ingest keeps using
its current methods unchanged. The repository stores `SportSchedule.Game`s. Add one
static predicate, `SportSchedule.counts(String status)` = not `postponed` and not
`canceled`, used by the grid service. `isFinal`'s `SETTLED` set stays as it is: it
answers a different question ("will this week's data still change"), so it shouldn't
be merged with "does this game count toward a team's week". The two sets share their
constant strings.

*Alternative*: keep the nested class and duplicate a 10-line parser in the reader.
Rejected, for the reason above.

## R4. Which teams get a row (decided)

Options: (a) every team code in a counted game; (b) intersect with team codes on
stored NBA players; (c) a hard-coded 30-team list.

**Decision**: (a). In 2025 the only non-NBA codes (STP/STR) appear in a `canceled`
game, so the counting rule removes them. (c) is a hand-maintained list that goes stale
on relocation/rebrand. (b) adds a join for a case not observed. If an un-canceled
exhibition ever appears, it shows up as a row with 1 game, visible and honest, and the
service test asserting 30 rows on the 2025 fixture will catch it. Not a silent filter.

## R5. Playoff weeks (measured once, rest declined)

There's no existing rule in the codebase for the last playoff week (grep for
`playoff_round_type`, `playoffEnd`: none). Measured for NBA 2025: `playoff_week_start`
19, `playoff_teams` 6, `playoff_round_type` 0, `last_scored_leg` 21 = 19 + ⌈log₂ 6⌉ − 1.
NBA 2026 has the same format with start 20, so weeks 20–22.

**Decision**: `end = start + ⌈log₂(teams)⌉ − 1` **only when `playoff_round_type` = 0**.
Any other value, or `start < 2`, or `teams < 2`, returns `end = null` with a reason.
Sleeper's other round types (two-week rounds or championship) aren't measured in any
league here, so the code doesn't interpret them (spec Assumptions).

Put it on `LeagueRepository.PlayoffFormat` beside `playoffWeekStart`. That means
reading `playoff_round_type` in the same query (`coalesce(...,0)`).

> ⚠️ **Watch the coalesce default**: a missing `playoff_round_type` coalescing to 0 would
> *assert* one-week rounds for a league that never said so (memory: "optional params
> that encode rules"). **Decision**: read it as nullable. Null → `end = null` with the
> reason "this league's settings don't say how long each playoff round is".

## R6. "This week" for the grid and the next opponent: one rule (measured)

There are three candidates: (a) the analysis rule, last stored scored week + 1;
(b) the schedule, first week with a not-yet-complete game; (c) Sleeper's league
`settings.leg`, which is stored in `league.settings_json` and upserted on every
ingest (`LeagueRepository:39-46`).

Measured 2026-10-05 (NFL week 4 in progress, MNF tonight): fantasy(heart) and
(Foot) Ball Knowers both read `leg` 4, `last_scored_leg` 3. `/state/nfl` reads week 4.
NBA 2026 reads `leg` 1, NBA 2025 (complete) reads `leg` 21. So on football today,
(a) and (c) agree.

(a) breaks on basketball. `ScoredWeeks` documents that NBA's current week is stored
*partial*, so last stored + 1 skips the week being played. (b) is timezone-free, but
it's a property of the NBA calendar, not the league. A league that starts late, or
whose week is offset, would disagree.

**Decision**: (c), `leg`, for both the grid's first column and the next matchup.
One rule, it's the league's own, and it's as fresh as the last refresh. It's read in
one place: a new `LeagueRepository.currentLeg(leagueId)` returning `OptionalInt`.
Absent → the grid starts at week 1 and the next-matchup says the week isn't known.
It's not defaulted to 1, for the same "optional that encodes a rule" reason as R5.

The analysis page keeps its own week (a), because it means "next unprojected week",
a different question. League home shows the projected line only when the two weeks
agree (FR-010), so the two rules never produce a contradictory block.

## R7. Next matchup: a new endpoint, not a projection-payload field (reasoned)

`LeagueHome.NextOpponentBlock` reads `getLeagueAnalysis`, and that's gated football-only
(`analysisOffered`). The pairing itself is in `league_matchup`. Future regular-season
weeks are stored by the history ingest up to `playoff_week_start − 1`
(`LeagueHistoryIngestService:311-316`), and unpublished weeks are deliberately not
stored (`scheduledWeeks`).

**Decision**: `GET /api/leagues/{id}/next-matchup`, a new `NextMatchupService` that
reads `LeagueMatchupRepository.between(leagueId, season, leg, leg)` and
`RosterSeasonRepository.forLeague` for names and manager ids. "Me" comes from
`managers.idsBySleeperUserId()`, the route `WeeklyReportService:213` and the analysis
scores block already use.

Measured: NBA 2026's `/matchups/1` returns `[]` today. So until Sleeper publishes
pairings (after the draft), the block says "pairings for week 1 aren't out yet". That's
correct, and it's what acceptance US3.2 expects.

`leg ≥ playoff_week_start` returns "the regular season is over", not a playoff pairing.
Future playoff pairings aren't stored, and a bracket isn't a fixture list.

*Alternative*: add `nextMatchup` to the weekly report payload. Rejected: that payload
is "the latest scored week", and coupling them makes one request fail for both blocks
(`useBlock` exists so they fail apart).

## R8. Page shape (decided; frontend)

- **Route** `/leagues/:id/schedule`, destination key `schedule`, group `thisWeek`,
  label "Schedule grid", `sports: ['nba']`, season-scoped href (`ctx.season`).
- **Grid** is a table: rows are teams, columns are weeks. Each cell prints the count,
  and its background tint encodes the same count. That's one encoding per mark, and
  the number is spelled out (memory: label the axis, spell out the number). The table
  scrolls horizontally inside its own container, and the page never scrolls sideways.
  The team column is sticky.
- **Default columns**: `leg` … last scheduled week. Weeks inside the league's playoff
  window get a header marker.
- **Sort**: "Next 1 / 2 / 3 / 4 weeks" (sum over `leg` … `leg+N−1`), and "Playoff
  weeks" (the US2 view, which also hides non-playoff columns). The 1–4 range is a UI
  choice, not a modelled quantity.
- **Freshness line**: "Schedule from Sleeper, fetched {relative time}. The NBA adds and
  moves games during the season." No mention of the Cup explanation (R1 is inferred).
- Design per memory: card/dark UI, tinted pills, and a layout that fits the content.
  This content is a grid, so it's a table, not cards.

## R9. Testing surface (reasoned)

- **Pure, no DB** (can't be skipped silently): `SportScheduleTest` (parse both shapes,
  `counts`), `ScheduleGridServiceTest` against a trimmed **real** 2025 fixture
  (keys `game_id, week, date, home.team, away.team, status` only). Its size is a guess,
  roughly 1,235 × ~110 bytes ≈ 130 KB, so measure it when it's written and trim to
  flat `home`/`away` strings if it's large. There's also a 2026 fixture. These assert SC-002/SC-003 on real data. `PlayoffWindowTest` covers 0/null/1/2
  round types.
- **DB ITs** (`SportScheduleRepositoryIT`: replace, empty-guard, and the transaction
  rolling back on failure). These skip silently when Postgres is down (memory), so
  check the skip count.
- **Preference-ordering test** (bug class 1) for the sort: a 4-game team must rank
  above a 3-game team for N = 1, and ties go by team code.
- **Web**: `ScheduleGrid.test.tsx`, a `destinations` test (NBA has `schedule`, NFL
  doesn't), and `LeagueHome.test.tsx` (NBA block from next-matchup, NFL projected line
  only when weeks agree).
