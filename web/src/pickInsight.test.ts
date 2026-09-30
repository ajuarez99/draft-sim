import { describe, expect, it } from 'vitest'
import type { PlayerRef, RealPick, Seat } from './api'
import {
  adpDelta,
  adpLabel,
  buildFactInsight,
  buildStartState,
  fillsFor,
  missingPickNos,
  openAfter,
  seatComplete,
  summarize,
} from './pickInsight'

const NFL_TEMPLATE = ['QB', 'RB', 'RB', 'WR', 'WR', 'TE', 'FLEX', 'FLEX', 'K', 'DEF', 'BN', 'BN']
const NBA_TEMPLATE = ['PG', 'SG', 'G', 'SF', 'PF', 'F', 'C', 'UTIL', 'UTIL', 'BN', 'BN']

let nextId = 1
function player(position: string, adp = 50): PlayerRef {
  const id = nextId++
  return {
    id,
    sleeperId: String(id),
    name: `${position}${id}`,
    position: position as PlayerRef['position'],
    team: 'SEA',
    adp,
    positionalRank: 1,
  }
}

describe('fillsFor / openAfter', () => {
  it('an RB into RB1-filled / RB2-open fills RB2', () => {
    expect(fillsFor('nfl', NFL_TEMPLATE, [player('RB')], player('RB'))).toBe('RB2')
  })

  it('a sixth WR fills nothing (depth)', () => {
    // WR1, WR2, FLEX, FLEX all taken by five receivers.
    const prior = [player('WR', 1), player('WR', 2), player('WR', 3), player('WR', 4), player('WR', 5)]
    expect(fillsFor('nfl', NFL_TEMPLATE, prior, player('WR', 6))).toBeNull()
  })

  it('basketball: a PG with PG filled and G open fills G, not UTIL', () => {
    expect(fillsFor('nba', NBA_TEMPLATE, [player('PG')], player('PG'))).toBe('G')
  })

  it('openAfter lists the slots still empty once the player is added, in roster order', () => {
    const open = openAfter('nfl', NFL_TEMPLATE, [player('QB')], player('RB'))
    expect(open).toEqual(['RB', 'WR', 'WR', 'TE', 'FLEX', 'FLEX', 'K', 'DEF'])
  })

  it('a final pick leaves openAfter empty', () => {
    const prior = [
      player('QB'), player('RB', 1), player('RB', 2), player('WR', 1), player('WR', 2),
      player('TE'), player('WR', 3), player('RB', 3), player('K'),
    ]
    expect(openAfter('nfl', NFL_TEMPLATE, prior, player('DEF'))).toEqual([])
  })
})

describe('summarize', () => {
  it('names the slot filled, what is still open, and a position with no player yet', () => {
    const s = summarize('nfl', 'RB2', ['TE', 'FLEX'], [player('RB'), player('RB')])
    expect(s).toContain('RB2')
    expect(s).toContain('TE')
    expect(s).toContain('FLEX')
    expect(s).toContain('QB')
  })

  it('says depth, not a slot, when nothing was filled', () => {
    const s = summarize('nfl', null, ['TE'], [player('WR')])
    expect(s.toLowerCase()).toContain('depth')
    expect(s).not.toMatch(/fills/i)
  })

  it('says the roster is complete, with no "still open", when nothing is left', () => {
    const s = summarize('nfl', null, [], [player('QB'), player('RB'), player('WR'), player('TE')])
    expect(s.toLowerCase()).toContain('roster complete')
    expect(s.toLowerCase()).not.toContain('still open')
  })

  it('uses basketball positions for basketball, never football ones', () => {
    const s = summarize('nba', 'G', ['SG', 'UTIL'], [player('PG'), player('PG')])
    expect(s).toContain('G')
    expect(s).toContain('UTIL')
    expect(s).toContain('C')
    expect(s).not.toMatch(/\b(QB|TE|FLEX|RB|WR)\b/)
  })
})

function seatOf(provenance: Seat['provenance'], draftsObserved: number): Seat {
  return {
    slot: 1,
    managerId: 1,
    manager: 'Sam',
    avatarId: null,
    provenance,
    reachBias: 0,
    relativeReachBias: null,
    relativeReachStdErr: null,
    unpredictability: 1,
    positionalTilt: {},
    note: null,
    draftsObserved,
    picksScored: 0,
  }
}

function realPick(pickNo: number, slot: number, p: PlayerRef): RealPick {
  return { pickNo, round: 1, slot, manager: `M${slot}`, avatarId: null, player: p }
}

describe('buildFactInsight', () => {
  const rb1 = player('RB', 5)
  const rb2 = player('RB', 9)
  const landed = [realPick(1, 1, rb1), realPick(2, 2, player('QB')), realPick(3, 1, rb2)]

  it('fills the fact fields from landed picks only, with no projection fields', () => {
    const i = buildFactInsight(landed[2], landed, [seatOf('FITTED', 2)], 'nfl', NFL_TEMPLATE)
    expect(i.fills).toBe('RB2')
    expect(i.openAfter).not.toContain('RB')
    expect(i.openAfter).toContain('QB')
    expect(i.rosterComplete).toBe(false)
    expect(i.modelShare).toBeNull()
    expect(i.likelyNext).toEqual({ state: 'none', reason: 'no projection yet' })
    expect(i.summary).toContain('RB2')
  })

  it('does not count another seat\'s picks as this seat\'s roster', () => {
    const i = buildFactInsight(landed[1], landed, [seatOf('FITTED', 2)], 'nfl', NFL_TEMPLATE)
    expect(i.fills).toBe('QB')
  })

  it('labels provenance exactly per the seat', () => {
    const at = (p: Seat['provenance'], n: number) =>
      buildFactInsight(landed[0], landed, [seatOf(p, n)], 'nfl', NFL_TEMPLATE).provenanceLabel
    expect(at('FITTED', 3)).toBe('fitted from 3 drafts')
    expect(at('BLENDED', 2)).toBe('fitted from 2 drafts')
    expect(at('STATED', 0)).toBe('stated by you')
    expect(at('NEUTRAL', 0)).toBe('no history — neutral seat')
  })

  it('reports no fit when the league has no roster template', () => {
    const i = buildFactInsight(landed[0], landed, [seatOf('FITTED', 1)], 'nfl', [])
    expect(i.fitKnown).toBe(false)
  })
})

describe('adpDelta / adpLabel', () => {
  it('ADP 30 taken at pick 16 is +14 and reads "before ADP", never "past"', () => {
    const d = adpDelta(player('WR', 30), 16)
    expect(d).toBe(14)
    expect(adpLabel(d)).toBe('14 before ADP')
    expect(adpLabel(d)).not.toMatch(/past/)
  })

  it('ADP 10 taken at pick 19 is -9 and reads "past ADP"', () => {
    const d = adpDelta(player('WR', 10), 19)
    expect(d).toBe(-9)
    expect(adpLabel(d)).toBe('9 past ADP')
  })

  it('within one pick of ADP reads "On ADP"', () => {
    expect(adpLabel(adpDelta(player('WR', 20), 20))).toBe('On ADP')
    expect(adpLabel(adpDelta(player('WR', 20), 21))).toBe('On ADP')
    expect(adpLabel(adpDelta(player('WR', 20), 19))).toBe('On ADP')
    expect(adpLabel(adpDelta(player('WR', 20), 22))).toBe('2 past ADP')
  })

  it('no ADP (999, like fallbackPlayerRef) gives null and no tag', () => {
    const offBoard = { ...player('WR'), adp: 999, positionalRank: 999 }
    expect(adpDelta(offBoard, 10)).toBeNull()
    expect(adpLabel(null)).toBeNull()
    expect(adpDelta({ ...player('WR'), adp: Number.NaN }, 10)).toBeNull()
  })
})

describe('missingPickNos / seatComplete', () => {
  it('lists the numbers absent from 1..picksMade', () => {
    expect(missingPickNos([{ pickNo: 1 }, { pickNo: 2 }, { pickNo: 5 }], 6)).toEqual([3, 4, 6])
    expect(missingPickNos([], 0)).toEqual([])
  })

  it('a hole only spoils the seat that owned the missing pick (plain snake, 4 teams)', () => {
    // Pick 6 is round 2, reversed: slot 3 owns it.
    expect(seatComplete(3, [6], 4, 0)).toBe(false)
    expect(seatComplete(2, [6], 4, 0)).toBe(true)
    expect(seatComplete(1, [], 4, 0)).toBe(true)
  })

  it('honours a reversal round: round 3 repeats round 2 direction', () => {
    // reversalRound 3: round 3 runs right to left, so pick 9 belongs to slot 4.
    expect(seatComplete(4, [9], 4, 3)).toBe(false)
    expect(seatComplete(1, [9], 4, 3)).toBe(true)
    // Plain snake would give pick 9 to slot 1.
    expect(seatComplete(1, [9], 4, 0)).toBe(false)
  })
})

describe('buildStartState', () => {
  const mk = (pickNo: number, sleeperId: string | null = `s${pickNo}`) => ({ pickNo, player: { sleeperId } })

  it('sends the whole prefix and stamps the highest pick when complete', () => {
    expect(buildStartState([mk(1), mk(2), mk(3)], [])).toEqual({
      startState: { 1: 's1', 2: 's2', 3: 's3' },
      asOfPick: 3,
    })
  })

  it('sends nothing when a pick is missing', () => {
    expect(buildStartState([mk(1), mk(3)], [2])).toEqual({ asOfPick: null })
  })

  it('sends nothing when any landed pick lacks a sleeperId', () => {
    const r = buildStartState([mk(1), mk(2, ''), mk(3)], [])
    expect(r).toEqual({ asOfPick: null })
    expect('startState' in r).toBe(false)
  })

  it('never sends an empty object', () => {
    expect(buildStartState([], [])).toEqual({ asOfPick: null })
  })
})
