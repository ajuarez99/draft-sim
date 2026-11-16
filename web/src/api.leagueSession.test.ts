import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getStreamTicket, getTargets, renewLeagueSession, unlockLeagueSite } from './api'
import { ApiError } from './apiError'
import { ACTIVE_GRACE_MS, isUnlocked, lock, lockedAfterIdleMinutes, noteActivity, SESSION_KEY, startSession, getSessionToken } from './leaguesLock'
import { clearUser, setUser } from './user'

// The league session through the real apiFetch: header attach, sliding renewal, the 401 re-lock. fetch is the
// only thing mocked. Spec 073 follow-up, second pass.

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  localStorage.clear()
  sessionStorage.clear()
  lock()
  clearUser()
})
afterEach(() => {
  vi.unstubAllGlobals()
  lock()
})

const reply = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(body === null ? null : typeof body === 'string' ? body : JSON.stringify(body), { status, headers })

const sentHeaders = (call = 0) => new Headers(fetchMock.mock.calls[call][1].headers)

// A session token as the server makes it: <expiry seconds>.<origin seconds>.<signature>.
const tok = (afterSeconds: number) => `${Math.floor(Date.now() / 1000) + afterSeconds}.1.sig`

// Any league call; the response shape does not matter to these tests.
const anyCall = () => renewLeagueSession()

describe('apiFetch with no session held (combined and timemachine builds, or a locked leagues build)', () => {
  it('sends no X-League-Session header and behaves as before', async () => {
    fetchMock.mockResolvedValue(reply(200, []))
    setUser({ sleeperUserId: '1', username: 'u', displayName: 'u', avatar: null })
    await getTargets({ sleeperDraftId: 'd' }).catch(() => {})
    expect(sentHeaders().has('X-League-Session')).toBe(false)
    expect(sentHeaders().get('X-Sleeper-User')).toBe('1')
  })

  it('a 401 league_session_required with nothing held changes nothing (no lock line)', async () => {
    fetchMock.mockResolvedValue(reply(401, { code: 'league_session_required' }))
    await anyCall()
    expect(lockedAfterIdleMinutes()).toBeNull()
  })

  it('a response carrying a renewed token does not start a session out of nothing', async () => {
    fetchMock.mockResolvedValue(reply(204, null, { 'X-League-Session': 'x.y' }))
    await anyCall()
    expect(isUnlocked()).toBe(false)
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
  })
})

describe('apiFetch with a session held', () => {
  let sess1 = ''
  beforeEach(() => {
    sess1 = tok(300)
    startSession(sess1, 300)
  })

  it('attaches X-League-Session to every request', async () => {
    fetchMock.mockResolvedValue(reply(204, null))
    await anyCall()
    await anyCall()
    expect(sentHeaders(0).get('X-League-Session')).toBe(sess1)
    expect(sentHeaders(1).get('X-League-Session')).toBe(sess1)
  })

  it('stores the refreshed token a response carries and sends it next', async () => {
    const sess2 = tok(301)
    fetchMock.mockResolvedValueOnce(reply(204, null, { 'X-League-Session': sess2 }))
    fetchMock.mockResolvedValueOnce(reply(204, null))
    await anyCall()
    expect(getSessionToken()).toBe(sess2)
    expect(JSON.parse(localStorage.getItem(SESSION_KEY)!).token).toBe(sess2)
    await anyCall()
    expect(sentHeaders(1).get('X-League-Session')).toBe(sess2)
  })

  it('CR-04: a slow response carrying an older token than the one now held is not adopted', async () => {
    const newer = tok(320)
    const older = tok(305)
    fetchMock.mockResolvedValueOnce(reply(204, null, { 'X-League-Session': newer }))
    fetchMock.mockResolvedValueOnce(reply(204, null, { 'X-League-Session': older }))
    await anyCall()
    await anyCall()
    expect(getSessionToken()).toBe(newer)
  })

  it('CR-05: a 401 league_session_required locks even when the session had already expired on the client (no header sent)', async () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(Date.now() + 10 * 60_000) // well past the 5-minute expiry; no timer ran, nothing locked yet
      fetchMock.mockResolvedValue(reply(401, { code: 'league_session_required', message: 'locked' }))
      const err = await getTargets({ sleeperDraftId: 'd' }).catch((e) => e)
      expect(sentHeaders().has('X-League-Session')).toBe(false) // expired: no token offered
      expect(err.status).toBe(401)
      expect(isUnlocked()).toBe(false)
      expect(localStorage.getItem(SESSION_KEY)).toBeNull()
      expect(lockedAfterIdleMinutes()).toBe(5)
    } finally {
      vi.useRealTimers()
    }
  })

  it('keeps the window the session was issued with when it renews', async () => {
    fetchMock.mockResolvedValue(reply(204, null, { 'X-League-Session': tok(301) }))
    const before = Date.now()
    await anyCall()
    const stored = JSON.parse(localStorage.getItem(SESSION_KEY)!)
    expect(stored.windowSeconds).toBe(300)
    expect(stored.expiresAt).toBeGreaterThanOrEqual(before + 299_000)
    expect(stored.expiresAt).toBeLessThanOrEqual(Date.now() + 300_000) // never later than the send time implies
  })

  it('a 401 league_session_required locks, shows the idle line, and the call still rejects as an ApiError', async () => {
    fetchMock.mockResolvedValue(reply(401, { code: 'league_session_required', message: 'locked' }))
    const err = await getTargets({ sleeperDraftId: 'd' }).catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.status).toBe(401)
    expect(isUnlocked()).toBe(false)
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
    expect(lockedAfterIdleMinutes()).toBe(5)
  })

  it('a 401 for another reason does not lock', async () => {
    fetchMock.mockResolvedValue(reply(401, { error: 'X-Sleeper-User is required' }))
    await anyCall()
    expect(isUnlocked()).toBe(true)
  })

  it('a 401 with a body that is not JSON does not lock', async () => {
    fetchMock.mockResolvedValue(reply(401, 'Unauthorized'))
    await anyCall()
    expect(isUnlocked()).toBe(true)
  })

  it("reads the 401 body from a clone: the caller's own error still carries the server's message", async () => {
    fetchMock.mockResolvedValue(reply(401, { code: 'league_session_required', message: 'locked' }))
    const err = await getTargets({ sleeperDraftId: 'd' }).catch((e) => e)
    expect(err.message).toBe('locked')
  })

  it('an idle user\'s background poll does not extend the session', async () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(Date.now() + ACTIVE_GRACE_MS + 1_000)
      const poll = `${Math.floor(Date.now() / 1000) + 300}.1.sig`
      fetchMock.mockResolvedValue(reply(200, [], { 'X-League-Session': poll }))
      await anyCall()
      expect(getSessionToken()).toBe(sess1)
      noteActivity()
      await anyCall()
      expect(getSessionToken()).toBe(poll)
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('unlockLeagueSite', () => {
  it('POSTs /api/admin/session with the admin token and stores the session it gets', async () => {
    fetchMock.mockResolvedValue(reply(200, { token: 'abc.def', expiresInSeconds: 300 }))
    expect(await unlockLeagueSite('s3cret')).toBe('ok')
    expect(JSON.stringify({ ...sessionStorage })).not.toContain('s3cret')
    expect(JSON.stringify({ ...localStorage })).not.toContain('s3cret')
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toMatch(/\/api\/admin\/session$/)
    expect(init.method).toBe('POST')
    expect(sentHeaders().get('X-Admin-Token')).toBe('s3cret')
    expect(isUnlocked()).toBe(true)
    expect(getSessionToken()).toBe('abc.def')
    expect(sessionStorage.getItem('bk.adminToken')).toBeNull()
  })

  it('works against a backend whose lock is off: same call, same answer', async () => {
    // The session endpoint answers regardless of LEAGUE_LOCK; the web only needs the 200 body.
    fetchMock.mockResolvedValue(reply(200, { token: 't.s', expiresInSeconds: 120 }))
    expect(await unlockLeagueSite('x')).toBe('ok')
    expect(JSON.parse(localStorage.getItem(SESSION_KEY)!).windowSeconds).toBe(120)
  })

  it('403 admin_token_required is "wrong" and stores nothing', async () => {
    fetchMock.mockResolvedValue(reply(403, { code: 'admin_token_required', message: 'x' }))
    expect(await unlockLeagueSite('nope')).toBe('wrong')
    expect(isUnlocked()).toBe(false)
    expect(sessionStorage.getItem('bk.adminToken')).toBeNull()
  })

  it.each([
    ['a network error', () => Promise.reject(new TypeError('Failed to fetch'))],
    ['a 404 (an older backend without the route)', () => Promise.resolve(reply(404, { error: 'nope' }))],
    ['a 500', () => Promise.resolve(reply(500, { error: 'boom' }))],
    ['a proxy error page', () => Promise.resolve(reply(502, '<html>Bad Gateway</html>'))],
    ['a 200 with no token', () => Promise.resolve(reply(200, {}))],
    ['a 200 with a bad window', () => Promise.resolve(reply(200, { token: 't', expiresInSeconds: 0 }))],
    ['a 200 that is not JSON', () => Promise.resolve(reply(200, 'ok'))],
    ['a 403 with another code', () => Promise.resolve(reply(403, { code: 'other' }))],
  ])('%s is "unreachable", never "ok"', async (_name, answer) => {
    fetchMock.mockImplementation(answer)
    expect(await unlockLeagueSite('x')).toBe('unreachable')
    expect(isUnlocked()).toBe(false)
  })
})

describe('renewLeagueSession', () => {
  it('GETs /api/league-session and reports ok on 204', async () => {
    startSession(tok(300), 300)
    const sess2 = tok(301)
    fetchMock.mockResolvedValue(reply(204, null, { 'X-League-Session': sess2 }))
    expect(await renewLeagueSession()).toBe(true)
    expect(String(fetchMock.mock.calls[0][0])).toMatch(/\/api\/league-session$/)
    expect(getSessionToken()).toBe(sess2)
  })
})

describe('getStreamTicket', () => {
  it('POSTs the draft id to /api/league-session/stream-ticket with the session header and returns the ticket', async () => {
    const s = tok(300)
    startSession(s, 300)
    fetchMock.mockResolvedValue(reply(200, { ticket: 't.123.sig', expiresInSeconds: 60 }))
    expect(await getStreamTicket('d 1')).toEqual({ ticket: 't.123.sig', expiresInSeconds: 60 })
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toMatch(/\/api\/league-session\/stream-ticket\?draftId=d%201$/)
    expect(init.method).toBe('POST')
    expect(sentHeaders().get('X-League-Session')).toBe(s)
  })

  it('a 401 league_session_required locks the site and the call rejects', async () => {
    startSession(tok(300), 300)
    fetchMock.mockResolvedValue(reply(401, { code: 'league_session_required', message: 'locked' }))
    await expect(getStreamTicket('d1')).rejects.toBeInstanceOf(ApiError)
    expect(isUnlocked()).toBe(false)
  })
})
