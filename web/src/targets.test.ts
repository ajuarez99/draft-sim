import { describe, expect, it } from 'vitest'
import type { AvailabilityRow } from './api'
import { mkPlayer } from './testLiveRoom'
import { fold, itemsFromServer, markTaken, matchesSearch, survivalFor, topAvailable, type TargetItem } from './targets'

const item = (name: string): TargetItem => {
  const player = mkPlayer('RB', name)
  return { sleeperId: player.sleeperId, name, player }
}

describe('fold / matchesSearch', () => {
  it('folds case and accents', () => {
    expect(fold('Nikola Jokić')).toBe('nikola jokic')
    expect(fold('Dāvis Bertāns')).toBe('davis bertans')
  })
  it('matches across accents and case', () => {
    expect(matchesSearch('jokic', 'Nikola Jokić')).toBe(true)
    expect(matchesSearch('JOKIĆ', 'Nikola Jokic')).toBe(true)
    expect(matchesSearch('luka', 'Nikola Jokić')).toBe(false)
  })
  it('does not filter on fewer than 2 characters', () => {
    expect(matchesSearch('', 'Nikola Jokić')).toBe(true)
    expect(matchesSearch('z', 'Nikola Jokić')).toBe(true)
    expect(matchesSearch(' z ', 'Nikola Jokić')).toBe(true)
    expect(matchesSearch('zz', 'Nikola Jokić')).toBe(false)
  })
})

describe('markTaken / topAvailable', () => {
  it('keeps taken targets in place, flagged', () => {
    const [a, b, c] = [item('A'), item('B'), item('C')]
    const marked = markTaken([a, b, c], new Set([a.player!.id, c.player!.id]))
    expect(marked.map((t) => [t.name, t.taken])).toEqual([['A', true], ['B', false], ['C', true]])
  })
  it('returns the first target not taken', () => {
    const [a, b, c] = [item('A'), item('B'), item('C')]
    expect(topAvailable([a, b, c], new Set([a.player!.id]))?.name).toBe('B')
    expect(topAvailable([a, b, c], new Set([a.player!.id, b.player!.id, c.player!.id]))).toBeUndefined()
  })
  it('a target missing from the board is never taken and never the top available', () => {
    const gone: TargetItem = { sleeperId: 'x', name: 'Gone', player: null }
    const b = item('B')
    expect(markTaken([gone], new Set([1, 2, 3]))[0].taken).toBe(false)
    expect(topAvailable([gone, b], new Set())?.name).toBe('B')
  })
  it('itemsFromServer puts board players first and keeps the missing ones', () => {
    const p = mkPlayer('WR', 'On Board')
    const items = itemsFromServer({ players: [p], missing: [{ sleeperId: 'm1', name: 'Off Board' }] })
    expect(items.map((t) => [t.name, t.player != null])).toEqual([['On Board', true], ['Off Board', false]])
  })
})

describe('survivalFor', () => {
  const p = mkPlayer('RB', 'Tracked')
  const avail: AvailabilityRow[] = [{ player: p, survivalByPick: { '20': 0.42, '33': 0 } }]
  it('returns the survival at the next pick', () => {
    expect(survivalFor(p, avail, 20, true)).toBe(0.42)
  })
  it('keeps a real zero as zero', () => {
    expect(survivalFor(p, avail, 33, true)).toBe(0)
  })
  it('is undefined, never 0, when the seat is unknown', () => {
    expect(survivalFor(p, avail, 20, false)).toBeUndefined()
  })
  it('is undefined when there is no next pick', () => {
    expect(survivalFor(p, avail, null, true)).toBeUndefined()
    expect(survivalFor(p, avail, undefined, true)).toBeUndefined()
  })
  it('is undefined when the player is absent from the table or the pick has no entry', () => {
    expect(survivalFor(mkPlayer('RB', 'Other'), avail, 20, true)).toBeUndefined()
    expect(survivalFor(p, avail, 99, true)).toBeUndefined()
    expect(survivalFor(p, undefined, 20, true)).toBeUndefined()
    expect(survivalFor(null, avail, 20, true)).toBeUndefined()
  })
})
