import { afterEach, describe, expect, it, vi } from 'vitest'
import { readPickCardsPref, writePickCardsPref } from './pickCardsPref'

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
})

describe('pick cards preference', () => {
  it('defaults to on when nothing is stored', () => {
    expect(readPickCardsPref()).toBe(true)
  })

  it('round-trips off and on under bk.pickCards.v1', () => {
    writePickCardsPref(false)
    expect(localStorage.getItem('bk.pickCards.v1')).toBe('off')
    expect(readPickCardsPref()).toBe(false)
    writePickCardsPref(true)
    expect(readPickCardsPref()).toBe(true)
  })

  it('reads as the default when storage throws, and a write is a no-op', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    expect(readPickCardsPref()).toBe(true)
    expect(() => writePickCardsPref(false)).not.toThrow()
  })
})
