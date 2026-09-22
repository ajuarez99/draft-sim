# Phase 1 Data Model: Reordering a ranking on a phone

**Feature**: 007-mobile-rank-drag | **Date**: 2026-09-22

**There is no persistent data in this feature.** No table, no column, no endpoint,
no request or response shape changes. Everything below is transient client state
that exists between a finger touching the screen and lifting off it, plus the one
already-persisted entity it edits.

Stating that explicitly matters, because a reader arriving at "data model" for a
drag fix should be able to stop after this paragraph and know that the ballot
storage, `MemberRankingService`, and the submitted-ordering contract are all out of
scope.

---

## Persisted entity (existing, unchanged)

### Ranking

An ordered list of every roster in the league, rank 1 first.

| Field | Type | Notes |
|---|---|---|
| ordering | `number[]` | Roster ids, index 0 = rank 1 |
| week | `number` | Unchanged |
| submittedAt | timestamp | Unchanged |

**Invariants** (already enforced in `web/src/rankOrder.ts`, unchanged by this
feature):

- Length equals league size.
- Every roster id appears exactly once; none missing, none repeated (FR-006).
- Submitted only when complete (FR-020).
- Local until submitted (FR-019).

**Editing rule**: placing a chip at a new rank splices it out and reinserts it,
shifting the ranks between by one. It is **not** a swap (FR-005). This is
`move(order, from, to)` at `rankOrder.ts:104-112` and it is already correct --
verified by reading, listed here because FR-005 asserts it and a reader should know
where it lives rather than assume it needs writing.

---

## Transient client state

All of it lives in `RankBoard.tsx` refs or state. None survives a release, a
remount, or an interruption (FR-014).

### RankOrder (existing)

`(ChipId | null)[]` -- the working ordering, one entry per rank, `null` for an
unfilled slot. Already the component's `order` state. Unchanged in shape.

Under live reordering (research.md §4) this now mutates **during** a drag rather
than only at its end. That is the one behavioural change to an existing structure,
and its consequence is that `measureRects()`'s existing `useEffect` on `order`
re-runs mid-drag, which is wanted.

### DragSession (existing, extended)

One per active gesture. Held in a ref so pointer moves do not render.

| Field | Type | Status | Purpose |
|---|---|---|---|
| pointerId | `number` | existing | Identifies the gesture across events |
| chipId | `ChipId` | existing | The team being moved |
| origin | `{kind:'tray'} \| {kind:'slot', index}` | existing | Where it started, for FR-012 |
| startX, startY | `number` | existing | Threshold origin |
| grabDX, grabDY | `number` | existing | Ghost offset from the pointer |
| lastX, lastY | `number` | existing | Latest pointer position |
| **pointerType** | `'mouse' \| 'pen' \| 'touch'` | **new** | Gates the handle requirement (FR-021) |
| **startedOnHandle** | `boolean` | **new** | True when pointerdown landed on the grip |
| **scrollParent** | `Element \| null` | **new** | Resolved once per drag; null means the viewport |
| **currentSlot** | `number \| null` | **new** | Where the chip sits right now under live reorder |

`lastX`/`lastY` stop being merely diagnostic: the autoscroll loop reads them every
frame to recompute the target rank while the finger is still (research.md §4).

### AutoScroll (new)

| Field | Type | Purpose |
|---|---|---|
| rafId | `number \| null` | Cancellation handle; must be cleared in `endDrag` |
| velocity | `number` | Signed px per frame; `0` means the loop idles |

**Lifecycle**: created when the drag threshold is crossed, cancelled in `endDrag`
-- which is already the single exit point for `pointerup`, `pointercancel` and
`lostpointercapture`, so FR-014 is satisfied by routing through it rather than by
adding new cleanup.

**Invariant**: `rafId` is null whenever `dragRef.current` is null. A loop that
outlives its drag scrolls a list nobody is touching.

### SlotGeometry (existing, re-validated more often)

`slotRectsRef: (DOMRect | null)[]` and `trayRectRef: DOMRect | null`.

Unchanged in shape. What changes is **when** it is refreshed: today only on `order`
change and at threshold-crossing; from here also on `scroll` of the resolved scroll
parent and on `resize`, for the duration of the drag (research.md §0 and §4).

**Invariant**: never read a rect measured before the last scroll. This is the
invariant the shipped code violates and the one the whole "lands on the wrong row"
class comes from.

### Selection (existing, unchanged)

`selected: ChipId | null` -- the tap-to-place path's held state. Gains a visible
consequence (the up/down buttons appear on the selected chip, FR-016) but no new
field.

---

## State transitions

```text
                    pointerdown on chip
                            │
         pointerType==='touch' && !startedOnHandle
                            ├──────────────► ignored, browser scrolls (FR-007)
                            │
                            ▼
                      armed (no capture yet)
                            │
                  moved ≥ 6px  ──► capture on container, resolve scrollParent,
                            │       measure rects, start rAF loop
                            ▼
                        dragging ◄─────────┐
                            │              │ pointer in edge zone:
                            │              │ scroll + recompute target each frame
                            │              │ crossing a row midpoint: commit move()
                            │              └─ (live reorder, FR-004/FR-011)
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
    pointerup         pointercancel      lostpointercapture
        │                   │                   │
        └───────────────────┴───────────────────┘
                            ▼
                      endDrag(pointerId)
              cancel rAF, remove scroll/resize listeners,
              release capture, clear ghost, announce once
```

Every terminal edge goes through `endDrag`, which is already true in the shipped
component and is the reason FR-014 is cheap: the new teardown (rAF, listeners) has
exactly one place to live.
