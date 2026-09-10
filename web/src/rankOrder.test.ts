import { describe, expect, it } from 'vitest'
import {
  isComplete,
  makeEmptyOrder,
  move,
  moveChipBy,
  ordinal,
  place,
  slotOf,
  toRosterIds,
  unplace,
  unplacedOf,
  type RankOrder,
} from './rankOrder'

describe('makeEmptyOrder', () => {
  it('makes an all-null board of the given length', () => {
    expect(makeEmptyOrder(4)).toEqual([null, null, null, null])
    expect(makeEmptyOrder(0)).toEqual([])
  })
})

describe('slotOf', () => {
  it('finds a placed chip', () => {
    expect(slotOf([null, 7, null], 7)).toBe(1)
  })

  it('returns null for an unplaced chip', () => {
    expect(slotOf([null, 7, null], 3)).toBeNull()
  })
})

describe('isComplete / toRosterIds', () => {
  it('is incomplete while any slot is null, and toRosterIds returns null', () => {
    const order: RankOrder = [1, null, 3]
    expect(isComplete(order)).toBe(false)
    expect(toRosterIds(order)).toBeNull()
  })

  it('is complete once every slot is filled, and toRosterIds returns rank-1-first', () => {
    const order: RankOrder = [3, 1, 2]
    expect(isComplete(order)).toBe(true)
    expect(toRosterIds(order)).toEqual([3, 1, 2])
  })

  it('an empty board (0 slots) is trivially complete', () => {
    expect(isComplete([])).toBe(true)
    expect(toRosterIds([])).toEqual([])
  })
})

describe('unplacedOf', () => {
  it('is every member id not currently in a slot, in the given order', () => {
    const order: RankOrder = [2, null, null]
    expect(unplacedOf(order, [1, 2, 3, 4])).toEqual([1, 3, 4])
  })

  it('is empty once the board is full', () => {
    const order: RankOrder = [3, 1, 2]
    expect(unplacedOf(order, [1, 2, 3])).toEqual([])
  })

  it('is everyone when the board is untouched', () => {
    const order = makeEmptyOrder(3)
    expect(unplacedOf(order, [1, 2, 3])).toEqual([1, 2, 3])
  })
})

describe('place', () => {
  it('drops an unplaced chip into an empty slot', () => {
    const order = makeEmptyOrder(3)
    expect(place(order, 5, 1)).toEqual([null, 5, null])
  })

  it('does not mutate the input array', () => {
    const order = makeEmptyOrder(3)
    place(order, 5, 1)
    expect(order).toEqual([null, null, null])
  })

  it('relocates an already-placed chip, clearing its old slot', () => {
    const order: RankOrder = [5, null, null]
    expect(place(order, 5, 2)).toEqual([null, null, 5])
  })

  it('placing into an occupied slot evicts the previous occupant to the tray', () => {
    const order: RankOrder = [1, 2, 3]
    // chip 1 (tray-bound in spirit -- but place() doesn't care where it came
    // from) takes slot 1 away from chip 2, which now simply isn't in `order`
    // anywhere -- i.e. it's unplaced.
    const next = place(order, 1, 1)
    expect(next).toEqual([null, 1, 3])
    expect(unplacedOf(next, [1, 2, 3])).toEqual([2])
  })

  it('placing a chip into the slot it already occupies is a no-op (same reference)', () => {
    const order: RankOrder = [null, 5, null]
    expect(place(order, 5, 1)).toBe(order)
  })

  it('throws on an out-of-range slot', () => {
    const order = makeEmptyOrder(3)
    expect(() => place(order, 1, 3)).toThrow(RangeError)
    expect(() => place(order, 1, -1)).toThrow(RangeError)
  })
})

describe('unplace', () => {
  it('clears the slot a chip occupies', () => {
    const order: RankOrder = [null, 7, null]
    expect(unplace(order, 7)).toEqual([null, null, null])
  })

  it('is a no-op (same reference) for a chip that is already unplaced', () => {
    const order: RankOrder = [null, 7, null]
    expect(unplace(order, 99)).toBe(order)
  })

  it('does not mutate the input array', () => {
    const order: RankOrder = [null, 7, null]
    unplace(order, 7)
    expect(order).toEqual([null, 7, null])
  })
})

describe('move', () => {
  it('moving to a later slot shifts everything between up by one', () => {
    // Classic list-reorder: drag rank-1 (1) down to rank-3. 2 and 3, which
    // sat at ranks 2 and 3, both move up one to fill the gap 1 left behind.
    const order: RankOrder = [1, 2, 3, 4]
    expect(move(order, 0, 2)).toEqual([2, 3, 1, 4])
  })

  it('moving to an earlier slot shifts everything between down by one', () => {
    const order: RankOrder = [1, 2, 3, 4]
    expect(move(order, 3, 1)).toEqual([1, 4, 2, 3])
  })

  it('moving by one slot is a plain swap-adjacent', () => {
    const order: RankOrder = [1, 2, 3]
    expect(move(order, 1, 2)).toEqual([1, 3, 2])
  })

  it('carries an empty slot along like any other item -- a mostly-empty board just relocates the one chip', () => {
    const order: RankOrder = [1, null, null, null, null]
    expect(move(order, 0, 3)).toEqual([null, null, null, 1, null])
  })

  it('moving a slot to itself is a no-op (same reference)', () => {
    const order: RankOrder = [1, 2, 3]
    expect(move(order, 1, 1)).toBe(order)
  })

  it('does not mutate the input array', () => {
    const order: RankOrder = [1, 2, 3]
    move(order, 0, 2)
    expect(order).toEqual([1, 2, 3])
  })

  it('throws on an out-of-range index, on either end', () => {
    const order: RankOrder = [1, 2, 3]
    expect(() => move(order, 0, 3)).toThrow(RangeError)
    expect(() => move(order, -1, 1)).toThrow(RangeError)
  })
})

describe('moveChipBy', () => {
  it('moves up (toward rank 1) on a negative delta', () => {
    const order: RankOrder = [1, 2, 3]
    expect(moveChipBy(order, 3, -1)).toEqual([1, 3, 2])
  })

  it('moves down (toward the last rank) on a positive delta', () => {
    const order: RankOrder = [1, 2, 3]
    expect(moveChipBy(order, 1, 1)).toEqual([2, 1, 3])
  })

  it('clamps at the top edge instead of throwing', () => {
    const order: RankOrder = [1, 2, 3]
    expect(moveChipBy(order, 1, -1)).toBe(order)
  })

  it('clamps at the bottom edge instead of throwing', () => {
    const order: RankOrder = [1, 2, 3]
    expect(moveChipBy(order, 3, 1)).toBe(order)
  })

  it('is a no-op for an unplaced chip', () => {
    const order: RankOrder = [1, null, 3]
    expect(moveChipBy(order, 99, 1)).toBe(order)
  })

  it('a large delta still clamps to the nearest edge rather than overshooting', () => {
    const order: RankOrder = [1, 2, 3, 4]
    expect(moveChipBy(order, 1, 100)).toEqual([2, 3, 4, 1])
  })
})

describe('ordinal', () => {
  it('handles the common cases', () => {
    expect(ordinal(1)).toBe('1st')
    expect(ordinal(2)).toBe('2nd')
    expect(ordinal(3)).toBe('3rd')
    expect(ordinal(4)).toBe('4th')
    expect(ordinal(9)).toBe('9th')
  })

  it('11th/12th/13th are the exception to the mod-10 rule, not "11st"', () => {
    expect(ordinal(11)).toBe('11th')
    expect(ordinal(12)).toBe('12th')
    expect(ordinal(13)).toBe('13th')
  })

  it('picks back up correctly right after the exception', () => {
    expect(ordinal(14)).toBe('14th')
    expect(ordinal(21)).toBe('21st')
    expect(ordinal(22)).toBe('22nd')
    expect(ordinal(23)).toBe('23rd')
  })

  it('the exception recurs every hundred (111th-113th), not just 11-13', () => {
    expect(ordinal(111)).toBe('111th')
    expect(ordinal(112)).toBe('112th')
    expect(ordinal(113)).toBe('113th')
    expect(ordinal(121)).toBe('121st')
  })
})

describe('a full drag-and-drop-free walkthrough (the click/keyboard path)', () => {
  it('place, reorder via move, then unplace, ends with the expected board', () => {
    let order = makeEmptyOrder(4)
    order = place(order, 10, 0)
    order = place(order, 20, 1)
    order = place(order, 30, 2)
    expect(unplacedOf(order, [10, 20, 30, 40])).toEqual([40])
    expect(isComplete(order)).toBe(false)

    order = place(order, 40, 3)
    expect(isComplete(order)).toBe(true)
    expect(toRosterIds(order)).toEqual([10, 20, 30, 40])

    // Arrow-key 40 up from last to first.
    order = moveChipBy(order, 40, -3)
    expect(toRosterIds(order)).toEqual([40, 10, 20, 30])

    // Unplace 20, ranking drops back to incomplete.
    order = unplace(order, 20)
    expect(isComplete(order)).toBe(false)
    expect(unplacedOf(order, [10, 20, 30, 40])).toEqual([20])
    expect(slotOf(order, 20)).toBeNull()
  })
})
