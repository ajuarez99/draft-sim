# Plan review: spec 030 (adversarial, before any code)

**Reviewed**: 2026-10-11, cold read of spec.md, plan.md, research.md, data-model.md,
contracts/api.md, quickstart.md and tasks.md against the code at `943b303` in this worktree.

**How each claim is known.** *Verified* means I read the code or ran the call this session.
*Inferred* means I reasoned it from what I read and didn't execute it. Live calls were made
to `api.sleeper.app` on 2026-10-11. The local Postgres wasn't queried (`psql` hung), so
nothing here was checked against stored rows.

Findings are ranked **Blocker** (the plan as written ships a wrong or misleading result),
**Should-fix** (a real defect or contradiction with a cheap amendment), and **Nit**.

---

## Blockers

### B1. IR-stashed players get projected as full-time starters
- **Verified (live)**: on "Ball Knowers" NBA 2026 (`1339351318115946496`), 11 of 12 rosters
  hold 1–2 players in `reserve`. All 19 reserve ids are also in `roster.players`, which is
  the list `LineupPass.over` iterates (`LeagueAnalysisService.java:457`). All 19 also have a
  per-game projection line, one of them at 29.71 pts/game (id 1380) and another at 23.66
  (id 1752).
- **Verified (code)**: nothing in `LineupPass` or `BasketballRules.startingLineup`
  (`BasketballRules.java:375`) excludes reserve players. R1 also measured that the source
  carries no games-played count (`gp` = 1.0).
- **Consequence (inferred)**: a manager who stashed an injured star on IR gets him in the
  best lineup at full per-game value every week. That can decide the ranking and the
  favorite headline. The football path has the same structure, but weekly projections
  zero out injured weeks. A per-game line can't. Spec Story 5's table says injuries come in
  "through the projection source's own games-played assumptions", but R1 shows the payload
  has none. That claim is false for this source.
- **Amendment**: in T021, read `roster.reserve` and keep reserve players out of the
  starter pool for both sports. Value them as bench at 0 with a new `reserve` count per
  roster, or at least for NBA. Add a test where an IR'd top scorer isn't started. Correct
  the Story 5 injuries row and add a line to the strength footer: "injuries and missed games
  aren't modelled; players on IR aren't counted". Mirror the new field in `api.ts`.

### B2. `SeasonState` flips to `IN_SEASON` on a partial, non-final week, and the favorite vanishes with nothing in its place
- **Verified (code)**:
  - T011 keys the state on `RosterWeekPointsRepository.storedWeeks` (`:82-88`), which
    counts any stored row.
  - For NBA, `last_scored_leg` tracks the week being played (`WeekFinality.java:7-12, :24-31`).
    `LeagueHistoryIngestService.ingestWeeklyPoints` stores every week up to
    `last_scored_leg` (`:248-302`), whatever its points are, so week 1 gets stored partial
    on its first night. If Sleeper sets `last_scored_leg` to 1 before a game finishes, the
    rows are stored as zeros.
  - Draft grades (`DraftGradesService.java:180-185`) and playoff odds
    (`PlayoffOddsService.java:170`) both use `ScoredWeeks.of(...).finalWeeks()` /
    `latestFinal()`, not `storedWeeks`.
- **Consequence (inferred)**: during the whole of NBA week 1 the home is `IN_SEASON`. The
  strength block and favorite are removed (T034), the grades summary answers
  `NO_SCORED_WEEKS`, and no forecast snapshot exists yet. So the home answers "who's the
  favorite" with nothing for about a week. That contradicts Story 3 AC2: the favorite
  should step aside only "when the existing in-season forecast becomes available".
- It also adds a fourth "is it scored" rule beside `LeagueSeasonResolver.PLAYED_WEEKS`,
  `ScoredWeeks` and `LeagueAnalysisService:227`, while FR-001 asks for one.
- **Not measured**: whether NBA `last_scored_leg` turns 1 before any week-1 game is final.
- **Amendment**:
  - Build `SeasonStateService` on `ScoredWeeks.Snapshot`.
  - Either define `DRAFTED_UNSCORED` as `latestFinal == 0`, or keep `latestStored` for the
    layout and add `firstWeekFinal: boolean` to C1. In the second case the strength block
    stays, labelled "preseason projection, before week 1 is final", until `latestFinal >= 1`
    or a playoff-odds snapshot exists.
  - Make T012 cover "a stored, non-final week 1".
  - V8 must record which signal flipped, and when.

---

## Should-fix

### S1. The home would fetch `/analysis` for NBA and render two "Next opponent" blocks
- **Verified (code)**: `LeagueHome.tsx:127` fetches analysis whenever `analysisOffered(sport)`,
  and `:172` renders `NextOpponentBlock` whenever that block isn't idle. Once T025 adds
  `nba` to `destinations.ts:250`, the NBA home renders `NextOpponentBlock` **and**
  `NextMatchupBlock` (`:173`). Both use the heading id `lh-next-h`.
- T025 says to *keep* the NBA fetch "since T029 uses it", but T029 reads `season-start`, not
  `/analysis`. As written, every NBA home load runs two full strength passes (two Sleeper
  roster calls), and the `IN_SEASON` NBA home changes, which breaks SC-005.
- `NextMatchupBlock`'s link (`:487-491`) also switches from the schedule grid to "Team
  strength" for NBA, in every state.
- **Amendment**: gate `:127` on `sport === 'nfl'` explicitly, not on `analysisOffered`.
  Decide and state whether the NBA next-opponent link changes. Add a home test that asserts
  exactly one `lh-next-h` for NBA.

### S2. `season-start` computes strength in `IN_SEASON` and then throws it away
T028 computes strength in `DRAFTED_UNSCORED` **or** `IN_SEASON`, but T034 never renders it
in `IN_SEASON`. The live NFL league is `IN_SEASON`, so every NFL home load would add a
second lineup pass and Sleeper roster fetch on top of `/analysis`. The multi-user audit
memory shows this app has been bitten by per-load fan-out before. **Amendment**: compute
`strength` only in `DRAFTED_UNSCORED` (plus B2's "until final" window), and return
`strength: null` otherwise.

### S3. Reason codes in a field that today holds sentences
- **Verified (code)**: `Projections.reason` and `Matchups.reason` carry English sentences
  (`LeagueAnalysisService.java:433-453`, `:265`). The web renders them raw through `NotYet`
  at five sites in `LeagueAnalysis.tsx` (`:214, :390, :530, :643, :1335`) and at
  `LeagueHome.tsx:415`.
- C1/C2 and T021/T022 put `PROJECTIONS_NOT_LOADED` and `ROSTERS_EMPTY` into the same
  field. T021 even says "keep the existing message style; the client maps the code", which
  contradicts itself.
- `ROSTERS_EMPTY` applies to NFL too, so next August the NFL home would print the bare code.
- **Amendment**: leave `reason` as a sentence and add a nullable `reasonCode` to
  `Projections` (and `Matchups` if it can carry one). Mirror it in `api.ts`. The client maps
  codes where it wants richer copy. List every render site in T026.

### S4. The "unprojected categories" rule is already wrong on live data
- **Verified (live)**: today's payload has `ff` on exactly 1 of 529 stat lines (0.05). R2
  says six scored keys "never appear"; today it's five.
- Under T021's rule ("scored keys that appear in **no** stored line"), one row is enough to
  drop `ff` from the footer, even though it's an implicit zero for 528 players. The rule
  flips on a single row, and T021's expected list of six would fail against live data.
- **Amendment**: report coverage instead. For each scored key, give the number of
  projected players whose line carries it. A key is "unprojected" when coverage is below
  a stated cut, e.g. under 50%, labelled arbitrary in `weights.yml`. Or show
  "flagrant fouls: projected for 1 of 529". Correct R2 visibly ("amended after review").

### S5. `gamesThisWeek` has no defined metric, and the method T036 says to call doesn't exist
- **Verified (code)**: `ScheduleGridService.Result` (`:67-69`) gives games per **NBA team**
  per week (`Team(team, int[] games, …)`). There's no per-roster method to "call, not
  recount".
- C1's example `"mine": 4` reads as a roster playing 4 games. A summed count over 14–16
  players would be about 45. Either number invites the "more games = more points" reading
  that the one-game-per-week memory refutes.
- **Amendment**: define the metric explicitly. The one that matters under one-credited-game
  is **"starters with no game in week N"** (or "rostered players with at least one game"),
  computed from the grid's team rows plus each player's team. Make it a pure helper in
  `ScheduleGridService`, with a test. Fix the C1 example.

### S6. The plan's own state table contradicts FR-004 and SC-005
FR-004 and SC-005 say the scored-week home is "identical to today's (same blocks, same
values)". C5 and T039 add a draft-grades block to `IN_SEASON`, which is the live NFL league
right now, so the NFL home changes on deploy. **Amendment**: amend FR-004/SC-005 visibly to
"identical except the draft-grades summary". Keep T039's test, which already does this.

### S7. The NBA Analysis page will say false things
- **Verified (code)**: in `LeagueAnalysis.tsx`, these strings and controls would apply to an
  NBA league:
  - `:1213-1215`: "weeks 1–19 · standard". `scoringKey` is `STANDARD` for NBA because
    `ppr` = `scoring_json.rec` → 0 (`LeagueRepository.java:146`, `ScoringKey.forReceptionPoints`).
  - `:1221-1222`: "rest-of-season points".
  - `:1242`: "the bye weeks are the drops".
  - `:532-534`: the bump block prints "One projected week is a point, not a line." when
    `byWeek` is empty. That's a claim, not hiding. T026 only hides `WeekStrip`.
  - `:1271-1290`: a week picker 1–19 whose every week returns identical NBA numbers (T021
    feeds one per-game map).
  - `:1303`: "a bye or a one-week injury moves who starts".
  - `:1338`: "rest-of-season window".
- **Amendment**: T026 names each of these. For `unit === 'PER_WEEK'`:
  - Hide the whole "Projected week by week" section and the week picker.
  - Replace the scoring label with "league scoring".
  - Rewrite the matchup note to "the same per-game projection every week".
  - For the Analysis payload, either set `scoringKey` to null for NBA or have the client
    ignore it by `unit`.

### S8. T023's ordering test has no seam to run through
- **Verified (code)**: `projectionBlocks` and `LineupPass` are private (`:426`, `:670`).
  `LineupPass` calls `sleeper.rosters`, `players.findAll`, `managers` and `rosterSeasons`
  in its constructor. The existing `LeagueAnalysisServiceTest` only tests statics, and
  builds the service with nulls (`:332`).
- **Amendment**: add a task before T023 that extracts a package-private static core, e.g.
  `rankRosters(List<rosterPlayers>, Map<String, Player>, Map<String, Double> points,
  SportRules, LeagueSettings, groups)`. Test SC-004 through it with real `BasketballRules`.
  Update the 11-argument `service(...)` helper for the new constructor dependencies
  (`PlayerSeasonProjectionRepository`, `GameScoringService`).

### S9. `players: null` from Sleeper will NPE before the `ROSTERS_EMPTY` guard runs
`roster.getOrDefault("players", List.of())` (`:457`) returns `null` when the key is
present with a JSON null, and the `for` loop then throws. That's the shape of a pre-draft
roster (inferred from Sleeper's usual payload, not measured today). **Amendment**: in T022,
treat null as empty, and add a test fixture with `"players": null`.

### S10. `nearTiePct` can silently bind to 0, and the health check can't see it
- **Verified (code)**: `weightsLoaded` is only `scoring.football() != null`
  (`HealthController.java:50`). T031's "check weightsLoaded still true" proves nothing
  about `draftsim.preseason`. A missing or misspelled key binds a primitive to 0.0, and
  then only exact ties count as ties.
- **Amendment**: make `PreseasonProperties` `@Validated` with `@Positive`, or fail at
  startup. Add a test that binds the real `config/weights.yml` and asserts 1.0. Register it
  in `DraftSimApplication`'s `@EnableConfigurationProperties` list next to
  `DraftGradeProperties` (`DraftSimApplication.java:23`).

### S11. Honesty: a 1% tie line under an uncertainty nobody has sized
The headline "Projected strongest roster: X, by 6.2 pts/week (2.5%)" reads as a real gap.
The inputs carry several errors larger than 1%:
- R3: the per-week figure runs about 15% low.
- S4: bonuses aren't projected.
- B1: IR players.
- The projections' own accuracy is unmeasured.

A 1% threshold labelled ARBITRARY is honest about the threshold, but not about the margin.

**Amendment**:
- Show 1st's and 2nd's projected values next to the margin, so it reads as two estimates.
- Add a line to the headline's footer: "a margin this size is within what these
  projections can't resolve". Better still, don't name a single favorite unless the gap
  beats a stated (arbitrary) cut that's bigger than 1%.
- Whichever cut is chosen, keep it labelled a guess.
- E5's denominators also disagree. T032 measures "within N% of rank 1" against rank 1,
  while E5's near-tie test uses `marginPct` over the next team. Pick one.

### S12. V6/T003/T042 parity can't isolate the change as written
- `/analysis` reads Sleeper rosters live on every request (`LeagueAnalysisService.java:432`)
  and projections are re-ingested, so a "before" capture taken on `main` and an "after"
  capture later will differ for reasons that have nothing to do with this change.
- **Amendment**:
  - Capture both back to back against the same DB, with no ingest in between and the same
    `X-Sleeper-User`.
  - Diff with `jq 'del(.projections.unit, .projections.basisNote, .projections.provenance,
    .projections.unprojectedCategories)'`.
  - Also capture `/season-start`'s NFL state and the NFL home's block list. SC-005 is a
    statement about the home, not just about `/analysis`.

---

## Nits

- **N1. Wrong type name.** T020 says to mirror into `api.ts`'s `Projections` type. It's
  `AnalysisProjections` (`api.ts:1507`). *Verified.*
- **N2. Draft id ambiguity.** C1's `draft.draftId` must be the **Sleeper** draft id: the
  routes are `/drafts/:draftId/board` (`App.tsx:108`) and the home links
  `draft.sleeperDraftId` (`LeagueHome.tsx:227`). Name it `sleeperDraftId`. The home already
  has it from `cachedDrafts()`, so E6 may be redundant. *Verified.*
- **N3. Loading flash.** T018 says to "delete the local `preSeason` derivation" and also to
  "fall back to today's layout while loading or on error". The fallback *is* that
  derivation. While `season-start` loads, last season's matchup, power and awards would
  flash and then disappear. Instead, hide the four FR-003 blocks until `season-start`
  resolves, and fall back to today's layout only on error. Keep `preSeason` only as that
  error fallback, and say so.
- **N4. Date parsing.** `startDate` is a date-only string. `new Date('2026-10-20')` parses
  as UTC midnight and renders as Oct 19 in US time zones. Format it without a UTC parse
  (split the string, or `T00:00` local).
- **N5. Two date sources.** R5 says to reuse spec 017's week-to-date mapping, but T014 takes
  `season_start_date` from `/v1/state`. `ScheduleGridService.Week(week, firstDate,
  lastDate)` already exists, so use week 1's `firstDate`, falling back to the state value
  only when the schedule isn't loaded. `leagues.currentLeg(id)` (`LeagueRepository.java:282`)
  is the existing `leg` read. *Verified.* R5 also mentions `previous_season` matching, which
  is 2025 live. Only `league_season` should be compared.
- **N6. Missing draft row.** E2's "no draft row counts as drafted" could be decided by
  `LeagueRow.status` (`pre_draft`/`drafting`) instead of guessing. That field is already on
  the row (`LeagueRepository.java:104-106`), and `complete()` is `status == complete`
  (`:123`). *Verified.*
- **N7. Outcome names.** C4's `OK | SKIPPED_FRESH | FAILED` aren't `DailyRefreshService`'s
  outcomes (`DONE`, `SKIPPED_ALREADY_TODAY`, `FAILED`, …, `:45-49`). A `FAILED` step makes
  `/refresh/daily` answer 500 (`RefreshController.java:66`). Since the source is an
  undocumented endpoint, consider best-effort semantics like trending
  (`DONE_*_FAILED_BEST_EFFORT`), and say which in C4.
- **N8. Scoring lookup.** The league's scoring isn't on `LeagueRow`. T021 should name
  `leagues.scoringOf(league.id())` (`LeagueRepository.java:89`), which drops non-numeric
  values on purpose. *Verified.*
- **N9. Hard-coded slots.** `BasketballRules` hard-codes the 9 slots (`:63`) and ignores
  `roster_positions`. Both live NBA leagues match it (*verified live*), so FR-005's "league's
  own roster slots" holds by coincidence. Add a guard (unavailable with a reason) when an
  NBA league's starting slots differ from `PG,SG,G,SF,PF,F,C,UTIL,UTIL`.
- **N10. Position labels.** `byPosition`/`rankByPosition` for NBA are by the starter's
  **primary** position (`BoardEntry.position()` = `player.primary()`), not by "lineup
  position" as FR-006 says. Reword FR-006 or the column header. Bench players with no
  position get labelled `WR` (`Player.java:19`, used at `LeagueAnalysisService.java:497`).
  That's harmless but visible in the NBA bench list. *Verified.*
- **N11. Null lists.** C1's `top` rows "with starters and bench omitted (null or empty)":
  use empty lists. `api.ts` types them as arrays, so null would be a silent type lie.
- **N12. Stale-source wording.** Plan design note 6 says showing `fetchedAt` stops an old
  set from reading as current. It doesn't, because the numbers still render. Add an age
  cut: past N days, the block says the projections are N days old.

---

## Claims I checked and found correct

These were verified by reading the code or making the call.

- **R4 holds.** `projectionBlocks` (`LeagueAnalysisService.java:426-468`) has its NBA gate
  at `:446`. It ranks through `Ranker` (`rankAll`, `:530-552`) and builds lineups through
  `SportRules.startingLineup` with a value function.
- **The NBA lineup works for these leagues.** `BasketballRules.startingLineup` runs a
  value-sorted matroid greedy over slots `PG,SG,G,SF,PF,F,C,UTIL,UTIL` (`:63-90, :375-387`),
  so it's correct for a non-ADP value function. `Position.forSport(NBA)` gives
  `PG,SG,SF,PF,C`, so `byPosition` has five sensible groups.
- **NFL-only code is harmless for NBA's ranking.** `league.ppr()`/`ScoringKey` only select
  NFL projection columns. For NBA they produce a meaningless `STANDARD` label (S7) and break
  nothing else.
- **`byWeek` consumers tolerate an empty list.** They are `WeekStrip`
  (`LeagueAnalysis.tsx:313`, returns null), the bump block (`:532`, which prints a sentence:
  S7) and `api.ts:1495`. No backend consumer reads it apart from the record copies.
- **The draft lookup exists.** It's `DraftRepository.forLeague(leagueId)` (`:267`).
- **V31 is free on every local and remote ref**, checked with `git ls-tree` across
  `for-each-ref`.
- **The admin gate already covers the new route.** `/api/ingest/**` is admin-gated
  (`WebConfig.java:38`), so C3 needs no new registration.
- **Live data matches R1 and R9.** Sleeper NBA state on 2026-10-11:
  `season_start_date: 2026-10-20`, `league_season: 2026`. Both NBA leagues report
  `in_season`, `leg: 1`, `last_scored_leg: null`. The projections are 529 lines out of
  2,120 rows, `gp` ∈ {1.0, absent}, all from `rotowire`.
- **R7 holds.** `PlayerSpotlightService.forLeague` (`:171-176`) answers about the league id
  it's given. What it *renders* for a 2026 league with no games wasn't checked (inferred
  OK). V5 should look at it block by block.
