import { describe, expect, it } from 'vitest'
import { leagueRefFromPath } from './railLeague'

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
    ['/leagues/999/history', '999'],
    ['/leagues/999/power', '999'],
    // The dev-only self-check harness hangs off /power; it is still that
    // league's page, so the rail should still say which league.
    ['/leagues/999/power/verify', '999'],
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
    // Not a route the app has -- and matching it would put a league's menu on
    // the "Nothing here" fallback, which is the one screen that should only
    // offer the way out.
    ['/leagues/999'],
    ['/drafts'],
  ])('has no league context on %s', (path) => {
    expect(leagueRefFromPath(path)).toBeNull()
  })
})
