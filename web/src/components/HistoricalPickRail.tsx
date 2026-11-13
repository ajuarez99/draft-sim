import { useEffect, useRef } from 'react'
import type { HistoricalPickView, HistoricalSport } from '../api'
import { cardToPlayerRef, teamName } from '../historicalCopy'
import { positionLabel, posPill } from '../positions'
import { roundPickLabel } from '../roundPickLabel'
import { slotForPick } from '../draftSlot'

type Props = {
  /** The reveal view's picks: only picks that have been revealed. Never the raw server list. */
  picks: HistoricalPickView[]
  teams: number
  rounds: number
  userSlot: number
  /** The pick being made now (the view's currentPickNo). */
  currentPickNo: number
  reversalRound: number
  sport: HistoricalSport
  /** Whether the draft is still going. Once it is over there is no current pick. */
  drafting: boolean
}

/** How many already-made picks stay above the current one. */
const LAST_MADE = 4

/**
 * Spec 063 C3: the pick rail. The last few picks made, then everything left in this round and the next, in pick
 * order. A made pick shows the player and the team that took him; an upcoming pick shows only the team. Your
 * picks are marked; the current pick is marked, bigger, and scrolled into view.
 *
 * It deliberately shows no "what this computer team needs": nothing here has checked that the computer teams draft
 * for need, and printing it would imply they do.
 */
export default function HistoricalPickRail({ picks, teams, rounds, userSlot, currentPickNo, reversalRound, sport, drafting }: Props) {
  const total = teams * rounds
  const listRef = useRef<HTMLOListElement>(null)
  const byNo = new Map(picks.map((p) => [p.pickNo, p]))

  const current = drafting ? Math.min(currentPickNo, total) : total + 1
  const roundNow = Math.ceil(Math.min(current, total) / teams)
  const from = drafting ? Math.max(1, current - LAST_MADE) : Math.max(1, total - LAST_MADE * 2 + 1)
  const to = drafting ? Math.min(total, (roundNow + 1) * teams) : total
  const nos: number[] = []
  for (let n = from; n <= to; n++) nos.push(n)

  // Keep the current pick in view inside the rail only (never scroll the page), without smooth motion when the
  // fan asked for less of it.
  useEffect(() => {
    const list = listRef.current
    const el = list?.querySelector<HTMLElement>('[aria-current="true"]')
    if (!list || !el) return
    const reduce = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const top = Math.max(0, el.offsetTop - list.clientHeight / 3)
    if (typeof list.scrollTo === 'function') list.scrollTo({ top, behavior: reduce ? 'auto' : 'smooth' })
    else list.scrollTop = top
  }, [current, drafting])

  const sportKey = sport === 'NFL' ? 'nfl' : 'nba'
  let lastRound = 0
  return (
    <section className="panel hist-rail" aria-label="Pick order">
      <h2 className="hist-rail-title">Pick order</h2>
      <ol className="hist-rail-list" ref={listRef}>
        {nos.map((n) => {
          const round = Math.ceil(n / teams)
          const made = byNo.get(n)
          const slot = made ? made.draftSlot : slotForPick(n, teams, reversalRound)
          const isCurrent = drafting && n === current
          const mine = slot === userSlot
          const head =
            round !== lastRound ? (
              <li key={`r${round}`} className="hist-rail-round" aria-hidden="true">
                Round {round}
              </li>
            ) : null
          lastRound = round
          const ref = made?.player ? cardToPlayerRef(made.player, sport) : null
          return [
            head,
            <li
              key={n}
              className={`hist-rail-pick${made ? ' made' : ' upcoming'}${mine ? ' me' : ''}${isCurrent ? ' current' : ''}${drafting && made && n === current - 1 ? ' fresh' : ''}`}
              aria-current={isCurrent ? 'true' : undefined}
            >
              <span className="hist-rail-no mono">{roundPickLabel(n, teams)}</span>
              <span className="hist-rail-body">
                <span className="hist-rail-team">
                  {teamName(slot, userSlot)}
                  {isCurrent && <span className="hist-rail-clock"> · on the clock</span>}
                </span>
                {ref && (
                  <span className="hist-rail-player">
                    <span {...posPill(ref, sportKey)}>{positionLabel(ref, sportKey)}</span>
                    <span className="hist-rail-name" title={ref.name}>
                      {ref.name}
                    </span>
                  </span>
                )}
              </span>
            </li>,
          ]
        })}
      </ol>
    </section>
  )
}
