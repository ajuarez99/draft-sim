import { describe, expect, it } from 'vitest'
import type { PredictedPick, SimulationResult } from './api'
import {
  asOfLabel,
  cellCandidates,
  isSurprise,
  likelyNext,
  modelShare,
  nextPickFor,
  type StampLike,
} from './pickInsight'
import { mkPlayer, mkRealPick } from './testLiveRoom'

const TEAMS = 4

function cell(pickNo: number, top: [ReturnType<typeof mkPlayer>, number], alts: [ReturnType<typeof mkPlayer>, number][], isModal = true): PredictedPick {
  return {
    pickNo,
    round: Math.ceil(pickNo / TEAMS),
    slot: 1,
    manager: 'Mgr1',
    avatarId: null,
    player: top[0],
    probability: top[1],
    isModal,
    alternatives: alts.map(([player, probability]) => ({ player, probability })),
  }
}

function stamp(asOfPick: number | null, cells: PredictedPick[], busy = false): StampLike {
  return busy
    ? { asOfPick, busy: true }
    : { asOfPick, busy: false, result: { board: cells } as unknown as SimulationResult }
}

describe('nextPickFor', () => {
  it('walks a plain snake', () => {
    // 4 teams: slot 1 picks 1, 8, 9, 16 ...
    expect(nextPickFor(1, 1, TEAMS, 4, 0)).toBe(8)
    expect(nextPickFor(1, 8, TEAMS, 4, 0)).toBe(9)
    expect(nextPickFor(3, 3, TEAMS, 4, 0)).toBe(6)
  })
  it('honours a reversal round', () => {
    // reversalRound 3: round 3 runs the same direction as round 2, so slot 1 picks 8 then 12.
    expect(nextPickFor(1, 8, TEAMS, 4, 3)).toBe(12)
  })
  it('is null when the seat has no pick left', () => {
    expect(nextPickFor(1, 16, TEAMS, 4, 0)).toBeNull()
    expect(nextPickFor(4, 13, TEAMS, 4, 0)).toBeNull()
  })
})

describe('cellCandidates', () => {
  it('sorts by share, and is not fooled by isModal false', () => {
    const a = mkPlayer('WR', 'Assigned')
    const b = mkPlayer('WR', 'MostVoted')
    const c = mkPlayer('WR', 'Third')
    const out = cellCandidates(cell(5, [a, 0.1], [[c, 0.2], [b, 0.6]], false))
    expect(out.map((x) => x.player.name)).toEqual(['MostVoted', 'Third', 'Assigned'])
    expect(out[0].player.name).not.toBe('Assigned')
  })
})

describe('likelyNext', () => {
  const pick = { pickNo: 4 }
  const a = mkPlayer('RB', 'A')
  const b = mkPlayer('RB', 'B')
  const c = mkPlayer('WR', 'C')
  const d = mkPlayer('WR', 'D')
  const none = new Set<number>()

  it('ready: top, at most two more, and wideOpen under the threshold', () => {
    const s = stamp(4, [cell(5, [a, 0.4], [[b, 0.3], [c, 0.2], [d, 0.1]])])
    const r = likelyNext([s], pick, 5, null, none)
    expect(r).toMatchObject({ state: 'ready', wideOpen: false })
    if (r.state !== 'ready') throw new Error()
    expect(r.top.player.name).toBe('A')
    expect(r.rest.map((x) => x.player.name)).toEqual(['B', 'C'])
    const wide = likelyNext([stamp(4, [cell(5, [a, 0.2], [[b, 0.1]])])], pick, 5, null, none)
    expect(wide).toMatchObject({ state: 'ready', wideOpen: true })
  })

  it('never uses a stale stamp that predates the pick', () => {
    const stale = stamp(3, [cell(5, [a, 0.9], [])])
    expect(likelyNext([stale], pick, 5, null, none)).toEqual({ state: 'none', reason: 'projection not back yet' })
  })

  it('never names a player already on the landed list', () => {
    const s = stamp(4, [cell(5, [a, 0.5], [[b, 0.3], [c, 0.1], [d, 0.05]])])
    const r = likelyNext([s], pick, 5, null, new Set([a.id]))
    if (r.state !== 'ready') throw new Error('expected ready')
    expect(r.top.player.name).toBe('B')
    expect([r.top, ...r.rest].some((x) => x.player.id === a.id)).toBe(false)
  })

  it('updating while a covering run is in flight and nothing covers yet', () => {
    expect(likelyNext([stamp(3, [])], pick, 5, 4, none)).toEqual({ state: 'updating' })
    // a run that started before the pick does not count
    expect(likelyNext([], pick, 5, 3, none)).toMatchObject({ state: 'none' })
  })

  it('busy when the newest attempt was refused', () => {
    expect(likelyNext([stamp(4, [], true)], pick, 5, null, none)).toEqual({ state: 'busy' })
    expect(likelyNext([stamp(null, [], true)], pick, 5, null, none)).toEqual({ state: 'busy' })
    // refused before this pick: irrelevant
    expect(likelyNext([stamp(2, [], true)], pick, 5, null, none)).toMatchObject({ state: 'none' })
  })

  it('none "no picks left" when there is no next pick, whatever the stamps say', () => {
    expect(likelyNext([stamp(4, [cell(5, [a, 0.9], [])])], pick, null, null, none)).toEqual({
      state: 'none',
      reason: 'no picks left',
    })
  })

  it('none when the stamp has no cell for the next pick', () => {
    expect(likelyNext([stamp(4, [cell(9, [a, 0.9], [])])], pick, 5, null, none)).toMatchObject({ state: 'none' })
  })
})

describe('modelShare', () => {
  const pick = mkRealPick(6, TEAMS, mkPlayer('WR', 'Taken'))
  const other = mkPlayer('WR', 'Other')
  const x = mkPlayer('RB', 'X')

  it('exact when the picked player is among the cell candidates', () => {
    const s = stamp(5, [cell(6, [other, 0.5], [[pick.player, 0.25], [x, 0.1]])])
    expect(modelShare([s], pick)).toEqual({ share: 0.25, bound: 'exact', asOfPick: 5 })
  })

  it('under the smallest shown share when he is not shown', () => {
    const s = stamp(5, [cell(6, [other, 0.5], [[x, 0.2], [mkPlayer('TE'), 0.07], [mkPlayer('QB'), 0.03]])])
    expect(modelShare([s], pick)).toEqual({ share: 0.03, bound: 'under', asOfPick: 5 })
  })

  it('exactly 0 when the cell shows fewer than four candidates and he is not one', () => {
    // Fewer than four means every player any run took here is listed.
    const s = stamp(5, [cell(6, [other, 0.6], [[x, 0.4]])])
    expect(modelShare([s], pick)).toEqual({ share: 0, bound: 'exact', asOfPick: 5 })
  })

  it('ignores a projection that already knew the pick, and busy ones', () => {
    const post = stamp(6, [cell(6, [pick.player, 1], [])])
    expect(modelShare([post], pick)).toBeNull()
    const pre = stamp(4, [cell(6, [pick.player, 0.3], [])])
    expect(modelShare([pre, stamp(5, [], true)], pick)).toEqual({ share: 0.3, bound: 'exact', asOfPick: 4 })
  })

  it('null with no stamps, or only a null-asOf one', () => {
    expect(modelShare([], pick)).toBeNull()
    expect(modelShare([stamp(null, [cell(6, [pick.player, 0.3], [])])], pick)).toBeNull()
  })
})

describe('isSurprise and asOfLabel', () => {
  it('exact is strict, under is inclusive at the threshold', () => {
    expect(isSurprise({ share: 0.049, bound: 'exact', asOfPick: 1 })).toBe(true)
    expect(isSurprise({ share: 0.05, bound: 'exact', asOfPick: 1 })).toBe(false)
    expect(isSurprise({ share: 0.05, bound: 'under', asOfPick: 1 })).toBe(true)
    expect(isSurprise({ share: 0.06, bound: 'under', asOfPick: 1 })).toBe(false)
    expect(isSurprise(null)).toBe(false)
  })

  it('labels "as of pick N" only when more than one pick stale', () => {
    expect(asOfLabel({ share: 0.2, bound: 'exact', asOfPick: 9 }, 10)).toBeNull()
    expect(asOfLabel({ share: 0.2, bound: 'exact', asOfPick: 8 }, 10)).toBe('as of pick 8')
    expect(asOfLabel(null, 10)).toBeNull()
  })
})
