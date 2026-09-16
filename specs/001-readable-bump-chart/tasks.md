---

description: "Task list for 001-readable-bump-chart"
---

# Tasks: A readable projected-week-by-week chart

**Input**: Design documents from `/specs/001-readable-bump-chart/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Tests ARE included. The spec requests them explicitly — SC-004 ("`hue.ts`
has unit tests. It is a shared rule with six call sites and currently no test at
all") — and quickstart.md §1 enumerates them.

**Organization**: Grouped by user story so each is independently implementable and
demoable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story the task serves (US1–US4)
- Paths are repo-root-relative

## Path Conventions

Web frontend inside an existing full-stack repo: all work is under `web/src/`.
No backend, API or schema change — `AnalysisRosterProjection` already carries
`isMe`, `avatarId`, `managerId` and `byWeek[].rank` (verified in `web/src/api.ts:866`).

---

## Phase 1: Setup

**Purpose**: Establish the baseline this change will be measured against.

- [X] T001 Run `cd web && npm test` and record the exact pass/fail/skip counts in `specs/001-readable-bump-chart/baseline.md` — the standing rule in `claude/lessons.md` is to separate verified from assumed, and a green count recorded *before* the change is what makes a later regression provable
- [ ] T002 [P] Capture a "before" screenshot to `specs/001-readable-bump-chart/before.png` — **not done as a file.** The before-state of record is the screenshot supplied with the original request; see `baseline.md`

**Checkpoint**: Baseline recorded.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The color vocabulary and the prop-shape change. Blocking because
making `selection`/`meRosterId` required props breaks the TypeScript build until
all three `BumpChart` callers pass them — this cannot be done one story at a time.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T003 [P] Create `web/src/managerColor.ts` exporting `FOCUS_SLOTS = ['#3987e5', '#d95926', '#199e70'] as const` (blue, orange, aqua) and `FOCUS_CAP = FOCUS_SLOTS.length`; `FOCUS_CAP` MUST be derived from the array, never a literal `3`, and the file MUST carry the validator command in a comment directly above the constant per `contracts/manager-color.md`
- [X] T004 [P] Add the `ManagerColorRole` union (`'me' | 'focus-1' | 'focus-2' | 'focus-3' | 'context'`) and a pure `roleFor(rosterId, selection, meRosterId)` to `web/src/managerColor.ts` per `data-model.md`; precedence is explicit — a roster that is BOTH selected and `me` resolves to its focus slot, because an explicit selection is the stronger signal
- [X] T005 [P] Add `.bump-series` context/focus/me stroke styles to `web/src/styles.css`: context `var(--muted)` width 1.25 opacity 0.35, me `var(--crimson)` width 2 opacity 1, focus width 3 opacity 1
- [X] T006 Create `web/src/managerColor.test.ts` with a guard test asserting `FOCUS_SLOTS` deep-equals the validated triple, whose failure message quotes the validator command — a fourth color added without re-validation is the way this regresses (research.md R2)
- [X] T007 Add `roleFor` unit tests to `web/src/managerColor.test.ts`: deselecting the FIRST of three selections leaves the other two on their original slots (FR-003), duplicates are rejected, and `selection.length` never exceeds `FOCUS_CAP`
- [X] T008 Change `BumpChartProps` in `web/src/components/BumpChart.tsx` to the shape in `contracts/bump-chart.md` — replace `highlighted: number | null` / `onHighlight` with **required** `selection: number[]`, **required** `meRosterId: number | null`, and `onToggle: (rosterId: number) => void`; both new props are required, not optional-with-default, because a defaulted param that encodes a rule has shipped as a bug in this repo three times
- [X] T009 Thread the new props through all three call sites so the build compiles: `ProjectedBumpBlock` and `ScoresBlock` in `web/src/pages/LeagueAnalysis.tsx`, and `web/src/pages/PowerRankings.tsx`
- [X] T010 Update `web/src/pages/LeagueAnalysis.test.tsx` and `web/src/pages/PowerRankings.stale.test.tsx` for the new prop shape, and confirm `web/src/pages/PowerRankings.chart.test.ts` still passes **unmodified** — `segmentsOf` is untouched, so if that test needs editing something drifted that shouldn't have

**Checkpoint**: Build green, tests green, color vocabulary in place. Stories can begin.

---

## Phase 3: User Story 1 — Find my own roster (Priority: P1) 🎯 MVP

**Goal**: The chart stops being fourteen competing lines. It becomes recessive
context plus one crimson line: yours.

**Independent Test**: Open League analysis signed in. Your roster is findable in
under a second without clicking anything, and without relying on hue.

- [X] T011 [P] [US1] Add `web/src/components/BumpChart.test.tsx` asserting the viewer's series renders with the crimson stroke and every other series renders with the context stroke
- [X] T012 [US1] Implement the context layer in `web/src/components/BumpChart.tsx` — unselected, non-viewer series stroke `var(--muted)` at width 1.25 / opacity 0.35, replacing the per-series `oklch(70% 0.14 hue)` stroke on both the `polyline` and `circle` marks
- [X] T013 [US1] Implement the `me` role in `web/src/components/BumpChart.tsx` — `rosterId === meRosterId` strokes `var(--crimson)` at width 2 / opacity 1, reusing the app's existing "crimson when it's you" convention from `Avatar.isMe`
- [X] T014 [US1] Pass `meRosterId` from both blocks in `web/src/pages/LeagueAnalysis.tsx`, derived from the existing `AnalysisRosterProjection.isMe` field (already on the wire, `web/src/api.ts:872`) — no API change
- [X] T015 [US1] Pass `meRosterId` from `web/src/pages/PowerRankings.tsx` using that page's own `isMe` field
- [X] T016 [US1] Update the panel copy in `web/src/pages/LeagueAnalysis.tsx` — "Click a line to follow it" is no longer the whole story (FR-010)

**Checkpoint**: The default state is readable. This alone is worth shipping.

---

## Phase 4: User Story 2 — Compare myself against a rival (Priority: P1)

**Goal**: Pin up to three rosters for colored comparison, on a palette that
passes every accessibility gate.

**Independent Test**: Pin two rivals. Exactly three lines are colored, eleven
recede. Unpin the first; the other two keep their original colors.

- [X] T017 [P] [US2] Add selection tests to `web/src/components/BumpChart.test.tsx`: three pinned rosters take slots 1/2/3 in pin order, and color never depends on rank or on array position in `series` (FR-003, contract rule 3)
- [X] T018 [US2] Render focus strokes in `web/src/components/BumpChart.tsx` — a series in `selection` takes `FOCUS_SLOTS[indexOf(rosterId)]` at width 3 / opacity 1; every series in `series` is still drawn, selection changes appearance only (contract rule 1)
- [X] T019 [US2] Convert `BumpLegend` in `web/src/pages/LeagueAnalysis.tsx` from single-highlight to multi-select pins, showing each manager's `Avatar` (using the existing `avatarId` on the wire) rather than a bare color swatch
- [X] T020 [US2] Enforce `FOCUS_CAP` in `web/src/pages/LeagueAnalysis.tsx` — a fourth pin is either disabled with a visible reason or releases the oldest pin; it MUST NOT generate a fourth color (FR-007)
- [X] T021 [US2] Adopt the same selection state shape in `web/src/pages/PowerRankings.tsx` so both pages read identically

**Checkpoint**: US1 and US2 both work. This is the full "clearer for a user" story.

---

## Phase 5: User Story 3 — Identify any line without selecting it (Priority: P2)

**Goal**: Identity never depends on color at all — for anyone, including a
grayscale or colorblind reader.

**Independent Test**: Apply `filter: grayscale(1)` in devtools. Every line's
owner is still nameable.

- [X] T022 [P] [US3] Add label tests to `web/src/components/BumpChart.test.tsx` asserting a direct text label renders for every series, not only focused ones
- [X] T023 [US3] Render a direct manager label at each series' right end in `web/src/components/BumpChart.tsx`, extending `marginRight` to make room (FR-008, NFR-002)
- [X] T024 [US3] Add vertical collision-nudging for the end labels in `web/src/components/BumpChart.tsx` — a 14-team league puts fourteen labels in one column and they will overlap at the default `yStep` of 22
- [X] T025 [US3] Implement the density fallback in `web/src/components/BumpChart.tsx` — when labels cannot be placed without overlap, label the pinned rosters and the viewer's, and leave the rest to hover
- [X] T026 [US3] Replace the per-point `<title>` with a nearest-line hover readout in `web/src/components/BumpChart.tsx` reporting manager · week · rank, with a hit target larger than the 3px mark (FR-009)

**Checkpoint**: The chart is readable with no color vision at all.

---

## Phase 6: User Story 4 — Consistent manager color across the app (Priority: P2)

**Goal**: Fix the actual `hueFor` bug. This is what makes manager colors genuinely
differ in the rail, the draft board and avatar fallbacks.

**Independent Test**: In a 14-team league, no two managers sit within 15° of hue.
Today the minimum gap is 1°.

- [X] T027 [P] [US4] Create `web/src/hue.test.ts` — the first test this shared rule has ever had. Assert: for n=14 the minimum gap between any two `hueForIndex` results is `>= 25`; `hueForIndex(0, 0)` throws rather than returning 0; and `hueForName` is byte-for-byte unchanged from today's `hueFor`. The spread test MUST fail against the current implementation (measured gap = 1°) before T028 is written
- [X] T028 [US4] In `web/src/hue.ts`, add `hueForIndex(index, count)` returning `Math.round((360 * index) / count)`, and rename the existing multiply-31 hash to `hueForName`. The rename is the point — a caller must not be able to reach for the wrong rule, which is how `claude/lessons.md` §16 shipped three times. Document `hueForName`'s known weakness (sequential seeds cluster) and state that it is NOT for manager identity
- [X] T029 [US4] Add `managerHues(rosters)` to `web/src/managerColor.ts` per `contracts/manager-color.md` — sorts by ascending `managerId` falling back to `rosterId` when `managerId` is null (FR-002), keyed to identity and never to rank (FR-003)
- [X] T030 [P] [US4] Add `managerHues` tie tests to `web/src/managerColor.test.ts`: a null `managerId` falls back to `rosterId` deterministically, and the mapping is unchanged by reordering the input array
- [X] T031 [P] [US4] Point `web/src/components/LeagueRailSection.tsx` and `web/src/pages/DraftPicker.tsx` (both call sites) at `hueForName` — these seed on a *league name*, which has no known set to index within, so the hash rule is correct here
- [X] T032 [US4] Add an optional `hue?: number` prop to `web/src/components/Avatar.tsx` that overrides the internally computed hue, falling back to `hueForName(seed)` when absent — a cosmetic fallback value, not a rule, so a default is legitimate here
- [X] T033 [US4] Feed `managerHues` into the manager-identity call sites: `web/src/pages/MockSetup.tsx`, `web/src/pages/LeagueAnalysis.tsx` (both blocks) and `web/src/pages/PowerRankings.tsx`, replacing `hueFor(String(r.managerId ?? r.rosterId))`

**Checkpoint**: All four stories done. Manager color is correct app-wide.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T034 Re-run the palette gate and confirm ALL CHECKS PASS: `node scripts/validate_palette.js "#3987e5,#d95926,#199e70" --mode dark --surface "#09121c" --pairs all` (quickstart.md §2)
- [X] T035 Walk the full browser checklist in `specs/001-readable-bump-chart/quickstart.md` §3 against a real 14-team league, including the grayscale check and the FR-003 unpin-the-first regression
- [X] T036 Verify FR-011 in the browser — confirm `ScoresBlock` in `web/src/pages/LeagueAnalysis.tsx` and the chart in `web/src/pages/PowerRankings.tsx` both inherited the treatment from the shared `web/src/components/BumpChart.tsx`, with no second implementation added anywhere
- [ ] T037 [P] Capture the "after" screenshot to `specs/001-readable-bump-chart/after.png` — **not done as a file.** The chart was verified in a browser and the screenshots are in the session transcript, but this session had no way to write them to disk
- [X] T038 [P] Record the outcome in `claude/league-analysis-week-by-week.md` — what was measured, what shipped, and the three-color ceiling, so the next session does not re-derive it
- [X] T039 Run `cd web && npm test` and compare against the `baseline.md` counts from T001; a changed skip count is a finding, not noise

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies
- **Foundational (Phase 2)**: depends on Setup — **BLOCKS all user stories**
- **US1 (Phase 3)**: depends on Phase 2
- **US2 (Phase 4)**: depends on Phase 2; shares `BumpChart.tsx` with US1 (see below)
- **US3 (Phase 5)**: depends on Phase 2; independent of US1/US2 in behavior
- **US4 (Phase 6)**: depends only on T003 — **genuinely independent**, could ship on its own branch
- **Polish (Phase 7)**: depends on whichever stories shipped

### Honest note on story independence

US1, US2 and US3 all edit `web/src/components/BumpChart.tsx`. They are
independently *testable and demoable*, but they are **not safely parallelizable
across developers** — same file. Run them sequentially (P1 → P1 → P2). US4 touches
a disjoint file set and is the one story that can genuinely run in parallel with
the others.

### Parallel Opportunities

- T003, T004, T005 (Phase 2) — three different files
- T006/T007 can be written while T008/T009 are in progress — different files
- **US4 (T027–T033) in parallel with US1–US3** — the only real cross-story parallelism here
- T037, T038 in Polish

---

## Parallel Example: Phase 2 Foundational

```bash
# Different files, no interdependencies:
Task: "Create web/src/managerColor.ts with FOCUS_SLOTS and FOCUS_CAP"
Task: "Add ManagerColorRole and roleFor() to web/src/managerColor.ts"
Task: "Add .bump-series context/focus/me styles to web/src/styles.css"
```

## Parallel Example: US4 alongside the chart work

```bash
# Disjoint file sets — safe to run concurrently with Phase 3–5:
Task: "Create web/src/hue.test.ts asserting >=25 degree minimum gap at n=14"
Task: "Add hueForIndex and rename hueFor to hueForName in web/src/hue.ts"
Task: "Point LeagueRailSection.tsx and DraftPicker.tsx at hueForName"
```

---

## Implementation Strategy

### MVP — US1 only (T001–T016)

Sixteen tasks. Delivers the single biggest readability win: the chart stops being
a hairball and your own line is obvious. **Stop and validate here** — it is worth
shipping on its own, and it is the cheapest point at which to find out whether the
recessive-context direction feels right before building pinning on top of it.

### Incremental Delivery

1. Setup + Foundational → build green, color vocabulary in place
2. **+ US1 → the default state is readable (MVP, ship it)**
3. + US2 → pinned comparison, three validated colors
4. + US3 → readable with no color vision at all
5. + US4 → manager color fixed app-wide

### If the direction is wrong

US1 is the checkpoint that tests the whole premise. If recessive-context does not
feel right there, `research.md` §R3 has the alternative already written up (small
multiples) — revisit before building US2 on a foundation you don't want.

---

## Notes

- `segmentsOf()` is not touched by any task. Bye-week gaps and thin-coverage
  dashes must behave exactly as they do today.
- Ranks stay backend-owned. No task recomputes a rank on the frontend — a second
  implementation of `Ranker` is a bug this repo has already shipped.
- No task adds a runtime dependency, an API field or a migration.
- Commit after each task or logical group.

---

## Implementation outcome (2026-09-16)

**37 of 39 done.** The two open items are T002 and T037, both "save a screenshot
to a file" — the chart *was* verified in a browser (see below), but this session
could not write those images to disk. Nothing else was skipped.

### Verified

- `cd web && npm test` — **30 files, 284 tests, 0 skipped, all passing.**
  Baseline was 27/244/0 (`baseline.md`), so +3 files and +40 tests.
- `npx tsc -b` — clean.
- `PowerRankings.chart.test.ts` passed **unmodified** throughout, as required.
- Palette gate: `ALL CHECKS PASS` with crimson in the set.
- Browser: default state, 14 end labels placed without collision, 3-pin cap with
  11 controls disabled, FR-003 release-the-first (survivors kept their colours),
  and a grayscale pass in which every line is still identifiable.

### Changed during implementation, with reasons

- **T021 was wrong about Power rankings.** Its `highlighted` drives page-wide
  state (team view, row pinning, focus panel), not just the chart, so it was
  adapted at the chart boundary instead of being rewritten. There was also a
  *fourth* BumpChart call site the plan never counted.
- **A `colorBy` union was added.** That fourth call site plots ranking MODES with
  meaningful fixed hues; greying it out would have broken a working chart.
- **The palette changed** from blue/orange/aqua to blue/aqua/yellow — the orange
  slot was ΔE 6.7 from the reader's crimson line. research.md R6a.
- **`selection` became slots, not a list** — releasing a pin was repainting the
  survivors, against FR-003. research.md R6b.
