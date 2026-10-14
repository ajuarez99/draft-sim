# Tasks: Trade verdicts from an ownership timeline

**Input**: Design documents in `specs/033-trade-verdicts/`: [spec.md](spec.md), [spec-review.md](spec-review.md),
[plan.md](plan.md), [plan-review.md](plan-review.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/api.md](contracts/api.md), [quickstart.md](quickstart.md)

**Tests**: Required. The plan's testing strategy and AGENTS.md bug classes 1–3 call for a
preference-ordering test, real-row fixtures, and ITs that execute the real SQL and JDBC binds.

**Amended while generating tasks (2026-10-14):** plan-review F5 said "count only weeks ≤
`settings_json.last_scored_leg`". But `engine/ScoredWeeks` (`ScoredWeeks.of(leagueId)` →
`Snapshot.isFinal(week)`) already owns the "which weeks are final" rule, including its
`loaded_complete` and no-fetch-rows cases. Reading `last_scored_leg` again would be a second
implementation of one rule, the bug class memory warns about. The tasks use `ScoredWeeks`, and
the wire's `throughWeek` = `Snapshot.latestFinal()`.

**Conventions for every task:**
- Builds with Java 21 and Spring Boot 3.5.
- The JSON bodies are hand-built `LinkedHashMap`s, never `Map.of` with nullable values.
- `web/src/api.ts` mirrors the controller field for field, in the same task that changes it.
- Nothing is committed (Allan approves commits).

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [X] T001 Create `backend/src/main/resources/db/migration/V32__trade_assets.sql`:
  `alter table league_transaction add column draft_picks jsonb, add column waiver_budget jsonb;`
  Both nullable, no default, with a header comment:
  - "`null` = stored before V32 (unknown); `[]` = none".
  - Verify V31 is still the highest existing version before writing.

---

## Phase 2: Foundational (blocks every story)

- [X] T002 Extend `LeagueTransactionRepository.Row` in
  `backend/src/main/java/com/ballknowers/draftsim/store/LeagueTransactionRepository.java`:
  - Add `String draftPicksJson, String waiverBudgetJson` (nullable).
  - Write them in `upsert`, as `?::jsonb`, and in the `on conflict ... do update set` list.
  - Read them in `forSeason`.
  - Change `forSeason`'s order to `order by created_at nulls last, id`. Lessons #42: stored
    ids aren't chronological (trade 1720 was accepted after 1753), and week is the proposal leg.
  - Update every other constructor call site of `Row`. Grep for `new LeagueTransactionRepository.Row(`.
- [X] T003 Add `List<Integer> tradeWeeksMissingAssets(long leagueId)` to the same repository:
  `select distinct week from league_transaction where league_id = ? and type = 'TRADE' and draft_picks is null`.
- [X] T004 In `backend/src/main/java/com/ballknowers/draftsim/ingest/TransactionIngestService.java`:
  - `toRow`: store Sleeper's `draft_picks` and `waiver_budget` verbatim via `JsonUtil.write`,
    writing `[]` when Sleeper sends null/absent. This applies to **every** transaction type.
  - `ingest`: before the week loop, read `tradeWeeksMissingAssets(league.id())`. Where
    `WeekFinality.mayStopRefetching(...)` would skip a week that's in that set, refetch it
    anyway. That's the R6 self-healing backfill.
  - Add a one-line comment on why: final weeks are otherwise never refetched, and finished
    seasons are skipped by the daily refresh.
- [X] T005 IT `backend/src/test/java/com/ballknowers/draftsim/store/LeagueTransactionAssetsIT.java`
  (`@SpringBootTest`, the `Assumptions` skip pattern of `engine/ScheduleSwapIT.java`):
  - V32 applied;
  - upsert round-trips non-null `draft_picks` / `waiver_budget` jsonb, plus an explicit `[]` and
    a null (bug class 3: run the real bind);
  - `tradeWeeksMissingAssets` executes and returns the week of a null-assets TRADE (bug class 2);
  - `forSeason` returns two rows in `created_at` order when their ids are reversed.
  - Use a throwaway league id and clean up after.
- [X] T006 Unit test in `backend/src/test/java/com/ballknowers/draftsim/ingest/` (extend the
  existing TransactionIngestService test if there is one, else add `TransactionIngestAssetsTest.java`).
  `toRow` on these Sleeper-shaped maps:
  - the real 2026 picks trade: `draft_picks` with 4 entries, `adds`/`drops` null;
  - the real NBA trade 766 shape: `waiver_budget: [{sender:8, receiver:1, amount:67}]`.
  Assert the JSON stored, and that `[]` is written when the keys are absent.

**Checkpoint:** the backend suite is green, with the skipped-test count checked (memory: "BUILD
SUCCESSFUL with ITs skipped" means Postgres was down).

---

## Phase 3: User Story 1, who won this trade (P1) 🎯 MVP

**Goal:** every trade on Roster Management shows a verdict or a stated state, in both sports,
including FAAB-only and picks-only trades (which today aren't shown at all).

**Independent test:** quickstart V1–V6, V8, V10–V12 against the local DB after the backfill.

### Tests for US1 (write first; they must fail before T010–T013)

- [X] T007 [P] [US1] `backend/src/test/java/com/ballknowers/draftsim/engine/OwnershipTimelineTest.java`,
  pure fixtures:
  - (a) **SC-002 preference order:** A gets a player started at 20/week, B one at 5/week →
    WINNER is A, margin is the summed difference. The mirrored fixture → WINNER is B.
  - (b) Bench-only weeks don't count toward `startedPoints`.
  - (c) EVEN when |margin| < 0.05. All-zero started totals → EVEN (the page wording is
    covered in T016).
  - (d) A negative started week (NFL defense, PIT −2.0) lowers the total.
  - (e) A three-side synthetic trade ranks all three sides (FR-008).
  - (f) NO_WEEK_YET when no week is final.
- [X] T008 [P] [US1] In the same test class, **real-row fixtures** copied from the local DB
  (`roster_week_points` / `league_transaction` / `draft_pick` rows written out as literals,
  with the query used in a comment). Expected results come from plan-review.md's executed run:
  - NBA 816: windows start at week 6.
  - NBA 704: windows start at week 2. Week 1 is a duplicate, started by the giver.
  - NBA 1426 → 1804: windows of week 15, then 16–19.
  - NBA 1753 → 1720: 1753's assets are `UNDONE`, and 1720's count from week 16.
  - NFL 2403 / 2428: weeks 5–6, then from week 7.
  - NFL 2464: 4039 → `LEFT_BEFORE_PLAYING`. 5012's week 7 is listed on the receiver
    unstarted, so it's not counted (F4).
  - NBA 766: 2334 → `LEFT_BEFORE_PLAYING`, and the verdict is NOT_COMPARABLE with a FAAB $67
    asset.
  - NBA 2026 picks trade: roster 11's R1/R2/R9 (slot 10) → Şengün / Haliburton / Jabari
    Smith for roster 8; roster 8's R1 (slot 2) → Wembanyama for roster 11; state NO_WEEK_YET.
- [X] T009 [P] [US1] IT `backend/src/test/java/com/ballknowers/draftsim/engine/TradeVerdictServiceIT.java`
  against the local DB's real leagues (skip by `Assumptions` when they're absent). For
  NBA 2025 (`1229352720222134272`):
  - a trade count of 21 once the backfill has run, 18 before (state which the test saw);
  - no trade reads "0–0";
  - one trade's started totals equal a hand-written SQL sum over `roster_week_points` to 0.1
    (SC-004).

  Repeat the SQL-sum check for one NFL 2025 trade (`1254190892974084096`).

### Implementation for US1

- [X] T010 [US1] Create pure `backend/src/main/java/com/ballknowers/draftsim/engine/OwnershipTimeline.java`
  per data-model.md, with no Spring and no DB.
  - **Inputs:** week rows `(week, rosterId, Map<playerKey, points>, Set<starterKey>)`; moves
    ordered by `acceptedAt` then id; drafted picks `(round, slot, managerId, playerKey)`;
    `Map<rosterId, managerId>`; `slotToManager`; the set of final weeks; `seasonComplete`.
  - **R1 attribution:** the starter's roster wins. Otherwise, a single listing. Two listings
    and no starter → duplicate, shown on both, flagged, 0 started, never summed across rosters.
  - **R2 stretches:** maximal consecutive runs, ended by a gap.
  - **R3 windows:** first week W ≥ leg owned by the receiver. Weeks [leg, W) may be only the
    giver or nobody; otherwise there's no window.
  - **Counted weeks:**
    - only final weeks (`ScoredWeeks`, see the header amendment);
    - counting starts at the first week the receiver **started** the asset (F4);
    - leading listed-unstarted weeks stay in the stretch, flagged, excluded from bench points.
  - **Causes, in this order (F2):**
    1. `UNDONE`: a later move returns P to a giving roster;
    2. `LEFT_BEFORE_PLAYING`: a later move takes P off the receiver to anywhere else;
    3. `NO_WEEK_YET`: season not complete, and no final week ≥ leg;
    4. `NEVER_PLAYED_FOR_THEM`: everything else.
  - **Pick resolution (R4):** original roster → manager → slot via `slotToManager` →
    `draft_pick(round, slot)`. The drafter must equal the receiver's manager, else
    `PICK_NOT_MATCHED`. A future season or a draft not complete → `NOT_DRAFTED_YET`.
  - **Verdict rules (data-model.md):**
    1. NOT_COMPARABLE if any side has no PLAYER or resolved-PICK asset;
    2. else NO_WEEK_YET if no counted week;
    3. else EVEN if |margin| < 0.05;
    4. else WINNER.
  - Sides are ranked by started total.
- [X] T011 [US1] Create `backend/src/main/java/com/ballknowers/draftsim/engine/TradeVerdictService.java`
  (`@Service`):
  - Load the inputs for one league-season:
    - `RosterWeekPointsRepository.breakdownsFor`, plus `starters`. Add a repository read if
      `breakdownsFor` lacks it; check before adding.
    - `LeagueTransactionRepository.forSeason`
    - `DraftRepository.forLeague` plus its picks with player sleeper ids. Add one read query
      if none exists.
    - `RosterSeasonRepository.forLeague`
    - `ScoredWeeks.of(leagueId)`
    - `LeagueRow.status` (`complete` → final)
  - Build `OwnershipTimeline`, and return the `TradeResult`s plus `throughWeek`,
    `seasonFinal` and `tradeAssetsLoaded` (false if any TRADE row has null `draft_picks`).
  - Trades are built from **adds, draft_picks and waiver_budget**, so FAAB-only and
    picks-only trades appear (R7).
- [X] T012 [US1] In `backend/src/main/java/com/ballknowers/draftsim/engine/TransactionAnalysisService.java`,
  `forLeague` delegates trades to `TradeVerdictService`. Counts and adds are unchanged.
  - `Result.trades` becomes the new type, and `Result` gains `throughWeek`, `seasonFinal` and
    `tradeAssetsLoaded`.
  - FR-013: a traded player's `postMoveRank` is computed over his R3 window weeks only. The
    adds rule (move week inclusive) stays as it is, comment included.
- [X] T013 [US1] In `backend/src/main/java/com/ballknowers/draftsim/api/RosterManagementController.java`,
  `transactionsBody` emits [contracts/api.md](contracts/api.md) exactly:
  - top-level `seasonFinal`, `tradeAssetsLoaded`, `throughWeek`;
  - each trade's `txId`, `week` (kept), `acceptedAt` (ISO), `assetsLoaded`, `verdict`, and
    ranked `sides` with typed `assets`.
  - Nullable fields per the contract, in `LinkedHashMap`s.
  - `trades[]` ordered by `acceptedAt` descending, then `txId`.
  - In the **same task**, update `web/src/api.ts`: replace `TradeSide` / `LeagueTrade`, add
    `TradeAsset` as a union on `kind`, add `TradeVerdict`, and extend `LeagueTransactions`.
    Field for field, nullability matching.
- [X] T014 [US1] Extend the existing controller/MVC test for `/transactions`, if one exists
  (grep `api/` tests for `transactions`), to assert the new top-level keys and one trade's
  shape. Keep the existing scoping assertions passing.
- [X] T015 [US1] Rewrite the trades section of `web/src/pages/RosterManagement.tsx` (currently
  lines ~226–251):
  - The **accepted date** replaces "Week N", formatted as a US Eastern date, the same
    convention spec 017 uses. Grep for an existing date helper before adding one.
  - A verdict line:
    - WINNER: "{team} +{margin} started points over {weeksCounted} weeks", suffixed "so far"
      unless `seasonFinal`.
    - EVEN: "Even", or "Even: neither side started what it got" when all started totals
      are 0.
    - NO_WEEK_YET: "No week played yet".
    - NOT_COMPARABLE: "Not comparable by points" plus what each side got.
    - Never render "0–0".
  - Per side: team name, started total, bench total.
  - Asset chips:
    - a player (`PlayerLink`), plus a status label for non-COUNTED statuses;
    - a pick: "2026 R1 (from {team}) → {player}", or its status;
    - FAAB: "${amount} FAAB".
  - Notes:
    - one line, "Realized started points only; no projections or trade values." (FR-012);
    - "Picks and FAAB not loaded for this season yet" when `tradeAssetsLoaded` is false.
  - A "Trades" jump link at the top of the page, anchored to this section (R10 default).
  - Styles go in the page's existing stylesheet. Use tinted pills, not flat uniform cards
    (memory: design feedback). Check that 375 px has no horizontal scroll.
- [X] T016 [US1] `web/src/pages/RosterManagement.test.tsx`: add cases for each verdict state's
  wording, the absence of "0–0", the "so far" suffix, the not-loaded note, and FAAB- and
  pick-only trades rendering. Use fixture payloads shaped exactly like the contract.

**Checkpoint:** US1 is complete. Run quickstart §1, the backfill, then V1–V6 and V8–V12 locally,
and the browser pass at 1280/375.

---

## Phase 4: User Story 2, ownership timeline (P2)

**Goal:** the evidence behind each verdict. Each traded asset expands into its whole-season
stretches, plus the league's reconciliation numbers.

**Independent test:** quickstart V7, and the expanded panel for NBA 1426/1804 and NFL 2403/2428.

- [X] T017 [P] [US2] `OwnershipTimelineTest`: reconciliation (R5 as amended by F1):
  - `unexplainedStretches`;
  - `sameWeekInAndOut` (the next move's leg ≤ this move's leg);
  - `neverOnAScoredRoster`, with ≤ 10 examples naming the next move.
  Fixtures: a same-leg add/drop stream, which counts in `sameWeekInAndOut` only, and a waiver
  dropped the next leg (NFL 2483 shape), which counts in `neverOnAScoredRoster`.
- [X] T018 [P] [US2] `TradeVerdictServiceIT`: reconciliation on the three finished seasons.
  Expect `unexplainedStretches` 0/0/0 and `neverOnAScoredRoster` 5/2/1 (NFL 2025 / NBA 2025 /
  NBA 2024). These are the plan-review script's numbers. If the Java gives different numbers,
  **don't adjust the test to match**. Record both in verification.md and find out which is wrong.
- [X] T019 [US2] `OwnershipTimeline`: produce `stretches` per traded player for the whole
  season. Each stretch has `rosterId`, `firstWeek`, `lastWeek`, `how` (DRAFT | TRADE | WAIVER |
  FREE_AGENT | COMMISSIONER | UNEXPLAINED), `viaTxId`, `startedPoints`, `benchPoints`, and
  flags for duplicate and listed-unstarted weeks. Also produce the `Reconciliation` record.
- [X] T020 [US2] Wire `stretches` (on PLAYER assets, including resolved picks' players) and
  top-level `reconciliation` through `TradeVerdictService` → `RosterManagementController` →
  `web/src/api.ts`, per the contract.
- [X] T021 [US2] `web/src/pages/RosterManagement.tsx`:
  - An expandable "How this was counted" panel per trade. It lists each asset's stretches in
    order: team, weeks, how it began, started/bench points. Duplicate and listed-unstarted
    weeks are labelled.
  - The reconciliation numbers go in the section's `HowThisWorks`, as plain counts with one
    sentence each. Expected counts aren't phrased as errors.
  - Tests go in `RosterManagement.test.tsx`.

**Checkpoint:** US2 is complete. Quickstart V7, and expand the two reversal pairs in the
browser.

---

## Phase 5: User Story 3, CSV export (P3)

**Goal:** download the season's trade log.

**Independent test:** quickstart V9.

- [X] T022 [P] [US3] Create `web/src/lib/tradeCsv.ts`, a pure function from
  `LeagueTransactions` to a CSV string.
  - One row per (trade, asset): accepted date, txId, receiving team, giving team(s), asset
    kind, player/pick/FAAB description, status, first week, last week, started points, bench
    points.
  - Header row; RFC 4180 quoting (names contain commas and apostrophes); `\n` line endings.
  - Tests in `web/src/lib/tradeCsv.test.ts`: quoting, FAAB and pick rows, and per-side
    started sums equal to the payload's `startedTotal`.
- [X] T023 [US3] A "Download CSV" button in the trades section of
  `web/src/pages/RosterManagement.tsx`. It uses a Blob + object URL, with filename
  `trades-{sport}-{season}.csv`. It's hidden when there are no trades. Add a test.

**Checkpoint:** quickstart V9. The CSV's per-side totals equal the page's for all NBA 2025
trades.

---

## Phase 6: Polish and verification

- [X] T024 Run both suites: backend `./gradlew test`, checking the skipped count; web
  `npx tsc -b && npm test && npm run build`. Record the counts.
- [X] T025 Measure the `/transactions` response time on NBA 2025, before (main) and after.
  Budget < 300 ms locally (plan). Record the numbers.
- [X] T026 Bug-hunting code review of the full diff. Not a style pass; it runs on the session
  model, not Sonnet.
- [X] T027 Live verification: restart the backend on this worktree's build (check the bootRun
  classpath; memory "worktree preview serves main"), run quickstart §1–§3, and write
  `specs/033-trade-verdicts/verification.md` with each V-item's measured result.
  - *Added after the Phase 2 diff review:* after the backfill, confirm
    `tradeWeeksMissingAssets` is empty for all four leagues. If Sleeper doesn't return a stored
    trade under its stored week, R6 would refetch that week on every ingest forever.
- [X] T028 Update `claude/competitor-gap-roadmap.md` (2.5 status), `README.md` (feature line),
  and `HANDOFF.md` (owed: the four prod `POST /api/ingest/transactions/{id}` calls with
  `X-Admin-Token` after deploy).

---

## Dependencies

- T001 → T002 → T003/T004 → T005/T006 (Phase 2), which blocks everything after.
- **US1:** T007–T009 are written first. T010 → T011 → T012 → T013 → T014; T015 needs T013's
  `api.ts`; T016 follows T015.
- **US2:** needs T010–T013. T017/T018 → T019 → T020 → T021.
- **US3:** needs T013 only (it reads the US1 payload). It can run alongside US2.
- **Phase 6:** after all stories.

## Parallel opportunities

- T005 and T006: different files.
- T007, T008 and T009: different test files or test methods, written before the code.
- T017 and T018 in parallel; T022 alongside T019–T021 (web lib vs backend).
- **Not parallel:** anything touching `RosterManagement.tsx` (T015, T016, T021, T023) runs in
  sequence. One writer per file.

## Implementation strategy

- **MVP = Phases 1–3 (US1).** That alone fixes the missing FAAB/pick trades and shows
  verdicts. Stop and verify there before US2/US3.
- **Build agents run on Sonnet** (AGENTS.md). Suggested split, each one at a time:
  1. Phases 1–2;
  2. US1 backend (T007–T014);
  3. US1 web (T015–T016);
  4. US2;
  5. US3.
- The session reads each diff before reporting it done.
