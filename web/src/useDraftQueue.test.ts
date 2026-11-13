import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDraftQueue, type QueueGameStatus } from './useDraftQueue'

const pool = (...ids: number[]) => ids.map((id) => ({ id }))
const KEY = 'bk.tm.queue.7'

beforeEach(() => localStorage.clear())
afterEach(() => vi.restoreAllMocks())

type P = { status: QueueGameStatus | null; available: { id: number }[] | null }
const setup = (initial: P) => renderHook((p: P) => useDraftQueue(7, p.status, p.available), { initialProps: initial })

describe('useDraftQueue', () => {
  it('adds, removes, toggles and reorders, and persists the order', () => {
    const { result } = setup({ status: 'DRAFTING', available: pool(1, 2, 3, 4) })
    act(() => result.current.add(2))
    act(() => result.current.add(3))
    act(() => result.current.add(4))
    act(() => result.current.add(3)) // a duplicate is ignored
    expect(result.current.ids).toEqual([2, 3, 4])
    act(() => result.current.moveUp(4))
    expect(result.current.ids).toEqual([2, 4, 3])
    act(() => result.current.moveUp(2)) // already first: unchanged
    act(() => result.current.moveDown(3)) // already last: unchanged
    expect(result.current.ids).toEqual([2, 4, 3])
    act(() => result.current.moveDown(2))
    expect(result.current.ids).toEqual([4, 2, 3])
    act(() => result.current.toggle(2))
    expect(result.current.has(2)).toBe(false)
    act(() => result.current.toggle(2))
    expect(result.current.ids).toEqual([4, 3, 2])
    act(() => result.current.remove(3))
    expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual([4, 2])
  })

  it('survives a reload: a new hook reads the stored order', () => {
    localStorage.setItem(KEY, JSON.stringify([5, 3, 9]))
    const { result } = setup({ status: null, available: null })
    expect(result.current.ids).toEqual([5, 3, 9])
  })

  it('does not prune before the game has loaded', () => {
    localStorage.setItem(KEY, JSON.stringify([5, 3]))
    const { result, rerender } = setup({ status: null, available: null })
    expect(result.current.ids).toEqual([5, 3])
    rerender({ status: 'DRAFTING', available: null }) // status known, pool not
    expect(result.current.ids).toEqual([5, 3])
    expect(localStorage.getItem(KEY)).not.toBeNull()
  })

  it('prunes a player when his pick is revealed (he leaves the view pool), keeping the rest in order', () => {
    localStorage.setItem(KEY, JSON.stringify([5, 3, 9]))
    const { result, rerender } = setup({ status: 'DRAFTING', available: pool(3, 5, 9, 10) })
    expect(result.current.ids).toEqual([5, 3, 9])
    rerender({ status: 'DRAFTING', available: pool(3, 9, 10) }) // 5 taken
    expect(result.current.ids).toEqual([3, 9])
    expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual([3, 9])
  })

  it('deletes the key once the game is COMPLETE or ABANDONED', () => {
    localStorage.setItem(KEY, JSON.stringify([5, 3]))
    const { result, rerender } = setup({ status: 'DRAFTING', available: pool(3, 5) })
    rerender({ status: 'COMPLETE', available: [] })
    expect(result.current.ids).toEqual([])
    expect(localStorage.getItem(KEY)).toBeNull()

    localStorage.setItem(KEY, JSON.stringify([1]))
    const second = setup({ status: 'ABANDONED', available: [] })
    expect(second.result.current.ids).toEqual([])
    expect(localStorage.getItem(KEY)).toBeNull()
  })

  it('falls back to memory when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const { result } = setup({ status: 'DRAFTING', available: pool(1, 2) })
    act(() => result.current.add(2))
    act(() => result.current.add(1))
    expect(result.current.ids).toEqual([2, 1])
  })

  it('ignores a corrupt stored value', () => {
    localStorage.setItem(KEY, '{not json')
    expect(setup({ status: null, available: null }).result.current.ids).toEqual([])
    localStorage.setItem(KEY, JSON.stringify([1, 'x', null, 1, 2]))
    expect(setup({ status: null, available: null }).result.current.ids).toEqual([1, 2])
  })
})
