import { StrictMode, createElement, type ReactNode } from 'react'
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoricalGameState, HistoricalPickView, HistoricalPlayerCard } from './api'
import { usePickReveal } from './usePickReveal'
import type { RevealSpeed } from './revealSpeed'

const TEAMS = 12
const ROUNDS = 14
const USER = 5
const POOL = 200

function card(id: number): HistoricalPlayerCard {
  return {
    id,
    name: `Player ${id}`,
    positions: ['SF'],
    team: null,
    age: null,
    rank: id,
    positionalRank: id,
    prior: null,
    rookiePick: null,
  }
}

const slotFor = (pickNo: number) => {
  const round = Math.ceil(pickNo / TEAMS)
  const i = (pickNo - 1) % TEAMS
  return round % 2 === 1 ? i + 1 : TEAMS - i
}

/** The responses a server would give for a full draft: the load, then one per user pick; the last is COMPLETE. */
function fixture(): HistoricalGameState[] {
  const total = TEAMS * ROUNDS
  const available = Array.from({ length: POOL }, (_, i) => card(i + 1))
  const picks: HistoricalPickView[] = []
  const myPicks = Array.from({ length: total }, (_, i) => i + 1).filter((n) => slotFor(n) === USER)
  const responses: HistoricalGameState[] = []
  const snapshot = (): HistoricalGameState => {
    const done = picks.length === total
    const next = picks.length + 1
    return {
      id: 1,
      sport: 'NBA',
      status: done ? 'COMPLETE' : 'DRAFTING',
      season: {} as HistoricalGameState['season'],
      teams: TEAMS,
      rounds: ROUNDS,
      rosterPositions: [],
      userSlot: USER,
      myPicks,
      currentPickNo: done ? total + 1 : next,
      onTheClockSlot: done ? null : slotFor(next),
      isUsersTurn: !done && slotFor(next) === USER,
      picks: picks.map((p) => ({ ...p })),
      available: done ? [] : [...available],
      result: null,
    }
  }
  const take = (seat: 'USER' | 'BOT') => {
    const n = picks.length + 1
    const p = available.shift()!
    picks.push({ pickNo: n, round: Math.ceil(n / TEAMS), draftSlot: slotFor(n), seatType: seat, player: p })
  }
  const botsUntilUser = () => {
    while (picks.length < total && slotFor(picks.length + 1) !== USER) take('BOT')
  }
  botsUntilUser()
  responses.push(snapshot())
  while (picks.length < total) {
    take('USER')
    botsUntilUser()
    responses.push(snapshot())
  }
  return responses
}

const wrapper = ({ children }: { children: ReactNode }) => createElement(StrictMode, null, children)

function setup(speed: RevealSpeed = 'NORMAL') {
  return renderHook(({ s }) => usePickReveal(s), { initialProps: { s: speed }, wrapper })
}

const NORMAL_MS = 1000

/** Each tick re-renders and schedules the next timer, so time has to be advanced one interval per act. */
function advance(n: number, ms = NORMAL_MS) {
  for (let i = 0; i < n; i++) {
    act(() => {
      vi.advanceTimersByTime(ms)
    })
  }
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

describe('usePickReveal', () => {
  it('never exposes an unrevealed pick across every tick of a full 12x14 draft (SC-002)', () => {
    const responses = fixture()
    expect(responses[responses.length - 1].picks).toHaveLength(168)
    const { result } = setup()
    act(() => result.current.load(responses[0]))
    let ticks = 0
    let sawFinalReveal = false

    const check = (known: HistoricalPickView[]) => {
      const v = result.current.view!
      const shownIds = new Set(v.picks.map((p) => p.player!.id))
      const availIds = new Set(v.available.map((c) => c.id))
      const through = v.picks.length === 0 ? 0 : v.picks[v.picks.length - 1].pickNo
      expect(v.picks.map((p) => p.pickNo)).toEqual(Array.from({ length: v.picks.length }, (_, i) => i + 1))
      for (const p of known) {
        if (p.pickNo > through) {
          expect(shownIds.has(p.player!.id)).toBe(false)
          expect(availIds.has(p.player!.id)).toBe(true)
        } else {
          expect(availIds.has(p.player!.id)).toBe(false)
        }
      }
      const ranks = v.available.map((c) => c.rank)
      expect(ranks).toEqual([...ranks].sort((a, b) => a - b))
      if (result.current.revealing) {
        expect(v.status).toBe('DRAFTING')
        expect(v.result).toBeNull()
        expect(v.isUsersTurn).toBe(false)
        expect(v.currentPickNo).toBe(through + 1)
      }
    }

    for (let i = 0; i < responses.length; i++) {
      if (i > 0) act(() => result.current.receive(responses[i]))
      const known = responses[i].picks
      check(known)
      if (responses[i].status === 'COMPLETE' && result.current.revealing) {
        sawFinalReveal = true
        // the pool survives the response whose own available list is empty
        expect(result.current.view!.available.length).toBeGreaterThan(0)
      }
      let guard = 0
      while (result.current.revealing) {
        const before = result.current.view!.picks.length
        act(() => {
          vi.advanceTimersByTime(NORMAL_MS)
        })
        ticks++
        expect(result.current.view!.picks.length).toBe(before + 1)
        check(known)
        if (++guard > 400) throw new Error('reveal never caught up')
      }
      const v = result.current.view!
      expect(v.status).toBe(responses[i].status)
      expect(v.isUsersTurn).toBe(responses[i].isUsersTurn)
      expect(v.currentPickNo).toBe(responses[i].currentPickNo)
    }
    expect(sawFinalReveal).toBe(true)
    // 168 picks, 14 of them the fan's own, which are not paced
    expect(ticks).toBe(168 - 14)
    expect(result.current.view!.status).toBe('COMPLETE')
  })

  it('reveals the opening picks one per tick and keeps the clock on the next unrevealed pick', () => {
    const responses = fixture()
    const { result } = setup()
    act(() => result.current.load(responses[0]))
    const v0 = result.current.view!
    expect(v0.picks).toEqual([])
    expect(v0.isUsersTurn).toBe(false)
    expect(v0.onTheClockSlot).toBe(1)
    expect(v0.available).toHaveLength(POOL)
    act(() => {
      vi.advanceTimersByTime(999)
    })
    expect(result.current.view!.picks).toHaveLength(0)
    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(result.current.view!.picks.map((p) => p.pickNo)).toEqual([1])
    expect(result.current.view!.onTheClockSlot).toBe(2)
    advance(3)
    expect(result.current.view!.picks.map((p) => p.pickNo)).toEqual([1, 2, 3, 4])
    expect(result.current.view!.isUsersTurn).toBe(true)
    expect(result.current.revealing).toBe(false)
  })

  it('shows the fan pick at once and paces only the computer picks after it', () => {
    const responses = fixture()
    const { result } = setup()
    act(() => result.current.load(responses[0]))
    act(() => result.current.skip())
    act(() => result.current.receive(responses[1]))
    const v = result.current.view!
    const userPick = responses[1].picks.find((p) => p.pickNo === 5)!
    expect(v.picks[v.picks.length - 1].pickNo).toBe(5)
    expect(v.picks[v.picks.length - 1].player!.id).toBe(userPick.player!.id)
    expect(result.current.revealing).toBe(true)
    expect(v.isUsersTurn).toBe(false)
    expect(v.currentPickNo).toBe(6)
  })

  it('skip() lands on the same view as waiting out the timers (SC-003)', () => {
    const responses = fixture()
    const a = setup()
    const b = setup()
    for (const h of [a, b]) {
      act(() => h.result.current.load(responses[0]))
      act(() => h.result.current.skip())
      act(() => h.result.current.receive(responses[1]))
    }
    act(() => a.result.current.skip())
    advance(200)
    expect(a.result.current.revealing).toBe(false)
    expect(b.result.current.revealing).toBe(false)
    expect(a.result.current.view).toEqual(b.result.current.view)
    expect(a.result.current.view).toEqual(responses[1])
  })

  it('INSTANT never schedules a timer and shows the true state', () => {
    const responses = fixture()
    const { result } = setup('INSTANT')
    act(() => result.current.load(responses[0]))
    expect(vi.getTimerCount()).toBe(0)
    expect(result.current.revealing).toBe(false)
    expect(result.current.view).toEqual(responses[0])
    act(() => result.current.receive(responses[1]))
    expect(vi.getTimerCount()).toBe(0)
    expect(result.current.view).toEqual(responses[1])
  })

  it('switching to INSTANT mid-reveal finishes the reveal without a timer', () => {
    const responses = fixture()
    const { result, rerender } = setup('SLOW')
    act(() => result.current.load(responses[0]))
    expect(result.current.revealing).toBe(true)
    rerender({ s: 'INSTANT' })
    expect(result.current.revealing).toBe(false)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('a game where the fan has already picked loads showing everything at once', () => {
    const responses = fixture()
    const { result } = setup()
    act(() => result.current.load(responses[3]))
    expect(result.current.revealing).toBe(false)
    expect(vi.getTimerCount()).toBe(0)
    expect(result.current.view).toEqual(responses[3])
  })

  it('a completed game loads showing the result at once, even with no fan pick in the list', () => {
    const responses = fixture()
    const done = responses[responses.length - 1]
    const noUser = { ...done, picks: done.picks.map((p) => ({ ...p, seatType: 'BOT' as const })) }
    const { result } = setup()
    act(() => result.current.load(noUser))
    expect(result.current.revealing).toBe(false)
    expect(result.current.view!.status).toBe('COMPLETE')
  })

  it('a response arriving mid-reveal loses and reorders nothing', () => {
    const responses = fixture()
    const { result } = setup()
    act(() => result.current.load(responses[0]))
    act(() => result.current.skip())
    act(() => result.current.receive(responses[1]))
    advance(3)
    // a second response while several picks of the first are still unrevealed
    act(() => result.current.receive(responses[2]))
    let guard = 0
    while (result.current.revealing && guard++ < 400) {
      const nos = result.current.view!.picks.map((p) => p.pickNo)
      expect(nos).toEqual(Array.from({ length: nos.length }, (_, i) => i + 1))
      act(() => {
        vi.advanceTimersByTime(1000)
      })
    }
    expect(result.current.view).toEqual(responses[2])
  })

  it('unmounting clears the pending timer', () => {
    const responses = fixture()
    const { result, unmount } = setup()
    act(() => result.current.load(responses[0]))
    expect(vi.getTimerCount()).toBe(1)
    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('keeps one timer at a time under StrictMode double effects', () => {
    const responses = fixture()
    const { result } = setup('FAST')
    act(() => result.current.load(responses[0]))
    expect(vi.getTimerCount()).toBe(1)
    act(() => {
      vi.advanceTimersByTime(400)
    })
    expect(vi.getTimerCount()).toBe(1)
    expect(result.current.view!.picks).toHaveLength(1)
  })
})
