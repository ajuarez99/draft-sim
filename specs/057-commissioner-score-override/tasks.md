# Tasks: Commissioner score override (057)

Input: [spec.md](spec.md), [plan.md](plan.md), [plan-review.md](plan-review.md). `[P]` = parallelisable.

## Phase 1 — Schema + resolver (blocks everything)

- [x] T001 `backend/src/main/resources/db/migration/V47__week_score_override.sql` per plan D1.
- [x] T002 `store/WeekScoreOverrideRepository.java`: `forWeek(leagueId, week)`, `replaceWeek(leagueId, week, Map<roster,BigDecimal>, Long enteredBy)` (delete+insert, `@Transactional`), `clearWeek(leagueId, week)`, `byLeagues(ids)`.
- [x] T003 `store/WeekScoreResolver.java` per plan D2; move `plausibleOfficial` + `MAX_OFFICIAL_DRIFT` here from `WeeklyPotService` (unchanged rules, unchanged 25%).
- [x] T004 `WeekScoreResolverTest` (pure): override beats feed (preference), override beats plausible official, official beats feed, implausible official → FEED, empty → identity. Move the pot's `plausibleOfficial` tests here.

## Phase 2 — Apply in the store reads (US1)

- [x] T005 `LeagueMatchupRepository.pairedWithScores` resolves `aPoints`/`bPoints`.
- [x] T006 `RosterWeekPointsRepository.allScores`, `through`, `extremes` (extremes = allScores query + Java sort/limit).
- [x] T007 Fix every test/constructor call site broken by the new constructor arg.
- [x] T008 `SeasonSuperlativesService.weekScoreStandings` input and `WeeklyReportService` team-level totals resolved; per-player ratios keep raw breakdown totals (FR-012 / M1).
- [x] T009 `WeeklyPotService`: drop own overlay, use resolver sources; `ScoreSource` + COMMISSIONER.
- [x] T010 IT against Postgres: an override row makes `pairedWithScores`/`extremes` return the override (the brief's regression test).

## Phase 3 — Endpoint (US1)

- [x] T011 `api/ScoreOverrideController.java` GET/PUT/DELETE per plan D4.
- [x] T011a FR-013 checks in the GET body (totals vs latest snapshot, W-L vs roster_season via recordsThrough).
- [x] T012 Controller IT: 403 non-commissioner, 400 partial / not final / negative / 3 decimals / non-number, save then clear round-trip.

## Phase 4 — Provenance (US2)

- [x] T013 `scoreSources` on superlatives `Result`; `scoreSource` on weekly report `Result`; mirror in `web/src/api.ts` (all three + override types).
- [x] T014 [P] `Superlatives.tsx` note; `WeeklyReport.tsx` note; `WeeklyPot.tsx` COMMISSIONER marker.
- [x] T015 `components/CommissionerScoresPanel.tsx` on the weekly report page (commissioner only), save/clear, refetch + bump data version.
- [x] T016 Vitest for the panel's input parsing (2 decimals, negative refused) and the notes.

## Phase 5 — Verify

- [x] T017 `./gradlew test` (check skipped count), `npx tsc -b && npm run build`, `npx vitest run`.
- [x] T018 Code review → `code-review.md`; live verification → `verification.md` (plan "Acceptance").
