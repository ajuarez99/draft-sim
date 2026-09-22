# Implementation Plan: Reordering a ranking on a phone

**Branch**: `007-mobile-rank-drag` | **Date**: 2026-09-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-mobile-rank-drag/spec.md`

## Summary

Make the power-rankings board usable with a thumb. Four changes, in the order they
matter:

1. **A drag handle.** `touch-action: none` moves off the full-width row and onto a
   44px grip inside it, so a swipe on the row scrolls the list instead of grabbing a
   team. Touch must start on the handle; mouse and pen keep the whole chip, with no
   delay.
2. **Autoscroll while held.** A `requestAnimationFrame` loop scrolls the slot list
   when the pointer nears its top or bottom edge, stopping at the ends, so a team
   can travel further than the window is tall.
3. **Live reordering instead of a deferred drop.** The board commits each move as
   the finger crosses a row's midpoint, the way the keyboard path already does. This
   deletes the stale-measurement bug rather than patching it, and makes "where will
   this land" visible around a fingertip.
4. **A phone-width modal, and instructions that are true on a phone.** The ballot
   modal currently spends 96px of a 375px screen on padding and tells touch users to
   press arrow keys. Both are fixed, and the selected chip gets real up/down buttons.

No backend work. No API, schema or stored-data change. Everything lands in
`web/src/`.

## Technical Context

**Language/Version**: TypeScript 5.7, React 18.3

**Primary Dependencies**: React 18.3.1, react-router-dom 6.30. **No drag-and-drop
library** -- the board is hand-rolled on Pointer Events, deliberately
(`claude/power-rankings-ballots.md`), and this feature does not introduce one.

**Storage**: N/A for this feature. The ranking lives in component state until
submitted; the existing ballot endpoints are untouched.

**Testing**: vitest 4.1 + jsdom 29 + @testing-library/react 16, run with
`npm test` in `web/`. jsdom implements neither layout nor `setPointerCapture`, so
geometry is tested as arithmetic in a pure module and the gesture itself is verified
in a real browser -- see research.md §6.

**Target Platform**: Mobile web browsers, 375px portrait as the design floor (iOS
Safari and Android Chrome); desktop browsers must not regress.

**Project Type**: Web application, frontend-only change. React SPA in `web/`,
Spring Boot API in `backend/` (not touched).

**Performance Goals**: The autoscroll loop holds a 60fps frame budget -- at most one
`getBoundingClientRect()` sweep of N rows per scroll event, none per frame. N is
league size, 8-14.

**Constraints**: No added latency on pointer devices (FR-021/SC-008) -- the handle
requirement applies to `pointerType === 'touch'` only. Existing keyboard and
`aria-live` behaviour is a floor (FR-017). Live reordering must not flood the
`aria-live` region; announce on release, not per micro-move.

**Scale/Scope**: One component (`RankBoard.tsx`, 546 lines), one new pure module,
one page's copy, one stylesheet block. Two call sites, both in `PowerRankings.tsx`.

## Constitution Check

`.specify/memory/constitution.md` **is an unfilled template** -- every principle is
still `[PRINCIPLE_N_NAME]` / `[PRINCIPLE_N_DESCRIPTION]` placeholder text, and the
version and ratification dates are unreplaced. There are no ratified gates to check
against, and saying "PASS" against placeholders would be theatre.

Substituting the conventions this codebase actually enforces, visible in
`claude/lessons.md` and in the shape of the existing code:

| Gate (de facto) | Status | Evidence |
|---|---|---|
| Ordering logic stays pure and unit-tested; only plumbing is browser-only | PASS | Extends the existing `rankOrder.ts` split with `dragGesture.ts` (research.md §6) |
| No new dependency where hand-rolled code already exists | PASS | No DnD library added; the existing Pointer Events architecture is kept |
| Design intent is written down before the code | PASS | This plan + research.md; `claude/power-rankings-ballots.md` amended on completion |
| Claims are verified by running, not by reading (`lessons.md` #2, #3) | GATED | Touch verification at 375px is a required task, not a nice-to-have -- see Risks |
| Accessibility path is first-class, not a fallback | PASS | FR-015/016/017; the keyboard path gains visible buttons and loses nothing |

**Flagged, not blocking**: the constitution should be filled in or deleted. A
template that every feature must "check against" and that says nothing trains
everyone to skip the gate. Out of scope here; worth its own task.

**Re-checked after Phase 1 design**: unchanged. The design added one pure module
and no dependency, kept `RankBoardProps` byte-identical so neither call site needs
editing, and left the ballot API untouched. The one row that is not a clean PASS is
the same one as before -- verification by running -- and Phase 1 answered it by
making the touch pass a numbered, countable deliverable in
[quickstart.md](./quickstart.md) §3 rather than an intention.

## Spec deltas

One requirement needs amending, because the chosen design contradicts it as written:

- **FR-007** says "a vertical swipe beginning **anywhere** on the board MUST scroll
  the board and MUST NOT move a team." The drag handle is a deliberate 44px
  exception to "anywhere" -- that is precisely what a handle is for.

  **Amended reading**: *a vertical swipe beginning anywhere on the board other than
  a drag handle MUST scroll the board and MUST NOT move a team.* SC-003's ten
  swipes should start on row bodies, which is what a reader trying to scroll would
  do anyway.

  The alternative that preserves FR-007 literally is long-press activation, rejected
  in research.md §1 for reasons that go to the heart of this complaint: it guesses
  at intent, and its wrong guesses are new wonkiness.

No other requirement changes. Nothing is dropped.

## Project Structure

### Documentation (this feature)

```text
specs/007-mobile-rank-drag/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── rankboard-interaction.md   # The board's interaction contract
├── checklists/
│   └── requirements.md  # Spec quality checklist (/speckit-specify output)
└── tasks.md             # NOT created by /speckit-plan
```

### Source Code (repository root)

```text
web/src/
├── components/
│   ├── RankBoard.tsx            # CHANGED: handle, autoscroll, live reorder,
│   │                            #   scroll/resize re-measure, up/down buttons
│   └── RankBoard.test.tsx       # NEW: tap path, copy, touch-vs-handle gating
├── dragGesture.ts               # NEW: pure geometry -- target slot, autoscroll
│                                #   step, scroll clamping
├── dragGesture.test.ts          # NEW: unit tests over fabricated rects
├── rankOrder.ts                 # UNCHANGED (move/place/unplace already correct)
├── pages/
│   └── PowerRankings.tsx        # CHANGED: instruction copy, both call sites
└── styles.css                   # CHANGED: .rankboard-handle, phone-width modal
                                 #   block, .drop-target removed
```

**Structure Decision**: Frontend-only, inside the existing `web/` React app. No
new directories. The one new module sits beside `rankOrder.ts` at `web/src/` root,
which is where this codebase already keeps pure logic extracted from components
(`snake.ts`, `pickRun.ts`, `teamNeeds.ts`, `rankOrder.ts` and eleven more, each with
a sibling `.test.ts`). `backend/` is not touched.

## Implementation order

Sequenced so each step is independently verifiable and the riskiest geometry lands
with tests already around it.

1. **`dragGesture.ts` + its tests.** Pure arithmetic, no DOM. Midpoint hysteresis,
   autoscroll step, scroll clamping. Red-green before anything touches the
   component. Covers FR-002, FR-004, FR-005, FR-006.
2. **The phone-width modal and the handle markup/CSS.** Visible, independently
   checkable, and it makes every later step easier to see. Covers US2, FR-007
   (amended), FR-008.
3. **`pointerType` gating in `handleChipPointerDown`.** One branch. Touch needs the
   handle; mouse keeps the chip. Covers FR-021.
4. **Live reordering**, replacing the deferred hit test at `pointerup`. Delete
   `.drop-target`. Covers FR-004, FR-011.
5. **Autoscroll loop** + `scroll`/`resize` re-measure during drag. Covers FR-001,
   FR-002, FR-003, FR-010.
6. **Announcements and the unchanged-release case.** Covers FR-012, FR-013, FR-014.
7. **Copy and the up/down buttons.** Covers FR-015, FR-016.
8. **Browser verification at 375px with touch**, written up. Covers SC-001 through
   SC-008 and the gated constitution row.

Steps 1-3 alone are a shippable improvement: the board would scroll properly and
drag correctly within the visible window. Step 5 is what makes US1 true.

## Risks

- **The gesture cannot be proven in jsdom.** This is the main one. No test in the
  existing harness can fail if the autoscroll loop or the handle gating is wrong,
  because jsdom has no layout. Mitigation: push every decision that *can* be
  arithmetic into `dragGesture.ts` where it is testable, and treat step 8 as a
  deliverable rather than a formality. `lessons.md` #2 and #3 are both instances of
  this codebase paying for a claim that was reasoned instead of run.
- **Autoscroll driven only by `pointermove`.** A still finger over a scrolling list
  changes target rank with no pointer event. The rAF loop must re-run the target
  calculation each frame. Called out in research.md §4; easy to get wrong, invisible
  until someone holds still.
- **Live reorder oscillation at row boundaries.** Mitigated by midpoint hysteresis,
  which is unit-tested; but the failure mode is a visible flicker, so it is worth
  watching for specifically in step 4's browser check.
- **Chip width at 375px.** Inner card width is ~279px today; a 44px handle plus a
  24px avatar leaves little for a name and a team line. The full-bleed modal (step 2)
  reclaims ~70px and should be done *before* the handle, not after, so the handle is
  never fitted into the cramped layout.
- **Concurrent sessions share this tree.** Several Claude sessions and one dev
  server may be live at once; `.claude/launch.json` carries alternate ports
  (`draft-sim-web-alt` on 5178, and 5179/5180/5181 paired with alternate backends)
  for exactly this. Use one rather than taking 5173.

## Complexity Tracking

No constitution violations to justify -- the constitution has no ratified gates (see
Constitution Check). The one deliberate deviation in this plan is a **spec**
deviation, not a complexity one, and it is recorded above under "Spec deltas":

| Deviation | Why needed | Simpler alternative rejected because |
|---|---|---|
| FR-007 amended to exempt the drag handle | A handle is the only mechanism that separates scroll from drag without guessing at intent | Long-press preserves FR-007's literal wording but arbitrates by timer; its misfires are the same "wonky" this feature exists to remove, and it needs non-passive `touchmove` plumbing React does not give us (research.md §1) |
