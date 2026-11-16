import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ACTIVE_GRACE_MS,
  getSessionToken,
  isUnlocked,
  lock,
  lockedAfterIdleMinutes,
  noteActivity,
  OLD_FLAG_KEY,
  renewSession,
  SESSION_KEY,
  sessionExpired,
  startSession,
  tokenExpiry,
  useUnlocked,
} from './leaguesLock'
import { invalidateRailLeagues } from './railLeague'
import { invalidateSearchIndex } from './searchIndex'
import { clearDraftStatsCache } from './draftStatsCache'
import { invalidateLeagueAvailability } from './useLeagueAvailability'

// The league site's unlocked state: "holds a session token whose expiry is in the future". The session is a
// 5-minute credential in localStorage; the admin token only ever goes to sessionStorage.

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-11-16T12:00:00Z'))
  localStorage.clear()
  sessionStorage.clear()
  lock()
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

const stored = () => JSON.parse(localStorage.getItem(SESSION_KEY)!)

describe('leaguesLock', () => {
  it('starts locked', () => {
    expect(isUnlocked()).toBe(false)
    expect(getSessionToken()).toBeNull()
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
    expect(lockedAfterIdleMinutes()).toBeNull()
  })

  it('startSession stores token, expiry and window under bk.leagueSession.v1', () => {
    startSession('tok', 300)
    expect(SESSION_KEY).toBe('bk.leagueSession.v1')
    expect(isUnlocked()).toBe(true)
    expect(getSessionToken()).toBe('tok')
    expect(stored()).toEqual({ token: 'tok', expiresAt: Date.now() + 300_000, windowSeconds: 300 })
  })

  it('CR-11: unlocking writes no admin token anywhere (startSession takes none)', () => {
    sessionStorage.setItem('some.other.key', 'kept')
    startSession('tok', 300)
    expect(sessionStorage.getItem('bk.adminToken')).toBeNull()
    expect(JSON.stringify({ ...sessionStorage })).toBe(JSON.stringify({ 'some.other.key': 'kept' }))
    expect(Object.keys(localStorage)).toEqual([SESSION_KEY])
  })

  it('the client expiry is measured from when the request was sent, not from when the answer landed', () => {
    const sent = Date.now() - 4_000
    startSession('tok', 300, sent)
    expect(stored().expiresAt).toBe(sent + 300_000)
  })

  it('is unlocked only until the expiry, then not (no token is offered, the watcher can see it expired)', () => {
    startSession('tok', 300)
    vi.setSystemTime(Date.now() + 299_000)
    expect(isUnlocked()).toBe(true)
    vi.setSystemTime(Date.now() + 1_000)
    expect(isUnlocked()).toBe(false)
    expect(getSessionToken()).toBeNull()
    expect(sessionExpired()).toBe(true)
  })

  it('lock() clears the session and is silent about idleness', () => {
    startSession('tok', 300)
    lock()
    expect(isUnlocked()).toBe(false)
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
    expect(lockedAfterIdleMinutes()).toBeNull()
  })

  it('CR-11: lock() empties the rail league list (refetched after a lock)', async () => {
    const api = await import('./api')
    const getDrafts = vi.spyOn(api, 'getDrafts').mockResolvedValue([])
    const { cachedDrafts } = await import('./railLeague')
    invalidateRailLeagues()
    startSession('tok', 300)
    await cachedDrafts()
    await cachedDrafts()
    expect(getDrafts).toHaveBeenCalledTimes(1) // cached
    lock()
    await cachedDrafts()
    expect(getDrafts).toHaveBeenCalledTimes(2) // refetched: the cache did not survive the lock
  })

  it('CR-11: lock() empties the stats and availability caches (refetched after a lock)', async () => {
    const api = await import('./api')
    const board = vi.spyOn(api, 'getStatLeaderboard').mockResolvedValue({} as never)
    const { fetchDraftStats } = await import('./draftStatsCache')
    clearDraftStatsCache()
    await fetchDraftStats('L1', 'season' as never)
    await fetchDraftStats('L1', 'season' as never)
    expect(board).toHaveBeenCalledTimes(1)
    lock()
    await fetchDraftStats('L1', 'season' as never)
    expect(board).toHaveBeenCalledTimes(2)
    expect(typeof invalidateLeagueAvailability).toBe('function')
  })

  it('CR-11: lock() empties the jump-to index (rebuilt after a lock)', async () => {
    vi.useRealTimers() // waitFor polls on real timers
    const api = await import('./api')
    vi.spyOn(api, 'getDrafts').mockResolvedValue([])
    const managers = vi.spyOn(api, 'getManagers').mockResolvedValue([])
    const { useSearchIndex } = await import('./searchIndex')
    invalidateSearchIndex()
    invalidateRailLeagues()
    startSession('tok', 300)
    const first = renderHook(() => useSearchIndex())
    await act(async () => {
      first.result.current.load()
    })
    await waitFor(() => expect(first.result.current.loading).toBe(false))
    const perBuild = managers.mock.calls.length
    expect(perBuild).toBeGreaterThan(0)
    // A second hook finds the cached index and asks for nothing.
    const second = renderHook(() => useSearchIndex())
    await act(async () => {
      second.result.current.load()
    })
    expect(managers.mock.calls.length).toBe(perBuild)
    lock()
    startSession('tok', 300)
    const third = renderHook(() => useSearchIndex())
    await act(async () => {
      third.result.current.load()
    })
    await waitFor(() => expect(managers.mock.calls.length).toBe(perBuild * 2))
  })

  it('onLock cleanups all run on lock(), and one that throws does not stop the others or the lock', async () => {
    const { onLock } = await import('./leaguesLock')
    const ran: string[] = []
    onLock(() => {
      throw new Error('boom')
    })
    onLock(() => ran.push('second'))
    startSession('tok', 300)
    lock()
    expect(ran).toEqual(['second'])
    expect(isUnlocked()).toBe(false)
  })

  it("lock('idle') remembers the window in minutes for the lock screen, and starting a session forgets it", () => {
    startSession('tok', 300)
    lock('idle')
    expect(lockedAfterIdleMinutes()).toBe(5)
    startSession('tok2', 120)
    expect(lockedAfterIdleMinutes()).toBeNull()
    lock('idle')
    expect(lockedAfterIdleMinutes()).toBe(2)
  })

  describe('renewSession (the sliding window)', () => {
    // Tokens as the server makes them: <expiry seconds>.<origin>.<sig>.
    const at = (sec: number) => `${Math.floor(Date.parse('2026-11-16T12:00:00Z') / 1000) + sec}.1.sig`

    it('moves the expiry forward by the window and stores it', () => {
      startSession(at(300), 300)
      vi.setSystemTime(Date.now() + 100_000)
      noteActivity()
      renewSession(at(400))
      expect(getSessionToken()).toBe(at(400))
      expect(stored().expiresAt).toBe(Date.now() + 300_000)
      expect(stored().windowSeconds).toBe(300)
    })

    it('CR-04: a slow response carrying an OLDER token never replaces a newer one', () => {
      // A at t=0 is slow (40 s); B sent at 20 s returns at once; A lands at 40 s with the token issued at 0 s.
      startSession(at(300), 300)
      const t0 = Date.now()
      vi.setSystemTime(t0 + 20_000)
      noteActivity()
      renewSession(at(320), t0 + 20_000) // B
      expect(getSessionToken()).toBe(at(320))
      const expiryAfterB = stored().expiresAt
      vi.setSystemTime(t0 + 40_000)
      noteActivity()
      renewSession(at(300), t0) // A, older
      expect(getSessionToken()).toBe(at(320))
      expect(stored().expiresAt).toBe(expiryAfterB)
      // And an equal expiry is not "strictly greater" either.
      renewSession(at(320), t0 + 40_000)
      expect(stored().expiresAt).toBe(expiryAfterB)
    })

    it('CR-04: the client never believes in a later expiry than the newest token implies', () => {
      startSession(at(300), 300)
      const t0 = Date.now()
      // The answer to a request sent at t0 + 10 s lands 40 s later: expiry is measured from the send time.
      vi.setSystemTime(t0 + 50_000)
      noteActivity()
      renewSession(at(310), t0 + 10_000)
      expect(stored().expiresAt).toBeLessThanOrEqual(t0 + 10_000 + 300_000)
    })

    it('CR-04: a server-side cap that moved the token expiry by only 20 s moves the client expiry by 20 s, not a window', () => {
      startSession(at(300), 300)
      const t0 = Date.now()
      vi.setSystemTime(t0 + 100_000)
      noteActivity()
      // Sent at +100 s, so sentAt + window would say +400 s; the token moved only 20 s, so the client says +320 s.
      renewSession(at(320), Date.now())
      expect(stored().expiresAt).toBe(t0 + 300_000 + 20_000)
    })

    it('a token with no readable expiry is not adopted', () => {
      startSession(at(300), 300)
      renewSession('opaque-token')
      expect(getSessionToken()).toBe(at(300))
    })

    it('the expiry never exceeds one window after the last input, so a poll cannot hold the screen open longer', () => {
      startSession(at(300), 300)
      const t0 = Date.now()
      noteActivity() // last input at t0
      vi.setSystemTime(t0 + 100_000) // still inside the grace
      renewSession(at(400), t0 + 100_000)
      expect(stored().expiresAt).toBe(t0 + 300_000) // not t0 + 100 s + 300 s
    })

    it('tokenExpiry reads only a canonical number before the first dot', () => {
      expect(tokenExpiry('1778932800.99.sig')).toBe(1778932800)
      for (const bad of ['', 'tok', '.1.2', '01.2.3', '+5.2.3', '-5.2.3', ' 5.2.3', '1234567890123.2.3', 't.5.sig']) {
        expect(tokenExpiry(bad), bad).toBeNull()
      }
    })

    it('does nothing when no session is held (a late response cannot resurrect a lock)', () => {
      renewSession('1778932800.1.sig')
      expect(isUnlocked()).toBe(false)
      expect(localStorage.getItem(SESSION_KEY)).toBeNull()
    })

    it('is ignored when the user has been inactive past the grace, so a background poll cannot keep an idle session alive', () => {
      startSession('1778932800.1.sig', 300)
      vi.setSystemTime(Date.now() + ACTIVE_GRACE_MS + 1_000)
      renewSession('1778933800.1.sig')
      expect(getSessionToken()).toBe('1778932800.1.sig')
      expect(stored().expiresAt).toBe(Date.parse('2026-11-16T12:05:00Z'))
    })
  })

  describe('load', () => {
    it('the old forgeable flag is not a session, and is deleted', async () => {
      localStorage.setItem(OLD_FLAG_KEY, '1')
      vi.resetModules()
      const fresh = await import('./leaguesLock')
      expect(fresh.isUnlocked()).toBe(false)
      expect(localStorage.getItem(OLD_FLAG_KEY)).toBeNull()
    })

    it('a stored, unexpired session is picked up (a new tab inside the window works)', async () => {
      localStorage.setItem(SESSION_KEY, JSON.stringify({ token: 't', expiresAt: Date.now() + 60_000, windowSeconds: 300 }))
      vi.resetModules()
      const fresh = await import('./leaguesLock')
      expect(fresh.isUnlocked()).toBe(true)
      expect(fresh.getSessionToken()).toBe('t')
    })

    it('a stored but expired session is dropped, and the lock screen will say it was idle', async () => {
      localStorage.setItem(SESSION_KEY, JSON.stringify({ token: 't', expiresAt: Date.now() - 1, windowSeconds: 300 }))
      vi.resetModules()
      const fresh = await import('./leaguesLock')
      expect(fresh.isUnlocked()).toBe(false)
      expect(localStorage.getItem(SESSION_KEY)).toBeNull()
      expect(fresh.lockedAfterIdleMinutes()).toBe(5)
    })

    it.each(['not json', '{}', '{"token":"","expiresAt":1,"windowSeconds":300}', '{"token":"t","expiresAt":"x","windowSeconds":300}',
      '{"token":"t","expiresAt":9999999999999,"windowSeconds":0}', 'null', '[]'])('a malformed stored record (%s) is not a session', async (raw) => {
      localStorage.setItem(SESSION_KEY, raw)
      vi.resetModules()
      const fresh = await import('./leaguesLock')
      expect(fresh.isUnlocked()).toBe(false)
    })

    it('blocked storage: the session still lasts for this page load', () => {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('blocked')
      })
      expect(() => startSession('tok', 300)).not.toThrow()
      expect(isUnlocked()).toBe(true)
    })
  })

  it('useUnlocked follows startSession and lock', () => {
    const { result } = renderHook(() => useUnlocked())
    expect(result.current).toBe(false)
    act(() => startSession('tok', 300))
    expect(result.current).toBe(true)
    act(() => lock())
    expect(result.current).toBe(false)
  })
})
