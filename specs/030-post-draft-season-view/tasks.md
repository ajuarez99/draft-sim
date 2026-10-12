---

description: "Task list for spec 030: the season after the draft, before the games"
---

# Tasks: The season after the draft, before the games

**Input**: Design documents from `specs/030-post-draft-season-view/`

**Prerequisites**: plan.md, spec.md, research.md (R1–R10), data-model.md (E1–E7), contracts/api.md (C1–C6), quickstart.md (V1–V8)

**Tests**: the spec explicitly requires some of them. SC-004 asks for a preference-ordering test (AGENTS.md bug class #1). FR-007a asks for a "no probability words" assertion. SC-005 asks for NFL JSON parity. FR-014 asks for missing-input states. Those tests are included. No other tests are added beyond what an edited file's existing suite needs to keep passing.

**Deadline**: Phases 1–5 have to be deployed before NBA week 1 is scored (about 2026-10-20 to 10-26). After that, `DRAFTED_UNSCORED` can't be live-verified until next season (quickstart V8 note).

**Rules for every task**:
- Java records changed here get mirrored field for field in `web/src/api.ts` in the **same** task (constitution rule 2).
- Nullable values in a response map: no `Map.of` (rule 4).
- Reasons go on the wire as codes, and the sentences live in the web client.
- Coding tasks run on Sonnet subagents (AGENTS.md). The parent session reads every diff.

## ⚠ Amended after plan review (2026-10-11): these override the task text below

[plan-review.md](plan-review.md) found defects in the tasks as first written. Where an item here
and a task's own text disagree, **this list wins**. The plan's "Amended after plan review" section
gives the reasons.

- **T011/T012 (B2, N6)**:
  - `SeasonStateService` is built on `ScoredWeeks.of(league.id())`, not `storedWeeks`.
    `DRAFTED_UNSCORED` means drafted and `latestFinal == 0`.
  - "Drafted" means `DraftRepository.forLeague(league.id())` holds a `complete` draft. If no
    draft row exists, it falls back to `LeagueRow.status` not in (`pre_draft`, `drafting`).
  - T012 adds "week 1 stored but not final gives `DRAFTED_UNSCORED`".
- **T013 (N7)**:
  - The step's outcomes are the real `DailyRefreshService.Outcome` values. A failure is
    best-effort: add `DONE_SEASON_PROJECTIONS_FAILED_BEST_EFFORT`, and never `FAILED`, so the
    daily refresh doesn't answer 500 because an undocumented endpoint wobbled.
  - Skipping because the data is fresh reports `DONE` with detail "fresh".
- **T014 (N5, N2, S2)**:
  - `firstWeek.startDate` comes from `ScheduleGridService`'s week `firstDate` for `leg`, falling
    back to `/v1/state` `season_start_date` only when its `league_season` equals the league's
    season.
  - `leg` comes from `leagues.currentLeg(id)`.
  - `draft` carries `sleeperDraftId`, not `draftId`.
  - `strength` is computed **only** in `DRAFTED_UNSCORED`.
- **T018 (N3, N4, B2)**:
  - While `season-start` is loading, hide the matchup, power and award blocks. Fall back to
    today's layout **only on error**, and keep the old `preSeason` derivation only as that
    error fallback, with a comment saying so.
  - In `DRAFTED_UNSCORED`, the matchup block renders only when its data is this season's
    (`block.data.season === seasonYear && requestedSeason == null`), which is the existing
    check at `LeagueHome.tsx:337`. Power and awards are dropped.
  - Format `startDate` by splitting `YYYY-MM-DD`, never with `new Date(str)`.
- **New T050 (S8), runs before T021**: extract a package-private static core from
  `LeagueAnalysisService` (`LineupPass.over`'s per-roster body), e.g.
  `static List<RosterProjection> rankRosters(List<RosterInput>, Map<String,Player>,
  Map<String,Double> points, SportRules, LeagueSettings, List<String> groups, String
  sleeperUserId)`, with `RosterInput(rosterId, ownerId, players, reserve)`. `LineupPass` calls
  it. NFL output must stay byte-identical. Update the test helper `service(...)` in
  `LeagueAnalysisServiceTest` for the new constructor args.
- **T020 (S3, N1, B1)**:
  - Add `String reasonCode` (nullable) to `Projections`. `reason` stays an English sentence.
  - Add `int reserve` to `RosterProjection`: the number of rostered players excluded as IR
    (always 0 on the NFL path).
  - Mirror both into `api.ts`'s **`AnalysisProjections`** / `RosterProjection` types.
- **T021 (B1, N8, N9, N10, S4)**:
  - NBA scoring comes from `leagues.scoringOf(league.id())`.
  - On the NBA path, `roster.reserve` players are excluded from the starter pool and listed
    in `bench` at 0 with `slot: "IR"`.
  - Add a guard: if the league's non-bench `roster_positions` aren't exactly
    `PG,SG,G,SF,PF,F,C,UTIL,UTIL` (the slots `BasketballRules` hard-codes), the result is
    unavailable with reasonCode `UNSUPPORTED_SLOTS` and a sentence.
  - `unprojectedCategories` = scored keys on **zero** stored lines (the live count is five,
    not six). Don't hard-code a list anywhere.
  - Reason codes go in `reasonCode`. `reason` gets a sentence.
- **T022 (S9)**: treat `players: null` and `reserve: null` as empty. Add a fixture.
- **T023**: runs through T050's static core with real `BasketballRules`. Add **an IR'd top
  scorer isn't started and is counted in `reserve`**.
- **T025 (S1)**:
  - `LeagueHome.tsx:127`'s analysis fetch is gated on `sport === 'nfl'` explicitly (not
    `analysisOffered`).
  - The NBA home must render exactly one `lh-next-h` heading. Add a test for it.
  - `NextMatchupBlock`'s link target is unchanged for NBA.
- **T026 (S7, S3, N10, N12)**: for `unit === 'PER_WEEK'`:
  - Hide the whole "Projected week by week" section, including the bump block's
    "One projected week is a point" sentence, and the week picker.
  - Replace "weeks X–Y · standard" (`LeagueAnalysis.tsx:1213`) with "league scoring ·
    per-game projections".
  - Replace "rest-of-season points" (`:1221`), "the bye weeks are the drops" (`:1242`), "a
    bye or a one-week injury moves who starts" (`:1303`) and "rest-of-season window"
    (`:1338`) with per-week wording.
  - Label positions "by primary position".
  - Render `reasonCode` through a client map, falling back to `reason`.
  - Show "Projections are N days old" when `sourceModifiedAt` is older than
    `preseason.stale-after-days` (3, ARBITRARY). The value comes through C1/C2 as
    `staleAfterDays`.
- **T028/T029 (N11, B1)**:
  - `top` rows carry `starters: []` and `bench: []`, never null.
  - The footer adds "Injuries and missed games aren't modelled; players on IR aren't counted."
- **T031/T032/T033/T034 (S10, S11)**:
  - Two keys under `draftsim.preseason`: `favorite-min-gap-pct: 3.0` and
    `stale-after-days: 3`, both commented hand-set and ARBITRARY.
  - `PreseasonProperties` is `@Validated` with `@Positive`, registered in
    `DraftSimApplication`'s `@EnableConfigurationProperties`. A test binds the real
    `config/weights.yml` and asserts both values.
  - Favorite math uses one denominator: `gapPct(a, b) = (a − b) / b × 100`, where b is the
    lower total. `leaders` = rank 1 plus every roster x with `gapPct(t1, x) <
    favoriteMinGapPct`. `marginPoints` and `marginPct` run from rank 1 to the first
    non-leader.
  - `favorite` carries `leaderTotal` and `nextTotal`, so the headline shows both values.
  - The C1 field `nearTiePct` is renamed `minGapPct`.
  - The footer says "a gap this size is within what these projections can't resolve" when
    `marginPct < 2 × minGapPct`.
- **T036/T037 (S5)**:
  - `gamesThisWeek` is replaced by `startersWithoutGame: { week, mine: [{name, team}],
    countByRoster }`. A pure static helper in `ScheduleGridService` takes `(Result,
    week, List<team codes>)` and returns the teams with 0 games that week. It gets a
    unit test.
  - The home copy reads "{n} of your projected starters have no game in week 1", or
    "All your projected starters play in week 1".
- **T039 (S6)**: the only `IN_SEASON` change is the grades block. FR-004 and SC-005 are amended
  to match.
- **T042 (S12)**:
  - Capture before and after back to back against the same DB and the same `X-Sleeper-User`,
    with no ingest in between. The "before" comes from a build of `main` 943b303 (`git
    stash` is forbidden here: use a separate `git worktree` at 943b303 on port 8081).
  - Diff with the new fields deleted (`unit`, `basisNote`, `provenance`,
    `unprojectedCategories`, `reasonCode`, `staleAfterDays`, `rosters[].reserve`).
  - Also record the NFL home's rendered block list before and after.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US4 map to spec.md's user stories. Story 5 (preseason odds) is a follow-up and has **no tasks here** (spec: "Not being built").

## Path conventions

Backend main code is under `backend/src/main/java/com/ballknowers/draftsim/` (written `B/`
below). Backend tests are under `backend/src/test/java/com/ballknowers/draftsim/` (`T/`).
Web code is under `web/src/`.

---

## Phase 1: Setup

- [X] T001 Confirm the baseline. In the worktree `.claude/worktrees/030-post-draft-season-view`, run `cd backend && ./gradlew test` with Postgres on 5433. Record the pass, fail **and skip** counts in `specs/030-post-draft-season-view/notes.md`. The skip count must be 0. "BUILD SUCCESSFUL with 52 skipped" means Postgres was down (memory: backend suite skips ITs silently). Run `cd web && npx tsc -b && npx vitest run` too and record the results.
- [X] T002 Re-check that no branch has taken migration V31. Run `git for-each-ref refs/heads` plus `git ls-tree` over `backend/src/main/resources/db/migration/`, and check `origin/main` as well. Record the result in `specs/030-post-draft-season-view/notes.md`. If V31 is taken, use the next free number in every later task.
- [X] T003 [P] Capture the NFL "before" JSON for SC-005. With the backend running from `main` (not this branch), save `GET /api/leagues/{NFL 2026 league id}/analysis` to `specs/030-post-draft-season-view/parity/nfl-analysis-before.json`. Take the NFL league id from the README league table.

---

## Phase 2: Foundational (blocks every story)

**Purpose**: the season-state rule (E2), plus the NBA projection storage and ingest (E1, R1). US1 needs the state. US2, US3 and US4 need the state and the projections.

- [X] T004 Create `backend/src/main/resources/db/migration/V31__player_season_projection.sql` per data-model E1.
  - Columns: `id bigserial primary key`, `sport text not null`, `season int not null`, `sleeper_player_id text not null`, `stats jsonb not null`, `basis text not null`, `source text`, `source_modified_at timestamptz`, `fetched_at timestamptz not null default now()`, `unique (sport, season, sleeper_player_id)`.
  - Add an index on `(sport, season)`.
  - Write a header comment saying why it's separate from V15's `player_projection`: V15 has no stats column, and its `pts_std` matches neither NBA league (R1).
- [X] T005 Create `B/store/PlayerSeasonProjectionRepository.java`. It needs:
  - `replaceAll(String sport, int season, List<Row> rows)`: upsert every row and delete every other row for that `(sport, season)` in **one transaction** (E1, "Replacement").
  - `Optional<Provenance> provenance(String sport, int season)`, returning `source`, the newest `source_modified_at`, the newest `fetched_at` and the row count.
  - `Map<String, Map<String, Double>> statsByPlayer(String sport, int season)`.
  - Bind `stats` as jsonb explicitly. A String bound against a `jsonb` column is a bug class #3 risk, so it has to be proven by an IT.
- [X] T006 [P] Add `season(String sport, int season)` to `B/ingest/SleeperProjectionClient.java`. It calls `GET /projections/{sport}/{season}?season_type=regular` with per-sport `position[]` values: NFL keeps `QB, RB, WR, TE, K, DEF`, and NBA uses `PG, SG, SF, PF, C`. Replace the single `POSITIONS` constant with a per-`Sport` lookup, so that `week(...)` for NFL sends exactly what it sends today.
- [X] T007 Create `B/ingest/SeasonProjectionIngestService.java`. It has `Result refresh(Sport sport, int season, boolean force)`.
  - Skip with `fresh = true` when `provenance.fetchedAt` is younger than the existing 6-hour `STALE_AFTER`. Reuse the constant from `ProjectionIngestService` or move it to one shared place, but don't copy the number.
  - Keep only rows whose `stats` holds `pts`.
  - Drop the `adp_*` keys.
  - **Reject the whole fetch** (log WARN, store nothing) if any row has `gp` present and not equal to 1.0. Data-model E1 says that means the basis changed.
  - Set `basis = 'PER_GAME'` and `source` from `company`, and take `source_modified_at` from `last_modified` (epoch ms).
  - Return `{ fetched, fresh, rowsStored, rowsDeleted, skippedNoStats, source, sourceModifiedAt }` (contract C3).
  - Throw `IllegalArgumentException` for `Sport.NFL`, with a sentence saying NFL season projections aren't wired up.
- [X] T008 [P] Write `T/ingest/SeasonProjectionIngestServiceTest.java`, a unit test with a stubbed client. Cover:
  - A stub row with only `adp_std` isn't stored.
  - A row with `gp: 3.0` rejects the whole batch.
  - `adp_*` keys are stripped.
  - The fresh-skip path makes no client call.
  - NFL throws.
- [X] T009 Write `T/store/PlayerSeasonProjectionRepositoryIT.java` against Postgres 5433. Cover:
  - A jsonb round-trip of a real per-game line. Use player `1970` from R1: `pts 31.88, reb 7.71, ast 8.17, stl 1.59, blk 0.48, to 3.77, tpm 3.76, dd 0.56, td 0.14`.
  - `replaceAll` deletes a player missing from the second call.
  - `provenance` returns the newest timestamps.
- [X] T010 Add `POST /api/ingest/season-projections?sport=&season=&force=` to `B/api/IngestController.java`, behind the existing `AdminGateInterceptor` registration like the other ingest routes (contract C3). `sport=nfl` returns 400 with the sentence from T007. Extend `T/api/IngestControllerTest.java` for the 400 and for the happy path with a mocked service. Also check that `T/api/NoIngestHintsInMessagesTest.java` still passes, since the new 400 sentence mustn't tell a reader to call an ingest route.
- [X] T011 Create `B/engine/SeasonStateService.java` with `enum SeasonState { PRE_DRAFT, DRAFTED_UNSCORED, IN_SEASON, COMPLETE }` and `SeasonState stateOf(LeagueRepository.LeagueRow league)`.
  - Implement exactly the data-model E2 order: (1) `league.complete()` gives `COMPLETE`; (2) the league's draft exists and its status isn't `complete` gives `PRE_DRAFT`; (3) `RosterWeekPointsRepository.storedWeeks(league.id())` is empty gives `DRAFTED_UNSCORED`; (4) otherwise `IN_SEASON`.
  - "A league with no draft row … counts as drafted."
  - Javadoc points at R5: Sleeper reports `status: in_season` for this preseason league, which is why stored weeks decide.
  - This is the **only** implementation of the rule (FR-001).
- [X] T012 [P] Write `T/engine/SeasonStateServiceTest.java` covering every branch of E2, including:
  - No draft row and no stored weeks gives `DRAFTED_UNSCORED`.
  - A complete league with zero stored weeks gives `COMPLETE`, because order matters.
  - Draft `drafting` gives `PRE_DRAFT`, even if stray weeks are stored.
- [X] T013 Add a `season-projections` step to `B/refresh/DailyRefreshService.java` (contract C4). For each sport with a current-season league whose `SeasonStateService` state is `PRE_DRAFT`, `DRAFTED_UNSCORED` or `IN_SEASON` (NBA only for now: skip NFL explicitly), call `SeasonProjectionIngestService.refresh(sport, season, false)`. Report through the existing `StepResult`: OK with the stored count, a fresh skip as its own outcome, FAILED with the message. A failure here must not fail the other steps. Extend `T/refresh/DailyRefreshServiceTest.java` with:
  - The step runs for NBA and not NFL.
  - A thrown ingest still lets the later steps run.

**Checkpoint**: run quickstart V1 locally (ingest about 529 rows; a second call is fresh) and V2's state half (`stateOf` the NBA 2026 league is `DRAFTED_UNSCORED` once T019 exposes it).

---

## Phase 3: User Story 1, "The home is about this season once the draft is done" (P1) 🎯 MVP

**Goal**: in `DRAFTED_UNSCORED`, the home leads with 2026 and drops the blocks that only hold last season's data (FR-002, FR-003, FR-004). There are no projections yet, so the MVP is useful even before US2.

**Independent Test**: on the NBA 2026 league, open the home. Matchup, power headline, awards and empty standings are gone. The header says the draft is complete and gives the first games date. The NFL home is unchanged.

- [X] T014 [US1] Create `B/engine/SeasonStartService.java` with `SeasonStart read(String sleeperLeagueId, String sleeperUserId)`. For this story, fill `sport`, `season`, `state` (from T011), `firstWeek` (E7) and `draft` (E6, the `draftId` only). Leave `strength: null`, because US2 fills it.
  - `firstWeek` is only non-null in `DRAFTED_UNSCORED`.
  - `week` = the league's Sleeper `settings.leg` or null.
  - `startDate` = the sport state's `season_start_date`, only when the state's `league_season` equals the league's season, else null (R5).
  - Reuse whatever spec 017's `ScheduleGridService` or `NextMatchupService` already reads for `leg` and the sport state. Don't add a second Sleeper state call if one is already cached.
  - `gamesThisWeek` is null in this task (US4, T034).
  - Build response maps mutably (rule 4).
- [X] T015 [US1] Create `B/api/SeasonStartController.java`: `GET /api/leagues/{sleeperId}/season-start` under `@RequestMapping("/api")`. Membership-scope it exactly like `NextMatchupController`. An unknown league, or one that isn't the caller's, is a 404 through the shared not-found path (contract C1).
- [X] T016 [US1] Mirror the records in `web/src/api.ts` **in the same change as T014/T015**. Add the `SeasonState` union, the `SeasonStart`, `FirstWeek` and `SeasonStartDraft` types (with `strength` typed now as `SeasonStrength | null`, per contract C1, so US2 doesn't reshape it), and `getSeasonStart(sleeperLeagueId)`, following `getNextMatchup`'s pattern.
- [X] T017 [P] [US1] Write `T/api/SeasonStartControllerIT.java` against Postgres. Cover:
  - A seeded league with a complete draft and no `roster_week_points` gives `state: DRAFTED_UNSCORED` and `firstWeek.week` populated.
  - The same league with one stored week gives `IN_SEASON` and `firstWeek: null`.
  - A non-member gets 404.
  - The JSON has no `Map.of` null crash when `startDate` and `draftId` are null.
- [X] T018 [US1] Change `web/src/pages/LeagueHome.tsx` to fetch `getSeasonStart(id)` with `useBlock`, keyed on `[id, version]`. Key the layout on `seasonStart.state` per contract C5, and never re-derive it from `draft.status` or `anyGames`. Delete or replace the local `preSeason` derivation (`LeagueHome.tsx:144`) so there's one rule.
  - In `DRAFTED_UNSCORED`: don't render `MatchupBlock`, `PowerBlock`, `AwardBlock` or the empty `StandingsBlock`.
  - The `PageHeader` `sub` reads "{season} · draft complete · first games {formatted startDate}", or "first games: date not known yet" when `startDate` is null.
  - Add a **Draft** block linking `/drafts/{draftId}/board` (E6) when `draftId` is non-null.
  - In every other state, render exactly as today (FR-004).
  - While `seasonStart` is loading or has errored, fall back to today's layout. Don't render a half-removed page.
- [X] T019 [US1] Extend `web/src/pages/LeagueHome.test.tsx` with:
  - `DRAFTED_UNSCORED`: none of the matchup, power, award or standings blocks render. The header text names the season and the date. The draft board link points at the right id.
  - A null `startDate` shows "date not known yet".
  - `IN_SEASON` and `PRE_DRAFT` render the same blocks as before the change (assert the block headings present).
  - A `season-start` error leaves today's layout.

**Checkpoint**: US1 works on its own. Quickstart V2 passes, and V5's "no 2025 blocks" half passes.

---

## Phase 4: User Story 2, "Who has the strongest team, and why" (P1)

**Goal**: every roster ranked by projected weekly strength under the league's own scoring, through the **existing** `LeagueAnalysisService.projectionBlocks` (R4). The Analysis page is offered for NBA, and the home shows a preview.

**Independent Test**: quickstart V3 (rebuild 3 totals by hand to 0.1) and V4 (the ordering test) both pass. Analysis opens for the NBA league with per-week labels and no week-by-week strip.

- [X] T020 [US2] In `B/engine/LeagueAnalysisService.java`, add the four fields to `Projections` (data-model E4):
  - `String unit`: `"REST_OF_SEASON"` or `"PER_WEEK"`.
  - `String basisNote`: a code, nullable.
  - `Provenance provenance`: a new nested record `{ String source, Instant sourceModifiedAt, Instant fetchedAt }`, nullable.
  - `List<String> unprojectedCategories`.

  Fill the NFL values per contract C2: `REST_OF_SEASON`, null, provenance from `player_projection`'s newest `fetched_at` and `source` over the window, and `[]`. Update `unavailable(...)` to carry them (`unit` still set, the others null or empty). **In the same task**, mirror them in `web/src/api.ts`'s `Projections` type (rule 2).
- [X] T021 [US2] Add the NBA path in `LeagueAnalysisService.projectionBlocks`. Replace the `if (league.sport() != Sport.NFL)` gate at `:446` with:
  - NFL keeps today's path byte for byte.
  - NBA builds `pointsByPlayer` from `PlayerSeasonProjectionRepository.statsByPlayer("nba", season)`. Score each line with `GameScoringService.score(league scoring_json, stats)`, the one "what is a game worth" implementation (R1). This is one game per starter (R3), never multiplied by games in the week.
  - Any other sport keeps `projectionsNotBuiltReason`.
  - NBA with an empty stats map returns `unavailable(...)` with a `PROJECTIONS_NOT_LOADED` reason. Keep the existing message style; the client maps the code (FR-014).
  - NBA sets `unit = "PER_WEEK"`, `basisNote = "ONE_AVERAGE_GAME_PER_STARTER"` and `provenance` from T005.
  - `unprojectedCategories` = the league's scoring keys that appear in **no** stored stats line, sorted. R2 measured these six for "Ball Knowers": `bonus_ast_15p, bonus_pt_40p, bonus_pt_50p, bonus_reb_20p, ff, tf`.
  - For NBA, set every `RosterProjection.byWeek` to `List.of()` (R4) by skipping `withWeekly`.
  - Feed the NBA `matchupBlock` the same `pointsByPlayer` map, so the home and matchup numbers can't disagree (C2).
  - Don't touch `LineupPass`, `rankAll`, `Ranker` or `BasketballRules.startingLineup`. They're reused as they are.
- [X] T022 [US2] Add the rosters-not-loaded guard (spec edge case, FR-014). If every roster from `sleeper.rosters(...)` has an empty `players` list, return `unavailable` with reason code `ROSTERS_EMPTY`, not a ranking of zeros. Apply it to both sports. For NFL this can't trigger on a live league, so SC-005 is unaffected.
- [X] T023 [P] [US2] Extend `T/engine/LeagueAnalysisServiceTest.java` with the **preference-ordering test (SC-004)**:
  - Build an NBA league of 4 rosters with stored per-game lines and a scoring map.
  - Assert the ranking. Then move the single highest-projected player from rank 1's roster to rank 4's roster and re-run.
  - Rank 4's roster `total` must rise and its rank must improve. Rank 1's `total` must fall.
  - Also assert that one starter's `points` equals a hand-computed `GameScoringService.score` of its line (to 0.01), and that `unprojectedCategories` lists a scored key that no line carries.
  - Also assert that `byWeek` is empty for NBA.
- [X] T024 [P] [US2] Extend `T/engine/LeagueAnalysisServiceTest.java` with:
  - NBA with no stored lines gives `available=false`, `PROJECTIONS_NOT_LOADED`, an empty `rosters` list, and no zeros.
  - All-empty rosters gives `ROSTERS_EMPTY`.
  - NFL's existing tests pass unmodified, apart from the new fields being asserted as `REST_OF_SEASON`, null and `[]`.
- [X] T025 [US2] In `web/src/destinations.ts`, change the Analysis destination's `sports` from `['nfl']` to `['nfl', 'nba']`, and update the comment that called basketball projections unbuilt (spec 016 history). Check `web/src/destinations.test.ts` and update whatever asserts NBA *excludes* Analysis. Check that `analysisOffered` in `web/src/pages/LeagueHome.tsx` follows it, and that the NBA home still renders `NextMatchupBlock`, not `NextOpponentBlock` (C6, spec 017 F7). If `analysisOffered` now fetches analysis for NBA on the home, keep that fetch, since US2's home preview (T029) uses it, but keep `NextOpponentBlock` NFL-only.
- [X] T026 [US2] In `web/src/pages/LeagueAnalysis.tsx`, label every projection number with `projections.unit`: "pts/week (projected)" for `PER_WEEK`, and today's wording for `REST_OF_SEASON`.
  - Hide the week-by-week strip or chart when every `byWeek` is empty, and draw nothing in its place.
  - Under the projection block, render a provenance line: the source, "updated {relative time of sourceModifiedAt}", and "accuracy for this league not measured".
  - When `basisNote === 'ONE_AVERAGE_GAME_PER_STARTER'`, add: "One average game per starter. Sleeper credits one game a week, and the credited game has run about 15% above the average, so these read low." (R3)
  - When `unprojectedCategories` is non-empty, add: "Not projected: {human names}". Map `bonus_ast_15p` → "15+ assist bonus", `bonus_pt_40p` → "40+ point bonus", `bonus_pt_50p` → "50+ point bonus", `bonus_reb_20p` → "20+ rebound bonus", `ff` → "flagrant fouls", `tf` → "technical fouls". Show any other key raw.
  - Map the reason codes `PROJECTIONS_NOT_LOADED` and `ROSTERS_EMPTY` to sentences.
  - Audit the page's NFL-only wording for an NBA league: bye weeks, "rest of season", FLEX copy. Gate or neutralise each one by `sport`/`unit`, and list each changed string in `specs/030-post-draft-season-view/notes.md`.
- [X] T027 [US2] Extend `web/src/pages/LeagueAnalysis.test.tsx` with:
  - The `PER_WEEK` fixture shows "pts/week (projected)" and no week strip.
  - The provenance line and the 15% note render.
  - Unprojected categories render as human names.
  - `PROJECTIONS_NOT_LOADED` renders its sentence and no ranking.
  - The NFL fixture renders exactly as before.
- [X] T028 [US2] Fill `strength` in `B/engine/SeasonStartService.java` (contract C1) **from the same `LeagueAnalysisService` projection pass**. Expose a method returning `Projections` that `analyse` also uses, so there's no second lineup computation.
  - `top` = the first 3 `RosterProjection` rows with `starters` and `bench` omitted (null or empty).
  - `me` = the caller's row if it's not in `top`, else null.
  - `teams` = the roster count.
  - Carry `available`, `reason`, `unit`, `provenance`, `unprojectedCategories` and `basisNote` through.
  - Only compute this in `DRAFTED_UNSCORED` or `IN_SEASON`. `favorite` stays null until US3 (T031).
  - Mirror any new record (`SeasonStrength`) in `web/src/api.ts` in the same change.
- [X] T029 [US2] Create `web/src/components/PreseasonStrengthBlock.tsx`, rendered by `LeagueHome.tsx` in `DRAFTED_UNSCORED` (C5).
  - The top 3 teams, each with avatar, team name, exact projected value and "pts/week (projected)", plus a bar with the exact number beside it (memory: label the axis, spell out the number).
  - "You" highlighted with your rank if you're outside the top 3.
  - A link to `/leagues/{id}/analysis` (SC-002, one tap).
  - The provenance footer from T026. Share the strings: extract them into a small module, e.g. `web/src/projectionCopy.ts`, used by both, not duplicated.
  - When `strength.available` is false, show the reason sentence only.
  - Follow `feedback_avoid_flat_uniform_cards`: tinted rows and real hierarchy, not identical flat cards.
- [X] T030 [US2] Write `web/src/components/PreseasonStrengthBlock.test.tsx`. Cover:
  - The top 3 render with exact values.
  - The me-row appears when the reader is outside the top 3, and not when inside.
  - The link goes to Analysis.
  - An unavailable state renders no numbers.
  - Every number in the block sits next to the word "projected".

**Checkpoint**: quickstart V3, V4 and V7 pass locally. Analysis opens for NBA.

---

## Phase 5: User Story 3, "Who's the favorite" (P2)

**Goal**: name the projected favorite and its margin over the next team, with no odds language. Report near-ties as ties (FR-007, FR-007a, FR-009).

**Independent Test**: the favorite equals rank 1 of the strength ranking, the margin rebuilds from the two totals, a forced near-tie renders as a tie, and the rendered block contains none of "odds", "likely" or "% to win".

- [X] T031 [US3] Add the `preseason.near-tie-pct: 1.0` key under `draftsim:` in `config/weights.yml`. Its comment says it's **hand-set and ARBITRARY, not fitted**: below this percentage gap the top teams are called a tie. Bind it through a new `B/config/PreseasonProperties.java` (`@ConfigurationProperties(prefix = "draftsim.preseason")`), registered the way `DraftGradeProperties` is. Check that `/api/health` `weightsLoaded` still reports true.
- [X] T032 [US3] Compute `favorite` in `B/engine/SeasonStartService.java` per data-model E5.
  - `leaders` = every roster tied at rank 1, plus any roster whose total is within `nearTiePct` percent of rank 1's total.
  - `marginPoints` = rank 1's `total` minus the first non-leader's `total`. For NFL, divide by the window's remaining weeks so it's always pts/week.
  - `marginPct` = `marginPoints / that roster's total × 100`, to one decimal.
  - `tie = leaders.size() > 1`.
  - `nearTiePct` echoes the config.
  - `null` when `strength.available` is false, or when there are fewer than 2 rosters.
  - Add a pure static helper for the math so it's unit-testable without I/O.
- [X] T033 [P] [US3] Write `T/engine/SeasonStartFavoriteTest.java` for the pure helper. Cover:
  - A clear leader gives the right `marginPoints` and `marginPct`.
  - Two teams 0.5% apart with a 1.0 threshold give `tie=true` and both leaders.
  - An exact tie at rank 1 gives a tie.
  - The NFL rest-of-season totals are divided to per-week.
  - A single roster gives null.
- [X] T034 [US3] In `web/src/components/PreseasonStrengthBlock.tsx`, render the favorite headline per contract C5.
  - Clear leader: "Projected strongest roster: {team}, by {marginPoints} pts/week ({marginPct}%)".
  - Tie: "Projected dead heat: {a} and {b}, within {nearTiePct}%". With 3 or more leaders, list them.
  - No headline when `favorite` is null.
  - In `IN_SEASON`, the block isn't rendered at all (FR-008). The in-season forecast and power blocks are what the home shows then, as today.
- [X] T035 [US3] Extend `web/src/components/PreseasonStrengthBlock.test.tsx` with:
  - The leader and tie headlines.
  - A **forbidden-words assertion (FR-007a)**: the block's full rendered text, case-insensitive, contains none of `odds`, `likely`, `% to win`, `chance` or `probability`, in both the leader and tie fixtures.

**Checkpoint**: the US1–US3 home is complete for the preseason window.

---

## Phase 6: User Story 4, "This season's numbers, not last season's" (P2)

**Goal**: show your games in week 1 before tip-off, and a draft-grades summary once a week is scored (FR-010 as amended, FR-011, FR-012).

**Independent Test**: in `DRAFTED_UNSCORED` the home shows your week-1 game count and the draft board link, and no grades. In `IN_SEASON` the home adds a best/worst draft-grade summary with the early badge. The spotlight answers about 2026 throughout.

- [X] T036 [US4] Fill `firstWeek.gamesThisWeek` in `B/engine/SeasonStartService.java` for NBA (E7) from the existing `ScheduleGridService` (spec 017). Use the games each roster's players have scheduled in `firstWeek.week`, counted exactly the way the schedule grid page counts them. Call its method, don't recount. The fields are `mine` (the caller's roster, or null) and `byRoster` (rosterId → count). It's null for NFL, and null when the schedule for that week isn't loaded. The home then says the schedule isn't loaded yet (FR-014). Mirror the shape in `web/src/api.ts` if the T016 type needs adjusting.
- [X] T037 [US4] In `web/src/pages/LeagueHome.tsx`, in `DRAFTED_UNSCORED` for NBA, show "{mine} games in week {week}" next to the existing `NextMatchupBlock`, or "schedule not loaded yet" when null. The text says these are scheduled games and makes no claim about points (memory: one game per week is credited; the schedule grid's "4 games ≈ 2×" premise was wrong).
- [X] T038 [US4] Fill `draft.grades` in `B/engine/SeasonStartService.java` **only in `IN_SEASON`** (E6, FR-010 as amended). Call the existing `DraftGradesService.read(...)` for the league's completed draft and condense it to `{ best: TeamGrade, worst: TeamGrade, gradesEarly, earlyThresholdWeeks }`. When the grades come back `available=false`, pass `grades: null` and carry the reason code. In `DRAFTED_UNSCORED`, always use null and skip the call (R6). Mirror it in `web/src/api.ts`.
- [X] T039 [US4] In `web/src/pages/LeagueHome.tsx`, in `IN_SEASON`, add a draft-grades summary block: best and worst team with letter grades, the existing early-season badge when `gradesEarly`, and a link to the completed draft board's grades view (`/drafts/{draftId}/board`). This is the only addition to the `IN_SEASON` home. Re-run T019's "IN_SEASON unchanged" test and update it to expect exactly this one added block, which keeps SC-005 explicit.
- [X] T040 [P] [US4] Extend `web/src/pages/LeagueHome.test.tsx` with:
  - `DRAFTED_UNSCORED` NBA shows the week-1 game count and no grades block.
  - A null `gamesThisWeek` shows "schedule not loaded yet".
  - `IN_SEASON` with grades shows best and worst plus the badge.
  - `IN_SEASON` with `grades: null` shows no grades block.
- [X] T041 [US4] Verify FR-012 **without changing code** (R7). Read `B/engine/PlayerSpotlightService.java:171-176`, then call `GET /api/leagues/1339351318115946496/spotlight` (or whatever route `PlayerSpotlightController` exposes) locally and confirm it answers about season 2026, not 2025. Record the response's season and state in `specs/030-post-draft-season-view/notes.md`. If it answers 2025, stop and report; don't patch it inside this task.

---

## Phase 7: Polish and cross-cutting

- [X] T042 NFL parity (SC-005, quickstart V6). With this branch's backend, save `GET /api/leagues/{NFL id}/analysis` to `specs/030-post-draft-season-view/parity/nfl-analysis-after.json`. Diff it against T003's file. The only allowed differences are the four new `projections` fields. Record the diff result in `notes.md`. Any other difference is a bug, so fix it before going on.
- [X] T043 Run the full suites: `cd backend && ./gradlew test` (skip count must be 0, compared against T001) and `cd web && npx tsc -b && npx vitest run && npm run build`. Record the counts in `notes.md`.
- [X] T044 Run quickstart V1–V7 locally against Postgres 5433. Ingest the real NBA 2026 season projections through C3 and use the real Sleeper rosters for `1339351318115946496`. V3 is the hand rebuild of 3 totals; write it out in `specs/030-post-draft-season-view/verification.md` with the actual numbers. Do V5 in the Browser pane through `preview_start`, at desktop width and at 375 px, with screenshots. Restart `bootRun` after the last Java change, and hard-refresh the tab after any vite restart.
- [X] T045 [P] Update `README.md`'s feature list and the `HANDOFF.md` top section. Say what shipped, that Story 5 (odds) is the planned follow-up, that the 15% credited-game note is a stated stand-in and not a fitted factor, and that quickstart V8 (after tip-off) is still owed.
- [X] T046 [P] Append any durable lesson found during the build to `claude/lessons.md`, as it's found and not batched (AGENTS.md working style). Candidates: "Sleeper reports in_season before tip-off", and "'already built' is not 'available now'" (R6).
- [ ] T047 Stop and ask Allan before committing (constitution rule 5). Show `git status` and the staged diff, limited to this spec's paths, because other sessions may share the tree.
- [ ] T048 After deploy, and only with Allan's go-ahead: run C3 on prod with his `X-Admin-Token`, then repeat V2 and V5 on `ballknowers.co` before NBA week 1 is scored. Record them in `verification.md`. Note that both Railway services deploy independently (memory: deployed to Railway), so confirm the backend route **and** the web bundle are both live.
- [ ] T049 Owed after tip-off: quickstart V8. The state flips to `IN_SEASON`, the home returns to today's layout plus the grades summary, and the spotlight answers about 2026. Record it with the date in `verification.md`.

---

## Dependencies and execution order

- **Phase 1** (T001–T003): no dependencies. T003 needs `main`'s backend running, so do it before switching the dev server to this branch.
- **Phase 2** (T004–T013) blocks every story:
  - T004 → T005 → T009.
  - T006 and T005 → T007 → T008 and T010.
  - T011 → T012.
  - T007 and T011 → T013.
- **US1** (T014–T019) needs T011. It doesn't need the projections (T004–T010), so the MVP can ship with Phase 2's state half done.
- **US2** (T020–T030) needs T005 and T007 (projections) and T014–T016 (the `season-start` shell).
  - T020 → T021 → T022 → T023/T024.
  - T025 → T026 → T027.
  - T021 → T028 → T029 → T030.
- **US3** (T031–T035) needs T028. T031 → T032 → T033, and T029 → T034 → T035.
- **US4** (T036–T041) needs T014.
  - T036 → T037.
  - T038 → T039.
  - T040 after T037 and T039.
  - T041 is independent.
- **Polish**:
  - T042–T044 come after every story.
  - T045/T046 can run any time after their facts exist.
  - T047 comes before any commit.
  - T048 comes after the commit and deploy.
  - T049 comes after 2026-10-20.

### Story completion order

`Phase 2 (state half)` → **US1 (MVP)** → `Phase 2 (projection half)` → **US2** → **US3**. **US4** can run beside US2 and US3 once US1's T014 exists.

## Parallel opportunities

- Phase 2: T006 runs beside T004/T005, T008 beside T009, and T012 beside any of T004–T010.
- US1: T017 (backend IT) runs beside T018/T019 (web).
- US2: T023 and T024 can be written in parallel. The T025–T027 web chain runs beside the T021–T022 backend chain once T020's api.ts change has landed.
- US3: T033 runs beside T034.
- US4: T036–T037 (games) runs beside T038–T039 (grades), and T041 can run any time.

### Parallel example: User Story 2

```text
Sonnet agent A: T021 → T022 → T023, T024   (backend: NBA path and its tests)
Sonnet agent B: T025 → T026 → T027         (web: Analysis page for NBA)
Both start after T020 (record + api.ts fields), since both read those fields.
Parent session: read both diffs, then T028 → T029 → T030.
```

## Implementation strategy

1. **MVP = Phase 1 + T011–T012 + US1.** The "weird state" is gone from the home, even before any projection exists. It can be deployed on its own if the rest runs late.
2. **Next increment = Phase 2 projections + US2.** This answers "who's got the strongest team". It's the main value, and it reuses the NFL engine.
3. **Then US3** (the favorite headline) and **US4** (week-1 games, grades after week 1).
4. Deploy before NBA week 1 is scored, then run T048 on prod. T049 is owed after tip-off.
5. **Follow-up spec work (not here):** Story 5, the preseason odds, with its measured pre-checks in research R9.

The pipeline is the same as every feature in this repo: an adversarial plan review **before** T001 (write `plan-review.md`), a bug-hunting code review after T043 (`code-review.md`), and live verification in T044, T048 and T049.
