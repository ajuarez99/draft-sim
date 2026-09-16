import { describe, expect, it } from 'vitest'
import {
  FOCUS_CAP,
  FOCUS_SLOTS,
  managerHues,
  opacityFor,
  roleFor,
  strokeFor,
  NO_PINS,
  pinnedCount,
  pinsFull,
  toggleSelection,
  widthFor,
  type PinSlots,
} from './managerColor'

/**
 * The guard test. The palette is not a taste question -- it is the output of a
 * validator run, and the only way it regresses is somebody adding a fourth
 * "obviously fine" colour without re-running it. So the expected value is
 * spelled out here and the failure message carries the command.
 */
describe('FOCUS_SLOTS', () => {
  it('is exactly the validated triple', () => {
    expect(
      FOCUS_SLOTS,
      'FOCUS_SLOTS changed. Re-run the palette gate before accepting this:\n' +
        '  node scripts/validate_palette.js "' +
        FOCUS_SLOTS.join(',') +
        '" --mode dark --surface "#09121c" --pairs all\n' +
        'It must report ALL CHECKS PASS. Three is the measured ceiling for crossing\n' +
        'lines on this surface, not a preference -- see research.md R2.\n' +
        'Validate WITH crimson (#d33a3c) in the set. The reader owns a crimson line\n' +
        'that is on screen at the same time, so four colours are visible at once --\n' +
        'checking these three alone once hid an orange slot sitting delta-E 6.7 from\n' +
        'crimson, which is two lines the same colour.',
    ).toEqual(['#3987e5', '#199e70', '#c98500'])
  })

  it('caps at three, derived from the array', () => {
    expect(FOCUS_CAP).toBe(3)
    expect(FOCUS_CAP).toBe(FOCUS_SLOTS.length)
  })

  /**
   * The bug the rendered chart caught and the unit tests did not: the palette
   * was validated as three colours, but four are on screen -- the reader's own
   * crimson line is always there too. The orange slot was delta-E 6.7 from
   * crimson under normal vision, well under the floor of 15.
   *
   * This asserts the shape of the mistake rather than re-implementing the
   * validator: no pin slot may sit in crimson's own corner of the hue wheel.
   */
  it('keeps every pin slot perceptually clear of the crimson the reader wears', () => {
    // --crimson, oklch(58% 0.19 25), resolved to hex for comparison.
    const CRIMSON = '#d33a3c'

    // OKLab, the same space the palette validator measures in. sRGB hue angle
    // is NOT a substitute: it rates the yellow slot 15deg from crimson while
    // OKLab -- which accounts for lightness and chroma -- puts them 17 apart,
    // comfortably clear. Getting this wrong once already nearly cost a good
    // palette, so the real metric is spelled out rather than approximated.
    const oklab = (hex: string) => {
      const lin = (c: number) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
      const [r, g, b] = [1, 3, 5].map((i) => lin(parseInt(hex.slice(i, i + 2), 16) / 255))
      const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
      const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
      const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
      return [
        0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
      ]
    }
    const deltaE = (a: string, b: string) => {
      const [x, y, z] = oklab(a)
      const [p, q, r] = oklab(b)
      return Math.hypot(x - p, y - q, z - r) * 100
    }

    for (const slot of FOCUS_SLOTS) {
      const d = deltaE(slot, CRIMSON)
      // The validator's normal-vision floor. The orange slot that shipped in the
      // first draft of this file scored 6.7 here.
      expect(d, `${slot} is only deltaE ${d.toFixed(1)} from crimson`).toBeGreaterThanOrEqual(15)
    }
  })
})

describe('roleFor', () => {
  it('assigns pin slots in pin order, not array order', () => {
    const selection: PinSlots = [7, 3, 11]
    expect(roleFor(7, selection, null)).toBe('focus-1')
    expect(roleFor(3, selection, null)).toBe('focus-2')
    expect(roleFor(11, selection, null)).toBe('focus-3')
  })

  it('gives the reader their own line when nothing is pinned', () => {
    expect(roleFor(4, NO_PINS, 4)).toBe('me')
    expect(roleFor(5, NO_PINS, 4)).toBe('context')
  })

  it('lets an explicit pin win over "this is you"', () => {
    // Pinning is something the reader just did; being yourself is not news.
    expect(roleFor(4, [4, null, null], 4)).toBe('focus-1')
  })

  it('recedes everyone else', () => {
    expect(roleFor(9, [7, 3, null], 4)).toBe('context')
  })

  it('reads the slot, not the pin count -- a hole does not shift anyone', () => {
    // Slot 1 is free; the roster in slot 2 is still focus-2.
    expect(roleFor(3, [null, 3, 11], null)).toBe('focus-2')
    expect(roleFor(11, [null, 3, 11], null)).toBe('focus-3')
  })
})

describe('toggleSelection', () => {
  it('pins into the first free slot and releases the slot it holds', () => {
    expect(toggleSelection(NO_PINS, 5)).toEqual([5, null, null])
    expect(toggleSelection([5, null, null], 5)).toEqual(NO_PINS)
  })

  /**
   * FR-003, and the bug that shipped in the first draft of this file.
   *
   * With a plain list, releasing the first of three shifted the survivors up and
   * repainted them: aqua became blue, yellow became aqua. The unit test asserted
   * that as if it were correct. It took clicking it in a browser to see that two
   * lines changing colour because a THIRD one left is exactly the disorientation
   * the requirement forbids.
   */
  it('leaves every surviving pin on its own colour when the first is released', () => {
    const before: PinSlots = [7, 3, 11]
    expect(roleFor(3, before, null)).toBe('focus-2')
    expect(roleFor(11, before, null)).toBe('focus-3')

    const after = toggleSelection(before, 7)

    expect(after).toEqual([null, 3, 11])
    // Unchanged. That is the whole point.
    expect(roleFor(3, after, null)).toBe('focus-2')
    expect(roleFor(11, after, null)).toBe('focus-3')
    expect(roleFor(7, after, null)).toBe('context')
  })

  it('reuses a freed slot rather than growing', () => {
    const withHole: PinSlots = [null, 3, 11]
    expect(toggleSelection(withHole, 8)).toEqual([8, 3, 11])
  })

  it('refuses a fourth pin rather than conjuring a fourth colour', () => {
    const full: PinSlots = [1, 2, 3]
    expect(pinsFull(full)).toBe(true)
    expect(toggleSelection(full, 4)).toEqual([1, 2, 3])
    expect(roleFor(4, toggleSelection(full, 4), null)).toBe('context')
  })

  it('counts only the filled slots', () => {
    expect(pinnedCount(NO_PINS)).toBe(0)
    expect(pinnedCount([null, 3, null])).toBe(1)
    expect(pinsFull([null, 3, null])).toBe(false)
  })
})

describe('strokeFor / widthFor / opacityFor', () => {
  it('maps each role to a stroke', () => {
    expect(strokeFor('me')).toBe('var(--crimson)')
    expect(strokeFor('context')).toBe('var(--muted)')
    expect(strokeFor('focus-1')).toBe('#3987e5')
    expect(strokeFor('focus-2')).toBe('#199e70')
    expect(strokeFor('focus-3')).toBe('#c98500')
  })

  it('lets pinned lines read first and context recede', () => {
    expect(widthFor('focus-1')).toBeGreaterThan(widthFor('me'))
    expect(widthFor('me')).toBeGreaterThan(widthFor('context'))
    expect(opacityFor('context')).toBeLessThan(1)
    expect(opacityFor('me')).toBe(1)
  })
})

describe('managerHues', () => {
  const rosters = (ids: [number, number | null][]) =>
    ids.map(([rosterId, managerId]) => ({ rosterId, managerId }))

  it('spreads fourteen rosters far enough apart to actually tell apart', () => {
    const fourteen = rosters(Array.from({ length: 14 }, (_, i) => [i + 1, i + 1]))
    const hues = [...managerHues(fourteen).values()].map((h) => h.hue).sort((a, b) => a - b)

    let min = 360
    for (let i = 1; i < hues.length; i++) min = Math.min(min, hues[i] - hues[i - 1])

    // The old hueFor scored 1 here: ids 1-9 landed on hues 49-57 and ids 10-19
    // on 127-136, which is the two-colour legend this feature was opened about.
    expect(min).toBeGreaterThanOrEqual(25)
  })

  it('is stable when the input array is reordered', () => {
    const a = rosters([
      [1, 30],
      [2, 10],
      [3, 20],
    ])
    const b = rosters([
      [3, 20],
      [1, 30],
      [2, 10],
    ])
    expect([...managerHues(a)]).toEqual([...managerHues(b)])
  })

  it('orders by managerId, not by rosterId or by array position', () => {
    const byManager = managerHues(
      rosters([
        [99, 1],
        [1, 2],
      ]),
    )
    expect(byManager.get(99)!.index).toBe(0)
    expect(byManager.get(1)!.index).toBe(1)
  })

  it('falls back to rosterId when a manager id is missing', () => {
    const mixed = managerHues(
      rosters([
        [5, null],
        [2, null],
      ]),
    )
    expect(mixed.get(2)!.index).toBe(0)
    expect(mixed.get(5)!.index).toBe(1)
  })

  it('returns an empty map for no rosters rather than dividing by zero', () => {
    expect(managerHues([]).size).toBe(0)
  })
})
