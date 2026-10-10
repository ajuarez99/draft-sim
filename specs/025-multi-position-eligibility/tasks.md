---
description: "Task list for spec 025: Multi-position eligibility for basketball players"
---

# Tasks: Multi-position eligibility for basketball players

**Amended after plan review (2026-10-09).** [plan-review.md](plan-review.md) found 1 blocker and 10 should-fixes, and research.md §Amendments **A1–A10 supersede** the plain text of any task below. The changed tasks are T009, T013, T014, T016, T017, T019, T021, T022 and T023, plus a new task, T002b.

**Input**: `specs/025-multi-position-eligibility/`: [plan.md](plan.md), [spec.md](spec.md) (clarified FR-002 = C, FR-004 = A), [research.md](research.md) R1–R8, [data-model.md](data-model.md), [contracts/api.md](contracts/api.md), [quickstart.md](quickstart.md).

**Tests**: included, per the repo bar. Each choice rule gets a preference or parity test that is **shown to fail** under the old rule (lessons #36).

**Working tree**: `C:\Users\allan\source\draft-sim-025`, branch `025-multi-position-eligibility` (from `024-draft-room-ux`). Coding tasks run on **Sonnet** and edit **only with Read + Edit** (lessons #37). The parent reads every diff. Ask before committing.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

- [X] T001 Add launch entries to the **main checkout's** `C:\Users\allan\source\draft-sim\.claude\launch.json`: `draft-sim-025-api-8096` (`SERVER_PORT=8096`, `CORS_ORIGINS=http://localhost:5197`, `cd /d …\draft-sim-025\backend`) and `draft-sim-025-web-5197` (`DRAFTSIM_PROXY_TARGET=http://localhost:8096`). First confirm with `netsh interface ipv4 show excludedportrange protocol=tcp` that neither port is reserved. Run `npm ci` in `web/`. Record what was done in `specs/025-multi-position-eligibility/verification.md`.
- [X] T002 Run an adversarial review of `plan.md` and `research.md` before any code, on the session model. Attack the "Risks for the adversarial review" list and check every R-claim against the code. Write `specs/025-multi-position-eligibility/plan-review.md`, and amend the docs visibly ("amended after review").

- [X] T002b **Capture the SC-004 baseline before any code** (review #16), at `f638445` on the 025 branch.
  - In a backend test, run `MockDraftEngine.advanceUntilUserOrEnd` for a fixed NBA `LeagueShape` (12 teams), with a fixed seat list and a fixed `rngSeed`.
  - Write the resulting bot pick sequence (pickNo → player id) to `backend/src/test/resources/spec025-sc004-baseline.json`, and commit the test so it asserts the replay equals that file.
  - T025 re-runs this test after the change.

---

## Phase 2: Foundational (blocks every story)

- [X] T003 In `backend/src/main/java/com/ballknowers/draftsim/engine/SimulationResult.java`, add `List<String> positions` to `record PlayerRef`, right after `position`. It must be **never null**: an empty list when a player has no stored positions.
  - `PlayerRef.from(BoardEntry e)` fills it with `e.player().positions().stream().map(Enum::name).toList()`.
  - Update the record Javadoc: `position` keeps its alphabetical-first meaning; `positions` is the full eligible list in stored (Sleeper, alphabetical) order.
  - Fix every call the compiler flags. The research found only `LeagueController.fallbackPlayerRef` (`backend/src/main/java/com/ballknowers/draftsim/api/LeagueController.java` ~line 333); fill it from `p.positions()`.
  - **Do not touch** `engine/` logic, `profile/`, `sport/`, `BoardEntry` or `BoardService`. This is a wire-only change (FR-009).
- [X] T004 In `web/src/api.ts`, mirror the new field on `PlayerRef` in the same change as T003: `positions?: string[]`. Comment it: optional because of the split deploy; full eligible list in Sleeper's alphabetical order; never treat `position` as primary in draft-room labels (spec 025 FR-002).
- [X] T005 [P] In `web/src/positions.ts`, add:
  - `eligiblePositions(p: Pick<PlayerRef,'position'|'positions'>): string[]`, which returns `p.positions` when present and non-empty, else `[p.position]`. This is **the only fallback rule**; comment it as such.
  - `positionLabel(p, sport): string`. For `nba`, the eligible positions sorted in the fixed order `PG, SG, SF, PF, C` (unknown values last) and joined with "/". For `nfl`, stored order joined with "/".
  - `isMultiPosition(p)`.

  Write `web/src/positions.eligibility.test.ts`:
  - Edwards `positions: ['PG','SG']` → "PG/SG";
  - Durant `['PF','SF']` → "SF/PF";
  - LeBron `['PF','PG','SF']` → "PG/SF/PF";
  - `positions` missing → `[position]`;
  - `positions: []` → `[position]`;
  - football `['RB']` → "RB".
- [X] T006 Run the backend suite and `npx tsc -b`. Curl `GET /api/drafts/1339351318128517120/pool?limit=5` on 8096 and confirm each player has `positions`. Record the output in verification.md.

**Checkpoint**: the field is on the wire and the helpers exist, with no visible change yet.

---

## Phase 3: User Story 1 — Scarcity by eligibility (P1) 🎯 MVP

**Goal**: all five NBA chips are real, counted by eligibility and labelled "eligible" (FR-004 = A).

**Independent test**: quickstart live check 1.

- [X] T007 [P] [US1] Extend `web/src/scarcity.test.ts`:
  - **Fixture:** the measured top-36 shapes as `positions` arrays.
  - **Pools:** pools are PG 18, SG 8, SF 7, PF 17, C 11.
  - **Drafting:** drafting a PG/SG lowers **both** PG-left and SG-left by 1.
  - **Fallback:** a player with no `positions` counts only toward `position`.
  - **Proven red:** the test **fails under the old `p.position === position` rule.** Run it against the unmodified function first and record the failure.
- [X] T008 [US1] In `web/src/scarcity.ts`, change pool and left counting (~line 80) from `p.position === position` to `eligiblePositions(p).includes(position)`. Update the module's header comment to explain FR-004 = A and why totals can exceed the starter count.
- [X] T009 [US1] *(A10, review #11: the disclaimer must also reach the run copy in `AvailabilityPanel` ~593 and `CompactRow`)* In `web/src/components/ScarcityMeter.tsx`:
  - The chip text says "eligible", e.g. "SG 8 eligible / 8". Use whatever compact form fits the chip, and keep the full sentence in `title`.
  - The starter-pool note adds "a player can count at more than one position".
  - The 0-pool note from spec 024 now reads "{positions}: no starter-pool players are eligible here".
  - Update `ScarcityMeter.test.tsx`.
- [X] T010 [US1] In `web/src/pages/LiveDraftView.tsx` (~lines 664-671), the pick card's scarcity line looks up the row for `pick.player.position`. Change it to pick the scarcest row among `eligiblePositions(pick.player)`: the lowest `leftNow`, with ties going to a running position. Its text names that position.

---

## Phase 4: User Story 2 — Filters and labels (P1)

**Goal**: the SG filter finds every SG-eligible player; multi-position pills read "PG/SG" with no rank (research R3).

**Independent test**: quickstart live check 2, and SC-002 recall.

- [X] T011 [P] [US2] Add filter tests in `web/src/components/AvailabilityPanel.test.tsx` and a new `web/src/components/PlayerPicker.filter.test.tsx`:
  - a PG/SG player appears under PG **and** SG;
  - ALL lists him once.
  - **SC-002 recall:** for a fixture of the measured top-108 shapes, the SG filter returns 100% of SG-eligible players.
  - Show it red under the old `.position === filter` comparison.
- [X] T012 [US2] Replace every draft-room filter comparison with `eligiblePositions(p).includes(filter)`:
  - `web/src/components/AvailabilityPanel.tsx` lines ~341 and ~439;
  - `web/src/components/OnTheClockPickInput.tsx` ~54;
  - `web/src/components/PlayerPicker.tsx` ~66;
  - `web/src/components/DraftStatsTable.tsx`, wherever its position filter is applied.
- [X] T013 [US2] *(A3, A8)* In `web/src/posRank.ts`, `posRank` and `posRankOrAdp` return `positionLabel(p, sport)` with **no rank number for every NBA player**, not just multi-position ones (Banchero "PF4" is 9th among PF-eligible players). Football is unchanged ("RB4").
  - Thread `sport` into `PlayerCard` and `PickInsightCard`, which don't receive it today.
  - `CompletedDraftBoard` (`/drafts/:id/board`) picks up the same labels through `DraftBoard`. That is accepted as in scope; check its tests.
  - Callers must pass `sport`. Make it a **required** parameter (no default; feedback memory on optional params that encode rules), and fix every caller.
  - Add tests: Edwards → "PG/SG"; Jokić `['C']` rank 2 → **"C"** (NBA has no rank); football RB rank 4 → "RB4"; a 999 rank stays the bare label.
- [X] T014 [US2] *(A7, A9)* Pills.
  - **Compact board cells** (`DraftBoard` with `density==='compact'`) render a **family code** for multi-position NBA players: PG/SG → "G", SF/PF → "F", C/PF → "F/C", spanning G and F → "G/F". `title` gives the full positions. Put a `familyCode(p)` helper in `positions.ts`, with tests.
  - **Colour:** the board-cell position tint and the pick feed's `--lead-hue` must not use the first position's colour for a multi-position player. Use the split gradient, or a neutral colour where a gradient can't apply.
  - **Then, as before:** Everywhere a draft-room pill renders `className={\`pos ${x.position}\`}` with the position as text, render `positionLabel` (or `posRank`) as the text instead. The class for a multi-position player becomes `pos multi`, with a `--pos-a`/`--pos-b`/`--pos-c` CSS custom-property set to each position's existing colour variable. Files:
  - `AvailabilityPanel.tsx` ~689;
  - `DraftBoard.tsx` (~251/267/300/311/321; read each);
  - `OnTheClockPickInput.tsx` ~83;
  - `PickFeed.tsx` ~83-122;
  - `PickInsightCard.tsx` ~68/139;
  - `PlayerCard.tsx` ~27/48/58/129;
  - `TargetStrip.tsx` ~108;
  - `TeamStrip.tsx` ~34-35.

  `DraftStatsTable.tsx` ~108 passes `position` to `PlayerFace` for its fallback avatar only. Leave that one.
- [X] T015 [US2] In `web/src/styles.css`, add the `.pos.multi` style: a split background as a linear-gradient across `var(--pos-a)`, `var(--pos-b)` and optional `var(--pos-c)`, with a slightly narrower font or letter-spacing so "SG/SF/PF" fits the compact board cell's pill. Keep the dark style.

---

## Phase 5: User Story 3 — One lineup rule (P2)

**Goal**: "Your team" and "fills a need" equal `BasketballRules`' answers on every parity case (FR-005, SC-003).

**Independent test**: the parity tests, plus quickstart live check 3 (an auto-finished mock reads 9 of 9).

- [X] T016 [US3] *(A4, A5: these supersede the accessor and byte-comparison text below)* Create `backend/src/test/java/com/ballknowers/draftsim/sport/NbaLineupParityFixtureTest.java`.
  - **No change to `BasketballRules`.** Derive `canJoinByMask` through the public API: `rosterNeed(probe, prepareLineup(...)) > benchFloor`, for a probe `BoardEntry` with each of the 32 masks and a value below every real player's.
  - **Compare as parsed JSON, not bytes** (`core.autocrlf=true`).
  - **Also record per-slot seating** (template order: slot → player id) from `startingLineup`.
  - **Cases.** Build at least 20 NBA rosters as `BoardEntry` lists with real `Position` lists and ADPs:
    - every top-108 shape;
    - the counterexample (a PG/SG drafted first, then a pure PG; expected both seated);
    - a full 14-player roster;
    - a 10th pick with nine starters filled;
    - a roster with no SG-eligible players;
    - rosters taken from an auto-finished mock's user picks. Hardcode them; don't query the DB.
  - **Expected values.** For each case, run `BasketballRules.prepareLineup`/`startingLineup` with `BasketballRules::value` and record:
    - the seated player ids;
    - filled slot kinds with counts;
    - `canJoinByMask[32]`, from `rosterNeed`'s join test. If `maskCanJoin` isn't reachable from the test, add a **package-private** accessor in `BasketballRules` without changing behaviour.
  - **Fixture.** Serialize as `web/src/__fixtures__/nba-lineup-parity.json`, per data-model.md, with stable key order.
  - **Behaviour.** If the system property `regenFixture=true`, write the file. Otherwise **assert the committed file equals** the generated JSON, failing with "run with -DregenFixture=true to regenerate".
  - Wire the property in `backend/build.gradle.kts` `tasks.test { systemProperty(...) }` only if needed.
  - Generate the file once.
- [X] T017 [US3] *(A1 BLOCKER: this supersedes "`slot` is the slot the candidate himself occupies")* `canJoin(lineup, player).slot` names, in order:
  1. an **open slot he is directly eligible for**: dedicated first, then pooled from most to least specific (G/F, then UTIL);
  2. otherwise, **the open slot the augmentation fills**;
  3. **never a filled slot.**

  Test: a second C with C filled and UTIL open → "UTIL".

  Create `web/src/lineup.ts`, a TypeScript port of `BasketballRules.prepareLineup` for any `rosterPositions` (starters only):
  - the slot-eligibility table from `teamNeeds.ts` `SLOT_ELIGIBILITY.nba` (move it to a shared export rather than duplicating);
  - the players' masks from `eligiblePositions`;
  - sort by `adp` ascending, **stable on drafted order** (research R5);
  - matroid greedy with Kuhn augmenting paths, mirroring `tryAssign` line for line, with a comment citing `BasketballRules.java` and the parity fixture.

  Export `seatLineup(rosterPositions, players) → { assign: slot→player|null, kept }` and `canJoin(lineup, player) → { ok, slot }`. `slot` is the slot the candidate himself occupies after augmentation.
- [X] T018 [US3] Create `web/src/lineup.parity.test.ts`. It loads `src/__fixtures__/nba-lineup-parity.json` and asserts that `seatLineup` and `canJoin` give identical seated ids, kind counts and `canJoinByMask` on **every** case. **Prove it can fail:** temporarily seat with the old two-pass greedy, confirm the counterexample case fails, restore, and record that in verification.md.
- [X] T019 [US3] *(A6)* Replace `AvailabilityPanel`'s `openSlots: Set<string>` prop with `fitFor?: (p: PlayerRef) => string | null`, built in `LiveDraftView`, `DraftView` and `MockDraftView` from the room's lineup. Use the same function for `PlayerPicker`, `OnTheClockPickInput` and `pickInsight`.
  In `web/src/teamNeeds.ts`:
  - **NBA:** `computeTeamNeeds` delegates to `seatLineup` and maps the result to `SlotStatus[]` in template order.
  - **Football:** unchanged (FR-008).
  - **Fit helpers:** `openSlotFor`, `needLabel` and `fitSlot` take the **player** (`PlayerRef`) instead of a `position` string. For NBA they use `canJoin`. For football they use `eligiblePositions(p)[0]`, i.e. today's behaviour.
  - **Callers:**
    - `AvailabilityPanel.tsx` ~446;
    - `OnTheClockPickInput.tsx` ~114;
    - `PlayerPicker.tsx` ~117;
    - `pickInsight.ts` ~29 (`fitSlot(sport, player, …)`);
    - any others `tsc` flags.
  - Update `teamNeeds.test.ts` and `pickInsight.test.ts`.
  - Add a case: the roster is a PG/SG then a pure PG, and both are seated, which is **red under the old code**.
- [X] T020 [US3] In `web/src/pickInsight.ts` ~64, the `have` set of positions becomes the union of `eligiblePositions` across the roster. Check what the set is used for, and adjust the wording if it renders "already has a PG"-style text.

---

## Phase 6: User Story 4 — Pick runs by eligibility (P3)

- [X] T021 [P] [US4] *(A2, the user's decision: family runs)* In `web/src/pickRun.test.ts`:
  - **Forward run:** six NBA picks, four of them single-family forwards (SF, PF, PF/SF), produce a **Forward** run.
  - **Spanning picks don't count:** an SG/SF pick counts toward no family.
  - **Copy:** "4 of the last 6 were forwards".
  - **Measured fixture:** the 2025 draft's real pick sequence (168 picks from the DB, written to a fixture) gives **26 run windows of 163, with 0 ties**.
  - **Football:** unchanged.
  - **Proven red:** each of these fails before the change.
- [X] T022 [US4] *(A2)* In `web/src/pickRun.ts`, NBA runs count **families**: G = {PG, SG}, F = {SF, PF}, C = {C}. A pick counts toward a family only if all of `eligiblePositions(p)` are in it.
  - **Ties:** none occur by construction, since each pick counts at most once. Comment it with FR-006.
  - **Downstream:** update the run copy everywhere it renders (`AvailabilityPanel` ~593, `ScarcityMeter`, `CompactRow`/`PickFeed`, `LiveDraftView`'s pick-card scarcity line), so an NBA run reads "guards", "forwards" or "centers".
  - **Football:** keeps its per-position runs.

---

## Phase 7: Polish and verification

- [X] T023 *(review #18: also make the text that renders the lean say "by listed position")* Keep `onBrand.ts` (lines ~47, 53, 76) on `.position` **on purpose**: it compares picks with the manager's fitted positional tilt, which the engine computes from the alphabetical-first position. Add a comment saying so and naming the follow-up ("Use Sleeper's real primary position in the NBA sim"). Record it in research.md as R9.
- [X] T024 Live verification per `quickstart.md` steps 1–5, on 8096/5197 after a restart:
  - the five measured chip counts;
  - SG/SF filter membership, Edwards "PG/SG" and Durant "SF/PF" pills with no rank;
  - an auto-finished 12-team NBA mock reading **9 of 9**;
  - football unchanged;
  - the "SG/SF/PF" pill fitting the compact cell (measure its width against the cell).

  Write `verification.md`, keeping verified and not-run apart.
- [X] T025 Verify SC-004:
  - `git diff --stat 024-draft-room-ux -- backend/src/main/java/com/ballknowers/draftsim/{engine,profile,sport}` shows only the `SimulationResult.java` record change;
  - the engine tests pass unchanged;
  - a fixed-seed `MockDraftEngine` replay test produces identical bot picks before and after.

  Record all three in verification.md.
- [X] T026 Run a bug-hunting code review of the full diff vs `024-draft-room-ux` → `code-review.md`. Fix the confirmed findings on Sonnet (Edit-only) and re-verify live.
- [X] T027 Update `HANDOFF.md` and the memory note. Add any durable lesson to `claude/lessons.md`. Candidate: "Sleeper's fantasy_positions are alphabetical; 'first' is not 'primary'."
- [X] T028 Run the full suites: backend (report **skipped**), and `web` tsc + vitest + build. Then ask Allan before committing.

---

## Dependencies & order

- **Setup:** T001 → **T002 (the review gates all code)**.
- **Phase 2:** T003 → T004 (same change); T005 is parallel with them. T006 last.
- **US1 (T007–T010)** and **US2 (T011–T015)** both need Phase 2, and can run in parallel. They touch different files except `AvailabilityPanel.tsx` (US2 filters/pills) and `LiveDraftView.tsx` (US1 T010), which don't overlap in lines, but run them sequentially on shared files.
- **US3:** T016 → T017 → T018 → T019 → T020. T019 touches `AvailabilityPanel`, `PlayerPicker` and `OnTheClockPickInput`, so it goes after US2's edits to those files.
- **US4:** independent after Phase 2.
- **Polish:** last.

## Parallel examples

- **After Phase 2:**
  - one agent on US1 (`scarcity.ts`, `ScarcityMeter.tsx`) plus US4 (`pickRun.ts`);
  - one agent on US2 (filters, posRank, pills, CSS);
  - the backend parity fixture T016, which is independent of the frontend.
- **Then** US3's frontend (T017–T020) on one agent.

## Implementation strategy

- **MVP = Phase 2 + US1 + US2:** the visible fix (real SG/SF chips, working filters, honest labels). Live-verify that slice before starting US3.
- **US3 next.** It's the correctness fix ("8 of 9"), and the riskiest change, so it's guarded by the parity fixture.
- **US4 is small.**
