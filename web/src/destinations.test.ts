import { describe, expect, it } from 'vitest'
// The route table as source text. Vite's `?raw` rather than node:fs so this
// suite needs no @types/node and stays in the same module world as the app.
import appSource from './App.tsx?raw'
import {
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

  it('points league pages at the current season and the board at the viewed one', () => {
    const older = draft({ sleeperDraftId: 'D_OLD', sleeperLeagueId: 'L_OLD', season: 2025 })
    const current = draft({ sleeperDraftId: 'D_NEW', sleeperLeagueId: 'L_NEW', season: 2026 })
    const viewingOlder: LeagueContext = {
      lineage: { current, seasons: [current, older] },
      season: older,
    }
    const history = LEAGUE_DESTINATIONS.find((d) => d.key === 'history')!
    const board = LEAGUE_DESTINATIONS.find((d) => d.key === 'board')!
    // History walks the whole chain itself, so it lives on the league.
    expect(history.href(viewingOlder)).toBe('/leagues/L_NEW/history')
    // The board is the one thing each season genuinely has its own of.
    expect(board.href(viewingOlder)).toBe('/drafts/D_OLD/board')
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
