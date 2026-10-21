import Avatar from './Avatar'
import type { PlayerOwnership, TrendRow } from '../api'
import { reasonSentence, statusAsOfLabel } from '../statCopy'

/**
 * specs/042 US1: the one ownership mark -- who has this player -- shared by the stat leaderboard, the
 * trends tables and the player page, which each drew their own before.
 *
 * Text always accompanies colour (FR-002): the avatar and the crimson ring are never the only signal,
 * "Yours" is spelled out. The as-of text rides in the `title`, read from the ownership's own `asOf`.
 * The state itself is `PlayerOwnership`'s -- the server's one rule -- and this holds no ownership rule.
 */
export default function OwnershipMark({
  o,
  verbose = false,
}: {
  o: PlayerOwnership
  /** The player page prints the not-drafted / unavailable reason; tables keep it short with a tooltip. */
  verbose?: boolean
}) {
  const title = asOfTitle(o)
  switch (o.state) {
    case 'ROSTERED':
      return (
        <span className="pp-owner" title={title}>
          <Avatar avatarId={o.avatarId} seed={o.ownerName ?? String(o.rosterId ?? '')} label={o.ownerName} isMe={o.isMe} />
          <span className="pp-owner-name">{o.ownerName ?? 'A team in this league'}</span>
          {o.isMe && <span className="lh-yours">Yours</span>}
        </span>
      )
    case 'FREE_AGENT':
      return <span className="lh-fa" title={title}>Free agent</span>
    case 'NOT_DRAFTED':
      return verbose
        ? <span className="muted small">{reasonSentence('NOT_DRAFTED')}</span>
        : <span className="muted small" title={reasonSentence('NOT_DRAFTED')}>not drafted</span>
    case 'UNAVAILABLE':
      return verbose
        ? <span className="muted small">{reasonSentence('UNAVAILABLE')}</span>
        : <span className="muted small" title={reasonSentence('UNAVAILABLE')}>–</span>
  }
}

/** "Rosters as of Oct 20, 11:02 AM EDT" / "Rosters at the end of week 18"; undefined when the server has none. */
function asOfTitle(o: PlayerOwnership): string | undefined {
  const a = o.asOf
  if (!a) return undefined
  if (a.kind === 'WEEK') return `Rosters at the end of week ${a.week}`
  const when = statusAsOfLabel(a.fetchedAt)
  return when ? `Rosters as of ${when}` : undefined
}

/**
 * The Trends rows carry ownership as a trio (`rostered`, `rosteredBy`, `rosteredByMe`) rather than a
 * `PlayerOwnership`; this normalises it. `rostered == null` is "unknown", not a free agent. Trends has
 * no avatar, so the mark falls back to the initial.
 */
export function ownershipFromTrend(r: Pick<TrendRow, 'rostered' | 'rosteredBy' | 'rosteredByMe'>): PlayerOwnership {
  if (r.rostered == null) {
    return { state: 'UNAVAILABLE', rosterId: null, ownerName: null, avatarId: null, isMe: false, asOf: null }
  }
  if (!r.rostered) {
    return { state: 'FREE_AGENT', rosterId: null, ownerName: null, avatarId: null, isMe: false, asOf: null }
  }
  return { state: 'ROSTERED', rosterId: null, ownerName: r.rosteredBy, avatarId: null, isMe: r.rosteredByMe === true, asOf: null }
}
