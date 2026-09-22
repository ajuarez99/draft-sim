---

description: "Task list for 007-mobile-rank-drag"
---

# Tasks: Reordering a ranking on a phone

**Input**: Design documents from `/specs/007-mobile-rank-drag/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/rankboard-interaction.md](./contracts/rankboard-interaction.md), [quickstart.md](./quickstart.md)

**Tests**: Included, and requested — [plan.md](./plan.md) "Implementation order" step 1 says red-green before anything touches the component, and [quickstart.md](./quickstart.md) §1 enumerates the required cases. The geometry is the only part of this feature a machine can check, so it is tested first and hardest.

**Organization**: Grouped by user story. Every story here is frontend-only; no task touches `backend/`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1–US4, mapping to the user stories in spec.md
- Exact file paths are given in every task

## Path Conventions

Web app. React SPA in `web/`, Spring Boot API in `backend/`. **This feature is entirely inside `web/src/`** — no API, schema, or stored-data change (data-model.md).

---

## Phase 1: Setup

**Purpose**: Know what green looks like before changing anything, and don't fight another session for a port.

- [X] T001 Record the baseline: run `npm test` and `npm run build` in `web/`, note the passing test count and that the build is clean, so any later failure is attributable to this feature
- [X] T002 [P] Pick a dev-server port no other session holds from `.claude/launch.json` (`draft-sim-web-alt` on 5178, or the 5179/5180/5181 entries) — several Claude sessions share this tree and 5173 may be taken

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The pure geometry every story depends on, and the layout change that must land before the handle is fitted.

**⚠️ CRITICAL**: No user story work begins until this phase is complete. T008 specifically must precede T009 — plan.md Risks: the inner card is ~279px wide today, and a 44px handle fitted into that cramped layout would have to be refitted afterwards.

- [X] T003 Create `web/src/dragGesture.ts` exporting the `SlotSpan` type and the three signatures verbatim from contracts §3 — `targetSlotFor(pointerY, spans, fromIndex)`, `autoScrollStep(pointerY, containerTop, containerBottom, zonePx, maxPxPerFrame)`, `clampScroll(next, scrollHeight, clientHeight)` — as stubs that throw. No DOM types in this module
- [X] T004 Write `web/src/dragGesture.test.ts` covering all twelve cases tabulated in quickstart.md §1 over fabricated `SlotSpan[]`; confirm every test FAILS before continuing
- [X] T005 Implement `targetSlotFor` in `web/src/dragGesture.ts` with midpoint hysteresis: return `fromIndex` unless the pointer has passed the **midpoint** of a neighbouring row in the direction of travel; always return an index in `[0, spans.length - 1]`; stateless, so two calls with the same arguments return the same answer
- [X] T006 Implement `autoScrollStep` in `web/src/dragGesture.ts`: returns `0` anywhere in the middle of the container, negative near the top, positive near the bottom, magnitude rising with depth into the zone and **never exceeding `maxPxPerFrame`**
- [X] T007 Implement `clampScroll` in `web/src/dragGesture.ts`: clamp to `[0, scrollHeight - clientHeight]`, and **return `0` when `scrollHeight <= clientHeight`** (the small-league case where the list does not overflow)
- [X] T008 [P] Add a `@media (max-width: 700px)` block to `web/src/styles.css` for the ballot modal: `.modal-backdrop` padding 24px→8px, `.modal-card` padding→14px, `.modal-card.wide` `max-height` 80vh→92vh. **Scope it to the ballot modal** — `.modal-backdrop`/`.modal-card` are shared with SeatPopover, the settings modal and the start-mock modal (contracts §4)

**Checkpoint**: `npm test` green with the new geometry tests. The ballot modal uses the full phone screen. No behaviour has changed yet.

---

## Phase 3: User Story 2 — Scroll the ranking without disturbing it (Priority: P1) 🎯 MVP

**Goal**: A swipe on a row reads the board. A drag from the grip moves a team. A mouse keeps the whole chip.

**Independent Test**: At 375px with touch emulation, swipe vertically starting on a team row — the list scrolls, no team is picked up, and the order afterwards is identical.

**Why this is the MVP rather than US1**: both are P1, but on touch this one is a prerequisite. Until drags start from a known place, autoscroll has nothing stable to build on, and every US1 check would have to be redone once initiation moved. It is also shippable alone — a board you can read without disturbing is already better than today.

### Implementation for User Story 2

- [X] T009 [US2] In `web/src/styles.css`, **remove `touch-action: none` from `.rankboard-chip`** (currently line ~2221) and add `.rankboard-handle`: minimum 44×44 hit area, `touch-action: none`, `cursor: grab`. The grip glyph is the ornament; the 44px box is the target (the same rule finding 10 applied to the chip)
- [X] T010 [US2] Render a grip element inside each **placed** chip in `renderChip` in `web/src/components/RankBoard.tsx`, with the glyph `aria-hidden` and an accessible name naming the team it moves. Tray chips do not need one — the tray is a wrapped flex row, not a scroller
- [X] T011 [US2] Extend `DragSession` in `web/src/components/RankBoard.tsx` with `pointerType: 'mouse' | 'pen' | 'touch'` and `startedOnHandle: boolean`, per data-model.md
- [X] T012 [US2] Gate `handleChipPointerDown` in `web/src/components/RankBoard.tsx`: `if (e.pointerType === 'touch' && !startedOnHandle) return` — let the browser scroll. Mouse and pen keep the entire chip as the grab area with no delay and no threshold change (FR-021)
- [X] T013 [P] [US2] Create `web/src/components/RankBoard.test.tsx` asserting: a touch `pointerdown` on the row body starts no drag; a touch `pointerdown` on the handle does; a mouse `pointerdown` on the row body does; and an incomplete order leaves submit disabled
- [X] T014 [US2] Browser-verify at 375×812 **with touch emulation** — quickstart scenario 1: ten vertical swipes over row bodies scroll the list ten times and move a team zero times (SC-003)

**Checkpoint**: The board can be read on a phone. Dragging still only reaches visible rows — that is US1.

---

## Phase 4: User Story 1 — Move a team the full height of the board (Priority: P1)

**Goal**: A held team can travel to any rank, including ranks off screen, in one gesture, and lands where it was aimed.

**Independent Test**: At 375px with touch emulation and a twelve-team league, drag the team in slot 12 to slot 1 in one uninterrupted gesture; the board reads 1–12 with that team first and no other pair transposed.

### Implementation for User Story 1

- [X] T015 [US1] Add a `slotsRef` for the `<ol className="rankboard-slots">` and a scroll-parent walk in `web/src/components/RankBoard.tsx`: first ancestor whose computed `overflow-y` is `auto` or `scroll` **and** whose `scrollHeight > clientHeight`, falling back to the viewport. Resolve **once per drag**, not per frame, and store it on `DragSession.scrollParent` (data-model.md). Do not hard-code `.rankboard-slots` — the component is used twice and owns none of its callers' chrome
- [X] T016 [US1] Replace the deferred drop placement in `web/src/components/RankBoard.tsx` with live reordering: on each `pointermove`, call `targetSlotFor` and commit `move(order, from, to)` when it differs from `DragSession.currentSlot`; `handleContainerPointerUp` then commits nothing and only ends the gesture. `rankOrder.ts` is unchanged — `move()` already splices and reinserts rather than swapping (FR-005)
- [X] T017 [US1] Delete the `.rankboard-slot.drop-target` rule from `web/src/styles.css` (~line 2201) and `setSlotHoverClass` from `web/src/components/RankBoard.tsx` — live reordering replaces the hover tint, and a rule nothing sets is worse than no rule
- [X] T018 [US1] Add `scroll` (on the resolved scroll parent) and `resize` listeners that call `measureRects()` in `web/src/components/RankBoard.tsx`, registered when the drag threshold is crossed and removed in `endDrag`. **Invariant: never read a rect measured before the last scroll** — this is the violated invariant the whole "landed on the wrong row" class comes from (research.md §0)
- [X] T019 [US1] Add the autoscroll `requestAnimationFrame` loop to `web/src/components/RankBoard.tsx`: velocity from `autoScrollStep`, applied via `clampScroll`, with named constants at module top (start at a 64px zone and ~12px/frame, tuned by feel). **The loop must recompute the target rank every frame from `DragSession.lastX/lastY`, not only on `pointermove`** — a still finger over a scrolling list changes target rank with no pointer event (research.md §4)
- [X] T020 [US1] Cancel the rAF loop and remove the T018 listeners inside `endDrag` in `web/src/components/RankBoard.tsx` — the single exit point already shared by `pointerup`, `pointercancel` and `lostpointercapture`. **Invariant: `rafId` is null whenever `dragRef.current` is null** (data-model.md); a loop that outlives its drag scrolls a list nobody is touching
- [X] T021 [US1] Browser-verify at 375×812 with touch emulation — quickstart scenarios 2 (last→first in one gesture), 3 (first→last), 4 (hold at an edge already at the end: scrolling stops, no jitter), 5 (hold still while scrolling: target keeps updating), 6 (ten deliberate drags, ten correct landings), 7 (no oscillation at row boundaries)

**Checkpoint**: US1 and US2 both work. This is the fix the complaint asked for.

---

## Phase 5: User Story 3 — Know what a gesture did (Priority: P2)

**Goal**: Success, no-op and interruption are each distinguishable, on a screen where a finger covers the target.

**Independent Test**: Perform a move, a release between rows, and a release outside the board; each produces a distinguishable on-screen outcome and each is announced.

### Implementation for User Story 3

- [X] T022 [US3] Announce **once per gesture, on release** in `web/src/components/RankBoard.tsx` — not on each intermediate live move from T016, which would flood the `aria-live` region. Keep the existing "Placed Nth of M" wording and the existing `.rankboard-sr-only` region (FR-013)
- [X] T023 [US3] Handle a release outside the list in `web/src/components/RankBoard.tsx`: the chip settles visibly back into the row it last legitimately occupied, and the board announces that the ranking is unchanged (FR-012, SC-005)
- [X] T024 [US3] Offset the drag ghost above the fingertip for `pointerType === 'touch'` in `web/src/components/RankBoard.tsx`, so the held chip is not wholly hidden under the finger (FR-011)
- [X] T025 [US3] Browser-verify — quickstart scenarios 8 (release outside: settles back, says unchanged), 11 (OS interruption: order unchanged, no stranded ghost, no runaway scroll), 12 (rotate mid-drag), 15 (screen reader hears one announcement, not one per micro-move)

**Checkpoint**: Nothing the board does is silent.

---

## Phase 6: User Story 4 — Rank without dragging at all (Priority: P2)

**Goal**: Any ordering reachable by tapping, described in words that are true on the device in hand.

**Independent Test**: Without dragging once, reorder a twelve-team board into an arbitrary target order and submit it.

**Note**: This story touches no gesture code and can be done at any point, including first or in parallel with Phase 3.

### Implementation for User Story 4

- [X] T026 [P] [US4] Fix the instruction copy in `web/src/pages/PowerRankings.tsx`: the ballot line at ~1428 ("Drag to reorder -- or tap a team and use the arrows") names arrow keys a phone does not have while omitting tap-then-tap, which works; the commissioner line at ~1416 says only "Drag to disagree". Both must describe interactions available on the device in use (FR-016)
- [X] T027 [US4] Add up/down buttons to the selected chip in `web/src/components/RankBoard.tsx`, 44px each, visible only while that chip is selected, calling the same `moveChipBy` the keyboard handler already calls — so the copy's promise is true on touch as well as on a keyboard (FR-015)
- [X] T028 [P] [US4] Extend `web/src/components/RankBoard.test.tsx`: tap a chip then a later slot moves it and shifts the ones between by one without swapping; tapping a selected chip clears the selection and changes nothing; the up/down buttons are present on the selected chip and move it one rank
- [X] T029 [US4] Browser-verify — quickstart scenarios 9 (full reorder by taps alone, submit enabled) and 10 (copy describes touch gestures, says nothing about arrow keys)

**Checkpoint**: All four stories independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T030 Browser-verify quickstart scenario 16: open the seat popover, the settings modal and the start-mock modal at 375px and confirm none was restyled by the T008 phone-width block — these three share `.modal-backdrop`/`.modal-card` and are the easiest regression here to miss
- [X] T031 Browser-verify quickstart scenarios 13 and 14 on a desktop viewport: a mouse drags from anywhere on a chip with no added delay (SC-008), and the keyboard path takes no more steps than before this feature (SC-007)
- [X] T032 Browser-verify quickstart scenario 17: time a full twelve-team reorder into a target order, confirm under 90 seconds (SC-002)
- [X] T033 Record the results of all seventeen quickstart §3 scenarios, **including any that failed and what was done about them** — the gesture cannot be proven by the test suite, so this log is the evidence (plan.md Risks)
- [X] T034 Update `claude/power-rankings-ballots.md`: its "Not verified" section says real touch input has never been exercised. Replace that sentence with what was actually verified, or this feature is not done. Note in the same pass that finding 10's "re-measure on `scroll` and `resize`" is now honoured
- [X] T035 Run `npm test` and `npm run build` in `web/` — both clean, with the new `dragGesture.test.ts` and `RankBoard.test.tsx` counted in

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies
- **Foundational (Phase 2)**: depends on Setup — **blocks all stories**. T008 must precede T009
- **US2 (Phase 3)**: depends on Phase 2
- **US1 (Phase 4)**: depends on Phase 2 and, on touch, on US2 — see below
- **US3 (Phase 5)**: depends on US1 (T023's "settles back" only means something once live reordering exists)
- **US4 (Phase 6)**: depends on Phase 2 only. Independent of every gesture task
- **Polish (Phase 7)**: depends on all stories

### User Story Dependencies

- **US2 (P1)** → the only story with no story-level dependency. Start here
- **US1 (P1)** → *technically* buildable against a mouse without US2, but on touch its drags would start from anywhere until T012 lands, so every check in T021 would need redoing. Sequence US2 → US1
- **US3 (P2)** → depends on US1
- **US4 (P2)** → fully independent; can run in parallel with Phase 3 or even before it

### Within Each Story

- Phase 2's tests (T004) are written and failing before T005–T007
- Geometry before plumbing: `dragGesture.ts` is complete before `RankBoard.tsx` consumes it
- Layout before the handle: T008 before T009
- Browser verification closes each story; it is not deferred to the end

### Parallel Opportunities

- T002 with T001
- T008 with T003–T007 (stylesheet vs. a new module — different files)
- T013 with T009–T012 (new test file vs. component and stylesheet)
- **All of Phase 6 (US4) with all of Phase 3 (US2)** — `PowerRankings.tsx` copy and the selected-chip buttons never touch the gesture path. The one caution is that T027 and T010 both edit `renderChip` in `RankBoard.tsx`, so they conflict; T026 and T028 are free
- T005, T006 and T007 are **not** parallel with one another — same file

---

## Parallel Example: Phase 2

```bash
# T008 edits styles.css while T003-T007 build the new pure module:
Task: "Add the phone-width modal media block in web/src/styles.css"
Task: "Create web/src/dragGesture.ts with the three contract signatures as stubs"
```

## Parallel Example: US2 alongside US4

```bash
# Different files, no gesture overlap:
Task: "Fix the instruction copy in web/src/pages/PowerRankings.tsx"      # T026
Task: "Create web/src/components/RankBoard.test.tsx gating assertions"   # T013
```

---

## Implementation Strategy

### MVP (User Story 2)

1. Phase 1 Setup
2. Phase 2 Foundational — geometry tested, modal widened
3. Phase 3 US2 — handle, `pointerType` gating
4. **STOP and VALIDATE**: ten swipes scroll ten times and move nothing; a mouse still drags from anywhere on a chip
5. Shippable: the board can be read on a phone without disturbing it

This departs from the usual "MVP = User Story 1". Both stories are P1, and the reason is given in Phase 3's header: on touch, US2 is the prerequisite.

### Incremental Delivery

1. Setup + Foundational → geometry proven by unit tests, modal uses the screen
2. US2 → read the board on a phone → **MVP**
3. US1 → move a team anywhere in one gesture → **this is the reported defect, fixed**
4. US3 → every gesture says what it did
5. US4 → no drag needed at all, and honest copy
6. Polish → shared-modal regression, desktop and keyboard regression, verification log

### Notes

- `[P]` = different files, no dependencies
- Commit after each task or logical group
- **A green test suite is not a working gesture.** jsdom has no layout and `getBoundingClientRect()` returns zeros, so nothing in `npm test` can fail if autoscroll or handle gating is wrong. The browser-verification tasks (T014, T021, T025, T029, T030–T033) are deliverables, not formalities — `claude/lessons.md` #2 and #3 are both instances of this codebase paying for a claim that was reasoned instead of run
- No task in this list touches `backend/`, any endpoint, or any stored ordering

---

## Deviations from these tasks, and why

Recorded rather than quietly absorbed. See
[verification.md](./verification.md) for the run these came out of.

- **T010 — the handle is `aria-hidden`, not given an accessible name.** The task
  asked for a name naming the team it moves. On reflection that would offer
  assistive technology a control it cannot operate: dragging is the one route AT
  cannot take. The operable paths — tap-to-place, ArrowUp/ArrowDown, and the
  selected chip's own ↑/↓ buttons — are all on the chip itself and all announced.
  A "reorder" control that does nothing when activated is worse than none.
- **T017 — `.rankboard-slot.drop-target` was kept, not deleted.** Live reordering
  does replace it for a chip already in the ordering, and it is no longer set on
  that path. But a chip dragged out of the **tray** is not in the ordering yet and
  still has its placement deferred to the drop, so the tint still has a real user.
  The rule stays, with a comment saying exactly when it applies. Deleting it would
  have broken the tray path to satisfy the letter of the task.
- **T032 — scenario 17 is recorded as NOT MEASURED, not as a pass.** No honest
  human timing is available from automation. What is measured is in the log: 22
  interactions for a full tap reorder, and last→first now possible in one gesture.
- **T008 grew by one line outside `styles.css`.** Scoping the phone-width block
  needed a hook, because `.modal-backdrop` is shared by six call sites; the ballot
  backdrop gained a `pr-ballot-backdrop` class in `PowerRankings.tsx`. Verified in
  scenario 16 that the other modals are untouched.
