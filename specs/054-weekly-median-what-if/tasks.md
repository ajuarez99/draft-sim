# Tasks: Weekly median what-if on League history (spec 054)

**Input**: `specs/054-weekly-median-what-if/` — spec.md, plan.md (**its "Amended after review"
section overrides earlier sections**), research.md, data-model.md (same), contracts/league-history-median.md, quickstart.md.

**Tests**: requested by plan.md "Tests" and data-model.md "Invariants (each one a test)".

Paths are relative to the worktree root. Java package root `backend/src/main/java/com/ballknowers/draftsim/`
(abbreviated `B/`), tests `backend/src/test/java/com/ballknowers/draftsim/` (`BT/`).

## Phase 1: Setup

- [X] T001 Baseline before any change: run `cd backend && ./gradlew test --tests '*ExpectedWins*' --tests '*LeagueHistory*'` and `cd web && npx tsc -b && npx vitest run src/pages/LeagueHistory.test.tsx`; record pass/fail/**skip counts** in `specs/054-weekly-median-what-if/build-notes.md` (memory: ITs skip silently when Postgres is down — if skipped, start a scratch Postgres per `claude/environment.md` and re-run; do NOT use the 5432 Postgres or spec 053's 5438 container).

## Phase 2: Foundational (blocks every story)

- [X] T002 In `B/engine/ExpectedWinsService.java`, extract the game-loading loop of `compute()` (paired games for `league.id()`, `p.season() == league.season()`, both points non-null, `ScoredWeeks.isFinal(week)`) into package-visible `List<Game> finalGames(LeagueRepository.LeagueRow league, WeekBound bound)`; `compute()` calls it. No behaviour change.
- [X] T003 In `B/engine/ExpectedWinsService.java`, add package-visible `static Map<Integer, WinLossTie> headToHead(List<Game> games)` (win/loss/tie per game, by `Double.compare`), beside `actualWins`.
- [X] T004 [P] Add `headToHead` cases (win, loss, exact tie) to `BT/engine/ExpectedWinsServiceTest.java`; run the whole `ExpectedWinsServiceTest` and `EngineOutputFrozenTest`, both green, unchanged expectations.

## Phase 3: User Story 1 + 2 — median for every season, and the what-if (P1) 🎯 MVP

US1 and US2 share one wire block and one service; splitting them would build the block twice.

**Independent test**: `GET /api/leagues/{id}/history` returns a `median` block on every season; for a median-off finished season every team has `withMedian == real + median` and median W+L+T == `gamesCounted`.

### Tests first

- [X] T005 [US1][US2] Create `BT/engine/MedianWhatIfServiceTest.java` against the static pure function from T006 (no Spring, no DB). Cases, each asserting exact values:
  - invariant 1 per team: `median.wins + median.losses + median.ties == gamesCounted`;
  - invariant 2: `withMedian == real + median` field by field, `winsGained == median.wins`;
  - invariant 3: `actualPlace` and `whatIfPlace` are each a permutation of `1..teams.size()`;
  - **strict preference ordering**: rosters A and B with equal real records, A higher PF, B more median wins ⇒ A's actualPlace < B's, B's whatIfPlace < A's, movement signs opposite;
  - playoff crossing: with `playoffTeams = 2`, one MADE and one MISSED at the line; none when the line is withheld;
  - odd roster count (5 rosters): median roster ties every week, its `winsGained` counts wins only;
  - `ALREADY_ON` ⇒ `realSource`, `real`, `withMedian`, `winsGained`, `actualPlace`, `whatIfPlace`, `playoffCross` all null, `median`/`allPlay` filled;
  - `OFFICIAL` chosen only when every roster's `gamesCounted == regularSeasonWeeks` AND official `W+L+T == regularSeasonWeeks` AND no null official field AND identical roster sets; each of the four failing ⇒ `COUNTED_GAMES` (four separate cases, no exception thrown);
  - `driftGames` = number of rosters whose `headToHead` ≠ official W-L-T, under `OFFICIAL`;
  - `playoff_week_start < 2` ⇒ status `NO_REGULAR_SEASON_BOUND`, what-if fields null;
  - no games ⇒ `NO_FINAL_WEEKS`, `weeksCounted 0`, `teams []`;
  - withheld codes: divisions ⇒ `DIVISIONS`, seed type ≠ 0 ⇒ `SEED_TYPE`, playoffTeams 0 ⇒ `NO_PLAYOFF_TEAMS`, complete season whose top-`playoffTeams` by actual place ≠ the set with `final_placement ≤ playoffTeams` ⇒ `ORDER_DISAGREES`.
  Confirm it fails (does not compile) before T006.

### Implementation

- [X] T006 [US1][US2] Create `B/engine/MedianWhatIfService.java` (`@Service`). Records (Java, nested): `SeasonMedian(String status, int weeksCounted, int driftGames, Integer regularSeasonWeeks, String realSource, Integer playoffTeams, String playoffLineWithheld, List<SeasonMedianTeam> teams)` and `SeasonMedianTeam(int rosterId, int gamesCounted, WinLossTie median, WinLossTie allPlay, WinLossTie real, WinLossTie withMedian, Integer winsGained, Integer actualPlace, Integer whatIfPlace, String playoffCross)` reusing `ExpectedWinsService.WinLossTie`. Status values exactly `WHAT_IF | ALREADY_ON | NO_FINAL_WEEKS | NO_REGULAR_SEASON_BOUND | UNAVAILABLE`; realSource `OFFICIAL | COUNTED_GAMES | null`; playoffCross `MADE | MISSED | null`; withheld codes `DIVISIONS | SEED_TYPE | NO_PLAYOFF_TEAMS | ORDER_DISAGREES`.
  - Static pure `whatIf(List<Game> games, List<RosterSeasonRepository.StandingRow> official, Optional<LeagueRepository.PlayoffFormat> format, boolean seasonComplete)` holds every rule; the instance method `forSeason(LeagueRow league, List<StandingRow> standings, Optional<PlayoffFormat> format)` only loads `expectedWins.finalGames(league, expectedWins.regularSeasonBound(league))` and calls it.
  - median/allPlay ONLY via `ExpectedWinsService.median` / `allPlay` (FR-002: no second rule).
  - `ALREADY_ON` iff `format.medianMatch()`; `regularSeasonWeeks = playoffWeekStart − 1` when `≥ 2`, else null and status `NO_REGULAR_SEASON_BOUND`.
  - Places: one comparator — win share `(W + 0.5·T) / (W+L+T)` desc, then points for desc, then rosterId asc. PF from official `pointsFor` under `OFFICIAL`, else summed from the counted games.
  - Playoff line only when `format.modelable()`; `ORDER_DISAGREES` check only for `seasonComplete`.
  - Make T005 pass.
- [X] T007 [US1][US2] In `B/api/LeagueHistoryController.java` `history()`: inject `MedianWhatIfService`; per season, pass the `rosterSeasons.forLeague(league.id())` rows already loaded and one `leagues.playoffFormat(league.id())`; `season.put("median", medianBlock(...))` built with `LinkedHashMap` (never `Map.of`: most what-if fields are null). Wrap each season's computation in try/catch(RuntimeException) → `log.warn` + an `UNAVAILABLE` block (`weeksCounted 0`, `teams []`, other fields null) so one season can never fail the response (FR-012). Key always present.
- [X] T008 [P] [US1][US2] Create `BT/api/LeagueHistoryMedianIT.java` (real Postgres, same base/setup style as `BT/api/LeagueHistoryCanCommissionIT.java`): seed a two-season chain — older season median off, complete, all regular weeks stored for every roster; newer season `league_average_match: 1`. Assert: `median` present on both seasons; older `status WHAT_IF`, `realSource OFFICIAL`, invariants 1–2 on every team; newer `ALREADY_ON` with null what-if fields; newer season's `median`/`allPlay` equal `GET /api/leagues/{id}/expected-wins` teams' figures for the same season exactly (SC-004). Report the run's skip count.
- [X] T009 [US1][US2] In `web/src/api.ts`: add `SeasonMedianStatus`, `SeasonMedian`, `SeasonMedianTeam` mirroring T006's records field for field (reuse the existing W-L-T type the expected-wins types use, if one exists, else add `WinLossTie`); `SeasonHistory.median?: SeasonMedian` (optional so an older backend renders dashes). Same change as T006/T007 (constitution: api.ts mirrors Java in the same change).
- [X] T010 [US1] In `web/src/pages/LeagueHistory.tsx`: `StandingsTable` takes the season's `median` block instead of `expected`; "Record vs all" and "Vs weekly median" read `allPlay`/`median` per roster from it (FR-001, FR-014); header span from `weeksCounted` (`N final wks`). Remove the `getExpectedWins` fetch and `expected` state from `LeagueHistory` (only `StandingsTable` used it — plan-review). Missing/`UNAVAILABLE` block ⇒ dashes.
- [X] T011 [US2] In `web/src/pages/LeagueHistory.tsx` + `web/src/styles.css`: for `WHAT_IF` seasons add a column group headed "If median had been on" (tinted group, distinct from real columns — FR-009, design memory: tinted groups not flat cards): **With median** (`W-L`, `-T` only when ties > 0), **Median games** (`+9 W / +5 L`, never wins alone — FR-004). For `ALREADY_ON` seasons, one muted line above the table: "This season already plays a median game every week — it's in the W-L above." No what-if columns. When `realSource === 'COUNTED_GAMES'` name the span; when `driftGames > 0` add the drift sentence from plan amendment #3. Table stays inside `.table-wrap` (no page-level horizontal scroll at phone width).
- [X] T012 [US1][US2] Update `web/src/pages/LeagueHistory.test.tsx`: rewrite the expected-wins tests around `:451-466` against `season.median` (they would otherwise pass for the wrong reason). Add: an **older** season renders its own median and vs-all figures (the bug as reported); WHAT_IF renders With median and `+N W / +M L`; ALREADY_ON renders the already-on line and no what-if columns; missing `median` key renders dashes.

**Checkpoint**: MVP — the reported question ("how many wins would each manager have gained") is answered for every median-off season.

## Phase 4: User Story 3 — would the order have changed? (P2)

**Independent test**: a WHAT_IF season shows actual → what-if place with movement, and MADE/MISSED tags at the line; withheld line shows its sentence.

- [X] T013 [US3] In `web/src/pages/LeagueHistory.tsx`, add **Place** to the what-if group: `actual → what-if` with ▲N / ▼N / "–" movement, and a "would have made playoffs" / "would have missed playoffs" tag from `playoffCross` (in-progress season: "would be in/out" wording). Map `playoffLineWithheld` codes to one sentence per season under the table (`DIVISIONS` "Division seeding isn't modelled…", `SEED_TYPE`, `NO_PLAYOFF_TEAMS`, `ORDER_DISAGREES` "This app's regular-season order doesn't match who actually made the playoffs, so no playoff marker is shown").
- [X] T014 [US3] Add to `web/src/pages/LeagueHistory.test.tsx`: movement sign rendering (up and down), MADE and MISSED tags, a withheld-code sentence.

## Phase 5: Polish

- [X] T015 Rewrite How-this-works in `web/src/pages/LeagueHistory.tsx` (FR-011): replace "not added to the real W-L record" wording, the `:432` header tooltip, and the `:661-662` "Luck page's calculation, one season at a time / any season it did not compute shows a dash" sentences. Explain: combined record, median games (W and L), place and its tie-break ("win share, then points for — this app's ordering, not Sleeper's seeding"), playoffs not replayed, the already-on case, counted-games and drift cases.
- [X] T016 Run the full suites: `cd backend && ./gradlew test` (report skipped count) and `cd web && npx tsc -b && npm run build && npx vitest run`; record results in `specs/054-weekly-median-what-if/build-notes.md`.
- [X] T017 Bug-hunting code review (separate pass, session model) → `specs/054-weekly-median-what-if/code-review.md`; fix findings.
- [X] T018 Live verification per quickstart.md V1–V7 on a scratch DB with the real league ingested; include the stored-score coverage measurement (plan amendment #7) and the 2026 `2 × weeks` / official-vs-recount check (V4) → `specs/054-weekly-median-what-if/verification.md`.

## Dependencies

- T001 → T002 → T003 → T004.
- T005 → T006 → T007 → T008. T009 with T006/T007. T010–T012 after T009.
- US3 (T013–T014) after T011. Polish after all.
- **Parallel**: T004 ‖ T005 (different files); T008 ‖ T009–T012 (backend IT vs web).

## Implementation strategy

MVP = Phases 1–3 (answers the request as asked). Phase 4 adds the standings consequence; Phase 5
is the repo's review + live-verification bar, not optional.
