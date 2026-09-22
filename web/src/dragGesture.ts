// Pure geometry behind RankBoard.tsx's touch drag
// (specs/007-mobile-rank-drag/, contracts §3).
//
// Same bargain as rankOrder.ts next door, and for the same reason: jsdom has
// no layout engine, so `getBoundingClientRect()` returns all zeros and no
// hit test can ever run under vitest. Everything here therefore takes plain
// numbers -- never a DOM node, never a DOMRect -- so the arithmetic that
// decides *which rank a finger is over* and *how fast to scroll* is testable,
// and only the plumbing that reads those numbers off the page is browser-only.
//
// rankOrder.ts owns what the ordering IS. This file owns where a pointer is
// pointing. Neither knows about React.

/** One row's vertical extent, viewport-relative -- the two fields of a
 *  DOMRect this module actually needs. */
export type SlotSpan = { top: number; bottom: number }

/**
 * The rank the dragged chip should occupy right now, given where the pointer
 * is and which rank the chip currently holds.
 *
 * Returns `fromIndex` unless the pointer has passed the MIDPOINT of a
 * neighbouring row in the direction of travel. The midpoint rather than the
 * edge is the whole point: swapping as soon as the pointer crosses a row
 * boundary oscillates, because each swap moves that boundary back under the
 * pointer and immediately re-triggers the opposite swap.
 *
 * `spans` may contain nulls -- RankBoard's slot refs are
 * `Array<HTMLLIElement | null>` and a row can be momentarily unmeasured
 * mid-render. A null span stops the scan rather than being skipped: never
 * jump a chip past a row whose position is unknown.
 *
 * Stateless. Two calls with the same arguments return the same answer.
 */
export function targetSlotFor(
  pointerY: number,
  spans: ReadonlyArray<SlotSpan | null>,
  fromIndex: number,
): number {
  const last = spans.length - 1
  if (last < 0) return 0
  const from = Math.min(Math.max(fromIndex, 0), last)

  const own = spans[from]
  // Unmeasured own row: no direction can be read, so hold still rather than
  // guess. The next pointermove after the next render will have a rect.
  if (!own) return from
  if (pointerY >= own.top && pointerY <= own.bottom) return from

  if (pointerY > own.bottom) {
    let target = from
    for (let i = from + 1; i <= last; i++) {
      const s = spans[i]
      if (!s) break
      if (pointerY < (s.top + s.bottom) / 2) break
      target = i
    }
    return target
  }

  let target = from
  for (let i = from - 1; i >= 0; i--) {
    const s = spans[i]
    if (!s) break
    if (pointerY > (s.top + s.bottom) / 2) break
    target = i
  }
  return target
}

/**
 * Signed pixels to scroll this frame while a chip is held near an edge.
 *
 * Zero anywhere in the middle of the container, negative in the top zone,
 * positive in the bottom zone, magnitude rising with depth into the zone and
 * capped at `maxPxPerFrame`. The caller still has to clamp the resulting
 * scroll position to the container's real ends -- see `clampScroll`.
 */
export function autoScrollStep(
  pointerY: number,
  containerTop: number,
  containerBottom: number,
  zonePx: number,
  maxPxPerFrame: number,
): number {
  if (!(zonePx > 0) || !(containerBottom > containerTop)) return 0

  // How far past the zone's inner boundary the pointer has reached. Negative
  // means it has not reached the zone at all. Beyond the container's own edge
  // this exceeds zonePx, which the ramp caps at full speed.
  const topDepth = zonePx - (pointerY - containerTop)
  const bottomDepth = zonePx - (containerBottom - pointerY)

  // A container shorter than two zones has them overlapping in the middle;
  // the deeper reading is the nearer edge, and it wins.
  if (topDepth > 0 && topDepth >= bottomDepth) return -ramp(topDepth, zonePx, maxPxPerFrame)
  if (bottomDepth > 0) return ramp(bottomDepth, zonePx, maxPxPerFrame)
  return 0
}

function ramp(depth: number, zonePx: number, maxPxPerFrame: number): number {
  return Math.min(depth / zonePx, 1) * maxPxPerFrame
}

/**
 * Clamp a proposed scrollTop to what the container can actually scroll.
 * Returns 0 when the content does not overflow at all -- a league small
 * enough to fit on screen, where there is nowhere to scroll to.
 */
export function clampScroll(next: number, scrollHeight: number, clientHeight: number): number {
  const max = scrollHeight - clientHeight
  if (!(max > 0)) return 0
  return Math.min(Math.max(next, 0), max)
}
