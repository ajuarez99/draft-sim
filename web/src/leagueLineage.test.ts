import { describe, expect, it } from 'vitest'
import { leagueLineages } from './leagueLineage'
import type { DraftSummary } from './api'

// Shapes mirror what /api/drafts actually returns for Allan's leagues -- two
// seasons of "West Coast Fantasy Football" and "(Foot) Ball Knowers", plus a
// single-season league, which is the case that must NOT get a season list.
function draft(over: Partial<DraftSummary> & Pick<DraftSummary, 'sleeperLeagueId' | 'season' | 'leagueName'>): DraftSummary {
  return {
    id: Number(over.sleeperLeagueId),
    sleeperDraftId: `d${over.sleeperLeagueId}`,
    leagueId: Number(over.sleeperLeagueId),
    teams: 12,
    rounds: 15,
    status: 'complete',
    startTime: null,
    previousLeagueId: null,
    ...over,
  }
}

describe('leagueLineages', () => {
  it('collapses two seasons of one league into a single entry', () => {
    const out = leagueLineages([
      draft({ sleeperLeagueId: '2', season: 2026, leagueName: 'West Coast', previousLeagueId: '1' }),
      draft({ sleeperLeagueId: '1', season: 2025, leagueName: 'West Coast' }),
    ])
    expect(out).toHaveLength(1)
    expect(out[0].current.season).toBe(2026)
    expect(out[0].seasons.map((s) => s.season)).toEqual([2026, 2025])
  })

  it('keeps unrelated leagues apart even when they share a name', () => {
    // The reason the key is the chain and not the name.
    const out = leagueLineages([
      draft({ sleeperLeagueId: '10', season: 2026, leagueName: 'Dynasty' }),
      draft({ sleeperLeagueId: '20', season: 2026, leagueName: 'Dynasty' }),
    ])
    expect(out).toHaveLength(2)
  })

  it('keeps one league together across a rename', () => {
    // The other reason: matching on name would split this into two cards.
    const out = leagueLineages([
      draft({ sleeperLeagueId: '2', season: 2026, leagueName: 'New Name', previousLeagueId: '1' }),
      draft({ sleeperLeagueId: '1', season: 2025, leagueName: 'Old Name' }),
    ])
    expect(out).toHaveLength(1)
    expect(out[0].current.leagueName).toBe('New Name')
  })

  it('groups a three-season chain regardless of input order', () => {
    const out = leagueLineages([
      draft({ sleeperLeagueId: '1', season: 2024, leagueName: 'L' }),
      draft({ sleeperLeagueId: '3', season: 2026, leagueName: 'L', previousLeagueId: '2' }),
      draft({ sleeperLeagueId: '2', season: 2025, leagueName: 'L', previousLeagueId: '1' }),
    ])
    expect(out).toHaveLength(1)
    expect(out[0].seasons.map((s) => s.season)).toEqual([2026, 2025, 2024])
  })

  it('groups seasons whose chain continues into years nobody ingested', () => {
    // 2025 points at a 2024 league that was never ingested. It is still the
    // earliest season we hold, so it is still the group key.
    const out = leagueLineages([
      draft({ sleeperLeagueId: '2', season: 2026, leagueName: 'L', previousLeagueId: '1' }),
      draft({ sleeperLeagueId: '1', season: 2025, leagueName: 'L', previousLeagueId: 'not-ingested' }),
    ])
    expect(out).toHaveLength(1)
    expect(out[0].seasons).toHaveLength(2)
  })

  it('leaves a single-season league as a lineage of one', () => {
    const out = leagueLineages([draft({ sleeperLeagueId: '7', season: 2026, leagueName: 'Solo' })])
    expect(out[0].seasons).toHaveLength(1)
  })

  it('orders leagues by their most recent season', () => {
    const out = leagueLineages([
      draft({ sleeperLeagueId: '1', season: 2024, leagueName: 'Older' }),
      draft({ sleeperLeagueId: '2', season: 2026, leagueName: 'Newer' }),
    ])
    expect(out.map((l) => l.current.leagueName)).toEqual(['Newer', 'Older'])
  })

  it('keeps only the newest draft when a league has more than one', () => {
    // `drafts` arrives newest-first, so the first row seen per league wins.
    const out = leagueLineages([
      draft({ sleeperLeagueId: '1', season: 2026, leagueName: 'L', sleeperDraftId: 'newest' }),
      draft({ sleeperLeagueId: '1', season: 2026, leagueName: 'L', sleeperDraftId: 'older' }),
    ])
    expect(out).toHaveLength(1)
    expect(out[0].current.sleeperDraftId).toBe('newest')
  })

  it('survives a cycle rather than hanging the picker', () => {
    const out = leagueLineages([
      draft({ sleeperLeagueId: 'a', season: 2026, leagueName: 'L', previousLeagueId: 'b' }),
      draft({ sleeperLeagueId: 'b', season: 2025, leagueName: 'L', previousLeagueId: 'a' }),
    ])
    expect(out).toHaveLength(1)
  })

  it('returns nothing for no drafts', () => {
    expect(leagueLineages([])).toEqual([])
  })
})
