import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { claimGuestGames, getPublicSeasonList, getSeats, listHistoricalGames } from './api'
import { GUEST_KEY, __resetGuestForTests, dropGuestId, guestId, peekGuestId } from './guest'
import { clearUser, setUser } from './user'

// Spec 074 T032: the guest id and the header funnel.

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

const ok = (body: unknown = {}) => ({ ok: true, status: 200, json: async () => body, headers: new Headers() }) as unknown as Response
const refuse = (status: number) => ({ ok: false, status, json: async () => ({}), headers: new Headers() }) as unknown as Response

let sent: { url: string; headers: Headers; method?: string }[]
let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  localStorage.clear()
  __resetGuestForTests()
  clearUser()
  sent = []
  fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    sent.push({ url, headers: new Headers(init?.headers), method: init?.method })
    return ok([])
  })
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  clearUser()
})

describe('the guest id', () => {
  it('is made on first need as a UUID, stored under bk.guest.v1, and is the same one every time after', () => {
    expect(peekGuestId()).toBeNull()
    const a = guestId()
    expect(a).toMatch(UUID)
    expect(localStorage.getItem(GUEST_KEY)).toBe(a)
    expect(guestId()).toBe(a)
    __resetGuestForTests() // a new page load reads it back
    expect(peekGuestId()).toBe(a)
  })

  it('does not cache "none yet": an id another tab stored in the meantime is used, not a second one (CR-06)', () => {
    expect(peekGuestId()).toBeNull()
    const other = '11111111-2222-4333-8444-555555555555'
    localStorage.setItem(GUEST_KEY, other) // another tab got there first
    expect(guestId()).toBe(other)
    expect(localStorage.getItem(GUEST_KEY)).toBe(other)
  })

  it('follows a storage event from another tab (CR-06)', () => {
    const mine = guestId()
    const other = '11111111-2222-4333-8444-555555555555'
    localStorage.setItem(GUEST_KEY, other)
    window.dispatchEvent(new StorageEvent('storage', { key: GUEST_KEY, newValue: other }))
    expect(guestId()).toBe(other)
    expect(mine).not.toBe(other)
  })

  it('builds a v4 UUID from getRandomValues when randomUUID is missing (CR-11)', () => {
    const real = globalThis.crypto
    vi.stubGlobal('crypto', { getRandomValues: real.getRandomValues.bind(real) })
    const a = guestId()
    expect(a).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })

  it('ignores a stored value that is not a UUID rather than sending it', () => {
    localStorage.setItem(GUEST_KEY, 'not-a-uuid')
    expect(peekGuestId()).toBeNull()
    expect(guestId()).toMatch(UUID)
  })

  it('survives blocked storage: the id still holds for this page load', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const a = guestId()
    expect(a).toMatch(UUID)
    expect(guestId()).toBe(a)
    expect(() => dropGuestId()).not.toThrow()
  })
})

describe('the request header funnel', () => {
  it('a guest sends X-Guest-Id to a Redraft route, and no Sleeper header', async () => {
    await listHistoricalGames()
    expect(sent[0].headers.get('X-Guest-Id')).toMatch(UUID)
    expect(sent[0].headers.has('X-Sleeper-User')).toBe(false)
    expect(sent[0].headers.get('X-Guest-Id')).toBe(localStorage.getItem(GUEST_KEY))
  })

  it('a signed-in visitor sends X-Sleeper-User and never the guest id', async () => {
    guestId()
    setUser({ sleeperUserId: '77', username: 'u', displayName: 'U', avatar: null })
    await listHistoricalGames()
    expect(sent[0].headers.get('X-Sleeper-User')).toBe('77')
    expect(sent[0].headers.has('X-Guest-Id')).toBe(false)
  })

  it('the guest header is only ever on /api/history requests: a league request carries none and makes no id', async () => {
    await getSeats('123')
    expect(sent[0].url).toContain('/api/drafts/123/seats')
    expect(sent[0].headers.has('X-Guest-Id')).toBe(false)
    expect(localStorage.getItem(GUEST_KEY)).toBeNull()
  })

  it('a public season read carries no identity and makes no guest id (nobody asked who they are)', async () => {
    await getPublicSeasonList('NBA')
    expect(sent[0].url).toContain('/api/history/public/seasons?sport=NBA')
    expect(sent[0].headers.has('X-Guest-Id')).toBe(false)
    expect(sent[0].headers.has('X-Sleeper-User')).toBe(false)
    expect(localStorage.getItem(GUEST_KEY)).toBeNull()
  })
})

describe('claimGuestGames', () => {
  it('does nothing, and asks nothing, when this browser never made a guest id', async () => {
    expect(await claimGuestGames('77')).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('sends both headers, and drops the guest id only after the server answered 200', async () => {
    const id = guestId()
    fetchMock.mockImplementationOnce(async (url: string, init?: RequestInit) => {
      sent.push({ url, headers: new Headers(init?.headers), method: init?.method })
      // Still there while the request is in flight: a crash here must not lose the games.
      expect(localStorage.getItem(GUEST_KEY)).toBe(id)
      return ok({ moved: 2, leftBehind: 1 })
    })
    expect(await claimGuestGames('77')).toEqual({ moved: 2, leftBehind: 1 })
    expect(sent[0].url).toContain('/api/history/claim')
    expect(sent[0].method).toBe('POST')
    expect(sent[0].headers.get('X-Sleeper-User')).toBe('77')
    expect(sent[0].headers.get('X-Guest-Id')).toBe(id)
    expect(peekGuestId()).toBeNull()
    expect(localStorage.getItem(GUEST_KEY)).toBeNull()
  })

  it.each([400, 401, 500])('keeps the guest id when the server answers %s, so the next try still has something to claim', async (status) => {
    const id = guestId()
    fetchMock.mockResolvedValueOnce(refuse(status))
    await expect(claimGuestGames('77')).rejects.toMatchObject({ status })
    expect(peekGuestId()).toBe(id)
    expect(localStorage.getItem(GUEST_KEY)).toBe(id)
  })
})
