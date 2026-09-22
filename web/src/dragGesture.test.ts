import { describe, expect, it } from 'vitest'
import { autoScrollStep, clampScroll, targetSlotFor, type SlotSpan } from './dragGesture'

/**
 * The twelve cases quickstart.md §1 requires, over fabricated geometry.
 *
 * Rows are 48px apart because that is what the real board is: a 44px
 * min-height chip plus the 4px gap between slots (styles.css). Row i spans
 * [48i, 48i + 44], so its midpoint is 48i + 22.
 */
const ROW_H = 44
const ROW_PITCH = 48

function rows(count: number): SlotSpan[] {
  return Array.from({ length: count }, (_, i) => ({ top: i * ROW_PITCH, bottom: i * ROW_PITCH + ROW_H }))
}

/** The midpoint of row i in the geometry above -- the threshold a pointer
 *  must pass before that row gives way. */
function midpointOf(i: number): number {
  return i * ROW_PITCH + ROW_H / 2
}

describe('targetSlotFor', () => {
  it('stays put while the pointer is inside its own row', () => {
    const spans = rows(12)
    expect(targetSlotFor(midpointOf(5), spans, 5)).toBe(5)
    expect(targetSlotFor(spans[5].top, spans, 5)).toBe(5)
    expect(targetSlotFor(spans[5].bottom, spans, 5)).toBe(5)
  })

  it('stays put when the pointer is past a neighbour\'s near edge but not its midpoint', () => {
    const spans = rows(12)
    // Just into row 6's box, well short of row 6's midpoint: this is the
    // hysteresis. Without it the board swaps here and then immediately swaps
    // back, because the swap moves the boundary under the pointer.
    const justInsideRowBelow = spans[6].top + 2
    expect(justInsideRowBelow).toBeLessThan(midpointOf(6))
    expect(targetSlotFor(justInsideRowBelow, spans, 5)).toBe(5)

    const justInsideRowAbove = spans[4].bottom - 2
    expect(justInsideRowAbove).toBeGreaterThan(midpointOf(4))
    expect(targetSlotFor(justInsideRowAbove, spans, 5)).toBe(5)
  })

  it('moves down one once the pointer passes the midpoint of the row below', () => {
    const spans = rows(12)
    expect(targetSlotFor(midpointOf(6), spans, 5)).toBe(6)
    expect(targetSlotFor(midpointOf(6) + 1, spans, 5)).toBe(6)
  })

  it('moves up one once the pointer passes the midpoint of the row above', () => {
    const spans = rows(12)
    expect(targetSlotFor(midpointOf(4), spans, 5)).toBe(4)
    expect(targetSlotFor(midpointOf(4) - 1, spans, 5)).toBe(4)
  })

  it('travels several rows at once when the pointer has moved that far', () => {
    const spans = rows(12)
    expect(targetSlotFor(midpointOf(9), spans, 2)).toBe(9)
    expect(targetSlotFor(midpointOf(1), spans, 10)).toBe(1)
  })

  it('clamps to the first and last rank', () => {
    const spans = rows(12)
    expect(targetSlotFor(-10_000, spans, 6)).toBe(0)
    expect(targetSlotFor(10_000, spans, 6)).toBe(11)
  })

  it('is stateless -- the same arguments give the same answer', () => {
    const spans = rows(12)
    const first = targetSlotFor(midpointOf(8) + 3, spans, 3)
    const second = targetSlotFor(midpointOf(8) + 3, spans, 3)
    expect(second).toBe(first)
    expect(targetSlotFor(midpointOf(8) + 3, spans, 3)).toBe(first)
  })

  it('never returns an index outside the board', () => {
    const spans = rows(12)
    for (const y of [-5000, -1, 0, 37, 260, 575, 5000]) {
      for (const from of [0, 5, 11]) {
        const got = targetSlotFor(y, spans, from)
        expect(got).toBeGreaterThanOrEqual(0)
        expect(got).toBeLessThanOrEqual(11)
      }
    }
  })

  it('will not jump a chip past a row it cannot measure', () => {
    // A slot ref can be momentarily null mid-render. Stopping at it is the
    // conservative read: an unmeasured row is not a row we know we cleared.
    const spans: (SlotSpan | null)[] = rows(12)
    spans[8] = null
    expect(targetSlotFor(midpointOf(10), spans, 5)).toBe(7)
  })
})

describe('autoScrollStep', () => {
  const TOP = 100
  const BOTTOM = 500
  const ZONE = 64
  const MAX = 12

  it('is zero in the middle of the container', () => {
    expect(autoScrollStep(300, TOP, BOTTOM, ZONE, MAX)).toBe(0)
    expect(autoScrollStep(TOP + ZONE, TOP, BOTTOM, ZONE, MAX)).toBe(0)
    expect(autoScrollStep(BOTTOM - ZONE, TOP, BOTTOM, ZONE, MAX)).toBe(0)
  })

  it('is negative in the top zone', () => {
    expect(autoScrollStep(TOP + 10, TOP, BOTTOM, ZONE, MAX)).toBeLessThan(0)
  })

  it('is positive in the bottom zone', () => {
    expect(autoScrollStep(BOTTOM - 10, TOP, BOTTOM, ZONE, MAX)).toBeGreaterThan(0)
  })

  it('grows with depth into the zone', () => {
    const shallow = Math.abs(autoScrollStep(TOP + 50, TOP, BOTTOM, ZONE, MAX))
    const deep = Math.abs(autoScrollStep(TOP + 5, TOP, BOTTOM, ZONE, MAX))
    expect(deep).toBeGreaterThan(shallow)

    const shallowDown = autoScrollStep(BOTTOM - 50, TOP, BOTTOM, ZONE, MAX)
    const deepDown = autoScrollStep(BOTTOM - 5, TOP, BOTTOM, ZONE, MAX)
    expect(deepDown).toBeGreaterThan(shallowDown)
  })

  it('never exceeds maxPxPerFrame, even far outside the container', () => {
    for (const y of [-10_000, TOP - 200, TOP, BOTTOM, BOTTOM + 200, 10_000]) {
      expect(Math.abs(autoScrollStep(y, TOP, BOTTOM, ZONE, MAX))).toBeLessThanOrEqual(MAX)
    }
  })

  it('is zero for a degenerate container or zone', () => {
    expect(autoScrollStep(300, 500, 100, ZONE, MAX)).toBe(0)
    expect(autoScrollStep(300, TOP, BOTTOM, 0, MAX)).toBe(0)
  })
})

describe('clampScroll', () => {
  it('clamps past either end', () => {
    expect(clampScroll(-40, 1000, 400)).toBe(0)
    expect(clampScroll(9999, 1000, 400)).toBe(600)
  })

  it('leaves a position inside the range alone', () => {
    expect(clampScroll(250, 1000, 400)).toBe(250)
  })

  it('returns 0 when the content does not overflow', () => {
    expect(clampScroll(120, 400, 400)).toBe(0)
    expect(clampScroll(120, 300, 400)).toBe(0)
  })
})
