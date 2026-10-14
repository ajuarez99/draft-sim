# Tasks: Projection snapshots and a measured accuracy number (spec 036)

**Input**: `specs/036-projection-foundation/` (spec.md, plan.md **including "Amended after plan review"**, research.md, data-model.md, contracts/, quickstart.md)

**Where docs disagree, plan.md's "Amended after plan review" section wins.** It changes the migration number (V36), the freeze mechanism (trigger), the scoring map (restricted), the NBA summary series, game completion, voided games, and the parity tolerance.

**Tests**: included. The spec's acceptance criteria require ordering tests (AGENTS.md bug class #1), freeze tests and a hand-computed accuracy check (SC-003).

**Environment**: Windows. Use `.\gradlew.bat` from `backend/`. Local Postgres is on 5433. **Do not run bootRun or ITs against the shared `draftsim` database.** Create and use `draftsim_036` (`createdb -h 127.0.0.1 -p 5433 -U draftsim draftsim_036`, password `draftsim`, and `DB_URL=jdbc:postgresql://localhost:5433/draftsim_036`). Applying V36 to the shared DB would break another session's V35 (research R11). Check how existing ITs pick their DB before running them, and point them at `draftsim_036` too.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

- [x] T001 Create database `draftsim_036` on the 5433 cluster. Confirm `./gradlew.bat test` (in `backend/`) can target it: find how ITs get their datasource (`backend/build.gradle.kts`, `backend/src/test/resources/`) and use an env/system-property override, never an edit that would repoint other sessions. Record the exact command in `specs/036-projection-foundation/quickstart.md` §0.
- [x] T002 Add the R10 block to `config/weights.yml` under `draftsim:`. Each key gets a comment saying it's hand-set and arbitrary:
  - `projection-accuracy: { min-samples: { nba: 500, nfl: 300 }, min-cell: 50, bands: { nba: [20, 35, 50], nfl: [5, 10, 17] } }`. Band edges are lower bounds of the 2nd..last band, in league points.
  - `projection-snapshot: { cutoff-et: { nba: "12:00", nfl: "09:00" }, lookahead-days: 7 }`.
- [x] T003 [P] Add `draftsim.projections.nfl-exact-scoring: false` to `backend/src/main/resources/application.yml`, with a comment: "flip only per specs/036 contracts/parity.md decision rule".

---

## Phase 2: Foundational

- [x] T004 Create `backend/src/main/resources/db/migration/V36__projection_snapshot.sql`:
  - The `projection_snapshot` table exactly as data-model E1: columns, `unique (sport, season, sleeper_player_id, game_id)`, `check (fetched_at < cutoff_at)`, index `(sport, season)`.
  - A `BEFORE UPDATE` trigger function that does `IF now() >= OLD.cutoff_at THEN RETURN NULL; END IF;` and also forces `NEW.cutoff_at := OLD.cutoff_at`, so the cutoff never changes.
  - `alter table player_projection add column stats jsonb`.
  - A header comment in the style of V15/V31 explaining why this is a separate table from `player_projection` (a frozen record vs a mutable cache) and why it's V36 (research R11, amended).
- [x] T005 [P] Create `backend/src/main/java/com/ballknowers/draftsim/config/ProjectionAccuracyProperties.java`, a `@ConfigurationProperties(prefix = "draftsim")`-style binding for T002's keys, following `config/PreseasonProperties.java`'s pattern. Register it the way `PreseasonProperties` is registered.
- [x] T006 Extract the "categories no projection line carries" rule from `engine/LeagueAnalysisService.java:605-612` into one static helper, `engine/ProjectedCategories.java`, with two methods:
  - `unprojected(Map<String, ? extends Number> scoring, Set<String> projectedKeys)` returns the sorted list, unchanged behavior.
  - `restrict(scoring, projectedKeys)` returns the scoring map without those keys.
  
  Make `LeagueAnalysisService` call it. The existing `LeagueAnalysisServiceTest` must still pass unchanged.

---

## Phase 3: User Story 1 — Pre-game snapshots (P1) 🎯 MVP, must deploy before 2026-10-20

**Goal**: a daily capture that stores each game's latest pre-game projection and freezes it at the cutoff.

**Independent test**: run the capture against week-1 NBA 2026 (still pre-game on 10-14). Rows are written with `fetched_at < cutoff_at`. A re-run refreshes without duplicating. A capture with `now` past the cutoff writes nothing for that game. A direct SQL update of a frozen row changes nothing.

### Tests for US1

- [x] T007 [P] [US1] `backend/src/test/java/com/ballknowers/draftsim/store/ProjectionSnapshotRepositoryIT.java`. Against a real DB:
  1. An insert with `fetched_at >= cutoff_at` is rejected by the CHECK.
  2. An `upsertPreGame` that refreshes an open row (cutoff in the future) replaces stats, `fetched_at`, `source_modified_at` and `season_avg_stats`, and leaves `cutoff_at` unchanged.
  3. With a row whose `cutoff_at` is in the past (insert with `fetched_at` and `cutoff_at` both in the past), a direct `UPDATE ... set stats = ...` leaves stats unchanged (trigger), and `upsertPreGame` on it reports frozen-skipped and changes nothing.
  4. A batch with two rows of the same key is deduplicated by the repository, not thrown.
- [x] T008 [P] [US1] `backend/src/test/java/com/ballknowers/draftsim/ingest/ProjectionSnapshotServiceTest.java`, a unit test with stubbed clients and repository and `capture(sport, now)` driven by an explicit `now`:
  1. A `pre_game` game dated tomorrow is written, with `cutoffAt` = date at 12:00 America/New_York for NBA and 09:00 for NFL, checked across a DST boundary (2026-11-01).
  2. A game whose schedule status is `complete`, `in_game`, `postponed` or `canceled` is counted `rowsNotPreGame` and never passed to the repository.
  3. A `pre_game` game with `now` past its cutoff is counted `rowsNotPreGame` and not passed.
  4. Week selection takes only weeks with a `pre_game` game dated in [today ET, today ET + lookahead-days], and an All-Star-gap window yields no weeks and no failure.
  5. NBA rows copy the player's `player_season_projection.stats` into `seasonAvgStats` (null when the player has none). NFL rows always get null.
  6. Stub rows without `pts` (NBA) or `pts_ppr` (NFL) are skipped.
  7. `adp_*` and `pos_adp_*` keys are stripped from stored stats.

### Implementation for US1

- [x] T009 [US1] Create `backend/src/main/java/com/ballknowers/draftsim/store/ProjectionSnapshotRepository.java` with:
  - `record Row(sport, season, week, sleeperPlayerId, gameId, gameDate, opponent, team, Map stats, Map seasonAvgStats /*nullable*/, source, Instant sourceModifiedAt, Instant fetchedAt, Instant cutoffAt)`.
  - `UpsertResult upsertPreGame(List<Row>)`: dedupe by key (keep the last), then `insert … on conflict (sport, season, sleeper_player_id, game_id) do update set stats=…, season_avg_stats=…, source_modified_at=…, fetched_at=…, opponent=…, team=… where excluded.fetched_at < projection_snapshot.cutoff_at`. Use the affected count to report written vs frozen-skipped.
  - Follow `PlayerSeasonProjectionRepository`'s JSONB bind pattern. Bind `timestamptz` from `OffsetDateTime`, never `java.sql.Timestamp` (AGENTS bug class #3).
- [x] T010 [US1] Create `backend/src/main/java/com/ballknowers/draftsim/ingest/ProjectionSnapshotService.java` implementing plan.md "US1: capture" plus amendment B2:
  - `Result capture(Sport sport, Instant now)` returns `Result(sport, season, List<Integer> weeks, rowsWritten, rowsFrozenSkipped, rowsNotPreGame)`.
  - The season gate is the same as `DailyRefreshService.seasonProjections`. Factor that shared "current season needing projections" check into one method so the two can't drift.
  - The schedule comes from `SleeperPlayerStatsClient.schedule(sport, season)`. NFL payload `home`/`away` are strings and NBA's are objects. Only `game_id`, `status`, `date` and `week` are needed.
  - Late rows are dropped **before** the repository call.
- [x] T011 [US1] Wire a `PROJECTION_SNAPSHOT` step into `backend/src/main/java/com/ballknowers/draftsim/refresh/DailyRefreshService.java`:
  - It runs for both sports, after `SEASON_PROJECTIONS` for NBA and after `TRENDING` for NFL.
  - Add outcome `DONE_PROJECTION_SNAPSHOT_FAILED_BEST_EFFORT`, plus a detail string per contracts/snapshot-refresh.md.
  - It records no `daily_capture` row.
  - Update the class javadoc. Update any existing `DailyRefreshService` tests that assert the exact step list.
- [x] T012 [US1] Add `POST /api/ingest/projection-snapshot?sport=` to `backend/src/main/java/com/ballknowers/draftsim/api/IngestController.java`. It calls `capture(sport, Instant.now())` and is admin-gated like its siblings.
- [x] T013 [US1] Run T007 and T008 and the full backend suite against `draftsim_036`. **Report the skipped-test count** (memory: the suite skips ITs silently when Postgres is unreachable). Then run the capture live once against Sleeper for `sport=nba` and `sport=nfl` on `draftsim_036`. Record the actual `weeks`, `rowsWritten` and `rowsNotPreGame`, and the `bool_and(fetched_at < cutoff_at)` query result, in `specs/036-projection-foundation/verification.md` (create it).

**Checkpoint**: US1 is shippable alone.

---

## Phase 4: User Story 2 — Accuracy (P1)

**Goal**: per-league accuracy under the league's restricted scoring, with "too early" below N, shown on the Analysis page and in the source line of both projection blocks.

**Independent test**: a seeded IT whose MAE, lean, n, did-not-play, pending and voided all equal a hand computation.

### Tests for US2

- [ ] T014 [P] [US2] `backend/src/test/java/com/ballknowers/draftsim/engine/ProjectionAccuracyServiceTest.java`, a unit test over in-memory samples:
  1. **Lean sign and ordering:** projections uniformly 2 points above actual give lean +2.0 and MAE 2.0. Uniformly below gives lean −2.0.
  2. **B3:** a realized line equal to the projected line plus `bonus_pt_40p: 1`, where the league scores `bonus_pt_40p: 2` and no projected line carries it, gives lean 0.0. Without the restriction the test must fail: show that in a comment with the would-be number.
  3. **Bands are judged on the projected value:** a sample projected 18 and scored 40 lands in band "0–20".
  4. A cell with n < min-cell has null mae/lean and keeps its n.
  5. Overall n < min-samples gives status TOO_EARLY, and every mae/lean is null.
  6. **Classification:**
     - schedule `complete` with a player_game row: scored.
     - `complete` without a row: did-not-play.
     - `pre_game` or `in_game`: pending.
     - `postponed` or `canceled`: voided.
  7. NBA: both series are computed over the snapshots that have `seasonAvgStats` only, and `withoutSeasonAverage` counts the rest. The summary uses SEASON_AVERAGE for NBA and GAME for NFL.
- [ ] T015 [P] [US2] `backend/src/test/java/com/ballknowers/draftsim/engine/ProjectionAccuracyReadIT.java`, against `draftsim_036`. Seed about 6 snapshots, the matching `player_game` rows, `sport_schedule` statuses and a league with known `scoring_json`. Assert the full `ProjectionAccuracy` against numbers computed by hand **in the test comments** (SC-003).
- [ ] T016 [P] [US2] `web/src/projectionCopy.test.ts` (extend if it exists). `provenanceLine` must render:
  - `"RotoWire projection via Sleeper"` for source `rotowire`;
  - the MEASURED text with n;
  - the TOO_EARLY text "too early to measure (n of N games)";
  - the old "accuracy for this league not measured" when accuracy is null.
  
  It must never print a number for TOO_EARLY.
- [ ] T017 [P] [US2] `web/src/components/ProjectionAccuracyPanel.test.tsx`:
  - TOO_EARLY shows counts and no figures.
  - MEASURED shows the typical miss and the lean in words ("runs 2.1 high" / "runs 2.1 low"), with no green/red class on the lean.
  - NBA shows two series tabs.
  - The excluded-categories caveat is rendered.

### Implementation for US2

- [ ] T018 [US2] Add the read to `ProjectionSnapshotRepository`: `List<Sample> forAccuracy(sport, season)`. It's one SQL statement that left-joins `player_game` on (sleeper_player_id, game_id) and joins `sport_schedule` on (sport, season, game_id) for status. It returns the raw snapshot stats, season_avg_stats, realized stats (nullable), schedule status (nullable), position (`player.positions[1]` joined on sleeper_id + sport) and game_date. Run the SQL by hand once against `draftsim_036` before wiring it in (bug class #2).
- [ ] T019 [US2] Create `backend/src/main/java/com/ballknowers/draftsim/engine/ProjectionAccuracyService.java`:
  - `ProjectionAccuracy forLeague(LeagueRow)` and `AccuracySummary summary(LeagueRow)`. The summary is derived from `forLeague`'s result, never computed separately.
  - The records are as data-model E3 plus `withoutSeasonAverage`, `voided` and `excludedCategories` (amendments S2/S5/B3).
  - Unit values are `GAME`/`PLAYER_WEEK`.
  - Scoring goes through `GameScoringService.score` with `ProjectedCategories.restrict(...)`, where the projected-key set is every key on any snapshot line of that sport-season.
  - No `Map.of` with nullable values.
  - Caveats are fixed strings: the morning-of line; for NBA "one game credited per week, this grades games"; and "Not graded: …" for excluded categories.
- [ ] T020 [US2] Create `backend/src/main/java/com/ballknowers/draftsim/api/ProjectionAccuracyController.java` with `GET /api/leagues/{sleeperId}/projection-accuracy`. Its membership scoping is copied from `LeagueAnalysisController`, and it returns 404 when the league is unknown or not visible.
- [ ] T021 [US2] Add `AccuracySummary accuracy` (nullable) to `LeagueAnalysisService.Projections`:
  - Fill it in both the football and the basketball paths via `accuracyService.summary(league)`, wrapped in try/catch so a failure gives null and never breaks projections.
  - Update the `unavailable(...)` constructor calls to pass null.
  - Then add the same field to `SeasonStartService.SeasonStrength` (`engine/SeasonStartService.java:77`), copied from the `Projections` it is built from at `:145`, `:244` and `:262`.
- [ ] T022 [US2] Update `web/src/api.ts`, field-for-field with the Java records:
  - Add `ProjectionAccuracy`, `AccuracySeries`, `AccuracyCell` and `AccuracySummary`.
  - Add `accuracy: AccuracySummary | null` to `AnalysisProjections` and `SeasonStrength`.
  - Add an api function `projectionAccuracy(sleeperLeagueId)` following the existing fetch helpers' auth headers.
- [ ] T023 [US2] Update `web/src/projectionCopy.ts`: `provenanceLine` takes the accuracy summary, and the `Footnoted` type gains `accuracy`. Its wording per plan.md "Web":
  - MEASURED: `"off by ±X.X pts a game on average (n games)"` for GAME, or `"… a week …"` for PLAYER_WEEK, with the series label for NBA ("season average").
  - TOO_EARLY: `"accuracy: too early to measure (n of N games)"`.
  - null: the current text.
- [ ] T024 [US2] Create `web/src/components/ProjectionAccuracyPanel.tsx`. It fetches the full accuracy, and its table rows are Overall, then by position, then by projected band. The columns are Games · Typical miss · Lean · (DNP in the footer). A cell with null mae shows "too few (n)". The lean is in words. There are tabs for NBA's two series, plus the caveats list. Follow the existing Analysis-page card styles. Before inventing styles, read `web/src/pages/LeagueAnalysis.tsx` and memory feedback on avoiding flat uniform cards.
- [ ] T025 [US2] Mount the panel in `web/src/pages/LeagueAnalysis.tsx` under the projections block, with `id="projection-accuracy"` so the source line can link to it.
- [ ] T026 [US2] Run `cd web && npx tsc -b && npm run build && npx vitest run`, and run the backend tests. Record the results in verification.md.

---

## Phase 5: User Story 3 — Football exact scoring (P2)

**Goal**: football projections stored with stat lines, a parity check with a pre-set tolerance, and exact league scoring behind a flag.

- [ ] T027 [P] [US3] `backend/src/test/java/com/ballknowers/draftsim/engine/ProjectionParityTest.java`, testing the tolerance rule from amendment S6: tolerance = Σ|weight_k| × 0.005 over the keys the row carries, plus 0.005. A row off by less than its tolerance is `withinTolerance`; a row off by more is `beyondTolerance`.
- [ ] T028 [US3] `ProjectionIngestService.toRows` keeps the stats map (minus `adp_*`/`pos_adp_*`). `PlayerProjectionRepository.Row` gains `Map<String,Object> stats` (nullable), and its upsert writes and updates `stats`. Fix every `Row` constructor caller.
- [ ] T029 [US3] Create `engine/ProjectionParityService.java` + `GET /api/ingest/projection-parity?sport=nfl&season=&week=` in `IngestController`, per contracts/parity.md as amended (S6: `withinTolerance` and `beyondTolerance` counts, `scoringSource`, the first 50 beyond-tolerance rows). Full-PPR scoring source: a stored NFL league with `rec = 1.0`, otherwise a constant map of Sleeper's documented default PPR values with a comment citing where they came from.
- [ ] T030 [US3] In `LeagueAnalysisService`'s football points path (around the `ScoringKey.forReceptionPoints` use, `:285-290` and `:500`): when `draftsim.projections.nfl-exact-scoring` is true and a row has stats, value it as `gameScoring.score(league scoring, stats)`. Otherwise use the column pick, row by row. Add a unit test showing both branches. **Leave the flag false.**
- [ ] T031 [US3] On `draftsim_036`, ingest NFL 2026 weeks 7–8 with force, run the parity route for each, and record `withinTolerance`, `beyondTolerance`, `scoringSource` and a diagnosis of the beyond-tolerance rows in verification.md (SC-005). Do not flip the flag. That decision goes back to the parent session.

---

## Phase 6: Polish

- [ ] T032 Amend `claude/competitor-gap-roadmap.md` in place (FR-015) with a status note in Phase 3:
  - 3.2 was already built by spec 030.
  - The "sum a week's NBA games" definition in `claude/projection-tools.md` is wrong under the measured one-game-per-week rule.
  - 3.1 is now spec 036.
  
  Add the matching "amended" note to `claude/projection-tools.md`'s Proposed design §1.
- [ ] T033 Measure the costs and record them in verification.md (amendment N3):
  - `POST /api/refresh/daily` wall-clock on `draftsim_036` with the new steps (state the before/after if obtainable).
  - The `/analysis` response time for the NBA and NFL leagues with and without the accuracy summary.
  - Add the cache only if the summary adds more than 200 ms.
- [ ] T034 Update `README.md` / `HANDOFF.md` only where they list migrations, routes or daily-refresh steps. Keep it to a short entry.

---

## Dependencies

- Setup (T001–T003) → Foundational (T004–T006) → US1 (T007–T013) → US2 (T014–T026). US2 needs the snapshot table and repository. US3 (T027–T031) depends only on Foundational and can run in parallel with US2. Polish runs last.
- US1 is the MVP and the deadline item. Nothing in US2 or US3 may block a US1-only deploy.

## Parallel examples

- After T006: T007 ∥ T008. Then T009 → T010 → T011 ∥ T012.
- US2: T014 ∥ T015 ∥ T016 ∥ T017 (tests), then T018 → T019 → T020 ∥ T021 → T022 → T023 ∥ T024 → T025.
- US3: T027 ∥ T028, then T029 → T030 → T031.

## Implementation strategy

1. T001–T013, then stop and report: this is the US1 deploy candidate.
2. US2, then US3.
3. Verification: the parent session runs the bug-hunting review and the live browser checks. The build pass doesn't claim "verified" for anything it hasn't run.
