import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { fetchLockIns, type LiveCall, type LockInReport, type ProvableLock, type WeeklyReport } from '../api'
import { CallRow, signedPct } from './LockInPanel'

/*
 * spec 045 US3/US4: lock-ins under each side of a Matchups card, NBA only.
 *
 * A separate read from the weekly report (plan review A16): the scores render first and a slow or failed lock-ins call
 * only ever takes this block away. An in-progress week shows the live Lock/Wait calls, a final week the locks that
 * were provable in it. One caption per area, and every margin names the baseline it was measured against.
 */

/** Statuses where lock-ins don't apply to this league at all: nothing is drawn. */
const HIDDEN = new Set(['NOT_NBA', 'NOT_SLEEPER', 'GAME_MODE'])

export const LOCK_IN_CAPTION = 'Locks are inferred from credited points; bars are learned from past weeks.'

type State =
  | { kind: 'off' }
  | { kind: 'loading'; mode: 'live' | 'past' }
  | { kind: 'failed' }
  | { kind: 'ready'; mode: 'live' | 'past'; report: LockInReport; week: number }

const Ctx = createContext<State>({ kind: 'off' })

export function MatchupLockInsProvider({ data, children }: { data: WeeklyReport | null; children: ReactNode }) {
  const [state, setState] = useState<State>({ kind: 'off' })

  useEffect(() => {
    if (!data || !data.available || data.sport !== 'nba' || !data.sleeperLeagueId) {
      setState({ kind: 'off' })
      return
    }
    const mode = data.weekFinal ? 'past' : 'live'
    let alive = true
    setState({ kind: 'loading', mode })
    fetchLockIns(data.sleeperLeagueId, mode === 'past' ? { week: data.week } : undefined)
      .then((report) => {
        if (alive) setState(HIDDEN.has(report.status) ? { kind: 'off' } : { kind: 'ready', mode, report, week: data.week })
      })
      .catch(() => {
        // /lock-ins answers 404, 400 or 200 only (an ESPN league is a 200 with NOT_SLEEPER, which HIDDEN
        // covers), so any error here means it could not be loaded.
        if (alive) setState({ kind: 'failed' })
      })
    return () => {
      alive = false
    }
  }, [data])

  return <Ctx.Provider value={state}>{children}</Ctx.Provider>
}

const LIVE_WORDS: Record<string, string> = {
  NO_HISTORY: "No completed lock-in season in this league's history to learn from, so no calls are made.",
  ROSTERS_FAILED: "The current lineups couldn't be read from Sleeper just now, so there are no calls.",
  NOT_LIVE: 'This league is not being played right now, so there are no live calls.',
}

/** Once per Matchups area: a loading / why-there-is-nothing line, or the one caption. */
export function LockInsNote() {
  const s = useContext(Ctx)
  if (s.kind === 'off') return null
  let line: string | null = null
  if (s.kind === 'loading') line = 'Loading lock-ins…'
  else if (s.kind === 'failed') line = "Lock-ins couldn't be loaded just now."
  else if (s.mode === 'live') {
    const live = s.report.live
    if (!live) line = s.report.reason ?? 'Lock-ins are not available for this league yet.'
    else if (live.status !== 'OK') line = LIVE_WORDS[live.status] ?? `Live calls are unavailable (${live.status}).`
    else if (live.week !== s.week) line = `Lock-in calls are for week ${live.week ?? '?'}, not this week (${s.week}), so none are shown here.`
  } else if (s.report.weekCovered === false) {
    line = s.report.reason ?? "Lock-ins can't be told for this week."
  }
  return (
    <div className="wr-lockins-note muted small" data-testid="lockins-note">
      {line && <p>{line}</p>}
      {s.kind === 'ready' && !line && <p>{LOCK_IN_CAPTION}</p>}
    </div>
  )
}

/** The lock-in content for one roster's side of a card. */
export function SideLockIns({ rosterId }: { rosterId: number }) {
  const s = useContext(Ctx)
  if (s.kind !== 'ready') return null
  if (s.mode === 'live') {
    const live = s.report.live
    if (!live || live.status !== 'OK' || live.week !== s.week) return null
    const mine: LiveCall[] = live.calls.filter((c) => c.rosterId === rosterId)
    if (mine.length === 0) return null
    return (
      <ul className="wr-lockins lockin-calls" aria-label="Live lock-in calls">
        {mine.map((c) => (
          <CallRow key={c.playerId} c={c} showManager={false} />
        ))}
      </ul>
    )
  }
  if (s.report.weekCovered === false) return <p className="wr-lockin-past muted small">can't tell for this week</p>
  const mine: ProvableLock[] = s.report.locks.filter((l) => l.rosterId === rosterId)
  if (mine.length === 0) return <p className="wr-lockin-past muted small">no locks</p>
  return (
    <ul className="wr-lockins" aria-label="Locks this week">
      {mine.map((l) => (
        <li key={l.playerId} className="wr-lockin-past">
          <PastLock l={l} />
        </li>
      ))}
    </ul>
  )
}

/** "Mitchell +54% stand-in (2 later games)": the projection margin when there is one, else the stand-in's; never pooled. */
function PastLock({ l }: { l: ProvableLock }) {
  const name = l.player ?? `Player ${l.playerId}`
  const proj = l.projection?.margin ?? null
  const margin = proj ?? l.standIn.margin
  const kind = proj != null ? 'projection' : 'stand-in'
  return (
    <>
      <strong>{name}</strong>
      {margin != null ? (
        <span className="mono">
          {signedPct(margin)} <span className="wr-lockin-kind">{kind}</span>
        </span>
      ) : (
        <span className="muted">no margin ({kind})</span>
      )}
      <span className="muted">
        ({l.laterGames === 0 ? 'no later games' : `${l.laterGames} later ${l.laterGames === 1 ? 'game' : 'games'}`})
      </span>
    </>
  )
}
