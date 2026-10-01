import { describe, expect, it } from 'vitest'
import { extremes } from './extremes'

describe('extremes', () => {
  it('returns null with fewer than two values or no spread', () => {
    expect(extremes([], true)).toBeNull()
    expect(extremes([3], true)).toBeNull()
    expect(extremes([null, 3, undefined], true)).toBeNull()
    expect(extremes([2, 2, 2], false)).toBeNull()
  })

  it('flips best and worst with the direction', () => {
    expect(extremes([1, 5, 3], true)).toEqual({ best: 5, worst: 1 })
    expect(extremes([1, 5, 3], false)).toEqual({ best: 1, worst: 5 })
  })

  it('is value-based, so every tied row matches best or worst', () => {
    const values = [9, 9, 4, 1, 1]
    const ex = extremes(values, true)!
    expect(values.filter((v) => v === ex.best)).toHaveLength(2)
    expect(values.filter((v) => v === ex.worst)).toHaveLength(2)
  })
})
