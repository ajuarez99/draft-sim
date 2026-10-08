import { createContext, useCallback, useContext } from 'react'
import { Link } from 'react-router-dom'
import Avatar from '../../components/Avatar'
import PersonName from '../../components/PersonName'
import { useLeagueLinkState } from '../../railLeague'
import { type AnalysisTeamLabel } from '../../api'
import { extremes } from '../../extremes'

export const SCORING_LABEL: Record<string, string> = {
  PPR: 'full PPR',
  HALF_PPR: 'half PPR',
  STANDARD: 'standard',
}

/**
 * Every roster's team name and Sleeper username, keyed by roster id. The
 * payload carries them once (`teams`) rather than on each of the block shapes
 * that name a manager, so every block on the page can put the team name first.
 */
export const TeamLabelsContext = createContext<ReadonlyMap<number, AnalysisTeamLabel>>(new Map())

/** The team name for a roster, falling back to the username, then "roster N". */
export function useTeamName() {
  const labels = useContext(TeamLabelsContext)
  return useCallback(
    (manager: string | null, rosterId: number) =>
      labels.get(rosterId)?.teamName ?? manager ?? `roster ${rosterId}`,
    [labels],
  )
}

export const pts = (n: number) => n.toFixed(1)

/**
 * Sleeper's status words are long enough to swamp a name in a lineup row
 * ("Brock Bowers LV QUESTIONABLE"), so the two that appear constantly get the
 * codes every fantasy site uses and the rest are shown as they come. The full
 * word rides in the title, so shortening it never costs the reader the fact.
 */
export const INJURY_CODE: Record<string, string> = { Questionable: 'Q', Doubtful: 'D' }

export function InjuryTag({ status }: { status: string }) {
  return (
    <span className="analysis-inj" title={status}>
      {INJURY_CODE[status] ?? status}
    </span>
  )
}

/**
 * A block that has nothing to show yet says why, in the same shape whichever
 * block it is. Never an empty table with headers over nothing -- that reads as
 * a broken page rather than an early one.
 */
export function NotYet({ reason }: { reason: string | null }) {
  return (
    <p className="muted small analysis-notyet" role="status">
      {reason ?? 'Nothing to show yet.'}
    </p>
  )
}

export function ManagerLink({
  managerId,
  manager,
  rosterId,
  avatarId,
  isMe,
}: {
  managerId: number | null
  manager: string | null
  rosterId: number
  avatarId: string | null
  isMe?: boolean
}) {
  const teamName = useTeamName()
  const username = useContext(TeamLabelsContext).get(rosterId)?.username ?? manager
  const name = teamName(manager, rosterId)
  // Carries this league to the manager's own page -- see RecordWho in
  // LeagueHistory, which does the same for the same reason.
  const linkState = useLeagueLinkState()
  if (managerId == null) return <span className="muted">{name}</span>
  return (
    <Link to={`/managers/${managerId}/history`} state={linkState} className="standings-manager">
      <Avatar avatarId={avatarId} seed={String(managerId)} label={name} isMe={isMe} />
      <PersonName teamName={name} username={username} />
    </Link>
  )
}

/**
 * The ranking score's formula in words, with ffwrapped's own string kept one
 * click away. The verbatim text is provenance (the formula is theirs, kept
 * as published on purpose -- LeagueAnalysisService), not something to read
 * cold: it names code variables. The plain sentence below restates it and must
 * change with it.
 */
export function FormulaNote({ formula }: { formula: string }) {
  return (
    <div className="analysis-formula">
      <p>
        Score = average week × 6, plus (best week + worst week) × 2, plus win % × 400, all ÷ 10.
      </p>
      <details>
        <summary>ffwrapped&apos;s formula, as published</summary>
        <code className="code">{formula}</code>
      </details>
    </div>
  )
}

/** Every row tied at the column's best (or worst) value is marked; nothing when there is no spread. */
export function heatKind(value: number, ex: ReturnType<typeof extremes>): 'best' | 'worst' | null {
  if (ex == null) return null
  return value === ex.best ? 'best' : value === ex.worst ? 'worst' : null
}

export function HeatCell({ value, kind }: { value: number; kind: 'best' | 'worst' | null }) {
  if (kind == null) return <>{pts(value)}</>
  return (
    <span className={`analysis-heat ${kind}`} title={kind === 'best' ? 'Highest in the league' : 'Lowest in the league'}>
      {kind === 'best' ? '▲' : '▼'} {pts(value)}
    </span>
  )
}
