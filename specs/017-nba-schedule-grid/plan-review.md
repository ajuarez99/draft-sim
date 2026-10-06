# Adversarial plan review

This review read the plan documents cold on 2026-10-05. It checked each claim
against branch `017-nba-schedule-grid` @ `78d86ad` (plan commit; code identical to
`main` @ `fffce72`), against the local Postgres on 5433, and against the public
Sleeper API. It ran before any code was written.

Every item is labelled **measured** (run today, output quoted) or **read** (from
code, not executed).

## Claims confirmed against the code

| Claim | Where checked | Result |
| --- | --- | --- |
| The schedule is fetched once per sport-season refresh and discarded (R2) | `PlayerGameIngestService.doRefresh:151-154` (read) | ✅ `Schedule.parse(stats.schedule(code, season))`, used only in memory |
| That path is reached for NBA 2026 while it is `pre_draft` | `LeagueRefreshService.refreshChain:189-205`, `RefreshDecision.decide` (read); local DB `league_refresh` (measured) | ✅ league 210 (NBA 2026) `last_success_at 2026-10-05 20:22:49Z`, `loaded_complete f`; 2025/2024 are `loaded_complete t` and skipped. `lastStartedWeek` is 0 pre-season, so the week loop is empty but step 1 still runs |
| Single-flight per `sport:season`, shared by the admin route | `refreshSportSeason:132-140`, `ingest:120-126` (read) | ✅ |
| Completed seasons are never refreshed again; NBA 2025 only via the admin route | `RefreshDecision.decide:28` (read) | ✅ |
| The daily job doesn't refresh chains | `DailyRefreshService.runAll:75-84` (read) | ✅ only players/board/trending. The schedule refreshes on visits only (stale after 1 h) |
| An empty-week guard exists to copy | `:196-204` (read) | ✅ (plan cites `:196-201`) |
| `Schedule` owns `sideTeam`, `SETTLED`, and the status vocabulary | `:363-491` (read) | ✅ `SETTLED` is `complete, postponed, canceled` |
| `league.settings_json` is the Sleeper `settings` object, upserted every ingest | `LeagueRepository.upsert:34-69`; `playoffFormat:231-244` reads `settings_json->>'playoff_teams'` (read) | ✅ |
| No existing rule computes the last playoff week | grep for `playoff_round_type`, `playoffEnd`, `log2`, brackets, frontend included (read) | ✅ every existing reader only uses `playoff_week_start − 1` (ExpectedWins:301, Superlatives:238, Analysis:237, PlayoffOdds:112, history ingest:316). There's no second implementation to collide with |
| Future fixtures are stored up to `playoff_week_start − 1` | `LeagueHistoryIngestService.ingestRemainingFixtures:307-330` (read); local `league_matchup` (measured) | ✅ (Foot) Ball Knowers 2026 holds weeks 1–14 |
| NBA 2026 has no pairings yet | `GET /v1/league/1339351318115946496/matchups/1` (measured) | ✅ `[]`. Draft `pre_draft`, starts `2026-10-10T21:15:08Z` (measured) |
| "Me" = Sleeper user → manager id → roster | `WeeklyReportService:213-226`, `ManagerRepository.idsBySleeperUserId:56-63` (read) | ✅ |
| Both routes 404 for a non-member and for no header | `LeagueMembership.visibleLeague:164-168`, `canSee:144-152` (read) | ✅ anonymous → only with a valid admin token |
| A new GET route under `/api/**` works from the browser | `WebConfig:43-54` (read) | ✅ global mapping, `X-Sleeper-User` allowed. Prod also needs `Authorization: Bearer` (`ApiTokenFilter`), which `api.ts` already sends |
| `NoIngestHintsInMessagesTest` covers the new reasons | ROOTS = `api`, `engine`, `mock` (read) | ✅ for engine/api code. **Not** `store/`, see note N3 |
| `analysisOffered` gates NextOpponentBlock to football | `LeagueHome.tsx:59-61, 122, 164` (read) | ✅ |
| Highest migration is V26; V27 is free everywhere | every local branch, remote and worktree (measured) | ✅ no `V27+` in any ref or worktree directory |
| R1 numbers for 2026 | `/schedule/nba/regular/2026` (measured) | ✅ 1,200 rows / 1,200 ids, all `pre_game`, weeks 1–25, 80 per team × 30, week 1 = 5×2, 24×3, 1×4 (the 4-game team is **PHI**) |
| R1 numbers for 2025 and SC-003 | `/schedule/nba/regular/2025` (measured) | ✅ 1,235 rows / 1,235 ids. 1,231 complete, 3 postponed (wk 12 CHI–MIA, wk 14 MEM–DEN, MIL–DAL), 1 canceled (wk 17 STP–STR). Counted totals: 28 teams × 82, NYK and SAS 83, 30 teams, no STP/STR |
| `playoff_round_type` 0 = one week per round (SC-004) | league settings (measured) | ✅ and stronger than the plan says: **three** data points, not one. NBA 2025 19 + 3 − 1 = 21 = `last_scored_leg`. NBA 2024 22 + 3 − 1 = 24. (Foot) Ball Knowers 2025 15 + 3 − 1 = 17. That holds across `playoff_type` 1 (NBA) and 0 (NFL) |
| `leg` today | `/v1/league/{id}` (measured) | ✅ NBA 2026 `leg 1`, no `last_scored_leg`. NBA 2025 `leg 21`, `lsl 21`, `complete`. NFL (Foot) 2026 `leg 4`, `lsl 3`. `/state/nfl` `leg 4`. `/state/nba` `{"week":2,"leg":0,"season_type":"pre"}` |
| Design-doc amendment (plan step 9) | `claude/nba-schedule-grid-and-streaming.md:84-92` (read) | ✅ already present in `78d86ad`. Step 9 only has HANDOFF/roadmap left |

## Findings: the plan was wrong or incomplete

Ranked by severity.

**F1 (high). The plan never says which league-season the two new services read.
Following the house pattern would make both answer about 2025 until 2026's first
week is scored.**
Every "one season" service resolves the URL through `LeagueSeasonResolver.resolve`
(`WeeklyReportService.forWeek:187`, `SeasonSuperlativesService.forLeague:226`).
The resolver walks back to the newest season with stored weeks
(`LeagueSeasonResolver:57-69`). NBA 2026 has none (measured: no 2026 rows; the
javadoc at `:22-24` records exactly this case). A builder copying a neighbouring
service would get these results for `/leagues/1339351318115946496/...`:
- **grid**: the 2025 schedule, `currentWeek` 21, i.e. "season over";
- **next-matchup**: league 2025, `leg 21 ≥ playoff_week_start 19`, so "The regular
  season is over."

That's exactly the 2026-10-05 → first scored week window this feature exists for.
No test in the plan would catch it: the state table and the fixtures are all
single-season.
**Amendment**: in data-model and contracts, state that both services read the
league row named by the URL (`leagues.bySleeperId`, the same row `visibleLeague`
returned) and **never** the resolver. The response's `season` is that row's season.
Add a service test with a two-season chain whose newest season has no stored weeks.
It should assert that the newest season's `leg`/schedule is used.

**F2 (high). The contract can't express "season over", so the spec's edge case
can't be implemented, and a finished league shows four weeks it never plays.**
Spec Edge Cases: "Season over (`leg` past the last scheduled week, or league
`complete`): the grid shows no upcoming weeks". But C1 carries no league status,
and `leg` doesn't run past the schedule:
- NBA 2025 is `complete` at `leg 21`. Its schedule runs to week 25 (Apr 6–12;
  measured week ranges). The client would show weeks 21–25 as "from the current
  week" for a league that ended Mar 15.
- NBA 2026 in season: playoffs end week 22 (Mar 15–21), and the schedule runs to
  week 25 (to Apr 11). From week 20 on, the default columns include 3 weeks after
  the league's season, and "Next 4 weeks" sums them.

V4 runs against the 2025 league, so the first live check would show this.
**Amendment**: add `seasonOver: boolean` to C1. It's server-computed: league
`status` complete, or `currentWeek` > `playoff.endWeek` when that's known, or >
the last stored week. Add the TS mirror. Default columns run `currentWeek …
playoff.endWeek` when `endWeek` is known, otherwise to the last scheduled week. The
"Next N" sum never crosses `endWeek`. Weeks after the league's season are dropped
from the default view, with one line saying so. Test this on the 2025 fixture with
`leg 21`/`complete`.

**F3 (medium-high). "A storage failure fails the run" fails much more than the
grid.**
Writing at step 1 means a throw there (DB hiccup, a constraint violation) costs a
lot (read: `refreshSportSeason:135-138` rethrows; `refreshChain:203-213`):
- every per-game week of that sport-season is skipped (weekly report top
  performers, superlatives, spotlight, absences);
- the remaining chain targets are skipped;
- `trendingRefresh.refreshIfStale` is skipped. It sits *after* the loop and only
  survives a `weeksFailed` throw, not an exception from inside the loop;
- the run then retries every 10 min (`RETRY_AFTER_FAILURE`), indefinitely.

So a defect in the newest, least critical table stalls the oldest, most-used data
for every league of the sport. A PK collision would be permanent. `Schedule.parse`
collapses duplicate `game_id`s silently (`byId` is a `HashMap`, `:391`), but a
`games()` list built "in parse order" from the raw rows wouldn't. A duplicate id
would then violate `(sport, season, game_id)` on every run. (Measured: no
duplicates today in NBA 2025/2026 or NFL 2025/2026.)
**Amendment**:
- Catch storage failures inside `doRefresh` and log them.
- Report them in `Result` (e.g. `boolean scheduleStored` / `scheduleStoreFailed`),
  since the class is "counted and returned, not merely logged" (`:94-97`).
- Have `refreshChain` treat that flag like `weeksFailed`: throw after the season
  loop and after trending, so the season is recorded FAILED but per-game data still
  lands.
- Store after the week loop, not before it.
- Build `games()` from the deduplicated map (last wins, matching `byId`), or
  insert with `on conflict (sport, season, game_id) do update`.
- Test it: a stubbed repository that throws still yields stored per-game rows and
  a failed refresh.

**F4 (medium). "The existing PlayerGameIngestService tests must stay green,
unchanged" can't hold as written.**
- `PlayerGameWeekIngestTest:438, 450, 510-513` reference
  `PlayerGameIngestService.Schedule` (`.parse`, `.sideTeam`) directly. The lift
  breaks compilation. The plan's own list names `PlayerGameIngestServiceTest`, which
  only tests `toRow` and is the wrong file.
- `PlayerGameWeekIngestTest:119` constructs the service positionally. Injecting
  `SportScheduleRepository` changes that call.
- `LeagueRepository.PlayoffFormat` is a positional record built at
  `PlayoffOddsServiceTest:51, 116, 128`. Adding `playoffRoundType` breaks those
  three.

**Amendment**: restate build step 2's proof. The only edits allowed to existing
tests are `Schedule` → `SportSchedule` renames, one extra constructor argument
(a mock), and the `PlayoffFormat` arity. The diff of those files must show no
changed assertion. List the three files by name.

**F5 (medium). "Sleeper's other round types aren't measured in any league here"
is false.**
Measured in the local DB: *West Coast Fantasy Football* (NFL, id 9466, season 2025)
has `playoff_week_start 15`, `playoff_teams 6`, **`playoff_round_type 1`**, and
`last_scored_leg 18` = 15 + 3. That fits a two-week championship (one extra week).
Its 2026 season (9465) is also round type 1. Meanwhile round type 0 has three
confirmations (table above), not one.
**Amendment**: correct the R5/spec Assumptions text, recording the three round-type
0 data points and the one round-type 1 point. Then decide openly. Either:
- keep refusing round type ≠ 0 (now a choice, not "unmeasured"); or
- support 1 as `end = start + rounds`, labelled "one league measured".

The grid is NBA-only, so this only bites an NBA league with round type 1. Add a
`PlayoffWindowTest` row for (15, 6, 1) whichever way it goes.

**F6 (medium). Quickstart V6 doesn't test the assumption it says it tests.**
The spec assumption is "schedule `week` = the **league's** matchup `leg`". V6 joins
`player_game` to `sport_schedule` on `game_id`. That compares Sleeper's
stats-entry `week` with Sleeper's schedule `week`, both the NBA calendar's
numbering, and the league's leg isn't in the query. It would read
`mismatched = 0` even if league week 8 covered schedule week 9.
**Amendment**: compare league weeks to schedule weeks. For league 211 (NBA 2025),
for each week *w*, take every player with `players_points[pid] > 0` in
`roster_week_points` week *w*. Each should have ≥ 1 `player_game` row whose
`game_id` sits in `sport_schedule` week *w*. Report the mismatch count, and also
the reverse direction for a sample. Keep the existing V6 query as a separate
sanity check, labelled for what it actually shows.

**F7 (medium). NFL league home around the week boundary is unmeasured, and SC-005
samples only mid-week.**
The NFL "Next opponent" changes source:
- Today it comes from the analysis week: last stored week + 1, with stored weeks
  bounded by `last_scored_leg` (`LeagueAnalysisService:227-232`;
  `LeagueHistoryIngestService:248`).
- After the change it's `leg`.

Today (measured) they agree: `leg 4`, `lsl 3`. Between MNF and Sleeper's flip they
can split, and nobody here has seen which value moves first:
- If `last_scored_leg` becomes 4 while `leg` is still 4, the new block names the
  **finished** week-4 opponent and drops the projected line. Today's block shows
  week 5 with a projection.
- If `leg` moves first, the block shows week 5 with no projection until the
  stored weeks catch up.

Either is a visible regression window every NFL week. This is reasoned, not
measured.
**Amendment**: before build step 8, curl `/v1/league/1346366555759341568` on Tue
2026-10-06 and Wed 2026-10-07 (several times each). Record `leg`/`last_scored_leg`
in research R6. If `last_scored_leg` can lead, define the rule for that state
explicitly, e.g. "if week `leg` is final under `WeekFinality.isFinal(leg, lsl,
leg)`, the next matchup is `leg + 1`". Put that rule in `NextMatchupService` only,
and add a state-table row. Extend SC-005 to "before and after, on a mid-week day
and on the Tuesday".

**F8 (medium). The new page and block must refetch when the background refresh
finishes.**
On a first visit after deploy, `sport_schedule` is empty: the grid says "not loaded
yet", and the refresh this same visit started fills it seconds later. LeagueHome
refetches its blocks on `useLeagueDataVersion(sleeperLeagueId)`
(`LeagueHome.tsx:107-114`). The plan's `ScheduleGrid` page and the new
NextOpponent fetch don't mention it. Without it, the reader sees "not loaded yet"
until a manual reload. The same goes for `leg` moving on Monday.
**Amendment**: in R8 and step 6, `ScheduleGrid` reads `useLeagueDataVersion` and
keys its `useBlock` on `[id, version]`, and the LeagueHome next-matchup block adds
`version` to its deps. Add a Vitest that bumps the version and expects a refetch.

**F9 (medium-low). The NBA next-opponent block would link to a page NBA doesn't
have.**
`NextOpponentBlock` always renders `<Link to={/leagues/${leagueId}/analysis}>Team
strength</Link>` (`LeagueHome.tsx:431-433`). Analysis is `sports: ['nfl']`
(`destinations.ts:183`).
**Amendment**: render that link only when `analysisOffered(sport)`. For basketball,
link to the new `schedule` destination, through `destinationsFor` so the sport gate
isn't restated.

**F10 (medium-low). The bye/non-member rules and the name sources are
underspecified for the build.**
- `LeagueMatchupRepository.between` filters `matchup_id is not null`
  (`:63-74`). A bye roster has **no row** at all, not a lone row. The state table's
  "caller on a bye (no partner)" needs the actual rule: the caller has a roster,
  fixtures exist for `leg`, and either no fixture row carries his roster or no
  other roster shares his `matchup_id`.
- Team names don't come from `RosterSeasonRepository.forLeague`. Its `StandingRow`
  carries `managerName` (display name) and `avatarId`. The team name is in
  `league_member`, via `LeagueMemberRepository.forLeague`, as both
  `WeeklyReportService:215-224` and `LeagueAnalysisService.teamLabels` do. Add that
  dependency.
- The contract sends `teamName`/`username` raw and nullable, so the fallback
  (team name → username → "roster N") stays client-side, as today
  (`LeagueHome.tsx:409-410`). Say so, so it doesn't become a third server copy.
- A manager with two rosters in one league: `myRosters` is a set in
  WeeklyReport. Pick a rule, such as lowest roster id, and note it.

**F11 (medium-low). Several quickstart steps won't run as written.**
- **V2**: `POST /api/leagues/{id}/refresh` needs `X-Sleeper-User`, or it's a 404
  (`RefreshController:104-110`). It only starts a run if the last success is
  > 1 h old (local NBA 2026: `20:22:49Z` today). It's asynchronous, so poll `GET
  …/refresh` until `state` isn't `RUNNING` before running the SQL.
- **V4**: the admin route fails closed when `ADMIN_TOKEN` is blank
  (`application.yml:35-38`), and `.claude/launch.json`'s `bootRun` doesn't set it.
  State that the server must start with `ADMIN_TOKEN` set.
- **V7**: the NBA "paired" path can only be checked after the draft
  (2026-10-10 21:15 UTC, measured). Add a dated check for 10-11 to 10-19: after a
  visit refresh, `league_matchup` holds week 1 for league 210, and next-matchup
  names the same partner as `/v1/league/1339351318115946496/matchups/1`.
- **V8.2**: name the expected first row, **PHI** (the only 4-game team in week 1,
  measured).

## Lower-severity notes

- **N1. Fixture location and size.** Existing Sleeper fixtures live in
  `src/test/resources/sleeper/` (`nba-2025-w10.json`, read by
  `PlayerGameWeekIngestTest`); `fixtures/` also exists. Pick `sleeper/` for
  consistency. Measured sizes trimmed to the six keys: 2025 is **157,661 B**
  nested and 135,431 B flat; 2026 is 153,207 B. (The raw 2025 payload is 1.05 MB
  because of `on_court`/`scoring`/`starters`.) **Keep the nested shape.** Flattening
  the NBA fixture would stop testing `sideTeam`'s `Map` branch on real data, which
  is the one sport-dependent part of the parser.
- **N2. Bind types (class #3).** Say how `fetched_at` and `game_date` bind. The repo
  has two conventions: `OffsetDateTime`/`LocalDate` via `setObject`
  (`SportTrendingRepository:18-19, 55`) and `Timestamp.from` (`SportWeekStatsRepository:49`).
  Follow the trending one (the newer, lesson-annotated one) and use `@Transactional` on
  `replaceSeason`, as `SportTrendingRepository.replace:41` does. The IT should read
  back a row's `fetched_at` and `game_date`, not just count rows.
- **N3. Reason strings in `store/` aren't scanned.** If the playoff window's
  reason text lives on `LeagueRepository.PlayoffFormat`, `NoIngestHintsInMessagesTest`
  never sees it. Return a code from `store/` and write the sentence in `engine/`.
- **N4. Frontend plumbing the plan omits.** `DestinationKey` is a closed union
  (`destinations.ts:40-42`). Add `'schedule'`, a `match` regex and
  `idKind: 'league'`. `destinations.test.ts` fails if `App.tsx` gets the route
  without the row, which is good.
- **N5. Index vs week number.** `teams[].games[i]` aligns with `weeks[i]`, and
  weeks are contiguous 1–25 today (measured). The client should still look weeks up
  by `week`, not by `currentWeek − 1` offsets, so a gap can't shift columns.
- **N6. A playoff window past the schedule.** A league with, say, `playoff_week_start`
  24 and 6 teams ends at week 26, but the NBA schedule has 25 weeks. The playoff view
  must show the missing week as "not in Sleeper's schedule", not quietly sum two
  weeks.
- **N7. Exhibition rows were observed, not hypothetical.** R4 calls option (b) "a
  case not observed". The 2025 All-Star game was in the schedule (as `canceled`).
  The 2026 one isn't there yet (measured: 1,200 rows, 30 codes). If it arrives as
  `pre_game`, two phantom "teams" sit in the grid for months. Showing them is
  defensible. Marking any code that isn't a stored NBA player's team as "not an NBA
  team" costs one read and stays honest.
- **N8. The current-week column includes games already played.** Mid-week, week
  `leg`'s count includes completed games. Label it ("games this week, including
  played"), since readers will take it as games left.
- **N9. Weeks 7–8 will change.** Measured: week 7 ends 2026-12-03 and week 8 starts
  12-12. That gap is where the inferred two missing games per team (80 → 82) would
  land. The freshness line covers it generally. Optionally show `seasonTotal` so a
  reader can see 80.
- **N10. Season-scoped href.** From a 2025 season page, the rail's Schedule link
  (with `ctx.season`) opens the 2025 grid, which reads as season over with F2.
  That's consistent with spec 011's one-season rule, so keep it, but don't call it a
  bug in live verification.
- **N11. R5's text contradicts itself.** It says "reading `playoff_round_type` in
  the same query (`coalesce(...,0)`)" and then decides not to coalesce. Delete the
  first phrase so a builder doesn't follow it.
- **N12. Pre-existing, noted.** `ingestRemainingFixtures` skips any week already
  paired (`:315`). If a commissioner regenerates the schedule before week 1, the
  stored pairings stay stale until the week is scored. That's out of scope, but it
  can make US3 disagree with Sleeper. Mention it in the V7 post-draft check.
