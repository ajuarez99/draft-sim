import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DEFAULT_SAVED_SETTINGS, SAVED_KEY, SAVED_MAX, readSaved, removeSaved, renameSaved, saveFormula } from './statCalcSaved'

/** specs/032 T021, contract C3. */
const settings = { ...DEFAULT_SAVED_SETTINGS, mode: 'per36' as const, top: 20, standardize: true }
const raw = () => localStorage.getItem(SAVED_KEY)

beforeEach(() => localStorage.clear())
afterEach(() => vi.restoreAllMocks())

describe('statCalcSaved', () => {
  it('round-trips a formula with its settings, under the versioned key', () => {
    const r = saveFormula({ name: 'Mine', expr: 'ast / tov', settings }, [], new Date('2026-10-12T18:04:00Z'))
    expect(r.saved).toBe(true)
    expect(r.notice).toBeNull()
    const stored = JSON.parse(raw()!)
    expect(stored.v).toBe(1)
    expect(stored.formulas).toHaveLength(1)
    expect(stored.formulas[0]).toMatchObject({ name: 'Mine', expr: 'ast / tov', settings, savedAt: '2026-10-12T18:04:00.000Z' })
    expect('stale' in stored.formulas[0]).toBe(false)
    expect(readSaved()).toEqual([{ ...stored.formulas[0], stale: false }])
  })

  it('does not save filters: only the six settings are kept', () => {
    saveFormula({ name: 'x', expr: 'pts', settings: { ...settings, position: 'C', team: 'LAL' } as never })
    expect(Object.keys(JSON.parse(raw()!).formulas[0].settings).sort()).toEqual(['dir', 'mode', 'qualifiedOnly', 'standardize', 'top', 'window'])
  })

  it('reads an absent, unparseable or wrong-version store as empty', () => {
    expect(readSaved()).toEqual([])
    localStorage.setItem(SAVED_KEY, '{not json')
    expect(readSaved()).toEqual([])
    localStorage.setItem(SAVED_KEY, JSON.stringify({ v: 2, formulas: [{ id: 'a', name: 'n', expr: 'pts', settings }] }))
    expect(readSaved()).toEqual([])
    localStorage.setItem(SAVED_KEY, JSON.stringify([1, 2]))
    expect(readSaved()).toEqual([])
    localStorage.setItem(SAVED_KEY, JSON.stringify({ v: 1, formulas: 'no' }))
    expect(readSaved()).toEqual([])
  })

  it('skips malformed entries and duplicate ids, and repairs bad settings', () => {
    localStorage.setItem(SAVED_KEY, JSON.stringify({
      v: 1,
      formulas: [
        null, 'x', { id: 1, name: 'n', expr: 'pts' }, { id: 'a', name: 'n' }, { id: 'b', name: 'n', expr: '  ' },
        { id: 'ok', name: 'Good', expr: 'pts', settings: { mode: 'bogus', top: 999, dir: 'asc' }, savedAt: '2026-01-01T00:00:00Z' },
        { id: 'ok', name: 'Dup', expr: 'ast' },
      ],
    }))
    const list = readSaved()
    expect(list.map((f) => f.id)).toEqual(['ok'])
    expect(list[0].settings).toEqual({ ...DEFAULT_SAVED_SETTINGS, top: 50, dir: 'asc' })
  })

  it('keeps an entry whose expression no longer parses, flagged stale', () => {
    localStorage.setItem(SAVED_KEY, JSON.stringify({
      v: 1,
      formulas: [
        { id: 'a', name: 'Old', expr: 'pts + gone_stat', settings, savedAt: '' },
        { id: 'b', name: 'Fine', expr: 'pts', settings, savedAt: '' },
      ],
    }))
    expect(readSaved().map((f) => [f.id, f.stale])).toEqual([['a', true], ['b', false]])
    // an unrelated write keeps the stale entry, and does not store the flag
    saveFormula({ name: 'New', expr: 'ast', settings })
    const stored = JSON.parse(raw()!).formulas
    expect(stored.map((f: { id: string }) => f.id).slice(0, 2)).toEqual(['a', 'b'])
    expect(stored[0].stale).toBeUndefined()
  })

  it('the 51st entry drops the oldest and returns a notice', () => {
    for (let i = 0; i < SAVED_MAX; i++) saveFormula({ name: `f${i}`, expr: 'pts', settings })
    expect(readSaved()).toHaveLength(SAVED_MAX)
    const r = saveFormula({ name: 'newest', expr: 'ast', settings })
    expect(r.list).toHaveLength(SAVED_MAX)
    expect(r.list[0].name).toBe('f1')
    expect(r.list.at(-1)!.name).toBe('newest')
    expect(r.notice).toContain('f0')
    expect(r.notice).toContain('50')
  })

  it('caps a name at 40 characters and trims it', () => {
    const r = saveFormula({ name: `  ${'a'.repeat(60)}`, expr: 'pts', settings })
    expect(r.list[0].name).toBe('a'.repeat(40))
  })

  it('rename and remove re-read the store first, so another tab’s entry is not lost', () => {
    const a = saveFormula({ name: 'A', expr: 'pts', settings }).list[0]
    // "another tab" adds an entry the first tab's in-memory list doesn't know about
    saveFormula({ name: 'B', expr: 'ast', settings })
    const r = renameSaved(a.id, 'A2', [a])
    expect(r.list.map((f) => f.name)).toEqual(['A2', 'B'])
    const r2 = removeSaved(a.id, r.list)
    expect(r2.list.map((f) => f.name)).toEqual(['B'])
    expect(readSaved().map((f) => f.name)).toEqual(['B'])
  })

  it('a blocked store reads as empty; a failed write reports saved:false and keeps the in-memory list', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    expect(readSaved()).toEqual([])
    const first = saveFormula({ name: 'One', expr: 'pts', settings })
    expect(first.saved).toBe(false)
    expect(first.list.map((f) => f.name)).toEqual(['One'])
    // the page passes its in-memory list back, so the second save still sees the first
    const second = saveFormula({ name: 'Two', expr: 'ast', settings }, first.list)
    expect(second.saved).toBe(false)
    expect(second.list.map((f) => f.name)).toEqual(['One', 'Two'])
    expect(removeSaved(first.list[0].id, second.list).list.map((f) => f.name)).toEqual(['Two'])
  })
})
