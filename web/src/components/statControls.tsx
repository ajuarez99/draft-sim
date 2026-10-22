import { useMemo } from 'react'
import { Link, useLocation } from 'react-router-dom'
import PlayerLink from './PlayerLink'
import type { LeaderboardRow, PlayerWindowKind, StatLeaderboard as Board } from '../api'
import { DASH } from '../statCells'
import { hasKnownOwnership, hasMine, ownershipOf, type Availability, type Filters, type StatMode } from '../statLeaderboard'
import { reasonSentence, seasonLabel } from '../statCopy'

/**
 * The leaderboard page's controls, shared with the stat calculator (specs/032 R13). Moved out of
 * pages/StatLeaderboard.tsx unchanged except where noted: SeasonPicker's path suffix and query
 * handling (C2, F9) and PlayerCell taking the sport rather than the whole board (N10).
 */

export const WINDOWS: { kind: PlayerWindowKind; label: string }[] = [
  { kind: 'SEASON', label: 'Season' },
  { kind: 'LAST_10', label: 'Last 10' },
  { kind: 'LAST_5', label: 'Last 5' },
]
export const MODES: { kind: StatMode; label: string }[] = [
  { kind: 'perGame', label: 'Per game' },
  { kind: 'totals', label: 'Totals' },
  { kind: 'per36', label: 'Per 36' },
]

export function PlayerCell({ row, sport, leagueId }: { row: LeaderboardRow; sport: Board['sport']; leagueId: string }) {
  // A null name still gets a link and a label (N2): never a blank where a player should be.
  const name = row.name ?? 'Unknown player'
  return (
    <>
      <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={row.sleeperPlayerId} sport={sport}>
        {name}
      </PlayerLink>
      <span className="sl-sub muted small">
        {[row.positions.join('/'), row.team].filter(Boolean).join(' · ') || DASH}
      </span>
      {!row.qualified && row.reason && (
        <span className="sl-sub sl-unranked small">
          {reasonSentence(row.reason, { date: row.stats.lastGameDate })}
        </span>
      )}
    </>
  )
}

export function Segmented<T extends string>({
  label, value, options, onChange, describedBy,
}: {
  label: string
  value: T
  /** `disabled` (specs/032 US5, FR-019): kept in the picker but inert, with `aria-disabled`; the page shows the reason in words beside it. */
  options: { kind: T; label: string; disabled?: boolean }[]
  onChange: (k: T) => void
  /** Id of the element that gives the reasons for disabled options. */
  describedBy?: string
}) {
  return (
    <div className="segmented sm" role="group" aria-label={label}>
      {options.map((o) => (
        <button
          key={o.kind}
          type="button"
          className={`segment${o.kind === value ? ' on' : ''}`}
          aria-pressed={o.kind === value}
          aria-disabled={o.disabled ? true : undefined}
          aria-describedby={o.disabled ? describedBy : undefined}
          onClick={() => {
            if (!o.disabled) onChange(o.kind)
          }}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

const availKey = (a: Availability) => (a.kind === 'roster' ? `roster:${a.rosterId}` : a.kind)
const parseAvail = (k: string): Availability =>
  k.startsWith('roster:') ? { kind: 'roster', rosterId: Number(k.slice(7)) } : k === 'free' ? { kind: 'free' } : k === 'rostered' ? { kind: 'rostered' } : k === 'mine' ? { kind: 'mine' } : { kind: 'all' }

export function FilterBar({ rows, f, onChange, nowLabel }: { rows: LeaderboardRow[]; f: Filters; onChange: (f: Filters) => void; nowLabel: string | null }) {
  const known = useMemo(() => hasKnownOwnership(rows), [rows])
  const mine = useMemo(() => hasMine(rows), [rows])
  const positions = useMemo(() => [...new Set(rows.flatMap((r) => r.positions))].sort(), [rows])
  const teams = useMemo(() => [...new Set(rows.map((r) => r.team).filter((t): t is string => !!t))].sort(), [rows])
  const rosters = useMemo(() => {
    const m = new Map<number, string>()
    for (const r of rows) {
      const o = ownershipOf(r)
      if (o.state === 'ROSTERED' && o.rosterId != null) {
        m.set(o.rosterId, o.ownerName ?? `Roster ${o.rosterId}`)
      }
    }
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]))
  }, [rows])
  return (
    <div className="sl-filters">
      <label className="sl-field">
        <span className="muted small">Position</span>
        <select value={f.position ?? ''} onChange={(e) => onChange({ ...f, position: e.target.value || null })}>
          <option value="">All</option>
          {positions.map((p) => <option key={p} value={p}>{p}</option>)}
        </select>
      </label>
      <label className="sl-field">
        <span className="muted small">NBA team</span>
        <select value={f.team ?? ''} onChange={(e) => onChange({ ...f, team: e.target.value || null })}>
          <option value="">All</option>
          {teams.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
      </label>
      <label className="sl-field">
        <span className="muted small">Availability</span>
        <select
          value={availKey(f.availability)}
          onChange={(e) => onChange({ ...f, availability: parseAvail(e.target.value) })}
          disabled={!known}
          aria-describedby={known ? undefined : 'sl-avail-why'}
        >
          <option value="all">All players</option>
          <option value="free">{nowLabel ? `Free agents (${nowLabel})` : 'Free agents'}</option>
          <option value="rostered">{nowLabel ? `Rostered (${nowLabel})` : 'Rostered'}</option>
          {mine && <option value="mine">My roster</option>}
          {rosters.map(([id, name]) => <option key={id} value={`roster:${id}`}>{`${name}’s roster`}</option>)}
        </select>
      </label>
      {!known && (
        <span id="sl-avail-why" className="muted small">
          {nowLabel ? `Nobody is on a roster yet (${nowLabel}), so there is no ownership to filter by.` : 'Ownership isn’t known for this season, so there is nothing to filter by.'}
        </span>
      )}
    </div>
  )
}

/**
 * Season links. `suffix` picks the page ('' = the leaderboard, '/calculator'). `keepSearch`, when
 * given, carries the current query string to the other season through that function (the
 * calculator strips `own=r:` because roster ids repeat every season; specs/032 C2, F9). Without it
 * no query string is carried, which is the leaderboard's behaviour.
 */
export function SeasonPicker({
  d, leagueId, suffix = '', keepSearch,
}: {
  d: Board
  leagueId: string
  suffix?: '' | '/calculator'
  keepSearch?: (search: URLSearchParams) => URLSearchParams
}) {
  const { search } = useLocation()
  if (d.seasons.length === 0) return null
  const qs = keepSearch ? keepSearch(new URLSearchParams(search)).toString() : ''
  return (
    <nav className="pp-seasons" aria-label="Season">
      {d.seasons.map((o) => {
        const here = o.sleeperLeagueId === leagueId
        return (
          <Link
            key={o.sleeperLeagueId}
            to={`/leagues/${encodeURIComponent(o.sleeperLeagueId)}/stats${suffix}${qs ? `?${qs}` : ''}`}
            className={`pp-season${here ? ' on' : ''}${o.hasGames ? '' : ' empty'}`}
            aria-current={here ? 'page' : undefined}
          >
            {seasonLabel(o.season)}
            {!o.hasGames && <span className="pp-season-note">no games yet</span>}
          </Link>
        )
      })}
    </nav>
  )
}

/**
 * The "Leaderboard · Calculator" switch shown under both stats pages' headers (specs/032 F13). Two
 * links, not a second "Table" button: the leaderboard already has a "View: Table | Stat leaders" control.
 */
export function StatsPagesNav({ leagueId, current }: { leagueId: string; current: 'leaderboard' | 'calculator' }) {
  const base = `/leagues/${encodeURIComponent(leagueId)}/stats`
  return (
    <nav className="sc-pages" aria-label="Stats pages">
      <Link to={base} className={`sc-page${current === 'leaderboard' ? ' on' : ''}`} aria-current={current === 'leaderboard' ? 'page' : undefined}>
        Leaderboard
      </Link>
      <Link to={`${base}/calculator`} className={`sc-page${current === 'calculator' ? ' on' : ''}`} aria-current={current === 'calculator' ? 'page' : undefined}>
        Calculator
      </Link>
    </nav>
  )
}
