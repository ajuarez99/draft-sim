# Tasks: Codebase Cleanup — Refactor & Removal (spec 021)

**Input**: Design documents from `specs/021-codebase-cleanup/`. Read the **"Amended
after review"** sections in spec.md, plan.md, research.md, data-model.md and
contracts/. Where they conflict with the text above them, they win.

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/C1–C3,
quickstart.md, plan-review.md

**Tests**: These are **required**, not optional. The spec's acceptance bar is "no
behavior change" (FR-001, FR-006), and the only proof of that is tests written
against the old code before it is changed. Every story phase starts with them.

**Where to work**: all paths are relative to the worktree
`C:\Users\allan\source\draft-sim-021` (branch `021-codebase-cleanup`). **Never** edit
`C:\Users\allan\source\draft-sim`; peer sessions share it. Coding subagents run on
Sonnet (AGENTS.md).

**Commits**: ask before committing (AGENTS.md). Where a task says "own commit", do
not squash it into its neighbours. Reviewers check the commit boundaries.

**Merge freeze**: **nothing merges to `main` from 2026-10-09 through the end of the
2026-10-10 NBA draft**, or while any live draft is polling. Both Railway services
auto-deploy on every push.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 removal (P1) · US2 helpers (P2a) · US3 typed responses (P2b) · US4 splits (P3)

---

## Phase 1: Setup (baseline)

**Purpose**: record what "unchanged" means before anything changes. Every later gate
compares against this.

- [X] T001 Start the local Postgres per AGENTS.md, then run `cd backend && ./gradlew test` and record the totals: tests run, **skipped** and failed. Write them to `specs/021-codebase-cleanup/baseline.md`. If skipped > 0, stop and fix the database first; the P2b/P3 gates need **0 IT skips**.
- [X] T002 [P] Run `cd web && npx vitest run && npx tsc -b && npm run build`. Append to `specs/021-codebase-cleanup/baseline.md` the vitest totals, `ls -la web/dist/assets/*.js *.css` sizes, and `sha256sum web/dist/assets/index-*.css`.
- [X] T003 [P] Record the measured duplicate counts in `specs/021-codebase-cleanup/baseline.md`, so the SC-003 and SC-004 deltas are computed rather than guessed:
  - `grep -rlE "static (double|Double) round[0-9]?\(" backend/src/main/java` (expect 17 files);
  - `grep -rn "function ordinal\|const ordinal" web/src` (expect 5);
  - per-controller `grep -c "new LinkedHashMap\|Map\.of(" backend/src/main/java/com/ballknowers/draftsim/api/*.java` (expect 141 total);
  - the **SC-004 denominator**: 141 minus the error bodies (`grep -cE 'Map\.of\("(error|message)"'` per file; expect 29) minus the SSE builds (the LeagueController heartbeat and the 3 in SimulationController; expect 4), which gives **108 in-scope success-body builds**. Record the measured number. If it isn't 108, use the measured one in the US3 checkpoint.

---

## Phase 2: Foundational

**Purpose**: the shared JSON oracle that US3 and US4 depend on. US1 and US2 do **not**
need it and can start right after Phase 1.

- [X] T004 Create `backend/src/test/java/com/ballknowers/draftsim/api/GoldenJson.java`, a test utility with these parts:
  - `ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build()` (Boot's modules; **never** `new ObjectMapper()`, see research R4 amended);
  - `assertMatchesGolden(Object body, String resourcePath, String... orderSensitivePaths)`, which loads `src/test/resources/golden/<resourcePath>.json`, compares with `JsonNode.equals` (key-order-insensitive, type-strict: `1` ≠ `1.0`, null ≠ absent), then additionally asserts **field order** inside each JSON-pointer path in `orderSensitivePaths` (for dynamic-key maps such as `positionalTilt` and `seedOdds`, research R3 rule 5);
  - a `-Dgolden.write=true` mode that writes the file instead of asserting. It is only for the characterization commit; the conversion commit must never use it.
- [X] T005 Create `backend/src/test/java/com/ballknowers/draftsim/api/GoldenJsonTest.java`. It proves the utility itself catches each failure mode: a reordered object passes; `1` vs `1.0` fails; null vs absent fails; a reordered key inside an order-sensitive path fails; an extra key (`"empty":true` from a record helper method) fails.

**Checkpoint**: the oracle is proven to catch every failure mode the review named.

---

## Phase 3: User Story 1 — Remove what nothing needs (Priority: P1) 🎯 MVP

**Goal**: the dev-only verify page, the publicly served mockup PNGs, the dead
`.verify-*` CSS and the placeholder constitution are gone. Merged worktrees and
branches are pruned after owner confirmation.

**Independent Test**:
- the suites and build match the baseline;
- `web/dist/pr-reference/` does not exist;
- after deploy, the production PNG URL does not return `image/png` (spec AS-1, amended);
- `git worktree list` shows only active work.

### Tests for US1 (written first, against the current code)

- [X] T006 [P] [US1] Create `web/src/pages/PowerRankings.builders.test.ts`. It characterizes the four builders that are imported **only** by the verify page and have no test: `ballotBlockState`, `buildDeck`, `recordLabel` and `roomTakeSentence`, imported from `./PowerRankings`.
  - Build inputs from the fixtures the existing `PowerRankings.*.test.tsx` files use.
  - Assert today's exact outputs, including an empty case and a blocked-ballot case for `ballotBlockState`.
  - This satisfies FR-003 ("converted to an automated test first"). It must pass **before** T008.

### Implementation for US1

- [X] T007 [US1] Write the C1 evidence to `specs/021-codebase-cleanup/removal-evidence.md` for `web/src/pages/PowerRankings.verify.tsx`, its route, `web/public/pr-reference/` and the `.verify-*` CSS rules:
  - grep each basename across `web/src backend/src scripts claude/scripts .github README.md DEPLOY.md HANDOFF.md`, and paste the output;
  - the expected hits are only the items being removed, plus `specs/` and `claude/` design docs, which are history and are left as they are.
- [X] T008 [US1] Delete `web/src/pages/PowerRankings.verify.tsx`. In `web/src/App.tsx`, remove the `import PowerRankingsVerify` line (about :22) and the `{import.meta.env.DEV && (<Route path="/leagues/:sleeperLeagueId/power/verify" …/>)}` block, together with its preceding comment (about :146–150).
- [X] T009 [P] [US1] Delete the directory `web/public/pr-reference/` (two PNGs, 626,914 + 235,172 bytes).
- [X] T010 [P] [US1] Delete every `.verify-*` rule in `web/src/styles.css`. There are 12 rules, starting near :3141. Afterwards confirm that `grep -rn "verify-" web/src` returns no class usages.
- [X] T011 [US1] Leave the four builders from T006 **exported**, because the new test imports them. In the commit message, say that `PowerRankings.tsx` exports are unchanged on purpose (research R1 amended).
- [X] T012 [P] [US1] Replace the placeholder `.specify/memory/constitution.md` with a short constitution:
  - title "Ball Knowers Constitution";
  - a line naming `AGENTS.md` "Hard rules" as the source of truth, with a comment saying to change both together;
  - the **6** hard-rule bullets quoted verbatim from `AGENTS.md`;
  - version 1.0.0, ratified 2026-10-07.

  **Owner decision**: the alternative is to delete the file. Ask before committing this task (research R8 amended).
- [X] T013 [US1] Gate:
  - rerun T001 and T002; tests and skips must equal the baseline;
  - `test ! -e web/dist/pr-reference` must succeed;
  - the CSS hash may change (the `.verify-*` rules are gone), so record the **new** `sha256sum web/dist/assets/index-*.css` in `baseline.md` as the P3 baseline;
  - `git diff --stat origin/main -- backend/src/main/resources/db/migration` must be **empty** (FR-009);
  - smoke check: screenshot power rankings at 1280 px in the browser pane, the only page whose stylesheet lost rules.

  Make one commit for T006–T011 (FR-003 order: the test lands in, or before, the deletion commit).

### Worktree and branch pruning (local only, gated on the owner)

- [X] T014 [US1] Write the **read-only** candidate list to `specs/021-codebase-cleanup/prune-candidates.md`, using the amended rule in research R7.
  - **Worktrees.** For each entry of `git worktree list --porcelain`, include it only if **all** of these hold:
    - it is not `C:/Users/allan/source/draft-sim`;
    - its branch is not `main`;
    - `git merge-base --is-ancestor <HEAD> origin/main` succeeds;
    - `git -C <path> status --porcelain` is empty;
    - the newest of its reflog entries and file mtimes is ≥ 3 days old.

    Show `git -C <path> status --ignored --porcelain` beside each one.
  - **Branches.** Include `git branch --merged origin/main` minus `main`, minus any branch checked out in a remaining worktree.
  - **Orphans.** List the unregistered directory `.claude/worktrees/phase1-normalization` separately, under the heading "orphan-dir".
- [X] T015 [US1] Show the T014 list to the owner in chat and **wait for an explicit yes**. Then, in this order:
  1. `git worktree remove <path>` for each approved worktree (never `--force`);
  2. `git worktree prune`;
  3. `git branch -d <name>` for each approved branch (never `-D`).

  Do not touch the orphan directory without a separate yes. Record what was removed in `prune-candidates.md`.

**Checkpoint**: US1 is shippable as one PR. After it merges and deploys (outside the
freeze):

- [ ] T016 [US1] Run `curl -s -o /dev/null -w "%{http_code} %{content_type}" https://www.ballknowers.co/pr-reference/2a-front-page-desktop.png` and check that the content-type is **not** `image/png`. Expect `200 text/html` from the SPA fallback. Then confirm both Railway services report the merged commit (DEPLOY.md). Record the output in `removal-evidence.md`.

---

## Phase 4: User Story 2 — One rule, one implementation (Priority: P2a)

**Goal**: one rounding implementation in the backend, one `ordinal` in the frontend,
and identical outputs.

**Independent Test**: each helper's agreement test passes over its full input range,
the suites match the baseline, and the grep counts from T003 drop to 1 definition each.

### Tests for US2 (written first)

- [X] T017 [P] [US2] Create `backend/src/test/java/com/ballknowers/draftsim/util/RoundingTest.java`, an agreement test against the old formula `Math.round(x * 10^k) / 10^k` for k ∈ {1,2,3,4}:
  - 100,000 doubles from a fixed-seed `java.util.Random(21)` over [-1e6, 1e6];
  - the explicit edge values 1.005, 2.675, 0.125, -0.5, -1.5, 0.0, -0.0, NaN, ±Infinity and `Double.MAX_VALUE`;
  - `round2OrNull(null)` returns `null`, and `round2OrNull(x)` equals `round2(x)` for non-null x.

  The test references `Rounding`, so it fails to compile until T019. That is expected; commit it with T019.
- [X] T018 [P] [US2] Create `web/src/format.test.ts`, which asserts that the switch algorithm (`draftGrades.ts:87`) and the table algorithm (`ExpectedWins.tsx:274`) agree for n ∈ [0,1000], -5..-1, 1.5, NaN and ±Infinity. Paste both bodies into the test as local functions, so the oracle survives their deletion. Then assert that `format.ts`'s `ordinal` equals the switch algorithm on the same inputs.

### Implementation for US2

- [X] T019 [US2] Create `backend/src/main/java/com/ballknowers/draftsim/util/Rounding.java`, a `final` class with a private constructor and these methods:
  - `round(double v, int places)` computed as `Math.round(v * f) / f` with `f = Math.pow(10, places)`;
  - `round1`, `round2`, `round3` and `round4`;
  - a null-preserving `static Double round2OrNull(Double v)`, needed by `DraftGradesService:519`. It **must have a different name** from `round2`. If it were an overload, the wildcard static import in T020/T021 would route every call that passes a `Double` object to it. Those calls crash on null today and would quietly return null instead, which is a hidden behavior change (analysis finding I1). Only `DraftGradesService` calls `round2OrNull`.

  There must be **no** `BigDecimal`: half-up via `Math.round` exactly matches today's output (research R2).
- [X] T020 [P] [US2] In `api/LeagueController.java`, `api/LeagueHistoryController.java` and `api/ManagerComparisonController.java`, delete the private `roundN` methods and add `import static com.ballknowers.draftsim.util.Rounding.*;`. The call sites are unchanged.
- [X] T021 [P] [US2] Do the same in these `engine/` files: `AbsenceCost`, `DraftGradesService` (its boxed `round2(Double)` call sites become `round2OrNull`), `ExpectedWinsService`, `HeadToHeadService`, `LeagueAnalysisService` (its `round(v, places)` maps to `Rounding.round`), `ManagerCareerService`, `MemberRankingService`, `PlayerTrendsService`, `PlayoffOddsSimulator`, `RosterManagementService`, `SeasonSuperlativesService`, `TransactionAnalysisService` and `WaiverPickupAttribution`. Also do `profile/ProfileService.java` (`round3`). All paths are under `backend/src/main/java/com/ballknowers/draftsim/`.
- [X] T022 [US2] Replace inline rounding **only** where the expression is literally `Math.round(E * 10^k) / 10^k` for a single expression `E`, at ManagerController:162-173, GameScoringService:54, PlayoffOddsService:414, Ranker:54, TransactionAnalysisService:390 and WeeklyReportService:384. **Do not touch `PlayoffOddsService:422`** (`Math.round(v * 1000.0 / total) / 1000.0`): it is not `round3(v/total)`, with 24 measured differences. Add a one-line comment there saying why it stays inline.
- [X] T023 [US2] Create `web/src/format.ts`, exporting `ordinal(n: number): string` with the switch algorithm copied verbatim from `web/src/draftGrades.ts:87`.
- [X] T024 [US2] Point the copies that already agree at `format.ts`:
  - in `web/src/draftGrades.ts` and `web/src/rankOrder.ts`, replace the local function with `export { ordinal } from './format'`, so `PlayerCard.tsx`, `LeagueHome.tsx`, `ManagerHistory.tsx`, `RankBoard.tsx` and `draftGrades.test.ts` keep their imports;
  - in `web/src/pages/ExpectedWins.tsx` (:274) and `web/src/pages/Superlatives.tsx` (:656), delete the local `ordinal` and import it from `../format`.
- [X] T025 [US2] Gate: rerun T001 and T002. Backend and vitest totals must equal the baseline plus the new tests; the built CSS hash must be unchanged; `git diff --stat origin/main -- backend/src/main/resources/db/migration` must be empty; the T003 greps must now show 1 `Rounding` and 1 `ordinal` definition (plus the PowerRankings copy, until T026). One commit for T017–T025.
- [X] T026 [US2] **Own commit, labelled as a fix (spec FR-001 exception).** In `web/src/pages/PowerRankings.tsx:139`, replace `export const ordinal = (n) => …'th'` with `export { ordinal } from '../format'`. This changes "21th" to "21st" for any rank ≥ 21. Grep `web/src` for test expectations of the old strings and update them in this commit. Message: `Fix PowerRankings ordinal: 21th -> 21st (spec 021 FR-001 exception)`.

**Checkpoint**: US2 is shippable on its own. T026 can be reverted alone.

---

## Phase 5: User Story 3 — Responses have a declared shape (Priority: P2b)

**Goal**: success bodies in `api/*.java` become typed records with identical JSON.
Error bodies (`error`/`message`), SSE payloads and controllers outside `api/` stay
maps (spec SC-004, amended).

**Independent Test** (per controller, contract C2 amended):
- the characterization commit is green on the old code;
- the conversion commit is green with **0 edits** under `golden/` or `*Characterization*`;
- **0 IT skips**.

**Depends on**: Phase 2 (T004–T005). It does not depend on US1 or US2.

**Rules for every conversion task below** (research R3 amended; data-model amended):

1. Classify each key by its **final emitted state on every path**. A key the code
   always sets, even to null, is a plain component. Only a key **absent** on some
   path gets `@JsonInclude(NON_NULL)`, and only on that component.
2. **One record per emitted shape**, not one per builder.
3. Polymorphic rows get an explicit `String type` component.
4. Never turn an int or long into a double.
5. Dynamic-key maps stay ordered `Map`s.
6. Response records have no `get*`/`is*` methods beyond their components.
7. Dto files are named by **response family** in
   `backend/src/main/java/com/ballknowers/draftsim/api/dto/`.
8. Update the mirroring type in `web/src/api.ts` in the same commit, or state in the
   commit message that it already matched.

### LeagueHistoryController (43 map builds; 11 are error bodies, which stay)

- [X] T027 [US3] **Characterization commit.** Create `backend/src/test/java/com/ballknowers/draftsim/api/LeagueHistoryControllerCharacterizationIT.java`: a `@SpringBootTest` with MockMvc and `@MockitoBean` services, following `ManagerControllerMvcIT.java`. Cover:
  - **endpoints**: `history`, `managerHistory`, `powerRankings`, `ballot` and the record book;
  - **sports**: NFL and NBA;
  - **`makesPlayoffsPct`**: null on an entry without odds, and present;
  - **`finalRank`**: present-null on a history row, and absent on a career row;
  - **lists**: empty versions;
  - **`positionalTilt`**: order-sensitive, passed to `GoldenJson` as an order-sensitive path.

  Write the goldens under `backend/src/test/resources/golden/league-history/` with `-Dgolden.write=true`, then run it without the flag. In the **same** commit, rewrite the `(Map<String,Object>)` casts in `LeagueHistoryCanCommissionIT.java` and `PowerComputeFinalityTest.java` as `JsonNode` assertions.
- [X] T028 [US3] **Conversion commit.** Create `api/dto/StandingsResponses.java` (the history-row and career-row records, kept separate), `api/dto/ManagerHistoryResponses.java`, `api/dto/PowerRankingResponses.java` (the entry, the snapshot, the member row, and the ballot with its members) and `api/dto/RecordBookResponses.java`, all under `backend/src/main/java/com/ballknowers/draftsim/`. Convert `standingRow`, `withFinalRank`, `recordBook`, `careerRow`, `entryRow`, `memberRow`, `snapshotRow`, `ballotMemberRow` and friends in `api/LeagueHistoryController.java`. Turn the read-backs (`(Long) e.get("managerId")` at :559-561 and `(Integer) entry.get("week")` at :593) into typed fields. `attachPlayoffOdds` produces a new record value with the odds set. The `body(Map.of("message", …))` error bodies stay. Check `web/src/api.ts` for history, power rankings and ballot.
- [X] T029 [US3] Gate the controller:
  - T027's test is green;
  - `git show --stat HEAD` lists nothing under `golden/` or `*Characterization*`;
  - the full backend suite has **0 skipped**;
  - `grep -c "new LinkedHashMap\|Map\.of(" api/LeagueHistoryController.java` is ≤ 11 (the error bodies only);
  - `git diff --stat origin/main -- backend/src/main/resources/db/migration` is empty (FR-009; it applies to every US3/US4 gate below, too).

### SuperlativesController (17 map builds; 4 are error bodies)

- [X] T030 [US3] **Characterization commit.** Create `.../api/SuperlativesControllerCharacterizationIT.java` (MockMvc with stubbed `SeasonSuperlativesService` and repositories). Cover each of the 8 `detailRow` subtypes with its `"type"` value, the full-standings body and the conduct-list body, plus empty cases. Write the goldens to `golden/superlatives/`. In the same commit, rewrite the casts in `SuperlativesControllerIT.java`, `SuperlativesStandingsIT.java` and `SuperlativesControllerConductListBatchTest.java` as `JsonNode` assertions.
- [X] T031 [US3] **Conversion commit.** Create `api/dto/SuperlativeResponses.java` with one record per detail subtype, each carrying an explicit `String type` component (use `@JsonTypeInfo` only if T030's goldens pass unchanged). Convert `body`, `detailRow` and `conductListBody` in `api/SuperlativesController.java`. Error bodies stay. Check the `SuperlativeDetail` union in `web/src/api.ts`, including `'rosterId' in d` at `Superlatives.tsx:433`: its presence or absence must be preserved.
- [X] T032 [US3] Gate as in T029: the map count must be ≤ 4.

### LeagueController (17 map builds; 6 are error bodies; the SSE heartbeat stays)

- [X] T033 [US3] **Characterization commit.** Create `.../api/LeagueControllerCharacterizationIT.java` (MockMvc with stubbed `profiles`, membership and reversal lookups). Cover `seats()` (owner configured and owner unset), the real board, track and the pool endpoints, plus empty cases. Write the goldens to `golden/league/`. In the same commit, rewrite the casts in `LeagueControllerSeatsOwnerConfiguredIT.java`, `LeagueControllerSeatsUnsetOwnerIT.java`, `LeagueControllerRealBoardTest.java`, `LeagueControllerTrackTest.java` and `LeagueAnalyticsContractTest.java` (if it touches this controller's body) as `JsonNode` assertions.
- [X] T034 [US3] **Conversion commit.** Create `api/dto/LeagueResponses.java` (seats, board, track, pool). Convert the success bodies in `api/LeagueController.java`. The heartbeat at :565 and the error bodies stay as maps. Check the corresponding `web/src/api.ts` types.
- [X] T035 [US3] Gate as in T029: the map count must be ≤ 6 error bodies, plus 1 SSE heartbeat.

**Checkpoint — SC-004**: **55 of 108** in-scope success-body map builds are gone
(50.9%; the denominator is from T003), and the three worst controllers' success bodies
are at 0. The breakdown is LHC 32 (43 − 11 error), SC 13 (17 − 4 error) and LC 10
(17 − 6 error − 1 heartbeat). This clears the bar by **one** build, so recount after
T035, and if the measured count falls short, do T036 (WeeklyReport, 9) before
stopping. P2b can stop here.

### Remaining `api/` controllers (optional within US3; each is its own char → conv pair)

These already expose a static seam, so a unit-level characterization test is enough.
It uses `GoldenJson.MAPPER`, has no Spring context, and follows `WeeklyReportShapeTest`.

- [X] T036 [P] [US3] `WeeklyReportController` (9): first rewrite the `.get()`/`.containsKey()` assertions in `WeeklyReportShapeTest.java` as `GoldenJson` goldens in `golden/weekly-report/`, then convert to `api/dto/WeeklyReportResponses.java`. The record **must not** rely on `@JsonInclude` on the service record; the shape test's header documents why.
- [ ] T037 [P] [US3] `PlayerSpotlightController` (7): rewrite `PlayerSpotlightShapeTest.java`, and the casts in `PlayerSpotlightTopOfNightIT.java` and `PlayerSpotlightWeekIT.java`, as goldens; then convert to `api/dto/PlayerSpotlightResponses.java`.
- [ ] T038 [P] [US3] `SeasonForecastController` (4): rewrite `SeasonForecastShapeTest.java` as goldens, with `seedOdds` as an **order-sensitive** path; then convert to `api/dto/SeasonForecastResponses.java`, keeping `seedOdds` a `LinkedHashMap`.
- [ ] T039 [P] [US3] `RosterManagementController` (8): char → conv commit pair following C2; dto `api/dto/RosterManagementResponses.java`.
- [ ] T040 [P] [US3] `ManagerComparisonController` (7): char → conv commit pair following C2; dto `api/dto/ManagerComparisonResponses.java`.
- [ ] T041 [P] [US3] `ManagerController` (6): char → conv commit pair following C2, with `positionalTilt` passed to `GoldenJson` as an **order-sensitive** path (it renders unsorted at `ManagerHistory.tsx:292`); dto `api/dto/ManagerResponses.java`.
- [ ] T042 [P] [US3] `ExpectedWinsController` (4): char → conv commit pair following C2; dto `api/dto/ExpectedWinsResponses.java`.
- [ ] T043 [US3] Out of scope, and **do not convert**: `ErrorHandler`, `MemberSetupController` (3 of 4 are error bodies), `MockDraftController`, `SimulationController` (SSE), `IngestController`, `SleeperUserController`, `HealthController`, and everything in `recap/` and `refresh/`. Record this list in the US3 PR description. **Also not planned:** the `engine/` package split listed in the spec's Split table. It was only ever "opportunistic", and the review moved the superlatives units into `engine` itself (T051), which leaves it nothing to piggyback on.

---

## Phase 6: User Story 4 — Large files split along their seams (Priority: P3)

**Goal**: no source file over 1,000 lines (SC-005), with no behavior change.
**One file per PR.** Each split must be its own merge (contract C3 amended).

**Independent Test**: each split's gate below.

### `web/src/styles.css` (5,568 lines). Depends on T013's CSS hash

- [ ] T044 [P] [US4] Cut `web/src/styles.css` into contiguous ranges along its existing section comments, **without reordering**: `web/src/styles/tokens.css`, `shell.css`, then one file per page section in source order. Make `web/src/styles.css` an index of `@import './styles/<file>.css';` lines in the original order, with the Google Fonts `@import url(...)` kept as **line 1**. `main.tsx`'s import is unchanged.
- [ ] T045 [US4] In the same commit as T044, update `web/src/useNarrow.test.ts:10` to read and concatenate `src/styles/*.css` in index order, instead of `fs.readFileSync('src/styles.css')`. Its assertions are unchanged.
- [ ] T046 [US4] Gate: `npm run build`, then `sha256sum web/dist/assets/index-*.css` must be **byte-identical** to the hash recorded in T013. As a smoke check, screenshot `/`, a league home, power rankings, superlatives and the draft board at 1280 and 375 px in the browser pane (hard-refresh after any dev-server restart).

### `web/src/api.ts` (2,568 lines, 108 importers)

- [ ] T047 [P] [US4] Move the types and fetchers into `web/src/api/<domain>.ts` files, **cut along the `// --- <source doc>: <title> ---` section markers already in `api.ts`** (16 markers, :336 to :2415, measured):
  - `core.ts` gets everything above :336: `API_BASE`, `API_TOKEN`, the fetch helpers, error reading, and the draft, board and sim types;
  - each marker's section becomes one file named after its title, e.g. `identity.ts` (:336), `leagueHistory.ts` (:775), `managerComparison.ts` (:1167), `leagueAnalysis.ts` (:1361), `ballots.ts` (:1667), …, `transactions.ts` (:2415);
  - the two superlatives sections (:2122, :2355) both go to `superlatives.ts`;
  - the unmarked comment at :541 belongs to the identity section.

  Keep the original order inside each file. `web/src/api.ts` becomes only `export * from './api/<domain>'` lines. **No file under `web/src/api/` imports `'../api'`**; they import each other directly, which avoids a TDZ cycle with `ALL_POWER_RANKING_KINDS`.
- [ ] T048 [US4] In the same PR, update the "mirror `web/src/api.ts`" references to point at the right `web/src/api/<domain>.ts`: `AGENTS.md:124`, `backend/src/main/java/com/ballknowers/draftsim/engine/SeasonSuperlativesService.java:112` and `:1205`, and `backend/src/main/java/com/ballknowers/draftsim/recap/RecapView.java:16`. Before editing, re-grep `grep -rn "api\.ts" AGENTS.md backend/src web/src` so that no reference is missed; the line numbers above may have drifted.
- [ ] T049 [US4] Gate:
  - `npx tsc -b` passes;
  - `git diff --stat` shows **0** changed importers outside `web/src/api*`;
  - all 26 `vi.mock('../api')` test files pass;
  - the built JS total is within ±1% of the baseline.

### `engine/SeasonSuperlativesService.java` (1,586 lines)

- [ ] T050 [US4] **Before the split, as its own commit.** Create `backend/src/test/java/com/ballknowers/draftsim/engine/SeasonSuperlativesServiceGoldenTest.java`, with stubbed repositories feeding fixed NFL and NBA season data and the `Result` serialized through `GoldenJson` to `golden/superlatives-service/`. This covers all 13 kinds, plus the "empty when best ≤ 0" cases for Waiver and Embiid.
- [ ] T051 [US4] Extract each superlative kind into its own package-private class **in package `engine`** (not a subpackage), e.g. `engine/SuperlativeWaiverKing.java`. The 22 package-private statics that tests call (64 call sites across 10 test classes) stay callable as `SeasonSuperlativesService.x(...)`, as forwarding statics where moved. Gate: T050 is green and unedited, and the existing tests compile untouched.

### `web/src/pages/PowerRankings.tsx` (1,575 lines)

- [ ] T052 [P] [US4] Move **every exported non-component** from `web/src/pages/PowerRankings.tsx` (list them first with `grep -nE "^export (function|const)" web/src/pages/PowerRankings.tsx` and exclude anything returning JSX; at least `buildHeadline`, `computeWeeklyStory`, `buildDeck`, `ballotBlockState`, `ballotsCountedIn`, `recordLabel` and `roomTakeSentence`), plus the private helpers only they use, to `web/src/pages/powerRankingsStory.ts`, re-exported from `PowerRankings.tsx`, so `LeagueHome.tsx` and the tests (`PowerRankings.*.test.*` and T006's builders test) keep their imports. Gate: those tests pass unedited, and power rankings has a screenshot smoke check.

### `web/src/pages/LeagueAnalysis.tsx` (1,346 lines)

- [ ] T053 [P] [US4] Extract one component per tab into `web/src/components/leagueAnalysis/<Tab>.tsx`; `LeagueAnalysis.tsx` keeps routing and state. Gate: `LeagueAnalysis.test.tsx` passes unedited except for import paths, and each tab has a screenshot smoke check.

### `api/LeagueHistoryController.java` (1,022 lines). **Depends on T028–T029**

- [ ] T054 [US4] Add a route-table test, `.../api/RouteTableTest.java`. It lists `RequestMappingHandlerMapping.getHandlerMethods()` paths and methods into `golden/route-table.json`, commits them **before** the split, and must stay unedited.
- [ ] T055 [US4] Split it into `LeagueHistoryController` (history and record book), `ManagerHistoryController` (manager history and career) and `PowerRankingsController` (power, ballot, submit, compute, commissioner, backfill), using T028's response-family dtos, so no record is duplicated. Gate: T054 and T027 are green and unedited, with 0 IT skips.

---

## Phase 7: Polish & cross-cutting

- [ ] T056 [P] Optional (spec "keep" candidates). Add a status index to `claude/README.md` marking each of the 53 `claude/*.md` docs shipped, idea or superseded, without deleting any. Separately, add a one-line purpose comment to each file in `scripts/`. Neither changes behavior.
- [ ] T057 Run [quickstart.md](quickstart.md) end to end against the final tree: the baseline commands, the zero-skip check, the CSS hash and the T016 production check. This counts as live verification only if a real `bootRun` and `vite dev` are clicked through in the browser pane: league home, power rankings, superlatives, manager history and draft board. That's this repo's bar; a green suite alone is not.
- [ ] T058 Update `HANDOFF.md` with what spec 021 shipped, what was scoped out (error bodies, SSE, `PlayoffOddsService:422`, `recap/` and `refresh/`) and what is **verified vs assumed**. Update the matching memory file.
- [ ] T059 Write `specs/021-codebase-cleanup/pr-description.md` per PR (US1, US2, US3 batches, and each US4 split). Each one lists the C1, C2 or C3 evidence and ends with the attribution line.

---

## Dependencies & execution order

```text
Phase 1 (baseline) ──┬──> US1 (T006–T016)          ──> merge #1 (after freeze)
                     ├──> US2 (T017–T026)          ──> merge #2
                     └──> Phase 2 (T004–T005) ──┬──> US3 LHC (T027–T029) ──> US4 LHC split (T054–T055)
                                                ├──> US3 SC (T030–T032)
                                                ├──> US3 LC (T033–T035)  ──> merge #3 (batched)
                                                ├──> US3 rest (T036–T042, optional)
                                                └──> US4 superlatives (T050–T051)
US1 T013 (CSS hash) ──> US4 styles (T044–T046)
US4 api.ts, PowerRankings, LeagueAnalysis: after Phase 1 only
```

- US1 and US2 are independent of each other and of Phase 2.
- Within US3, the three controllers are independent. Conversions edit different
  files, but **merge them as one batch** (deploy concern, plan amended).
- The LHC split (T055) **must** follow its conversion (T028); this is fixed by the
  review (finding 14).
- The styles split (T044) needs T013's post-removal CSS hash as its oracle.
- **T026 before T052.** Both edit `web/src/pages/PowerRankings.tsx`: T026 the ordinal
  at :139, T052 the builder move. Merge the T026 fix first, so the moved builders pick
  up the shared `ordinal` (analysis finding F2).

## Parallel opportunities

- **Phase 1**: T002 ∥ T003.
- **US1**: T006 ∥ T009 ∥ T010 ∥ T012; T008 waits for T006 to be green.
- **US2**: T017 ∥ T018; then T020 ∥ T021 (different files); T023–T024 on the frontend ∥ T019–T022 on the backend.
- **US3**: the LHC, SC and LC char/conv pairs are separate files, so a different agent can take each. T036 ∥ T037 ∥ T038.
- **US4**: T044, T047, T052 and T053 touch disjoint files. Still, each is its own PR, and none should run while a peer session edits the same file.

## Implementation strategy

1. **MVP = US1.** It is a small diff that removes about 860 KB of publicly served
   mockups and 379 lines of harness. Ship it first, after the freeze.
2. **US2** next: mechanical, with exhaustive agreement tests. T026 is the only visible
   change, and it is isolated in its own commit.
3. **US3** stops at the SC-004 checkpoint (the three worst controllers). T036–T042
   happen only if the first three went cleanly.
4. **US4**, one file per PR, CSS and `api.ts` first (pure moves, byte-level gates).
   The LHC split goes last.

Each phase ends at a checkpoint that leaves the product working and unchanged.
