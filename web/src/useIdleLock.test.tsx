import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { IDLE_TICK_MS, RENEW_SPACING_MS, useIdleLock } from './useIdleLock'
import {
  ACTIVE_GRACE_MS,
  isUnlocked,
  lock,
  lockedAfterIdleMinutes,
  renewSession,
  sessionExpiresAt,
  startSession,
} from './leaguesLock'
import { renewLeagueSession } from './api'

// The idle watcher with fake timers. The network call is replaced by a fake server that behaves like the real
// one: it answers with a session token whose embedded expiry is "now + window" and hands it to renewSession the
// way apiFetch does. The session store (leaguesLock) is real.

const WINDOW = 300
let serverOrigin = 0
const serverToken = () => `${Math.floor(Date.now() / 1000) + WINDOW}.${serverOrigin}.sig`

vi.mock('./api', () => ({ renewLeagueSession: vi.fn() }))
const renew = vi.mocked(renewLeagueSession)

const SEC = 1000
const MIN = 60 * SEC

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
}

/** Starts a session the way unlock does, with a token that carries a real embedded expiry. */
function unlock() {
  serverOrigin = Math.floor(Date.now() / 1000)
  startSession(serverToken(), WINDOW)
}

const input = (type = 'scroll') => window.dispatchEvent(new Event(type))

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-11-16T12:00:00Z'))
  localStorage.clear()
  sessionStorage.clear()
  lock()
  renew.mockReset()
  renew.mockImplementation(async () => {
    const sent = Date.now()
    renewSession(serverToken(), sent)
    return true
  })
  setVisibility('visible')
})
afterEach(() => {
  vi.useRealTimers()
  lock()
  setVisibility('visible')
})

describe('useIdleLock', () => {
  it('the timing constants: the tick and the renewal spacing are both well below the grace (CR-01 second mechanism)', () => {
    expect(IDLE_TICK_MS).toBe(30 * SEC)
    expect(RENEW_SPACING_MS).toBe(15 * SEC)
    expect(ACTIVE_GRACE_MS).toBeGreaterThanOrEqual(2 * IDLE_TICK_MS)
    expect(ACTIVE_GRACE_MS).toBeGreaterThan(IDLE_TICK_MS)
  })

  it('CR-01: unlock, read to 4:10, scroll until 4:55 -> still unlocked at 5:00 and a renewal was sent', async () => {
    unlock()
    renderHook(() => useIdleLock(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4 * MIN + 10 * SEC)
    })
    expect(renew).not.toHaveBeenCalled()
    // Scroll every 5 s from 4:10 to 4:55.
    for (let t = 0; t <= 45; t += 5) {
      input('scroll')
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5 * SEC)
      })
    }
    input('keydown') // 4:55
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5 * SEC + 100)
    })
    expect(renew).toHaveBeenCalled()
    expect(isUnlocked()).toBe(true)
    expect(lockedAfterIdleMinutes()).toBeNull()
    // And it holds for another window less the quiet time, then locks one window after the last input.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4 * MIN)
    })
    expect(isUnlocked()).toBe(true)
  })

  it('CR-01: the very first input in the last seconds before expiry renews at once, not at the next tick', async () => {
    unlock()
    renderHook(() => useIdleLock(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(WINDOW * SEC - 2 * SEC)
    })
    input('keydown')
    expect(renew).toHaveBeenCalledTimes(1) // synchronously, from the handler
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3 * SEC)
    })
    expect(isUnlocked()).toBe(true)
  })

  it('CR-05: with no input at all the site locks at exactly the window, not at the next tick, saying it was idle', async () => {
    unlock()
    renderHook(() => useIdleLock(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(WINDOW * SEC - 1)
    })
    expect(isUnlocked()).toBe(true)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(isUnlocked()).toBe(false)
    expect(lockedAfterIdleMinutes()).toBe(5)
    expect(renew).not.toHaveBeenCalled()
  })

  it('an input in the first half of the window does not call the server', () => {
    unlock()
    renderHook(() => useIdleLock(true))
    input('keydown')
    expect(renew).not.toHaveBeenCalled()
  })

  it.each(['pointerdown', 'keydown', 'wheel', 'scroll', 'touchstart'])('%s counts as activity', async (type) => {
    unlock()
    renderHook(() => useIdleLock(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200 * SEC)
    })
    input(type)
    expect(renew).toHaveBeenCalledTimes(1)
  })

  it('a mousemove alone is not activity', async () => {
    unlock()
    renderHook(() => useIdleLock(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200 * SEC)
    })
    input('mousemove')
    expect(renew).not.toHaveBeenCalled()
  })

  it('a burst of events sends one request, and the next one waits for the spacing', async () => {
    unlock()
    renderHook(() => useIdleLock(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200 * SEC)
    })
    for (let i = 0; i < 20; i++) input('keydown')
    expect(renew).toHaveBeenCalledTimes(1)
    // The session now has a full window left, so more input does not renew until it is past half again.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(RENEW_SPACING_MS + 1)
    })
    input('keydown')
    expect(renew).toHaveBeenCalledTimes(1)
  })

  it('no second request while one is in flight', async () => {
    unlock()
    let finish: () => void = () => {}
    renew.mockImplementation(() => new Promise<boolean>((resolve) => (finish = () => resolve(true))))
    renderHook(() => useIdleLock(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200 * SEC)
    })
    input('keydown')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(RENEW_SPACING_MS + 1)
    })
    input('keydown')
    expect(renew).toHaveBeenCalledTimes(1)
    finish()
  })

  it('a single failed renewal does not lock; the next input tries again and a good one keeps the session', async () => {
    unlock()
    renew.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    renderHook(() => useIdleLock(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200 * SEC)
    })
    input('keydown')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(renew).toHaveBeenCalledTimes(1)
    expect(isUnlocked()).toBe(true)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(RENEW_SPACING_MS)
    })
    input('keydown')
    expect(renew).toHaveBeenCalledTimes(2)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    // The second renewal worked: the expiry moved well past the original 5:00.
    expect(sessionExpiresAt()!).toBeGreaterThan(Date.parse('2026-11-16T12:05:00Z') + 100 * SEC)
  })

  it('does not renew from a hidden tab', async () => {
    unlock()
    renderHook(() => useIdleLock(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200 * SEC)
    })
    setVisibility('hidden')
    input('keydown')
    expect(renew).not.toHaveBeenCalled()
  })

  it('CR-05: an expiry that passed while the tab was hidden locks the moment it becomes visible', () => {
    unlock()
    renderHook(() => useIdleLock(true))
    setVisibility('hidden')
    // The clock moves with no timer firing (a sleeping laptop), then the tab comes back.
    vi.setSystemTime(Date.now() + 10 * MIN)
    expect(isUnlocked()).toBe(false)
    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(lockedAfterIdleMinutes()).toBe(5)
  })

  it('CR-05: a clock jump with the tab still "visible" (lid closed) locks on window focus', () => {
    unlock()
    renderHook(() => useIdleLock(true))
    vi.setSystemTime(Date.now() + 60 * MIN)
    window.dispatchEvent(new Event('focus'))
    expect(lockedAfterIdleMinutes()).toBe(5)
    expect(isUnlocked()).toBe(false)
  })

  it('pageshow also re-checks', () => {
    unlock()
    renderHook(() => useIdleLock(true))
    vi.setSystemTime(Date.now() + 60 * MIN)
    window.dispatchEvent(new Event('pageshow'))
    expect(isUnlocked()).toBe(false)
  })

  it('a still-valid session is left alone when the tab becomes visible', () => {
    unlock()
    renderHook(() => useIdleLock(true))
    setVisibility('hidden')
    vi.setSystemTime(Date.now() + 2 * MIN)
    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(isUnlocked()).toBe(true)
    expect(lockedAfterIdleMinutes()).toBeNull()
  })

  it('does nothing when disabled (locked, or a build without the lock)', async () => {
    renderHook(() => useIdleLock(false))
    input('keydown')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10 * MIN)
    })
    expect(renew).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('cleans up its expiry timer, tick and listeners on unmount', async () => {
    unlock()
    const before = vi.getTimerCount()
    const { unmount } = renderHook(() => useIdleLock(true))
    expect(vi.getTimerCount()).toBeGreaterThanOrEqual(before + 2) // the expiry timer and the safety tick
    unmount()
    expect(vi.getTimerCount()).toBe(before)
    input('keydown')
    window.dispatchEvent(new Event('focus'))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10 * MIN)
    })
    expect(renew).not.toHaveBeenCalled()
    // Unmounted, so it did not lock the (long expired) session either: that is the next mount's job.
  })

  it('the expiry timer is re-armed when the expiry moves (another tab renewed), not fired at the old time', async () => {
    unlock()
    renderHook(() => useIdleLock(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100 * SEC)
    })
    // Another tab extended the session by two minutes (shared through storage, read by this tab).
    startSession(serverToken(), WINDOW)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(WINDOW * SEC - 100 * SEC + 1)
    })
    // The original 5:00 has passed but the session now ends at 6:40.
    expect(isUnlocked()).toBe(true)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100 * SEC)
    })
    expect(isUnlocked()).toBe(false)
  })
})
