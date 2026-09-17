import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getMockSession, getMockSessions } from './api'

/**
 * The frontend and the backend are separate Railway services and deploy
 * independently, so every rollout passes through "new frontend, old backend".
 *
 * On 2026-09-14 that state lasted long enough to matter: the frontend shipped
 * with `m.sport.toUpperCase()` on every mock row, the backend that answers
 * `/api/mocks` did not ship, and the resulting
 * `Cannot read properties of undefined (reading 'toUpperCase')` threw during
 * render and took the whole home screen to a white page. One absent field, one
 * dead site.
 *
 * These pin the tolerance, using response bodies copied from the actually
 * deployed old backend.
 */
function respondWith(body: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(body),
    }),
  )
}

beforeEach(() => {
  vi.unstubAllGlobals()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('mock responses from a backend older than this bundle', () => {
  // Verbatim from https://api.ballknowers.co/api/mocks while it was serving
  // the pre-V14 build -- no `sport`, no `sourceLeagueName`, no `reversalRound`.
  const OLD_SUMMARY = {
    id: 4,
    status: 'IN_PROGRESS',
    teams: 12,
    rounds: 15,
    userSlot: 5,
    currentPickNo: 5,
    createdAt: '2026-09-11T05:31:58.519812Z',
  }

  it('gives a sport-less summary row a football sport rather than undefined', async () => {
    respondWith([OLD_SUMMARY])

    const [row] = await getMockSessions()

    expect(row.sport).toBe('nfl')
    // The actual crash: this call is what the home screen makes on every row.
    expect(() => row.sport.toUpperCase()).not.toThrow()
    expect(row.sport.toUpperCase()).toBe('NFL')
  })

  it('gives a sport-less session plain snake rather than undefined', async () => {
    respondWith({ ...OLD_SUMMARY, myPicks: [5], seats: [], picks: [], available: [] })

    const state = await getMockSession(4)

    expect(state.sport).toBe('nfl')
    // DraftBoard would default this itself, but only if it arrives undefined
    // rather than being read off a field that doesn't exist.
    expect(state.reversalRound).toBe(0)
  })

  it('does not overwrite a real sport when the backend does send one', async () => {
    respondWith([{ ...OLD_SUMMARY, sport: 'nba' }])

    const [row] = await getMockSessions()

    expect(row.sport).toBe('nba')
  })

  it('does not overwrite a real reversal round', async () => {
    respondWith({ ...OLD_SUMMARY, sport: 'nba', reversalRound: 3, myPicks: [], seats: [], picks: [], available: [] })

    const state = await getMockSession(4)

    expect(state.reversalRound).toBe(3)
    expect(state.sport).toBe('nba')
  })

  /*
   * V16's sourceSleeperLeagueId is the newest field to pass through this same
   * window. It must stay ABSENT rather than be defaulted: there is no honest
   * fallback for "which league seeded this mock", and inventing one would put a
   * league's name in the rail of a mock that never came from it.
   */
  it('leaves an absent source league id absent rather than defaulting it', async () => {
    respondWith({ ...OLD_SUMMARY, myPicks: [], seats: [], picks: [], available: [] })

    const state = await getMockSession(4)

    expect(state.sourceSleeperLeagueId ?? null).toBeNull()
    // What MockDraftView does with it. The rail publishes nothing and shows no
    // League section; the page renders.
    expect(() => String(state.sourceSleeperLeagueId ?? '')).not.toThrow()
  })

  it('keeps a real source league id', async () => {
    respondWith({
      ...OLD_SUMMARY,
      sourceSleeperLeagueId: 'L_REAL',
      myPicks: [],
      seats: [],
      picks: [],
      available: [],
    })

    const state = await getMockSession(4)

    expect(state.sourceSleeperLeagueId).toBe('L_REAL')
  })

  it('leaves an absent source league id absent on a summary row too', async () => {
    respondWith([OLD_SUMMARY])

    const [row] = await getMockSessions()

    expect(row.sourceSleeperLeagueId ?? null).toBeNull()
  })
})
