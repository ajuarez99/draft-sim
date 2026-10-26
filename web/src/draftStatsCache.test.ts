import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearDraftStatsCache, fetchDraftStats } from './draftStatsCache'

const getStatLeaderboard = vi.fn()
vi.mock('./api', () => ({ getStatLeaderboard: (...a: unknown[]) => getStatLeaderboard(...a) }))

beforeEach(() => {
  clearDraftStatsCache()
  getStatLeaderboard.mockReset()
  getStatLeaderboard.mockImplementation(() => Promise.resolve({}))
})

describe('fetchDraftStats', () => {
  it('shares one request per key, and a newer data version refetches (spec 049 N2)', async () => {
    await fetchDraftStats('L1', 'SEASON', 0)
    await fetchDraftStats('L1', 'SEASON', 0)
    await fetchDraftStats('L1', 'SEASON')
    expect(getStatLeaderboard).toHaveBeenCalledTimes(1)
    await fetchDraftStats('L1', 'SEASON', 1)
    await fetchDraftStats('L1', 'SEASON', 1)
    expect(getStatLeaderboard).toHaveBeenCalledTimes(2)
    // a caller that doesn't know the version reads what is cached, and never downgrades it
    await fetchDraftStats('L1', 'SEASON')
    await fetchDraftStats('L1', 'SEASON', 0)
    expect(getStatLeaderboard).toHaveBeenCalledTimes(2)
  })
})
