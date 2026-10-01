import { MemoryRouter } from 'react-router-dom'
import { createElement, type ReactNode } from 'react'
import { renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import * as api from './api'
import type { DraftSummary } from './api'
import { acceptsLeagueHint, invalidateRailLeagues, leagueRefFromPath, useRailLeague } from './railLeague'

/**
 * The rail lives outside `<Routes>`, so it can't use `useParams` and matches
 * the path itself (railLeague.ts). That makes the matcher the one place a
 * route-table change can silently stop the rail showing league context --
 * hence a test per shape rather than a smoke test.
 */
describe('leagueRefFromPath', () => {
  it.each([
    ['/drafts/abc123', 'abc123'],
    ['/drafts/abc123/board', 'abc123'],
    ['/drafts/abc123/live', 'abc123'],
    ['/drafts/abc123/', 'abc123'],
  ])('reads the draft id from %s', (path, id) => {
    expect(leagueRefFromPath(path)).toEqual({ kind: 'draft', sleeperDraftId: id })
  })

  it.each([
    ['/leagues/999', '999'],
    ['/leagues/999/history', '999'],
    ['/leagues/999/power', '999'],
    // The dev-only self-check harness hangs off /power; it is still that
    // league's page, so the rail should still say which league.
    ['/leagues/999/power/verify', '999'],
    // The regression this feature exists for. Analysis was linked from the
    // rail and missing from the matcher, so opening it deleted the League
    // section that linked you -- four of five destinations gone at the moment
    // you used the fifth.
    ['/leagues/999/analysis', '999'],
    ['/leagues/999/analysis/', '999'],
  ])('reads the league id from %s', (path, id) => {
    expect(leagueRefFromPath(path)).toEqual({ kind: 'league', sleeperLeagueId: id })
  })

  it.each([
    ['/'],
    ['/managers'],
    ['/managers/12/history'],
    ['/mock/new'],
    ['/mock/4'],
    ['/nonsense'],
    ['/drafts'],
  ])('has no league context on %s', (path) => {
    expect(leagueRefFromPath(path)).toBeNull()
  })
})

/**
 * Two routes belong to a league that their URL cannot name: a manager's own
 * history page, and a mock seeded from a league. They take the league from a
 * hint instead -- but only they do, so a hint left over from somewhere else
 * can't decorate an unrelated page with a League section.
 */
describe('acceptsLeagueHint', () => {
  it.each([['/managers/12/history'], ['/managers/12/history/'], ['/mock/4'], ['/mock/4/']])(
    'accepts a hint on %s',
    (path) => {
      expect(acceptsLeagueHint(path)).toBe(true)
    },
  )

  it.each([
    ['/'],
    ['/managers'],
    // The seat-setup form, not a room: no session, so no league to take.
    // Same carve-out railDefaultCollapsed makes.
    ['/mock/new'],
    ['/mock/new/'],
    ['/nonsense'],
    // Already knows its league from the path; a hint must not get a second say.
    ['/leagues/999/history'],
    ['/drafts/abc123/board'],
  ])('refuses a hint on %s', (path) => {
    expect(acceptsLeagueHint(path)).toBe(false)
  })
})

/**
 * specs/011 T004. A league page for an older season resolves `season` to that
 * season's lineage entry, not to `lineage.current` -- the rail's year links and
 * "on" marker depend on it. Pins existing behaviour (railLeague.ts, the
 * `kind === 'league'` branch of resolve()).
 */
describe('useRailLeague on an older season league page', () => {
  afterEach(() => {
    invalidateRailLeagues()
    vi.restoreAllMocks()
  })

  function draft(over: Partial<DraftSummary>): DraftSummary {
    return {
      id: 1,
      sleeperDraftId: 'D1',
      leagueId: 1,
      leagueName: 'Test League',
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

  it('resolves season to the older lineage entry for /leagues/<older id>/superlatives', async () => {
    invalidateRailLeagues()
    vi.spyOn(api, 'getDrafts').mockResolvedValue([
      draft({ id: 2, sleeperDraftId: 'D_NEW', sleeperLeagueId: 'L_NEW', season: 2026, previousLeagueId: 'L_OLD' }),
      draft({ id: 1, sleeperDraftId: 'D_OLD', sleeperLeagueId: 'L_OLD', season: 2025 }),
    ])
    const wrapper = ({ children }: { children: ReactNode }) => createElement(MemoryRouter, null, children)

    const { result } = renderHook(() => useRailLeague('/leagues/L_OLD/superlatives'), { wrapper })

    await waitFor(() => expect(result.current).not.toBeNull())
    expect(result.current!.season.sleeperLeagueId).toBe('L_OLD')
    expect(result.current!.lineage.current.sleeperLeagueId).toBe('L_NEW')
  })
})
