# Interaction Contract: RankBoard

**Feature**: 007-mobile-rank-drag | **Date**: 2026-09-22

This project exposes no new network interface for this feature. The contract that
*does* change is the one between `RankBoard` and the person using it, plus the
module boundary between the component and the new pure geometry. Both are written
here because both are things other code and other people depend on.

The existing API contract for ballots (`specs/002-league-history-record-book/`
and the power-rankings endpoints) is **unchanged**: same request, same response,
same stored ordering.

---

## 1. Component contract (`RankBoardProps`)

**Unchanged.** No prop is added, removed or re-typed.

```ts
type RankBoardProps = {
  members: RankBoardMember[]
  ariaLabel: string
  initialOrder?: number[]
  onSubmit: (orderedRosterIds: number[]) => void
  submitLabel?: string
  submitting?: boolean
}
```

This is deliberate. Both call sites in `PowerRankings.tsx` keep working without
edits to their props, and the "seed once, remount via `key`" contract in the
component's docblock still holds. Callers own their copy; the component owns the
ordering UI. Nothing in this feature moves that line.

`onSubmit` still fires only with a complete ordering, rank 1 first (FR-019, FR-020).

---

## 2. Gesture contract

What the board promises to do with a given input. This is the part that changes.

| Input | Today | After |
|---|---|---|
| Touch swipe on a row body | Picks the team up after 6px | **Scrolls the list.** No team moves |
| Touch drag from the grip handle | n/a (no handle) | **Moves that team**, live, following the finger |
| Touch drag held near the list's top/bottom edge | Nothing scrolls | **List scrolls** toward that edge until an end |
| Mouse drag from anywhere on a chip | Moves the team | **Unchanged** -- same grab area, no delay, no handle needed |
| Tap a chip, tap a slot | Moves the team | Unchanged, and now described in the copy |
| Tap a chip | Selects it | Selects it **and reveals up/down buttons** |
| `ArrowUp` / `ArrowDown` on a focused chip | Moves one rank | Unchanged |
| `Escape` | Deselects | Unchanged |
| Release outside any slot | Silently nothing | **Says nothing changed**, chip settles back visibly |
| OS interruption mid-drag | Order preserved, ghost cleared | Unchanged, plus the rAF loop is cancelled |

**Invariants across every row of that table:**

- The order is always exactly one team per rank, 1..N (FR-006).
- A placement shifts; it never swaps (FR-005).
- Nothing is sent to the server until submit (FR-019).
- Every completed change is announced once to `aria-live` (FR-013) -- once per
  gesture, not once per intermediate live move.

---

## 3. Pure module contract (`web/src/dragGesture.ts`)

New module. Takes numbers, returns numbers, touches no DOM -- so it is testable in
jsdom, where layout does not exist (research.md §6).

```ts
/** Vertical extent of one row, viewport-relative. */
export type SlotSpan = { top: number; bottom: number }

/**
 * The rank the dragged chip should occupy right now.
 * Returns `fromIndex` unless the pointer has passed the MIDPOINT of a
 * neighbouring row in the direction of travel -- the hysteresis that stops
 * boundary oscillation.
 */
export function targetSlotFor(
  pointerY: number,
  spans: SlotSpan[],
  fromIndex: number,
): number

/**
 * Signed pixels to scroll this frame. Zero when the pointer is outside the
 * edge zone. Magnitude rises with depth into the zone, capped at maxPxPerFrame.
 */
export function autoScrollStep(
  pointerY: number,
  containerTop: number,
  containerBottom: number,
  zonePx: number,
  maxPxPerFrame: number,
): number

/** Clamp a proposed scrollTop to [0, scrollHeight - clientHeight]. */
export function clampScroll(
  next: number,
  scrollHeight: number,
  clientHeight: number,
): number
```

**Guarantees these functions must hold** (and the unit tests assert):

- `targetSlotFor` returns an index in `[0, spans.length - 1]`, always.
- `targetSlotFor` is stable: called twice with the same pointer position and the
  same `fromIndex`, it returns the same answer. No internal state.
- `autoScrollStep` returns `0` when the pointer is anywhere in the middle of the
  container, negative near the top, positive near the bottom.
- `autoScrollStep` never exceeds `maxPxPerFrame` in magnitude.
- `clampScroll` never returns a value outside the scrollable range, including when
  `scrollHeight <= clientHeight` (in which case it returns `0`) -- this is FR-002's
  end-stop, and the degenerate case is a league small enough not to overflow.

---

## 4. Style contract

| Selector | Change |
|---|---|
| `.rankboard-chip` | **Loses `touch-action: none`** (styles.css:2221) |
| `.rankboard-handle` | **New.** 44×44 minimum, `touch-action: none`, `cursor: grab` |
| `.rankboard-slot.drop-target` | **Removed** -- live reorder replaces it (research.md §4) |
| `.modal-backdrop`, `.modal-card`, `.modal-card.wide` | **New `@media (max-width: 700px)` block**: backdrop padding 24→8px, card padding →14px, wide `max-height` 80vh→92vh |

The 44px floor on the handle is not negotiable and is the same rule finding 10
applied to the chip: the grip glyph is the ornament, the 44px box is the target.

**Note for whoever implements the modal block**: `.modal-backdrop` and
`.modal-card` are shared with SeatPopover, the settings modal and the start-mock
modal. The phone-width rules must be scoped so they do not silently restyle three
other surfaces -- scope them to the ballot modal, or verify all four.
