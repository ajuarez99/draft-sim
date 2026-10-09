import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DEFAULT_COLUMNS } from './statLeaderboard'
import { readStatChoice, resetStatChoice, writeStatChoice } from './statChoice'

const KEY = 'bk.draftStats.v1'

beforeEach(() => localStorage.clear())
afterEach(() => vi.restoreAllMocks())

describe('statChoice (spec 023 US2)', () => {
  it('reads the defaults when nothing was ever chosen', () => {
    expect(readStatChoice()).toEqual([...DEFAULT_COLUMNS])
  })

  it('reads the defaults for another version or invalid JSON', () => {
    localStorage.setItem(KEY, JSON.stringify({ v: 2, columns: ['gp'] }))
    expect(readStatChoice()).toEqual([...DEFAULT_COLUMNS])
    localStorage.setItem(KEY, '{not json')
    expect(readStatChoice()).toEqual([...DEFAULT_COLUMNS])
    localStorage.setItem(KEY, JSON.stringify({ v: 1, columns: 'gp' }))
    expect(readStatChoice()).toEqual([...DEFAULT_COLUMNS])
  })

  it('skips unknown ids and drops duplicates', () => {
    localStorage.setItem(KEY, JSON.stringify({ v: 1, columns: ['gp', 'bogus', 'gp'] }))
    expect(readStatChoice()).toEqual(['gp'])
  })

  it('skips an id the picker does not offer, such as a Draft value column', () => {
    localStorage.setItem(KEY, JSON.stringify({ v: 1, columns: ['pick', 'usg'] }))
    expect(readStatChoice()).toEqual(['usg'])
  })

  it('treats an empty list as a real choice, not as absent', () => {
    localStorage.setItem(KEY, JSON.stringify({ v: 1, columns: [] }))
    expect(readStatChoice()).toEqual([])
  })

  it('round-trips a written choice in order', () => {
    writeStatChoice(['usg', 'fp', 'gp'])
    expect(readStatChoice()).toEqual(['usg', 'fp', 'gp'])
    expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual({ v: 1, columns: ['usg', 'fp', 'gp'] })
  })

  it('reads the defaults when storage throws, and does not throw on write', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    expect(readStatChoice()).toEqual([...DEFAULT_COLUMNS])
    expect(() => writeStatChoice(['gp'])).not.toThrow()
    expect(() => resetStatChoice()).not.toThrow()
  })

  it('reset writes the defaults explicitly and returns them', () => {
    writeStatChoice(['usg'])
    expect(resetStatChoice()).toEqual([...DEFAULT_COLUMNS])
    expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual({ v: 1, columns: [...DEFAULT_COLUMNS] })
  })
})
