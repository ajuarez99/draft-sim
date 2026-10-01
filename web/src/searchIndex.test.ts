import { describe, expect, it } from 'vitest'
import { buildSearchIndex, leagueDestinations, mergeManagers, searchDestinations } from './searchIndex'
import type { DraftSummary, ManagerSummary, Sport } from './api'

function draft(over: Partial<DraftSummary> = {}): DraftSummary {
  return {
    id: 1,
    sleeperDraftId: 'D1',
    leagueId: 1,
    leagueName: 'Ball Knowers',
    season: 2026,
    teams: 12,
    rounds: 15,
    status: 'complete',
    startTime: null,
    sleeperLeagueId: 'L1',
    previousLeagueId: null,
    sport: 'nfl',
    ...over,
  }
}

function manager(over: Partial<ManagerSummary> = {}): ManagerSummary {
  return {
    managerId: 1,
    manager: 'popsharky',
    avatarId: null,
    provenance: 'FITTED',
    effectiveReachBias: 0,
    empiricalReachBias: null,
    relativeReachBias: null,
    relativeReachStdErr: null,
    unpredictability: 1,
    positionalTilt: {},
    note: null,
    draftsObserved: 0,
    picksScored: 0,
    stated: {} as ManagerSummary['stated'],
    ...over,
  }
}

describe('league destinations', () => {
  it('offers a football league its pages, Analysis included', () => {
    const labels = leagueDestinations([draft()]).map((d) => d.label)
    expect(labels).toContain('Standings')
    expect(labels).toContain('Power rankings')
    expect(labels).toContain('Team strength')
  })

  // The sport gate is read from the destination table, not restated here --
  // which is the point. If the rail and the palette ever disagree about this,
  // one of them is reading a second copy of the rule.
  it('never offers Analysis for a basketball league', () => {
    const rows = leagueDestinations([draft({ sport: 'nba', leagueName: 'Hoops' })])
    expect(rows.map((d) => d.label)).not.toContain('Team strength')
    expect(rows.map((d) => d.label)).toContain('Power rankings')
  })

  it('never offers "Mock it", which opens a modal rather than going anywhere', () => {
    expect(leagueDestinations([draft()]).map((d) => d.label)).not.toContain('Mock it')
  })

  it('names the league on every row, so two leagues’ Standings are distinguishable', () => {
    const rows = leagueDestinations([
      draft({ sleeperLeagueId: 'L1', sleeperDraftId: 'D1', leagueName: 'Ball Knowers' }),
      draft({ id: 2, leagueId: 2, sleeperLeagueId: 'L2', sleeperDraftId: 'D2', leagueName: 'West Coast' }),
    ])
    const histories = rows.filter((r) => r.label === 'Standings')
    expect(histories).toHaveLength(2)
    expect(histories.map((h) => h.context).sort()).toEqual(['Ball Knowers', 'West Coast'])
    for (const r of rows) expect(r.context).not.toBe('')
  })

  it('lists each season of a multi-season league, distinguished by year', () => {
    const rows = leagueDestinations([
      draft({ sleeperLeagueId: 'L26', sleeperDraftId: 'D26', season: 2026, previousLeagueId: 'L25' }),
      draft({ id: 2, leagueId: 2, sleeperLeagueId: 'L25', sleeperDraftId: 'D25', season: 2025 }),
    ])
    const boards = rows.filter((r) => r.kind === 'season-board')
    expect(boards.map((b) => b.label).sort()).toEqual(['2025 board', '2026 board'])
    expect(boards.find((b) => b.label === '2025 board')?.href).toBe('/drafts/D25/board')
  })

  // specs/011 T010: the palette means "this league, now", so its league-page
  // results always use the newest season (searchIndex.ts builds
  // `{ lineage, season: current }`), whatever year the rail is showing.
  it('points every league-page result at the newest season of a multi-season league', () => {
    const rows = leagueDestinations([
      draft({ sleeperLeagueId: 'L26', sleeperDraftId: 'D26', season: 2026, previousLeagueId: 'L25' }),
      draft({ id: 2, leagueId: 2, sleeperLeagueId: 'L25', sleeperDraftId: 'D25', season: 2025 }),
    ]).filter((r) => r.kind === 'league-page')
    const leagueRows = rows.filter((r) => r.href.startsWith('/leagues/'))
    expect(leagueRows.length).toBeGreaterThan(5)
    for (const r of leagueRows) {
      expect(r.href, r.label).toMatch(/^\/leagues\/L26(\/|$)/)
    }
  })

  it('does not list seasons for a league that has only one', () => {
    expect(leagueDestinations([draft()]).filter((r) => r.kind === 'season-board')).toEqual([])
  })

  it('sends an unfinished season to the room rather than an empty board', () => {
    const rows = leagueDestinations([
      draft({ sleeperLeagueId: 'L26', sleeperDraftId: 'D26', season: 2026, status: 'pre_draft', previousLeagueId: 'L25' }),
      draft({ id: 2, leagueId: 2, sleeperLeagueId: 'L25', sleeperDraftId: 'D25', season: 2025 }),
    ])
    expect(rows.find((r) => r.label === '2026 board')?.href).toBe('/drafts/D26')
  })
})

describe('manager merge', () => {
  // The rule that costs the most to get wrong: ten of the twelve Ball Knowers
  // managers are the same Sleeper user in football and basketball, so a naive
  // per-sport concat lists most of the league twice.
  it('lists a manager who plays both sports once, carrying both sports', () => {
    const rows = mergeManagers([
      { sport: 'nfl', managers: [manager({ managerId: 7, manager: 'popsharky' })] },
      { sport: 'nba', managers: [manager({ managerId: 7, manager: 'popsharky' })] },
    ])

    expect(rows).toHaveLength(1)
    expect(rows[0].sports.sort()).toEqual(['nba', 'nfl'])
  })

  it('merges on manager id, not on display name', () => {
    const rows = mergeManagers([
      { sport: 'nfl', managers: [manager({ managerId: 1, manager: 'Chris' })] },
      { sport: 'nba', managers: [manager({ managerId: 2, manager: 'Chris' })] },
    ])

    // Two people who share a name are two people.
    expect(rows).toHaveLength(2)
  })

  it('keeps a single-sport manager single-sport', () => {
    const rows = mergeManagers([
      { sport: 'nfl', managers: [manager({ managerId: 3, manager: 'onlyball' })] },
      { sport: 'nba', managers: [] },
    ])

    expect(rows[0].sports).toEqual(['nfl'])
  })

  it('points a manager at their own history page', () => {
    expect(mergeManagers([{ sport: 'nfl', managers: [manager({ managerId: 9 })] }])[0].href).toBe(
      '/managers/9/history',
    )
  })
})

describe('searching', () => {
  const index = buildSearchIndex(
    [
      draft({ sleeperLeagueId: 'L1', sleeperDraftId: 'D1', leagueName: 'Ball Knowers' }),
      draft({ id: 2, leagueId: 2, sleeperLeagueId: 'L2', sleeperDraftId: 'D2', leagueName: 'West Coast Fantasy' }),
    ],
    [{ sport: 'nfl' as Sport, managers: [manager({ managerId: 4, manager: 'kieriskash' })] }],
  )

  it('returns everything for an empty query, so the palette opens as a list', () => {
    expect(searchDestinations(index, '')).toHaveLength(index.length)
    expect(searchDestinations(index, '   ')).toHaveLength(index.length)
  })

  it('finds a league by part of its name', () => {
    const hits = searchDestinations(index, 'knowers')
    expect(hits.length).toBeGreaterThan(0)
    for (const h of hits) expect(h.context).toBe('Ball Knowers')
  })

  it('finds a manager by part of their name', () => {
    const hits = searchDestinations(index, 'kieris')
    expect(hits.map((h) => h.label)).toContain('kieriskash')
  })

  it('finds a page across leagues by its own name', () => {
    const hits = searchDestinations(index, 'power')
    expect(hits.filter((h) => h.label === 'Power rankings')).toHaveLength(2)
  })

  it('matches a league and a page together', () => {
    const hits = searchDestinations(index, 'west coast history')
    expect(hits[0].label).toBe('Standings')
    expect(hits[0].context).toBe('West Coast Fantasy')
  })

  // specs/013 US3: pages were renamed for fans; the old names still find them.
  it('finds a renamed page by its former name', () => {
    const lookup = (q: string) => searchDestinations(index, q).map((h) => h.label)
    expect(lookup('expected wins')).toContain('Luck')
    expect(lookup('season forecast')).toContain('Playoff odds')
    expect(lookup('roster management')).toContain('Bench points')
    expect(lookup('weekly report')).toContain('Matchups & awards')
    expect(lookup('superlatives')).toContain('Awards')
    expect(lookup('analysis')).toContain('Team strength')
  })

  it('finds a page by its new name', () => {
    expect(searchDestinations(index, 'luck').map((h) => h.label)).toContain('Luck')
  })

  it('finds nothing for a query that matches nothing', () => {
    expect(searchDestinations(index, 'zzzzz')).toEqual([])
  })

  it('gives every row a stable unique id', () => {
    const ids = index.map((r) => r.id)
    expect(new Set(ids).size).toBe(ids.length)
  })
})
