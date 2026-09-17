import { describe, expect, it } from 'vitest'
import { acceptsLeagueHint, leagueRefFromPath } from './railLeague'

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
    // Not a route the app has -- and matching it would put a league's menu on
    // the "Nothing here" fallback, which is the one screen that should only
    // offer the way out.
    ['/leagues/999'],
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
