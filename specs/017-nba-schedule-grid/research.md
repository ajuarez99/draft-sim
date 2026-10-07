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

**Decision**: write the parsed schedule in that same run. No new Sleeper call and no
new scheduler step.

> **Amended after review (F3, 2026-10-05):** the first version wrote the schedule
> *before* the week loop and let a storage failure throw. That would have skipped
> every per-game week of the sport-season, the rest of the chain and the trending
> refresh, retrying every 10 minutes. And since `Schedule.parse` de-duplicates
> `game_id` silently while a raw list wouldn't, one duplicate id would have made the
> failure permanent. Now:
> - store **after** the week loop;
> - catch and log a storage failure, and return it as `Result.scheduleStoreFailed`;
> - `refreshChain` throws for it after the season loop and after trending, the same
>   way it handles `weeksFailed`, so the season reads FAILED but per-game data lands;
> - rows come from the parser's de-duplicated map (last wins, matching `byId`), and
>   the insert is `on conflict (sport, season, game_id) do update`.
>
> The bullet "A storage failure fails the run" below now means "fails the run *after*
> everything else has run".

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
on relocation/rebrand. (b) adds a join for a case not observed as un-canceled.

> **Amended after review (N7):** the original said "a case not observed". An
> exhibition row *was* observed (2025's All-Star game), just canceled. The 2026 one
> isn't in the schedule yet (1,200 rows, 30 codes, measured). Decision unchanged,
> because a live row would show up visibly, not silently. If it ever arrives as
> `pre_game`, revisit (b). If an un-canceled
exhibition ever appears, it shows up as a row with 1 game, visible and honest, and the
service test asserting 30 rows on the 2025 fixture will catch it. Not a silent filter.

## R5. Playoff weeks (measured once, rest declined)

There's no existing rule in the codebase for the last playoff week (grep for
`playoff_round_type`, `playoffEnd`: none). Measured for NBA 2025: `playoff_week_start`
19, `playoff_teams` 6, `playoff_round_type` 0, `last_scored_leg` 21 = 19 + ⌈log₂ 6⌉ − 1.
NBA 2026 has the same format with start 20, so weeks 20–22.

**Decision**: `end = start + ⌈log₂(teams)⌉ − 1` **only when `playoff_round_type` = 0**.
Any other value, or `start < 2`, or `teams < 2`, returns `end = null` with a reason.

> **Amended after review (F5):** the original said Sleeper's other round types "aren't
> measured in any league here". **False.** The review measured *West Coast Fantasy
> Football* (NFL, local ids 9466/9465) at `playoff_round_type` 1. Its 2025 season ran
> 15 → `last_scored_leg` 18 = 15 + 3, which fits a two-week championship. Round type
> 0 also has **three** confirmations, not one: NBA 2025 (19→21), NBA 2024 (22→24) and
> (Foot) Ball Knowers 2025 (15→17). **Decision, now a choice rather than a gap:**
> still refuse round type ≠ 0. The grid is basketball-only, no NBA league here uses
> type 1, and one NFL league is too little to encode a rule. A `PlayoffWindowTest` row
> pins (15, 6, 1) → refused.

Put it on `LeagueRepository.PlayoffFormat` beside `playoffWeekStart`, reading
`playoff_round_type` in the same query, **uncoalesced** (see the warning below;
amended after review N11, which caught the earlier text contradicting it).
`store/` returns a refusal *code* (`NO_START`, `TOO_FEW_TEAMS`, `ROUND_TYPE_UNKNOWN`,
`ROUND_TYPE_UNSUPPORTED`). The sentence is written in `engine/`, where
`NoIngestHintsInMessagesTest` scans it (N3).

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

> **Amended after review (F1):** "the league" means the row the URL names
> (`leagues.bySleeperId`, the one `visibleLeague` returned), **never**
> `LeagueSeasonResolver`. The resolver walks back to the newest season with scored
> weeks. For NBA 2026 that's 2025, which would read `leg` 21 and "season over" for
> this feature's whole launch window. Both services carry a two-season-chain test.
>
> **Amended after review (F2):** for a finished league, `leg` stays at its last week
> (NBA 2025: 21), while the NBA schedule runs to 25. So `leg` alone can't say "season
> over". The grid response adds `lastLeagueWeek` (= `lastPlayoffWeek` when known,
> else `playoff_week_start − 1` when ≥ 1, else the last scheduled week) and
> `seasonOver` (league `complete`, or `leg` > `lastLeagueWeek`). Default columns and
> the Next-N sum stop at `lastLeagueWeek`.
>
> **Open after review (F7):** football's `leg` vs the analysis week hasn't been
> observed across the Tue/Wed boundary (after MNF). If `last_scored_leg` can reach
> `leg` before `leg` advances, "next opponent" would name the finished week. **Before
> NFL league home switches to this endpoint**, curl
> `/v1/league/1346366555759341568` several times on 2026-10-06 and 10-07, record the
> pairs here, and define the rule for whichever order is seen. Until then NFL home
> keeps its current block. This doesn't block basketball: NBA 2026 has no scored
> week, and its week-boundary behaviour is checked in V7 after the draft.
>
> **T004 readings (amended 2026-10-07): the window was missed, so no rule yet.** Nobody
> took the 10-06 (Tuesday) readings, which were the ones that mattered.
>
> | UTC | `leg` | `last_scored_leg` |
> |---|---|---|
> | 2026-10-07T14:49:12Z (Wed) | 5 | 4 |
> | 2026-10-07T14:54:05Z (Wed) | 5 | 4 |
>
> These are after the boundary, the same `leg N, lsl N−1` order spec 009 saw mid-week
> (`leg 4, lsl 3`). They don't say what happens between MNF ending and `leg` moving.
> Redo on **Tue 2026-10-13**, several readings from MNF's end until `leg` reads 7.
> T042–T044 stay gated.

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

> **Amended after review (F10):** team names aren't on `RosterSeasonRepository`'s
> `StandingRow` (it has `managerName`, `avatarId`, `managerId`). They come from
> `LeagueMemberRepository.forLeague`, as in `WeeklyReportService:215-224` and
> `LeagueAnalysisService.teamLabels`. `LeagueMatchupRepository.between` drops
> null-`matchup_id` rows, so a bye roster has **no row at all**. The bye rule is in
> data-model. The name fallback (team name → username → "roster N") stays client-side
> as today, so the server doesn't grow a third copy.

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
- *Amended after review:*
  - Columns are looked up by `week` number, never by index offsets (N5).
  - The current-week column is labelled "including games played" (N8).
  - A playoff week missing from the schedule shows as "not in Sleeper's schedule",
    not as 0 (N6).
  - The page keys its fetch on `useLeagueDataVersion`, so the visit's own refresh
    fills an empty grid without a reload (F8).
  - From a past season, the rail opens that season's grid, which reads "season over".
    That's spec 011's one-season rule, not a bug (N10).
- **Freshness line**: "Schedule from Sleeper, fetched {relative time}. The NBA adds and
  moves games during the season." No mention of the Cup explanation (R1 is inferred).
- Design per memory: card/dark UI, tinted pills, and a layout that fits the content.
  This content is a grid, so it's a table, not cards.

## R9. Testing surface (reasoned)

- **Pure, no DB** (can't be skipped silently): `SportScheduleTest` (parse both shapes,
  `counts`), `ScheduleGridServiceTest` against a trimmed **real** 2025 fixture
  (keys `game_id, week, date, home.team, away.team, status` only), and a 2026 fixture.
  *Amended after review (N1):* measured at 157,661 B (2025) and 153,207 B (2026).
  They go in `src/test/resources/sleeper/` beside `nba-2025-w10.json`. **Keep the nested
  `home`/`away` shape**, because flattening would stop testing `sideTeam`'s `Map`
  branch on real data. The earlier "~130 KB, flatten if large" guess is withdrawn. These assert SC-002/SC-003 on real data. `PlayoffWindowTest` covers 0/null/1/2
  round types.
- **DB ITs** (`SportScheduleRepositoryIT`: replace, empty-guard, and the transaction
  rolling back on failure). *Amended after review (N2):* binds use
  `OffsetDateTime`/`LocalDate` via `setObject`, following `SportTrendingRepository`, and
  `replaceSeason` is `@Transactional`. The IT reads back a row's `fetched_at` and
  `game_date`, not just a count. That's bug class #3, which only shows at runtime.
- **Existing tests (F4):** `PlayerGameWeekIngestTest` and `PlayoffOddsServiceTest` get
  mechanical edits only, with no assertion diffs.
- **Two-season chain (F1)** for both services. **Season over (F2)** on the 2025
  fixture with `leg` 21 and `complete`. **Store failure (F3):** a throwing repository
  still yields per-game rows and a FAILED refresh. These skip silently when Postgres is down (memory), so
  check the skip count.
- **Preference-ordering test** (bug class 1) for the sort: a 4-game team must rank
  above a 3-game team for N = 1, and ties go by team code.
- **Web**: `ScheduleGrid.test.tsx`, a `destinations` test (NBA has `schedule`, NFL
  doesn't), and `LeagueHome.test.tsx` (NBA block from next-matchup, NFL projected line
  only when weeks agree).
