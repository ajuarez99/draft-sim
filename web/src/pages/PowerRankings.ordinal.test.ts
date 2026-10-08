import { describe, expect, it } from 'vitest'
import type { PowerRankingEntry } from '../api'
import { roomTakeSentence } from './PowerRankings'

/**
 * specs/021-codebase-cleanup T026, the spec's one named FR-001 exception.
 * PowerRankings.tsx had its own `n === 1 ? 'st' : …` ordinal. It was right for every
 * n up to 20, and wrong from 21 on ("21th"). It now uses format.ts's ordinal. Rank 21
 * means a league of 21 or more teams, which no current league has, so this is the
 * whole visible change.
 */
describe('Power Rankings ordinals past 20', () => {
  const entry = (best: number, worst: number) =>
    ({ rosterId: 1, rank: best, bestRank: best, worstRank: worst, ballotCount: 3 }) as unknown as PowerRankingEntry

  it('says 21st, 22nd and 23rd, not 21th', () => {
    expect(roomTakeSentence(entry(21, 21), 24)).toBe('Ranked 21st on every ballot')
    expect(roomTakeSentence(entry(22, 23), 24)).toBe('22nd or 23rd on every ballot')
  })
})
