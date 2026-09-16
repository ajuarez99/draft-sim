import { describe, expect, it } from 'vitest'
import { hueForIndex, hueForName } from './hue'

/**
 * The first test this shared rule has ever had, which is most of why it was
 * wrong for so long: six call sites, no coverage, and a defect you only see by
 * plotting fourteen of its outputs next to each other.
 */

describe('hueForIndex', () => {
  it('spreads a fourteen-team league far enough apart to tell apart', () => {
    const hues = Array.from({ length: 14 }, (_, i) => hueForIndex(i, 14)).sort((a, b) => a - b)

    let min = 360
    for (let i = 1; i < hues.length; i++) min = Math.min(min, hues[i] - hues[i - 1])

    // For contrast: the old hash scored 1 here. Manager ids 1-9 landed on hues
    // 49-57 and ids 10-19 on 127-136, which is why a fourteen-team legend
    // showed two colours.
    expect(min).toBeGreaterThanOrEqual(25)
  })

  it('stays inside the wheel', () => {
    for (let n = 1; n <= 20; n++) {
      for (let i = 0; i < n; i++) {
        const h = hueForIndex(i, n)
        expect(h).toBeGreaterThanOrEqual(0)
        expect(h).toBeLessThan(360)
      }
    }
  })

  it('is pure -- same input, same hue', () => {
    expect(hueForIndex(3, 12)).toBe(hueForIndex(3, 12))
  })

  it('throws rather than silently painting everyone the same colour', () => {
    // Returning 0 for an empty set would look like a working chart in which
    // every line happened to be red.
    expect(() => hueForIndex(0, 0)).toThrow()
    expect(() => hueForIndex(-1, 4)).toThrow()
    expect(() => hueForIndex(4, 4)).toThrow()
  })
})

describe('hueForName', () => {
  /**
   * Renamed, not rewritten. It still hashes, it still clusters on sequential
   * seeds, and that is fine for league NAMES -- which is now the only thing it
   * is allowed to colour. Pinning the outputs here means a future "improvement"
   * to it has to be deliberate.
   */
  it('is byte-for-byte the old hueFor', () => {
    const old = (seed: string) => {
      let h = 0
      for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) % 360
      return h
    }
    for (const seed of ['The Room', 'Ball Knowers', 'dynasty-2', '', 'a', '101']) {
      expect(hueForName(seed)).toBe(old(seed))
    }
  })

  it('still clusters on sequential seeds -- documented, not fixed', () => {
    // This is the defect that made the feature necessary. It is asserted rather
    // than removed so nobody reaches for this function for manager identity and
    // rediscovers it on a chart.
    expect(hueForName('1')).toBe(49)
    expect(hueForName('9')).toBe(57)
    expect(Math.abs(hueForName('2') - hueForName('1'))).toBe(1)
  })
})
