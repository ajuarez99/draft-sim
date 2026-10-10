import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DraftTargets } from './api'
import { ApiError } from './apiError'
import { mkPlayer } from './testLiveRoom'
import { useTargets } from './useTargets'

type Deferred<T> = { promise: Promise<T>; resolve: (v: T) => void; reject: (e: unknown) => void }
function deferred<T>(): Deferred<T> {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const getTargets = vi.fn()
const putTargets = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getTargets: (...a: unknown[]) => getTargets(...a),
  putTargets: (...a: unknown[]) => putTargets(...a),
}))

const SCOPE = { sleeperDraftId: 'd1' }
const empty: DraftTargets = { players: [], missing: [] }
const A = mkPlayer('RB', 'Ann Alpha')
const B = mkPlayer('WR', 'Bob Bravo')
const C = mkPlayer('TE', 'Cy Charlie')

beforeEach(() => {
  getTargets.mockReset()
  putTargets.mockReset()
})

async function ready(initial: DraftTargets = empty) {
  getTargets.mockResolvedValue(initial)
  const hook = renderHook(() => useTargets(SCOPE))
  await waitFor(() => expect(hook.result.current.status).toBe('ready'))
  return hook
}

describe('useTargets', () => {
  it('loads once and shows the server list in order', async () => {
    const { result } = await ready({ players: [A, B], missing: [{ sleeperId: 'gone', name: 'Gone Guy' }] })
    expect(result.current.items.map((t) => t.name)).toEqual(['Ann Alpha', 'Bob Bravo', 'Gone Guy'])
    expect(getTargets).toHaveBeenCalledTimes(1)
  })

  it('two rapid edits keep one PUT in flight and the last list wins', async () => {
    const { result } = await ready()
    const first = deferred<DraftTargets>()
    putTargets.mockReturnValueOnce(first.promise).mockResolvedValue(empty)

    act(() => result.current.add(A))
    act(() => result.current.add(B))
    act(() => result.current.add(C))
    // Edits show immediately, before any save returns.
    expect(result.current.items.map((t) => t.name)).toEqual(['Ann Alpha', 'Bob Bravo', 'Cy Charlie'])
    expect(putTargets).toHaveBeenCalledTimes(1)
    expect(putTargets.mock.calls[0][1]).toEqual([A.sleeperId])

    await act(async () => first.resolve(empty))
    await waitFor(() => expect(putTargets).toHaveBeenCalledTimes(2))
    // Coalesced: the second PUT carries the latest list, not B then C separately.
    expect(putTargets.mock.calls[1][1]).toEqual([A.sleeperId, B.sleeperId, C.sleeperId])
    expect(result.current.error).toBe(false)
  })

  it('a slow response never overwrites a newer local edit', async () => {
    const { result } = await ready()
    const first = deferred<DraftTargets>()
    putTargets.mockReturnValueOnce(first.promise).mockResolvedValue(empty)
    act(() => result.current.add(A))
    act(() => result.current.add(B))
    // The stale response says only [A]; local state must stay [A, B].
    await act(async () => first.resolve({ players: [A], missing: [] }))
    await waitFor(() => expect(putTargets).toHaveBeenCalledTimes(2))
    expect(result.current.items.map((t) => t.name)).toEqual(['Ann Alpha', 'Bob Bravo'])
  })

  it('a failed save sets error, keeps the edit, and retry sends it again', async () => {
    const { result } = await ready()
    putTargets.mockRejectedValueOnce(new ApiError(500)).mockResolvedValue(empty)
    act(() => result.current.add(A))
    await waitFor(() => expect(result.current.error).toBe(true))
    expect(result.current.items.map((t) => t.name)).toEqual(['Ann Alpha'])
    act(() => result.current.retry())
    await waitFor(() => expect(result.current.error).toBe(false))
    expect(putTargets).toHaveBeenCalledTimes(2)
    expect(putTargets.mock.calls[1][1]).toEqual([A.sleeperId])
  })

  it('reorders and removes through the same save path', async () => {
    const { result } = await ready({ players: [A, B, C], missing: [] })
    putTargets.mockResolvedValue(empty)
    act(() => result.current.move(C.sleeperId, -1))
    await waitFor(() => expect(putTargets).toHaveBeenCalledTimes(1))
    expect(putTargets.mock.calls[0][1]).toEqual([A.sleeperId, C.sleeperId, B.sleeperId])
    act(() => result.current.remove(A.sleeperId))
    await waitFor(() => expect(putTargets).toHaveBeenCalledTimes(2))
    expect(putTargets.mock.calls[1][1]).toEqual([C.sleeperId, B.sleeperId])
  })

  describe('refetch on window focus', () => {
    it('refetches when idle, and applies a list changed on another device', async () => {
      const { result } = await ready({ players: [A], missing: [] })
      getTargets.mockResolvedValue({ players: [A, B], missing: [] })
      act(() => void window.dispatchEvent(new Event('focus')))
      await waitFor(() => expect(result.current.items).toHaveLength(2))
      expect(getTargets).toHaveBeenCalledTimes(2)
    })

    it('does not refetch while a save is in flight', async () => {
      const { result } = await ready()
      const pending = deferred<DraftTargets>()
      putTargets.mockReturnValue(pending.promise)
      act(() => result.current.add(A))
      act(() => void window.dispatchEvent(new Event('focus')))
      expect(getTargets).toHaveBeenCalledTimes(1)
      await act(async () => pending.resolve(empty))
    })

    it('does not refetch while errored, so the failed edit is not replaced by the server list', async () => {
      const { result } = await ready()
      putTargets.mockRejectedValue(new ApiError(500))
      act(() => result.current.add(A))
      await waitFor(() => expect(result.current.error).toBe(true))
      act(() => void window.dispatchEvent(new Event('focus')))
      expect(getTargets).toHaveBeenCalledTimes(1)
      expect(result.current.items.map((t) => t.name)).toEqual(['Ann Alpha'])
    })

    it('a refetch that returns after a local edit is dropped', async () => {
      const { result } = await ready()
      const slowGet = deferred<DraftTargets>()
      getTargets.mockReturnValueOnce(slowGet.promise)
      act(() => void window.dispatchEvent(new Event('focus')))
      putTargets.mockResolvedValue(empty)
      act(() => result.current.add(A))
      await act(async () => slowGet.resolve(empty))
      expect(result.current.items.map((t) => t.name)).toEqual(['Ann Alpha'])
    })
  })

  it('a hook mounted for the same scope waits for another hook\'s in-flight save before its first GET', async () => {
    const a = await ready()
    const save = deferred<DraftTargets>()
    putTargets.mockReturnValueOnce(save.promise)
    act(() => a.result.current.add(A))
    a.unmount() // the user navigates to the other room while the PUT is in the air
    expect(getTargets).toHaveBeenCalledTimes(1)

    getTargets.mockResolvedValue({ players: [A], missing: [] }) // what the server holds once the PUT commits
    const b = renderHook(() => useTargets(SCOPE))
    await act(async () => {
      await Promise.resolve()
    })
    expect(getTargets).toHaveBeenCalledTimes(1) // B has not asked yet
    expect(b.result.current.status).toBe('loading')

    await act(async () => save.resolve(empty))
    await waitFor(() => expect(b.result.current.status).toBe('ready'))
    expect(getTargets).toHaveBeenCalledTimes(2)
    expect(b.result.current.items.map((t) => t.name)).toEqual(['Ann Alpha'])
  })

  describe('an older backend', () => {
    it('a 404 on load means unavailable, without throwing', async () => {
      getTargets.mockRejectedValue(new ApiError(404))
      const { result } = renderHook(() => useTargets(SCOPE))
      await waitFor(() => expect(result.current.status).toBe('unavailable'))
      expect(result.current.error).toBe(false)
    })

    it('a 404 on save means unavailable too', async () => {
      const { result } = await ready()
      putTargets.mockRejectedValue(new ApiError(404))
      act(() => result.current.add(A))
      await waitFor(() => expect(result.current.status).toBe('unavailable'))
    })

    it('a 401 on save hides the stars with a sign-in note instead of a retry loop (review R8)', async () => {
      const { result } = await ready()
      putTargets.mockRejectedValue(new ApiError(401))
      act(() => result.current.add(A))
      await waitFor(() => expect(result.current.status).toBe('signedOut'))
      expect(result.current.error).toBe(false)
      expect(result.current.items).toEqual([])
    })

    it('another load failure holds editing back instead of risking an overwrite', async () => {
      getTargets.mockRejectedValue(new ApiError(500))
      const { result } = renderHook(() => useTargets(SCOPE))
      await waitFor(() => expect(result.current.status).toBe('loadFailed'))
      getTargets.mockResolvedValue({ players: [A], missing: [] })
      act(() => result.current.retry())
      await waitFor(() => expect(result.current.status).toBe('ready'))
      expect(result.current.items).toHaveLength(1)
    })
  })
})
