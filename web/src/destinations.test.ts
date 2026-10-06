import { describe, expect, it } from 'vitest'
// The route table as source text. Vite's `?raw` rather than node:fs so this
// suite needs no @types/node and stays in the same module world as the app.
import appSource from './App.tsx?raw'
import {
  DESTINATION_GROUPS,
  LEAGUE_DESTINATIONS,
  destinationFromPath,
  destinationsFor,
  labelOf,
  leagueIdFromPath,
  type LeagueContext,
} from './destinations'
import type { DraftSummary, Sport } from './api'

/*
 * The invariants in specs/003-easier-navigation/contracts/destinations.md.
 *
 * The one that earns its keep is "every league-scoped route in App.tsx has a
 * row here" -- it reads App.tsx rather than a list copied into this file,
 * because a copied list is the third source of truth this whole module exists
 * to delete. Adding a league route without a destination row fails here.
 */

function draft(over: Partial<DraftSummary> = {}): DraftSummary {
  return {
    id: 1,
    sleeperDraftId: 'd100',
    leagueId: 1,
    leagueName: 'Test League',
    season: 2026,
    teams: 12,
    rounds: 15,
    status: 'complete',
    startTime: null,
    sleeperLeagueId: 'L100',
    previousLeagueId: null,
    sport: 'nfl',
    ...over,
  }
}

function ctx(over: Partial<DraftSummary> = {}): LeagueContext {
  const d = draft(over)
  return { lineage: { current: d, seasons: [d] }, season: d }
}

describe('the destination table', () => {
  it('has unique keys', () => {
    const keys = LEAGUE_DESTINATIONS.map((d) => d.key)
    expect(new Set(keys).size).toBe(keys.length)
  })

  // Invariant 3. Typed as required, so this guards the other half: a row that
  // is present but empty offers the page to nobody, which fails silently.
  it('gives every destination a non-empty sports list', () => {
    for (const d of LEAGUE_DESTINATIONS) {
      expect(d.sports.length, `${d.key} has no sports`).toBeGreaterThan(0)
    }
  })

  // Invariant 1. This is the test that would have caught the Analysis bug on
  // the day it shipped: Analysis had an href and no matcher, so a URL the rail
  // itself produced was unrecognisable to the rail.
  it('recognises every URL it can build', () => {
    for (const d of LEAGUE_DESTINATIONS) {
      if (d.isAction) continue
      expect(d.match, `${d.key} has no match`).not.toBeNull()
      const href = d.href(ctx({ status: d.key === 'live' ? 'drafting' : 'complete' }))
      expect(destinationFromPath(href), `${d.key} built ${href}, which no row matches`).toBe(d.key)
    }
  })

  it('pairs match with idKind and href in both directions', () => {
    for (const d of LEAGUE_DESTINATIONS) {
      if (d.isAction) {
        expect(d.match).toBeNull()
        expect(d.idKind).toBeNull()
      } else {
        expect(d.match).not.toBeNull()
        expect(d.idKind).not.toBeNull()
      }
    }
  })

  it('captures the right id kind from a built URL', () => {
    const c = ctx({ sleeperDraftId: 'D7', sleeperLeagueId: 'L7', status: 'complete' })
    expect(leagueIdFromPath('/drafts/D7/board')).toEqual({ idKind: 'draft', id: 'D7' })
    expect(leagueIdFromPath('/leagues/L7/history')).toEqual({ idKind: 'league', id: 'L7' })
    expect(leagueIdFromPath('/leagues/L7/analysis')).toEqual({ idKind: 'league', id: 'L7' })
    expect(leagueIdFromPath(c.lineage.current.sleeperLeagueId)).toBeNull()
  })

  it('matches no id outside a league', () => {
    for (const p of ['/', '/managers', '/managers/12/history', '/mock/4', '/mock/new', '/nope']) {
      expect(destinationFromPath(p), p).toBeNull()
      expect(leagueIdFromPath(p), p).toBeNull()
    }
  })

  it('keeps the bare draft route and its /board form the same destination', () => {
    expect(destinationFromPath('/drafts/D7')).toBe('board')
    expect(destinationFromPath('/drafts/D7/board')).toBe('board')
    expect(destinationFromPath('/drafts/D7/live')).toBe('live')
  })

  it('treats the dev-only verify harness as inside the league', () => {
    expect(destinationFromPath('/leagues/L7/power/verify')).toBe('power')
    expect(leagueIdFromPath('/leagues/L7/power/verify')).toEqual({ idKind: 'league', id: 'L7' })
  })
})

describe('destinationsFor', () => {
  // Invariant 4. The gate is one-way: football-only means basketball never
  // sees it, not that basketball sees a broken version of it.
  it('never offers Analysis to a basketball league', () => {
    const keys = destinationsFor(ctx({ sport: 'nba' })).map((d) => d.key)
    expect(keys).not.toContain('analysis')
    expect(destinationsFor(ctx({ sport: 'nfl' })).map((d) => d.key)).toContain('analysis')
  })

  // Invariant 5.
  it('offers Follow live only while a draft is pre_draft or drafting', () => {
    for (const status of ['pre_draft', 'drafting']) {
      expect(destinationsFor(ctx({ status })).map((d) => d.key), status).toContain('live')
    }
    for (const status of ['complete', 'unknown', null]) {
      expect(destinationsFor(ctx({ status })).map((d) => d.key), String(status)).not.toContain('live')
    }
  })

  it('labels the board by what the season is doing', () => {
    const board = LEAGUE_DESTINATIONS.find((d) => d.key === 'board')!
    expect(labelOf(board, draft({ status: 'pre_draft' }))).toBe('Draft room')
    expect(labelOf(board, draft({ status: 'drafting' }))).toBe('Draft room')
    expect(labelOf(board, draft({ status: 'complete' }))).toBe('Draft board')
    expect(labelOf(board, draft({ status: null }))).toBe('Mock draft')
  })

  // specs/011: History is chain-wide and lives on the newest season; the board
  // and every one-season page follow the season being viewed.
  it('points History at the chain and season-scoped rows at the viewed season', () => {
    const older = draft({ sleeperDraftId: 'D_OLD', sleeperLeagueId: 'L_OLD', season: 2025 })
    const current = draft({ sleeperDraftId: 'D_NEW', sleeperLeagueId: 'L_NEW', season: 2026 })
    const viewingOlder: LeagueContext = {
      lineage: { current, seasons: [current, older] },
      season: older,
    }
    const hrefOf = (key: string) => LEAGUE_DESTINATIONS.find((d) => d.key === key)!.href(viewingOlder)
    // History walks the whole chain itself, so it lives on the league.
    expect(hrefOf('history')).toBe('/leagues/L_NEW/history')
    // The board is the one thing each season genuinely has its own of.
    expect(hrefOf('board')).toBe('/drafts/D_OLD/board')
    const oneSeason: Array<[string, string]> = [
      ['analysis', 'analysis'],
      ['rosterManagement', 'roster-management'],
      ['expectedWins', 'expected-wins'],
      ['forecast', 'forecast'],
      ['weeklyReport', 'weekly-report'],
      ['superlatives', 'superlatives'],
    ]
    for (const [key, path] of oneSeason) {
      expect(hrefOf(key), key).toBe(`/leagues/L_OLD/${path}`)
    }
  })

  // The rule as an iteration rather than a list, so a new league page added to
  // the table defaults to "one season" and has to opt out by name.
  it('every /leagues/ row except the chain-wide ones uses the viewed season', () => {
    const older = draft({ sleeperDraftId: 'D_OLD', sleeperLeagueId: 'L_OLD', season: 2025 })
    const current = draft({ sleeperDraftId: 'D_NEW', sleeperLeagueId: 'L_NEW', season: 2026 })
    const ctxOlder: LeagueContext = { lineage: { current, seasons: [current, older] }, season: older }
    // `power` is chain-wide for now; whether it should follow the season is
    // pending decision T019 (specs/011). Remove it from this set if that lands.
    const chainWide = new Set(['home', 'history', 'power'])
    let checked = 0
    for (const d of LEAGUE_DESTINATIONS) {
      if (d.isAction) continue
      const href = d.href(ctxOlder)
      if (!href.startsWith('/leagues/')) continue
      checked++
      const expected = chainWide.has(d.key) ? 'L_NEW' : ctxOlder.season.sleeperLeagueId
      expect(href.split('/')[2], d.key).toBe(expected)
    }
    expect(checked).toBeGreaterThan(6)
  })
})

describe('coverage of App.tsx', () => {
  /** Every `path="..."` React Router is given, read from the source so a new
   *  route cannot pass this suite by being absent from a copied list. */
  function routePathsInApp(): string[] {
    return [...appSource.matchAll(/path="([^"]+)"/g)].map((m) => m[1])
  }

  /** Substitutes a sample value for every `:param` segment. */
  function concrete(path: string): string {
    return path.replace(/:[A-Za-z]+/g, 'X1')
  }

  it('finds the route table', () => {
    expect(routePathsInApp().length).toBeGreaterThan(5)
  })

  // Invariant 2. A league-scoped route is one under /leagues or /drafts; those
  // are the two URL shapes that carry a league. If App.tsx grows another one
  // and destinations.ts does not, this fails.
  it('has a destination row for every league-scoped route', () => {
    const leagueScoped = routePathsInApp().filter(
      (p) => p.startsWith('/leagues/') || p.startsWith('/drafts/'),
    )
    expect(leagueScoped.length).toBeGreaterThan(0)
    for (const p of leagueScoped) {
      const path = concrete(p)
      expect(
        destinationFromPath(path),
        `${p} is league-scoped in App.tsx but no row in destinations.ts matches it`,
      ).not.toBeNull()
    }
  })

  it('does not claim routes that belong to no league', () => {
    const globalRoutes = routePathsInApp().filter(
      (p) => !p.startsWith('/leagues/') && !p.startsWith('/drafts/') && p !== '*',
    )
    for (const p of globalRoutes) {
      expect(destinationFromPath(concrete(p)), p).toBeNull()
    }
  })
})

describe('sports', () => {
  it('covers every sport the app knows', () => {
    const sports: Sport[] = ['nfl', 'nba']
    for (const s of sports) {
      expect(destinationsFor(ctx({ sport: s })).length, s).toBeGreaterThan(0)
    }
  })

  // specs/008-season-superlatives T016: both sports, same reasoning as
  // Expected wins and Weekly report -- nothing about superlatives is a
  // projection.
  it('offers Superlatives to both sports', () => {
    expect(destinationsFor(ctx({ sport: 'nfl' })).map((d) => d.key)).toContain('superlatives')
    expect(destinationsFor(ctx({ sport: 'nba' })).map((d) => d.key)).toContain('superlatives')
  })
})

describe('groups (specs/013 US3)', () => {
  it('puts every row in a known group', () => {
    const known = new Set(DESTINATION_GROUPS.map((g) => g.key))
    for (const d of LEAGUE_DESTINATIONS) expect(known.has(d.group), d.key).toBe(true)
  })

  it('keeps every group non-empty for a football league', () => {
    const rows = destinationsFor(ctx({ sport: 'nfl' }))
    for (const g of DESTINATION_GROUPS) {
      expect(rows.filter((d) => d.group === g.key).length, g.key).toBeGreaterThan(0)
    }
  })

  it('keeps every group non-empty for a basketball league', () => {
    const rows = destinationsFor(ctx({ sport: 'nba' }))
    for (const g of DESTINATION_GROUPS) {
      expect(rows.filter((d) => d.group === g.key).length, g.key).toBeGreaterThan(0)
    }
  })

  it('only gives a heading to groups with more than the home row', () => {
    expect(DESTINATION_GROUPS.filter((g) => g.heading === null).map((g) => g.key)).toEqual(['home'])
  })

  it('never lets a former label equal the label', () => {
    for (const d of LEAGUE_DESTINATIONS) {
      if (d.formerLabel === undefined) continue
      expect(d.formerLabel, d.key).not.toBe(labelOf(d, draft()))
    }
  })

  it('offers League home to both sports', () => {
    for (const sport of ['nfl', 'nba'] as Sport[]) {
      expect(destinationsFor(ctx({ sport })).map((d) => d.key)).toContain('home')
    }
    expect(destinationFromPath('/leagues/L7')).toBe('home')
    expect(leagueIdFromPath('/leagues/L7')).toEqual({ idKind: 'league', id: 'L7' })
  })
})

// specs/017-nba-schedule-grid T023: a basketball tool, season-scoped.
describe('schedule grid destination', () => {
  it('is offered to basketball and not football', () => {
    expect(destinationsFor(ctx({ sport: 'nba' })).map((d) => d.key)).toContain('schedule')
    expect(destinationsFor(ctx({ sport: 'nfl' })).map((d) => d.key)).not.toContain('schedule')
  })

  it('is recognised from its own path', () => {
    expect(destinationFromPath('/leagues/x/schedule')).toBe('schedule')
    expect(leagueIdFromPath('/leagues/x/schedule')).toEqual({ idKind: 'league', id: 'x' })
  })
})

// specs/019-minutes-streaming T016: a basketball tool, season-scoped.
describe('trends destination', () => {
  it('is offered to basketball and not football', () => {
    expect(destinationsFor(ctx({ sport: 'nba' })).map((d) => d.key)).toContain('trends')
    expect(destinationsFor(ctx({ sport: 'nfl' })).map((d) => d.key)).not.toContain('trends')
  })

  it('is recognised from its own path', () => {
    expect(destinationFromPath('/leagues/x/trends')).toBe('trends')
  })
})
