# Verification: spec 025

Verified and not-run are kept apart below. Measured numbers are given as measured.

## Environment (T001, 2026-10-10)

- **Launch entries:** `draft-sim-025-api-8096` and `draft-sim-025-web-5197` were added to the main checkout's `.claude/launch.json`. Ports are checked against `netsh … excludedportrange`; neither 8096 nor 5197 is reserved.
- **Web dependencies:** `npm ci` in `draft-sim-025/web`.
- **Postgres:** 5433, the shared dev cluster. 5432 is left alone.
- **Context:** this was built on the day of the 2026 NBA draft. The branch is unmerged and undeployed, and nothing here touches production.

## SC-004 baseline captured before any code (T002b, 2026-10-10) — VERIFIED

- **The test:** `backend/src/test/java/com/ballknowers/draftsim/engine/Spec025SimBaselineTest.java`, with `src/test/resources/spec025-sc004-baseline.json`.
  - A 12-team, 14-round NBA mock of **168 picks** over a 240-player synthetic **multi-position** board, with a fixed `rngSeed` (20260910).
  - The user sits at slot 5 and takes the lowest ADP each time; every other seat is a bot.
  - It compares parsed lists, not bytes.
- **Proven red:** changing one id turned it red, and restoring it turned it green.
- **Gradle wiring:** `-DregenBaseline` is wired into the test JVM in `build.gradle.kts`.
- **Recorded gap:** there is no MANAGER, fitted-profile seat, so manager-tilt code paths aren't exercised by this replay. Bots still run `PickScorer` with roster need over multi-position lineups.

## Phase 2: wire field (T003–T006, 2026-10-10) — VERIFIED

- **Backend suite:** 1548 tests, 0 failed, **0 skipped**. `Spec025SimBaselineTest` passes, so the simulation output is unchanged.
- **`EngineOutputFrozenTest`:** it hashes `SimulationResult.toString()`, so the build agent made it strip `, positions=[…]` before hashing. The **golden SHA is unchanged and still matches**, which is a second, independent proof that the simulation output is byte-identical (SC-004).
- **Web:** tsc clean, vitest 1429/1429.
- **HTTP** (`GET /api/drafts/1339351318128517120/pool?limit=6` on 8096): `Anthony Edwards PG ['PG','SG']`, `Nikola Jokić C ['C']`, `Giannis Antetokounmpo PF ['PF']`. ✓
- **Review note:** `eligiblePositions(p, sport?)` makes `sport` optional. That is the "optional param that encodes a rule" trap, and the US2 build makes it required.

## T016 parity fixture (2026-10-10) — VERIFIED

- **The test:** `NbaLineupParityFixtureTest` generates `web/src/__fixtures__/nba-lineup-parity.json` with **28 cases** from `BasketballRules`, through its public API only. Nothing under `backend/src/main` changed for this task.
- **Spot checks:**
  - The counterexample seats both players: PG/SG at SG, the pure PG at PG.
  - Five pure Cs seat 3.
  - A full roster can join with 0 of 32 masks.
- **Proven red:** swapping one `seating` entry turned it red with the stale-fixture message; restoring it turned it green.
- **Gap found and fixed by the build agent:** Gradle didn't treat the fixture as a test input, so an edited JSON left the test UP-TO-DATE and a stale fixture would never fail. It is now declared with `inputs.files(...)`.
- **Open:** the "auto-draft-like" cases are hand-built. T016 asked for rosters from real auto-finished mocks; those are added in the US3 step from local mock sessions 3721–3724.

## US1 + US4 (T007–T010, T021–T022, 2026-10-10) — unit-VERIFIED; live check pending

- **Proven red, before the change:**
  - `pickRun`: 6 of 15 failed. The real 2025-draft fixture test failed `expected 43 to be 26`, and 43 is the old alphabetical-first count.
  - `scarcity`: 3 of 14 failed (pools, PG/SG drafting, family run marking both rows).
  - `ScarcityMeter`: 4 of 11 failed.
- **After:** all pass, and the file runs are 40/40.
- **Family runs on the real 2025 draft:** **26 of 163 windows, 0 ties**, which matches research A2.
- **Edit-only rule breached:** the build agent appended tests with `cat >>` and changed an import with `sed -i`. The parent checked that both files are intact: the diffs are append-only plus an import, and the tests pass.
- **Open for the US2/US3 side:**
  - The run copy in `PickFeed` (~83) and `AvailabilityPanel` (~593) must use `runLabel`, or NBA will print "were F".
  - In a guards run, both the PG and SG chips show the same run sentence (duplicated text).
  - `.compact-scarcity .scarcity-note` truncates the new disclaimer at 34ch; the full text is in `title`.

## US2 (T011–T015, 2026-10-10) — unit-VERIFIED; live check pending

- **Proven red:** with the old `.position === filter` comparison restored, the SG filter returned **0 of 42** SG-eligible players in the top-108 fixture, in `PlayerPicker`, `OnTheClockPickInput` and `AvailabilityPanel` (tiers and stats). Restored, it returns 42 of 42.
- **Suites:** tsc clean, **1462/1462**.
- **What changed:**
  - `sport` is now required on `eligiblePositions` and `isMultiPosition`.
  - `posRank` gives no rank on any NBA badge ("C", "PG/SG"); football keeps "RB4".
  - Multi-position pills use a hard-split gradient.
  - Compact cells show `familyCode`, with the full label in `title`.
  - A multi-position player gets no `--lead-hue`.
  - `PlayerCard` and `PickInsightCard` get a required `sport`.
- **Edit-only rule breached:** one `node -e` string replace in `positions.ts`. The file is intact and its tests pass.
- **Open, carried into the US3 brief:**
  - `familyCode` keeps a default `sport='nba'`;
  - the NBA "Rank" column in `PlayerPicker`/`OnTheClockPickInput` repeats the pill label;
  - `PickFeed`'s run copy;
  - the duplicated run sentence on the PG and SG chips;
  - the compact-cell name width (A9) and the gradient look are **not yet checked in a browser**.

## US3 (T017–T020) — VERIFIED (build agent's run, parent read the report)

- **Parity:** `lineup.ts` (the TypeScript port of `BasketballRules.prepareLineup` and `tryAssign`) matches the backend on **all 37 parity cases**, 28 synthetic plus 9 real. That covers seated ids, per-slot seating, filled kinds and the can-join answer for all 32 position combinations.
- **Proven red:** with the old two-pass greedy swapped in, **94 of 192** parity tests failed.
- **Real auto-drafted rosters** (local mocks 3721–3724, including 9- and 12-pick prefixes). The old frontend greedy seated fewer starters than the backend in **8 of 10**: **7 or 8 of 9** where the backend seats 9.
- **Backend suite:** 1550 tests, 0 skipped. **Web:** 1662 tests; tsc and build clean.
- **Behaviour change, recorded:** *which* slot a player sits in now follows the backend's seating. Two old `teamNeeds` assertions about exact slot placement were rewritten to check the seated set.

## Live verification (T024, 2026-10-10) — VERIFIED at a 1440×900 viewport

Web 5197 on backend 8096.

1. **SC-001**, `/drafts/1339351318128517120/live` (12 teams, pre-draft): five chips, **PG 38/38 · SG 41/41 · SF 37/37 · PF 45/45 · C 31/31 eligible**, exactly research A10's prediction. Each `title` gives the full sentence, and the starter-pool note adds "a player can count at more than one position". ✓
2. **US2:**
   - The **SG** filter lists 45 rows, including **Anthony Edwards "PG/SG"** and **Devin Booker "PG/SG"**.
   - The **SF** filter lists 38, including **Jayson Tatum "SF/PF"** and **Kevin Durant "SF/PF"**.
   - No NBA badge carries a rank. ✓
3. **US3 / SC-003:** a 12-team NBA mock (3857), auto-finished, reads "**9 of 9 starters**". The same scenario read 8 of 9 under spec 024. ✓
4. **SC-005 (football):** badges keep their ranks ("RB1", "WR2"), and no multi-position pills appear.
   - **Found and fixed by the parent:** the scarcity chips said "RB 1 / 37 **eligible**", plus the multi-position note. The eligibility wording is now NBA-only.
   - The football chips read "RB 1 / 37" again, and the note is the plain definition.
   - Two `ScarcityMeter` tests that asserted the wording on a **football** fixture were rewritten to NBA, and a football test was added asserting its absence (12/12).
5. **Compact cells (A9):**
   - **Found by measuring:** in a 92 px compact cell the **name had 15–19 px, about 2–3 characters, for every player**, single-position included. That is a **spec 024 layout defect** its verification never measured.
   - **Fixed in CSS:** tighter padding and gaps, a badge sized to its text, and round.pick moved to the cell's top-right corner (Sleeper's placement), with the badge and name on the lower line.
   - **Measured after**, using layout widths, since the pane's scaling skews bounding boxes: names get **at least 57 px**, **142 of 168 fully visible** (multi-position 96 of 111), **0 overlaps** between name and round.pick, and the cell height is unchanged at 29 px.
   - **The same CSS must be applied to the 024 branch** if 024 ships without 025.
6. **T023:** `onBrand` stays on the first listed position, with a comment and a `title` on the lean text (research R9).

## SC-004 (T025, 2026-10-10) — VERIFIED

- `git diff --stat 024-draft-room-ux -- backend/src/main/.../{engine,profile,sport}` changes **only `engine/SimulationResult.java`**, the wire record. All of `backend/src/main`: that file plus `api/LeagueController.java` (`fallbackPlayerRef`).
- With `--rerun-tasks`: `Spec025SimBaselineTest` (168-pick fixed-seed replay), `EngineOutputFrozenTest` (golden hash unchanged) and `NbaLineupParityFixtureTest` give **BUILD SUCCESSFUL**.

## Code review (T026) and fixes, 2026-10-10 — VERIFIED

**Review:** [code-review.md](code-review.md) found **1 bug, 4 risks and 8 nits**. It also ran tsc, 1663 web tests and the parity, baseline and frozen-output backend tests.

**B1 BUG, fixed: "Fills X" could name a slot the strip then showed as still open.**
- **Why:** `canJoin` picked a directly-eligible open slot (research A1), while the strip re-runs the seating, which can move the newcomer into a filled slot and shift its occupant elsewhere.
- **How often:** replaying the real mocks 3721–3724, **9 of 32** "Fills" claims were contradicted.
- **Fix:** `canJoin.slot` is now *defined* as the slot that goes from open to filled when the lineup is re-seated with him added. That supersedes A1's heuristic, and it agrees with the strip by construction.
- **Proven red:** 8 of 41 new tests failed against the old `canJoin`. Those include a property test over every fixture case × all 32 masks, and the review's "pure PG + PG/SG" case.
- **A test that asserted the bug:** one existing `teamNeeds` test expected "Fills SG" for that case, which is the bug itself. It now expects "Fills G", the slot that actually fills.
- **Live,** on mock 3858 mid-draft (PG, PG/SG and C on the roster): every list tag (G 22, SF 7, PF 3, UTIL 8) names a slot the team strip shows as open, and no error. ✓

**R4, fixed:** `pickRun`'s private copy of the "NBA WR = no position" rule now calls `eligiblePositions`, so there is one implementation.

**Recorded, not fixed:**
- **R1:** in the completed board's steal/reach view, a multi-position cell's split tint is overridden by the value tint. Inferred from CSS.
- **R2:** football is unchanged only because no *active* NFL player lists two positions. 9 inactive ones do.
- **R3:** the TS lineup accepts any template, while the backend hardcodes nine slots. All local NBA leagues use the nine-slot one.
- **Nits:** feed "UTIL2" vs tag "Fills UTIL"; pills show at most 3 colours; no ADP-tie or no-position fixture cases; the baseline test can't fail on this diff, since no engine code changed.
- **The compact-cell CSS fix changes football too.** That is deliberate: it is the spec 024 layout defect, and it applies to both sports.

## Final suites (T028, 2026-10-10)

- **Web:** tsc clean, **1704/1704** (110 files), production build OK.
- **Backend:** **1550 tests, 0 failed, 0 errors, 0 skipped.**

**Not verified, plainly:**
- a live NBA draft *in progress*;
- the steal/reach view with multi-position cells (R1);
- the spec 024 branch's copy of the compact-cell CSS fix, which is applied in `draft-sim-024`, uncommitted and **unmeasured there**. Its badges still carry ranks ("PG12"), which are wider than 025's.
