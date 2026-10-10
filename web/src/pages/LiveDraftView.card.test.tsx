import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LiveState, RealPick } from '../api'
import { mkLive, mkPlayer, mkRealPick, mkSeats } from '../testLiveRoom'
import LiveDraftView from './LiveDraftView'

/**
 * The card lifecycle (spec 012, R9): what opens one, what replaces it, what
 * closes it, and -- as important -- what never opens one (history).
 */

const getRealDraftBoard = vi.fn()
const getSeats = vi.fn()
const getDrafts = vi.fn()
const streamSimulationQuietly = vi.fn()
vi.mock('../api', () => ({
  getRealDraftBoard: (...a: unknown[]) => getRealDraftBoard(...a),
  getSeats: (...a: unknown[]) => getSeats(...a),
  getDrafts: (...a: unknown[]) => getDrafts(...a),
  streamSimulationQuietly: (...a: unknown[]) => streamSimulationQuietly(...a),
  createMockSessionFromDraft: vi.fn(),
  // Spec 024: the room loads the user's targets; these tests have none.
  getTargets: () => Promise.resolve({ players: [], missing: [] }),
  putTargets: () => Promise.resolve({ players: [], missing: [] }),
}))

const store = vi.hoisted(() => ({
  live: null as unknown,
  listeners: new Set<() => void>(),
}))
vi.mock('../useLiveDraft', async () => {
  const React = await import('react')
  return {
    useLiveDraft: () => {
      const [, force] = React.useReducer((x: number) => x + 1, 0)
      React.useEffect(() => {
        store.listeners.add(force)
        return () => {
          store.listeners.delete(force)
        }
      }, [])
      return { live: store.live, connected: true, secondsSinceContact: 0, error: null }
    },
  }
})

vi.mock('../components/DraftBoard', () => ({ default: () => null }))
vi.mock('../components/AvailabilityPanel', () => ({ default: () => null }))
vi.mock('../components/LiveStatusBar', () => ({ default: () => null }))
vi.mock('../components/PlayerCard', () => ({ default: () => null }))
vi.mock('../components/SeatPopover', () => ({ default: () => null }))

const TEAMS = 4

const all: RealPick[] = []
function draftThrough(n: number): RealPick[] {
  while (all.length < n) {
    const i = all.length + 1
    all.push(mkRealPick(i, TEAMS, mkPlayer(i % 2 ? 'K' : 'DEF', `Player ${i}`)))
  }
  return all.slice(0, n)
}

function pushLive(picks: RealPick[], over: Partial<LiveState> = {}) {
  store.live = mkLive(picks, TEAMS, over)
  act(() => store.listeners.forEach((f) => f()))
}

async function flush() {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
}

async function advance(ms: number) {
  await act(async () => {
    vi.advanceTimersByTime(ms)
  })
}

async function renderRoom(initialPicks: number) {
  const picks = draftThrough(initialPicks)
  store.live = mkLive(picks, TEAMS)
  getRealDraftBoard.mockResolvedValue({ draftId: 'd1', teams: TEAMS, rounds: 15, status: 'drafting', picks })
  render(
    <MemoryRouter initialEntries={['/live/d1']}>
      <Routes>
        <Route path="/live/:draftId" element={<LiveDraftView />} />
      </Routes>
    </MemoryRouter>,
  )
  await flush()
  await flush()
}

function dialogs() {
  return screen.queryAllByRole('dialog')
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: false })
  all.length = 0
  localStorage.clear()
  getRealDraftBoard.mockReset()
  getSeats.mockReset()
  getDrafts.mockReset()
  streamSimulationQuietly.mockReset()
  getSeats.mockResolvedValue(mkSeats(TEAMS))
  getDrafts.mockResolvedValue([])
  streamSimulationQuietly.mockReturnValue(new Promise(() => {}))
})

afterEach(() => {
  vi.useRealTimers()
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
})

describe('the pick card lifecycle', () => {
  it('picks already landed on mount raise no card', async () => {
    await renderRoom(6)
    expect(dialogs()).toHaveLength(0)
  })

  it('a pick that arrives opens a card, without taking focus', async () => {
    await renderRoom(5)
    pushLive(draftThrough(6))
    const card = screen.getByRole('dialog', { name: /Player 6/ })
    expect(card.contains(document.activeElement)).toBe(false)
  })

  it('three picks inside 10s leave exactly one card, showing the newest', async () => {
    await renderRoom(4)
    pushLive(draftThrough(5))
    await advance(3000)
    pushLive(draftThrough(6))
    await advance(3000)
    pushLive(draftThrough(7))
    expect(dialogs()).toHaveLength(1)
    expect(screen.getByRole('dialog', { name: /Player 7/ })).toBeTruthy()
  })

  it('auto-dismisses after 8s', async () => {
    await renderRoom(4)
    pushLive(draftThrough(5))
    await advance(7900)
    expect(dialogs()).toHaveLength(1)
    await advance(200)
    expect(dialogs()).toHaveLength(0)
  })

  it('does not dismiss while hovered, and starts counting again after', async () => {
    await renderRoom(4)
    pushLive(draftThrough(5))
    fireEvent.mouseEnter(screen.getByRole('dialog'))
    await advance(30000)
    expect(dialogs()).toHaveLength(1)
    fireEvent.mouseLeave(screen.getByRole('dialog'))
    await advance(8100)
    expect(dialogs()).toHaveLength(0)
  })

  it('going on the clock closes it and suppresses new ones', async () => {
    await renderRoom(4)
    pushLive(draftThrough(5))
    expect(dialogs()).toHaveLength(1)
    // mkSeats' mySlot is 1.
    pushLive(draftThrough(5), { onTheClockSlot: 1 })
    expect(dialogs()).toHaveLength(0)
    pushLive(draftThrough(6), { onTheClockSlot: 1 })
    expect(dialogs()).toHaveLength(0)
  })

  it('a pick delivered while the tab is hidden never opens a card', async () => {
    await renderRoom(4)
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    pushLive(draftThrough(5))
    expect(dialogs()).toHaveLength(0)
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    await advance(1000)
    expect(dialogs()).toHaveLength(0)
  })

  it('with the preference off, no card appears; turning it off closes an open one', async () => {
    await renderRoom(4)
    pushLive(draftThrough(5))
    expect(dialogs()).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Pick cards on' }))
    expect(dialogs()).toHaveLength(0)
    expect(localStorage.getItem('bk.pickCards.v1')).toBe('off')
    pushLive(draftThrough(6))
    expect(dialogs()).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Pick cards off' }).getAttribute('aria-pressed')).toBe('false')
  })

  it('starts off when the stored preference is off', async () => {
    localStorage.setItem('bk.pickCards.v1', 'off')
    await renderRoom(4)
    pushLive(draftThrough(5))
    expect(dialogs()).toHaveLength(0)
  })

  it('clicking an older feed row opens that pick\'s card and takes focus', async () => {
    await renderRoom(6)
    expect(dialogs()).toHaveLength(0)
    // The compact row's ticker shows only the latest pick until expanded (spec 024 FR-001c).
    fireEvent.click(screen.getByRole('button', { name: /^▾ Last/ }))
    fireEvent.click(screen.getByRole('button', { name: /Player 5/ }))
    const card = screen.getByRole('dialog', { name: /Player 5/ })
    expect(card.contains(document.activeElement)).toBe(true)
  })
})
