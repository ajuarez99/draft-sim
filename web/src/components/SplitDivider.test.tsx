import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SplitDivider, { SPLIT_STORAGE_KEY, TARGET_ROUNDS } from './SplitDivider'

// Row heights are the props, so the minimums are exact: board min = 72 + 3*33 = 171,
// list min = 88 + 3*27 = 169. With a 1000px area the board can span 171..831.
// The default is header + 7 compact rounds = 303, whatever the area.
const base = {
  totalHeight: 1000,
  rounds: 7,
  headerHeight: 72,
  compactRowHeight: 33,
  fullRowHeight: 74,
  listRowHeight: 27,
  listChromeHeight: 88,
}
const MIN = 171
const MAX = 831
const DEFAULT_PX = 72 + TARGET_ROUNDS * 33

function setup(over: Partial<React.ComponentProps<typeof SplitDivider>> = {}) {
  const onBoardHeight = vi.fn()
  const onDensityChange = vi.fn()
  render(<SplitDivider {...base} onBoardHeight={onBoardHeight} onDensityChange={onDensityChange} {...over} />)
  return { sep: screen.getByRole('separator'), onBoardHeight, onDensityChange }
}
const now = (sep: HTMLElement) => Number(sep.getAttribute('aria-valuenow'))

beforeEach(() => window.localStorage.clear())
afterEach(() => vi.restoreAllMocks())

describe('SplitDivider', () => {
  it('is a focusable horizontal separator exposing its range', () => {
    const { sep } = setup()
    expect(sep).toHaveAttribute('aria-orientation', 'horizontal')
    expect(sep).toHaveAttribute('tabindex', '0')
    expect(sep).toHaveAttribute('aria-valuemin', String(MIN))
    expect(sep).toHaveAttribute('aria-valuemax', String(MAX))
    expect(now(sep)).toBe(DEFAULT_PX)
  })

  it('moves one board row per Up/Down', () => {
    const { sep } = setup()
    const start = now(sep)
    fireEvent.keyDown(sep, { key: 'ArrowDown' })
    expect(now(sep)).toBe(start + 33)
    fireEvent.keyDown(sep, { key: 'ArrowUp' })
    fireEvent.keyDown(sep, { key: 'ArrowUp' })
    expect(now(sep)).toBe(start - 33)
  })

  it('Home and End go to the minimums: 3 board rounds, 3 list rows', () => {
    const { sep } = setup()
    fireEvent.keyDown(sep, { key: 'Home' })
    expect(now(sep)).toBe(MIN)
    fireEvent.keyDown(sep, { key: 'End' })
    expect(now(sep)).toBe(MAX)
  })

  it('never goes under a minimum, however far it is pushed', () => {
    const { sep } = setup()
    for (let i = 0; i < 40; i++) fireEvent.keyDown(sep, { key: 'ArrowUp' })
    expect(now(sep)).toBe(MIN)
    for (let i = 0; i < 80; i++) fireEvent.keyDown(sep, { key: 'ArrowDown' })
    expect(now(sep)).toBe(MAX)
  })

  it('Enter and double-click both reset to the default', () => {
    const { sep } = setup()
    fireEvent.keyDown(sep, { key: 'End' })
    fireEvent.keyDown(sep, { key: 'Enter' })
    expect(now(sep)).toBe(DEFAULT_PX)
    fireEvent.keyDown(sep, { key: 'Home' })
    fireEvent.doubleClick(sep)
    expect(now(sep)).toBe(DEFAULT_PX)
    expect(window.localStorage.getItem(SPLIT_STORAGE_KEY)).toBeNull()
  })

  it('drags with the pointer, and stops when released', () => {
    const { sep } = setup()
    const start = now(sep)
    fireEvent.pointerDown(sep, { clientY: 400, pointerId: 1 })
    fireEvent.pointerMove(sep, { clientY: 460, pointerId: 1 })
    expect(now(sep)).toBe(start + 60)
    fireEvent.pointerUp(sep, { pointerId: 1 })
    fireEvent.pointerMove(sep, { clientY: 700, pointerId: 1 })
    expect(now(sep)).toBe(start + 60)
  })

  it('saves the fraction and restores it on the next mount', () => {
    const first = setup()
    fireEvent.keyDown(first.sep, { key: 'End' })
    expect(Number(window.localStorage.getItem(SPLIT_STORAGE_KEY))).toBeCloseTo(MAX / 1000)
    document.body.innerHTML = ''
    const second = setup()
    expect(now(second.sep)).toBe(MAX)
  })

  it('falls back to the default when localStorage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const { sep } = setup()
    expect(now(sep)).toBe(DEFAULT_PX)
    expect(() => fireEvent.keyDown(sep, { key: 'ArrowDown' })).not.toThrow()
  })

  it('ignores a stored value that is not a fraction', () => {
    window.localStorage.setItem(SPLIT_STORAGE_KEY, 'banana')
    const { sep } = setup()
    expect(now(sep)).toBe(DEFAULT_PX)
  })

  it('defaults to 7 compact rounds on any area size, and a stored fraction wins over it', () => {
    const tall = setup({ totalHeight: 1400 })
    expect(now(tall.sep)).toBe(DEFAULT_PX)
    document.body.innerHTML = ''
    window.localStorage.setItem(SPLIT_STORAGE_KEY, '0.5')
    const stored = setup()
    expect(now(stored.sep)).toBe(500)
    fireEvent.keyDown(stored.sep, { key: 'Enter' })
    expect(now(stored.sep)).toBe(DEFAULT_PX)
    expect(window.localStorage.getItem(SPLIT_STORAGE_KEY)).toBeNull()
  })

  it('reports the board height once the area is measured, and not before', () => {
    const measured = setup()
    expect(measured.onBoardHeight).toHaveBeenLastCalledWith(DEFAULT_PX)
    document.body.innerHTML = ''
    const unmeasured = setup({ totalHeight: 0 })
    expect(unmeasured.onBoardHeight).not.toHaveBeenCalled()
    expect(unmeasured.onDensityChange).not.toHaveBeenCalled()
  })

  it('reports compact below rounds x full row + header, and full at or above it (FR-001b)', () => {
    // 7 * 74 + 72 = 590, so the 303px default is compact.
    const { sep, onDensityChange } = setup()
    expect(onDensityChange).toHaveBeenLastCalledWith('compact')
    fireEvent.keyDown(sep, { key: 'End' })
    expect(onDensityChange).toHaveBeenLastCalledWith('full')
    fireEvent.keyDown(sep, { key: 'Home' })
    expect(onDensityChange).toHaveBeenLastCalledWith('compact')
  })
})
