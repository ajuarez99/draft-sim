import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useIdleLock, useWatching } from './useIdleLock'
import { isUnlocked, lock, renewSession, startSession } from './leaguesLock'
import { renewLeagueSession } from './api'

// Allan's decision 2026-11-16 (lock review CR-06): an open, visible live draft room counts as activity.
// Same fake server as useIdleLock.test.tsx; the session store is real.

const WINDOW = 300
let serverOrigin = 0
const serverToken = () => `${Math.floor(Date.now() / 1000) + WINDOW}.${serverOrigin}.sig`

vi.mock('./api', () => ({ renewLeagueSession: vi.fn() }))
const renew = vi.mocked(renewLeagueSession)
const MIN = 60_000

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-11-16T12:00:00Z'))
  localStorage.clear()
  lock()
  renew.mockReset()
  renew.mockImplementation(async () => {
    renewSession(serverToken(), Date.now())
    return true
  })
  setVisibility('visible')
  serverOrigin = Math.floor(Date.now() / 1000)
  startSession(serverToken(), WINDOW)
})
afterEach(() => {
  vi.useRealTimers()
  lock()
  setVisibility('visible')
})

describe('useWatching', () => {
  it('a visible watched page with no input at all stays unlocked well past the idle window', async () => {
    renderHook(() => {
      useIdleLock(true)
      useWatching()
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20 * MIN)
    })
    expect(isUnlocked()).toBe(true)
    expect(renew).toHaveBeenCalled()
  })

  it('without it, the same untouched page locks at the window (the control)', async () => {
    renderHook(() => useIdleLock(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6 * MIN)
    })
    expect(isUnlocked()).toBe(false)
  })

  it('a hidden tab does not count as watching and locks on schedule', async () => {
    setVisibility('hidden')
    renderHook(() => {
      useIdleLock(true)
      useWatching()
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6 * MIN)
    })
    expect(isUnlocked()).toBe(false)
  })

  it('leaving the watched page stops the beat, so the site locks one window later', async () => {
    const idle = renderHook(() => useIdleLock(true))
    const watching = renderHook(() => useWatching())
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10 * MIN)
    })
    expect(isUnlocked()).toBe(true)
    watching.unmount()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6 * MIN)
    })
    expect(isUnlocked()).toBe(false)
    idle.unmount()
  })

  it('does nothing harmful where no idle lock is mounted (combined and Time Machine builds)', async () => {
    lock()
    renderHook(() => useWatching())
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2 * MIN)
    })
    expect(renew).not.toHaveBeenCalled()
  })
})
