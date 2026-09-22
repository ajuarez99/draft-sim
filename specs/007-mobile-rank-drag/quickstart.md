# Quickstart & Validation: Reordering a ranking on a phone

**Feature**: 007-mobile-rank-drag | **Date**: 2026-09-22

How to run this feature and prove it works. The automated half is cheap and proves
the arithmetic; the manual half is the only thing that can prove the gesture, and
it is not optional -- see plan.md, Risks.

---

## Prerequisites

- Node (the version the repo already builds with; `web/package.json` pins nothing
  stricter than its dependency ranges).
- A running backend **only** for the live checks, not for the unit tests.
- Postgres up if you start the backend -- `claude/environment.md` has the details,
  and a backend with the database down will skip its integration tests silently
  rather than fail, which is a known trap in this tree.

**Several Claude sessions share this working tree and its ports.** Do not take 5173
or 8080 on the assumption they are free. `.claude/launch.json` carries alternates
for exactly this reason: `draft-sim-web-alt` (5178), and `draft-sim-web-8081` /
`-8082` / `-8099` paired with backends on those ports.

---

## 1. Unit tests -- the geometry

```bash
cd web && npm test
```

Expected: the whole suite green, including two files that are new in this feature.

**`src/dragGesture.test.ts`** must cover, over fabricated `SlotSpan[]` (no DOM):

| Case | Asserts |
|---|---|
| Pointer inside its own row | `targetSlotFor` returns `fromIndex` -- no move |
| Pointer just past a neighbour's near edge, not its midpoint | still `fromIndex` (hysteresis) |
| Pointer past a neighbour's midpoint, moving down | `fromIndex + 1` |
| Pointer past a neighbour's midpoint, moving up | `fromIndex - 1` |
| Pointer far above row 0 / far below row N-1 | clamps to `0` / `spans.length - 1` |
| Same inputs called twice | same output (no hidden state) |
| Pointer mid-container | `autoScrollStep` returns `0` |
| Pointer in the top zone | negative, magnitude ≤ `maxPxPerFrame` |
| Pointer in the bottom zone | positive, magnitude ≤ `maxPxPerFrame` |
| Deeper into the zone | strictly larger magnitude than shallower |
| `clampScroll` beyond either end | clamped to `0` / `scrollHeight - clientHeight` |
| `clampScroll` when content does not overflow | `0` |

**`src/components/RankBoard.test.tsx`** must cover what needs no layout:

| Case | Asserts | Requirement |
|---|---|---|
| Tap a chip, tap a later slot | that chip is at the later rank; the ones between shifted by one; nothing swapped | FR-005, US4 |
| Tap a chip, tap it again | selection cleared, order unchanged | US4 |
| Selected chip | up/down buttons are in the document and move the chip by one | FR-015, FR-016 |
| `pointerdown` with `pointerType: 'touch'` on the row body | no drag starts | FR-007 |
| `pointerdown` with `pointerType: 'touch'` on the handle | a drag starts | FR-008 |
| `pointerdown` with `pointerType: 'mouse'` on the row body | a drag starts | FR-021 |
| Board with an incomplete order | submit disabled | FR-020 |

**What these cannot prove**: autoscroll, hit accuracy, `touch-action`, capture, or
whether any of it feels right. jsdom has no layout -- `getBoundingClientRect()`
returns zeros. Do not read a green suite as a working gesture.

---

## 2. Type and build check

```bash
cd web && npm run build
```

`tsc -b` then `vite build`. Expected: clean.

---

## 3. Live validation -- the part that actually decides

Start a dev server on a port nobody else is using, e.g.:

```bash
cd web && npm run dev -- --port 5178 --strictPort
```

Open the ballot: `/leagues/:sleeperLeagueId/power`, then the button that opens the
ranking modal. Set the viewport to **375 × 812** and enable **touch emulation** --
mouse-driven pointer events will pass checks that a finger fails, which is exactly
how the current defects shipped.

Run each scenario and record the result. The success criteria are counted, so count.

| # | Scenario | Pass condition | Covers |
|---|---|---|---|
| 1 | Swipe up and down over the row bodies, ten times | List scrolls ten times; zero teams move | SC-003, US2 |
| 2 | Drag from the grip on the last team up to rank 1, one continuous gesture | List autoscrolls while held; team lands first; no other pair transposed | SC-001, US1 |
| 3 | Same, downward: rank 1 to last | Symmetric | US1 |
| 4 | Hold at the top edge after the list has reached rank 1 | Scrolling stops; nothing jitters; gesture still live | FR-002 |
| 5 | Hold still inside the edge zone while it scrolls | Target rank keeps updating under the stationary finger | research.md §4 |
| 6 | Ten deliberate grip-drags to a named rank | Ten out of ten land where aimed | SC-004 |
| 7 | Watch a drag across several row boundaries | No flicker or oscillation at any boundary | plan.md Risks |
| 8 | Release outside the list | Chip settles back visibly; board says nothing changed | SC-005, FR-012 |
| 9 | Reorder the full board using taps only, no drag | Any target order reachable; submit enabled | SC-006 |
| 10 | Read the instruction copy | Describes touch gestures; says nothing about arrow keys | FR-016 |
| 11 | Backgrounding the app mid-drag (or an OS gesture) | Order unchanged, no ghost stranded, no runaway scroll | FR-014 |
| 12 | Rotate to landscape mid-drag | Nothing stranded; rects re-measured | Edge case |
| 13 | Repeat 2 and 6 on a desktop viewport with a mouse | Drag from anywhere on the chip, no delay | SC-008, FR-021 |
| 14 | Keyboard: tab to a chip, arrows, Escape, submit | Same number of steps as before this feature | SC-007, FR-017 |
| 15 | Screen reader on a completed move | One announcement, "Placed Nth of M" -- not one per intermediate move | FR-013 |
| 16 | Open the other three modals (seat popover, settings, start-mock) at 375px | None restyled by the new phone-width block | contracts §4 |
| 17 | Time a full 12-team reorder into a target order | Under 90 seconds | SC-002 |

Scenario 16 exists because `.modal-backdrop` / `.modal-card` are shared. It is the
easiest thing in this list to forget and the most likely to cause a complaint that
has nothing to do with rankings.

---

## 4. Definition of done

- `npm test` green, including the two new files with the coverage listed in §1.
- `npm run build` clean.
- All seventeen live scenarios run at 375px **with touch emulation**, results
  recorded -- including any that failed and what was done about them.
- `claude/power-rankings-ballots.md` updated: its "Not verified" section currently
  says real touch input has never been exercised. Either that sentence is now false
  and should be replaced with what was verified, or this feature is not done.
