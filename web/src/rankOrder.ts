// Pure array operations behind RankBoard.tsx (claude/power-rankings-ballots.md
// "The drag board", finding 18 in claude/plan-review-power-rankings-ballots.md).
//
// jsdom implements neither `Element.setPointerCapture` nor a real layout
// engine -- `getBoundingClientRect()` returns all zeros in this harness -- so
// no slot-hit test can ever run under vitest. Every bit of *ordering* logic
// therefore lives here, as plain array transforms with no React and no DOM,
// so it's the one part of the board that can be unit-tested at all. RankBoard
// imports these and never reorders its `order` array by hand.
//
// A `RankOrder` is one entry per numbered slot (index 0 == rank 1), holding
// the chip id placed there or `null` for an empty slot. A chip not present
// anywhere in the array is "in the tray", unranked -- there is no separate
// tray array; `unplacedOf` derives it from the full member-id list instead,
// which is what lets "I haven't touched him yet" and "I put him here" stay
// distinguishable (see the design doc's "why not pre-seed the tray").

export type ChipId = number
export type RankOrder = ReadonlyArray<ChipId | null>

/** An all-empty board with `slotCount` numbered slots. */
export function makeEmptyOrder(slotCount: number): RankOrder {
  return Array<ChipId | null>(slotCount).fill(null)
}

/** The 0-based slot a chip currently occupies, or null if it's unplaced. */
export function slotOf(order: RankOrder, chipId: ChipId): number | null {
  const i = order.indexOf(chipId)
  return i === -1 ? null : i
}

/** True once every slot holds a chip -- the gate for "ready to submit". */
export function isComplete(order: RankOrder): boolean {
  return order.every((c) => c !== null)
}

/**
 * The full ranking as roster ids, rank 1 first -- or null while any slot is
 * still empty. Never returns a partial list; the caller has nothing sensible
 * to POST until every slot is filled.
 */
export function toRosterIds(order: RankOrder): ChipId[] | null {
  return isComplete(order) ? (order as ChipId[]) : null
}

/** Every member id not currently occupying a slot, in `allChipIds` order. */
export function unplacedOf(order: RankOrder, allChipIds: readonly ChipId[]): ChipId[] {
  const placed = new Set<ChipId>()
  for (const c of order) if (c !== null) placed.add(c)
  return allChipIds.filter((id) => !placed.has(id))
}

function assertSlotInRange(order: RankOrder, slot: number) {
  if (!Number.isInteger(slot) || slot < 0 || slot >= order.length) {
    throw new RangeError(`slot ${slot} is out of range for a ${order.length}-slot board`)
  }
}

/**
 * Place `chipId` into `slot` -- the tray-to-slot drop, and the click-to-place
 * path when the selected chip was unplaced. If `chipId` was already sitting
 * in a different slot, it's removed from there first (so `place` is also a
 * safe way to relocate an already-placed chip in one step, e.g. seeding an
 * initial order). If `slot` is already occupied, that occupant is evicted --
 * it simply stops appearing in the returned order, which is what makes it
 * "unplaced": the caller doesn't need a separate eviction step, and dropping
 * a chip onto a full slot reads as "this slot is now this chip" rather than
 * silently doing nothing. Reordering *among* already-placed chips is `move`,
 * not this -- `place` always treats its target as a direct overwrite.
 */
export function place(order: RankOrder, chipId: ChipId, slot: number): RankOrder {
  assertSlotInRange(order, slot)
  const from = order.indexOf(chipId)
  if (from === slot) return order
  const next = order.slice()
  if (from !== -1) next[from] = null
  next[slot] = chipId
  return next
}

/**
 * Remove `chipId` from wherever it's placed, returning it to the tray. A
 * no-op if it's already unplaced.
 */
export function unplace(order: RankOrder, chipId: ChipId): RankOrder {
  const from = order.indexOf(chipId)
  if (from === -1) return order
  const next = order.slice()
  next[from] = null
  return next
}

/**
 * Reorder the board by moving whatever is at slot `from` to slot `to` --
 * the slot-to-slot drag ("drag between slots to reorder"). This is a plain
 * remove-then-insert (`Array.splice` twice), so everything between the two
 * indices shifts by one to close the gap and make room, the way dragging an
 * item up or down a list works everywhere else. It operates on slot
 * *indices*, not chip ids -- deliberately, per finding 18 -- and treats an
 * empty slot exactly like a chip: reordering never invents or destroys
 * emptiness, it just carries whatever was at `from` (chip or hole) to `to`
 * and shifts the rest to fill in behind it.
 */
export function move(order: RankOrder, from: number, to: number): RankOrder {
  assertSlotInRange(order, from)
  assertSlotInRange(order, to)
  if (from === to) return order
  const next = order.slice()
  const [item] = next.splice(from, 1)
  next.splice(to, 0, item)
  return next
}

/**
 * Move whichever chip is at `chipId`'s current slot up (`delta < 0`) or down
 * (`delta > 0`) by `delta` slots, clamped to the board -- the ArrowUp/
 * ArrowDown keyboard path. A no-op if the chip is unplaced (nothing to move)
 * or already at the clamped edge.
 */
export function moveChipBy(order: RankOrder, chipId: ChipId, delta: number): RankOrder {
  const from = slotOf(order, chipId)
  if (from === null) return order
  const to = Math.min(Math.max(from + delta, 0), order.length - 1)
  if (to === from) return order
  return move(order, from, to)
}

/**
 * "1st"/"2nd"/"3rd"/"4th"... for a 1-based rank, for the aria-live
 * announcement ("Placed 4th of 12"). Handles the 11th/12th/13th exception
 * (not "11st") -- easy to get wrong, worth its own tests.
 */
export function ordinal(n: number): string {
  const mod100 = n % 100
  if (mod100 >= 11 && mod100 <= 13) return `${n}th`
  switch (n % 10) {
    case 1:
      return `${n}st`
    case 2:
      return `${n}nd`
    case 3:
      return `${n}rd`
    default:
      return `${n}th`
  }
}
