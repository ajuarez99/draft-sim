import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLiveDraft } from './useLiveDraft'
import { holdsSession, lock, startSession } from './leaguesLock'
import { clearUser, setUser } from './user'
import { getStreamTicket } from './api'

// The live-draft EventSource cannot send headers. With the league lock the URL carries a single-purpose,
// 60-second STREAM TICKET fetched right before every EventSource (never the session), and only when a session is
// held. Only the ticket call is mocked; the session store is real.

vi.mock('./api', async (orig) => ({ ...(await orig<typeof import('./api')>()), getStreamTicket: vi.fn() }))
const ticket = vi.mocked(getStreamTicket)

const urls: string[] = []
let sources: FakeEventSource[] = []

class FakeEventSource {
  static CLOSED = 2
  onopen: (() => void) | null = null
  readyState = 0
  listeners: Record<string, ((ev: unknown) => void)[]> = {}
  constructor(url: string) {
    urls.push(url)
    sources.push(this)
  }
  addEventListener(type: string, fn: (ev: unknown) => void) {
    ;(this.listeners[type] ??= []).push(fn)
  }
  close() {
    this.readyState = 2
  }
  fail() {
    this.readyState = 2
    this.listeners.error?.forEach((fn) => fn(new Event('error')))
  }
}

let n = 0
beforeEach(() => {
  urls.length = 0
  sources = []
  n = 0
  ticket.mockReset()
  ticket.mockImplementation(async () => ({ ticket: `t.${++n}.sig`, expiresInSeconds: 60 }))
  vi.stubGlobal('EventSource', FakeEventSource)
  localStorage.clear()
  sessionStorage.clear()
  lock()
  clearUser()
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  lock()
  clearUser()
})

const SESSION = `${Math.floor(Date.now() / 1000) + 300}.1.sig`

describe('useLiveDraft EventSource URL', () => {
  it('has no query and asks for no ticket when signed out with no session (combined build, lock off)', () => {
    renderHook(() => useLiveDraft('d1'))
    // Synchronous, exactly as before the lock existed.
    expect(urls).toEqual(['/api/drafts/d1/live-stream'])
    expect(ticket).not.toHaveBeenCalled()
  })

  it('with only a user and no session: user= and no ticket call', () => {
    setUser({ sleeperUserId: '77', username: 'u', displayName: 'u', avatar: null })
    renderHook(() => useLiveDraft('d1'))
    expect(urls).toEqual(['/api/drafts/d1/live-stream?user=77'])
    expect(ticket).not.toHaveBeenCalled()
  })

  it('with a session held: fetches a ticket for that draft, then connects with ticket= and never session=', async () => {
    startSession(SESSION, 300)
    renderHook(() => useLiveDraft('d1'))
    expect(urls).toEqual([]) // nothing opens before the ticket is back
    await act(async () => {})
    expect(ticket).toHaveBeenCalledWith('d1')
    expect(urls).toEqual(['/api/drafts/d1/live-stream?ticket=t.1.sig'])
    expect(urls[0]).not.toContain('session=')
    expect(urls[0]).not.toContain(SESSION)
  })

  it('joins user and ticket with &, one query string', async () => {
    setUser({ sleeperUserId: '77', username: 'u', displayName: 'u', avatar: null })
    startSession(SESSION, 300)
    renderHook(() => useLiveDraft('d1'))
    await act(async () => {})
    expect(urls).toEqual(['/api/drafts/d1/live-stream?user=77&ticket=t.1.sig'])
  })

  it('gets a NEW ticket for the hook\'s own reconnect after the browser gives up', async () => {
    vi.useFakeTimers()
    startSession(SESSION, 300)
    renderHook(() => useLiveDraft('d1'))
    await act(async () => {})
    expect(urls).toEqual(['/api/drafts/d1/live-stream?ticket=t.1.sig'])
    act(() => sources[0].fail()) // CLOSED: the browser will not retry by itself
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    expect(ticket).toHaveBeenCalledTimes(2)
    expect(urls).toEqual(['/api/drafts/d1/live-stream?ticket=t.1.sig', '/api/drafts/d1/live-stream?ticket=t.2.sig'])
  })

  it('a failed ticket call reports an error and retries after the usual delay, with a fresh ticket call', async () => {
    vi.useFakeTimers()
    startSession(SESSION, 300)
    ticket.mockRejectedValueOnce(new Error('upstream down'))
    const { result } = renderHook(() => useLiveDraft('d1'))
    await act(async () => {})
    expect(urls).toEqual([])
    expect(result.current.error).toBe('upstream down')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    expect(ticket).toHaveBeenCalledTimes(2)
    expect(urls).toEqual(['/api/drafts/d1/live-stream?ticket=t.1.sig'])
  })

  it('unmounting before the ticket arrives opens nothing', async () => {
    startSession(SESSION, 300)
    let release: (v: { ticket: string; expiresInSeconds: number }) => void = () => {}
    ticket.mockImplementation(() => new Promise((resolve) => (release = resolve)))
    const { unmount } = renderHook(() => useLiveDraft('d1'))
    unmount()
    await act(async () => release({ ticket: 't.9.sig', expiresInSeconds: 60 }))
    expect(urls).toEqual([])
  })

  it('a session that is not held at connect time asks for nothing (holdsSession gate)', () => {
    expect(holdsSession()).toBe(false)
    renderHook(() => useLiveDraft('d2'))
    expect(ticket).not.toHaveBeenCalled()
  })
})
