import { useCallback, useEffect, useMemo, useReducer } from 'react'
import type { HistoricalGameState, HistoricalPlayerCard } from './api'
import { REVEAL_MS, type RevealSpeed } from './revealSpeed'

/**
 * Paces the computer picks of a Time Machine draft (spec 063 US1). The server has already made every pick in a
 * response; this holds "revealed through pick N" and exposes a `view` of the server state cut at N. Every
 * consumer in the room reads `view`, never the raw response, which is what keeps an unrevealed pick's player
 * out of the board and in the available list.
 *
 * Not useRevealedBoard: that one reveals a predicted board from pick 0 and pauses on the user's picks; it has no
 * "resume where the last response ended" and no derived available list (plan B1, research R1).
 */

type State = {
  state: HistoricalGameState | null
  /**
   * Everyone not yet drafted when the current reveal began, in the server's rank order. The `available` list of
   * the response that completes the draft is empty, so the pool is built from the last response's available list
   * PLUS its picks (an unrevealed pick's player is not in `available` any more), and survives a completing
   * response (plan review PR-01).
   */
  pool: HistoricalPlayerCard[]
  revealedThrough: number
  target: number
}

type Action =
  | { type: 'LOADED'; state: HistoricalGameState; instant: boolean }
  | { type: 'RESPONSE'; state: HistoricalGameState; instant: boolean }
  | { type: 'TICK' }
  | { type: 'SKIP' }

const INITIAL: State = { state: null, pool: [], revealedThrough: 0, target: 0 }

const lastPickNo = (g: HistoricalGameState) => g.picks.reduce((m, p) => Math.max(m, p.pickNo), 0)

function buildPool(prev: HistoricalPlayerCard[], g: HistoricalGameState): HistoricalPlayerCard[] {
  const byId = new Map<number, HistoricalPlayerCard>()
  for (const c of prev) byId.set(c.id, c)
  for (const c of g.available) byId.set(c.id, c)
  for (const p of g.picks) if (p.player) byId.set(p.player.id, p.player)
  return [...byId.values()].sort((a, b) => a.rank - b.rank)
}

function reduce(s: State, a: Action): State {
  switch (a.type) {
    case 'LOADED': {
      const target = lastPickNo(a.state)
      // The one case that replays on a reload: a game in which the fan has not picked yet. Every other load
      // shows the true state, so a reload never hides picks the fan already saw.
      const fromStart =
        a.state.status === 'DRAFTING' && !a.state.picks.some((p) => p.seatType === 'USER') && target > 0
      return {
        state: a.state,
        pool: buildPool([], a.state),
        revealedThrough: fromStart && !a.instant ? 0 : target,
        target,
      }
    }
    case 'RESPONSE': {
      const target = lastPickNo(a.state)
      let revealed = Math.min(s.revealedThrough, target)
      if (a.instant) {
        revealed = target
      } else {
        // The fan's own pick(s) show immediately; the computer picks after them are paced.
        const bySeat = new Map(a.state.picks.map((p) => [p.pickNo, p.seatType]))
        while (revealed < target && bySeat.get(revealed + 1) === 'USER') revealed++
      }
      return { state: a.state, pool: buildPool(s.pool, a.state), revealedThrough: revealed, target }
    }
    case 'TICK':
      return s.revealedThrough < s.target ? { ...s, revealedThrough: s.revealedThrough + 1 } : s
    case 'SKIP':
      return s.revealedThrough < s.target ? { ...s, revealedThrough: s.target } : s
  }
}

export type PickReveal = {
  /** The state to render. Null before the first load. Equals the server state once the reveal has caught up. */
  view: HistoricalGameState | null
  revealing: boolean
  /**
   * The SERVER's status is DRAFTING (the view may still say so while a final reveal runs, after the server has
   * finished). Only for deciding whether a request can still be legal; never render from it.
   */
  serverDrafting: boolean
  /** Highest pick number shown, and the highest the server has made. */
  revealedThrough: number
  target: number
  /** Hand the hook the first load of a game. */
  load: (g: HistoricalGameState) => void
  /** Hand the hook a response to a pick or an auto call. A failed request must not call this. */
  receive: (g: HistoricalGameState) => void
  skip: () => void
}

export function usePickReveal(speed: RevealSpeed): PickReveal {
  const [s, dispatch] = useReducer(reduce, INITIAL)
  const ms = REVEAL_MS[speed]
  const instant = ms === 0
  const revealing = s.state != null && s.revealedThrough < s.target

  // One timer at a time. The cleanup makes StrictMode's double-invoked effect and an unmount safe. INSTANT
  // schedules nothing: it finishes the reveal in the reducer.
  useEffect(() => {
    if (!revealing) return
    if (instant) {
      dispatch({ type: 'SKIP' })
      return
    }
    const t = setTimeout(() => dispatch({ type: 'TICK' }), ms)
    return () => clearTimeout(t)
  }, [revealing, instant, ms, s.revealedThrough])

  const view = useMemo<HistoricalGameState | null>(() => {
    const g = s.state
    if (!g) return null
    if (s.revealedThrough >= s.target) return g
    const revealed = g.picks.filter((p) => p.pickNo <= s.revealedThrough)
    const taken = new Set<number>()
    for (const p of revealed) if (p.player) taken.add(p.player.id)
    const next = g.picks.find((p) => p.pickNo === s.revealedThrough + 1)
    return {
      ...g,
      status: 'DRAFTING',
      result: null,
      isUsersTurn: false,
      picks: revealed,
      available: s.pool.filter((c) => !taken.has(c.id)),
      currentPickNo: s.revealedThrough + 1,
      onTheClockSlot: next ? next.draftSlot : g.onTheClockSlot,
    }
  }, [s])

  const load = useCallback((g: HistoricalGameState) => dispatch({ type: 'LOADED', state: g, instant }), [instant])
  const receive = useCallback((g: HistoricalGameState) => dispatch({ type: 'RESPONSE', state: g, instant }), [instant])
  const skip = useCallback(() => dispatch({ type: 'SKIP' }), [])

  return { view, revealing, serverDrafting: s.state?.status === 'DRAFTING', revealedThrough: s.revealedThrough, target: s.target, load, receive, skip }
}
