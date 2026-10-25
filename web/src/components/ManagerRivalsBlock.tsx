import { Link } from 'react-router-dom'
import Avatar from './Avatar'
import type { ChainRivals, RivalEntry } from '../api'
import { recordText } from '../rivalries'

/**
 * specs/047-hall-of-fame US3: a manager's nemesis (the opponent they have the worst record against) and victim (the
 * best), one sub-block per league chain inside that sport's career block. Chains are never merged: a manager can
 * have three football chains, and "your nemesis" in one says nothing about another.
 *
 * Only opponents met at least N times count (N is hand-set, and printed), a nemesis needs a LOSING record and a
 * victim a WINNING one, and every tied opponent is shown. A "former" tag marks an opponent who is no longer in the
 * chain's newest season. Each entry links to the pair's versus page.
 */

function Entry({ managerId, e, kind }: { managerId: number; e: RivalEntry; kind: 'nemesis' | 'victim' }) {
  const o = e.opponent
  const label = o.teamName?.trim() || o.name
  const inner = (
    <>
      <Avatar avatarId={o.avatarId} seed={String(o.managerId ?? `r${o.rosterId}`)} label={label} className="rv-avatar" />
      <span className="mrv-name">{label}</span>
      {e.former && <span className="mrv-former">former</span>}
      <span className="mrv-rec">{recordText(e.wins, e.losses, e.ties)}</span>
      <span className="mrv-met">{e.meetings} met</span>
    </>
  )
  return (
    <li className={`mrv-entry mrv-${kind}`}>
      {o.managerId == null ? (
        <span className="mrv-link">{inner}</span>
      ) : (
        <Link
          to={`/managers/${managerId}/versus/${o.managerId}`}
          className="mrv-link"
          aria-label={`${label}: ${recordText(e.wins, e.losses, e.ties)}, ${e.meetings} met`}>
          {inner}
        </Link>
      )}
    </li>
  )
}

function Side({ managerId, title, kind, entries, none }: {
  managerId: number
  title: string
  kind: 'nemesis' | 'victim'
  entries: RivalEntry[]
  none: string
}) {
  return (
    <div className={`mrv-side mrv-side-${kind}`}>
      <h5 className="mrv-title">{title}</h5>
      {entries.length === 0 ? (
        <p className="muted small mrv-none">{none}</p>
      ) : (
        <ul className="mrv-list">
          {entries.map((e) => <Entry key={e.opponent.managerId ?? `r${e.opponent.rosterId}-${e.opponent.name}`} managerId={managerId} e={e} kind={kind} />)}
        </ul>
      )}
    </div>
  )
}

function Chain({ managerId, c }: { managerId: number; c: ChainRivals }) {
  const n = c.minMeetings
  // The rivalry settings didn't load: there is no N to claim anything against, so say nothing.
  if (n == null) return null
  return (
    <div className="mrv-chain">
      <h4 className="mrv-chain-h">Rivals · {c.leagueName}</h4>
      {c.status === 'NONE_QUALIFY' ? (
        <p className="muted small">No opponent has met them {n} times yet ({n} is hand-set).</p>
      ) : (
        <div className="mrv-sides">
          <Side
            managerId={managerId}
            title="Nemesis"
            kind="nemesis"
            entries={c.nemesis}
            none={`No nemesis: nobody they have met ${n}+ times has a losing record against them.`}
          />
          <Side
            managerId={managerId}
            title="Victim"
            kind="victim"
            entries={c.victim}
            none={`No victim: nobody they have met ${n}+ times has a winning record against them.`}
          />
        </div>
      )}
    </div>
  )
}

/** The sub-blocks for one sport's career block. Renders nothing when there are no chains for it. */
export default function ManagerRivalsBlock({ managerId, chains }: { managerId: number; chains: ChainRivals[] }) {
  if (chains.length === 0) return null
  return (
    <div className="mrv-block">
      {chains.map((c) => <Chain key={c.headSleeperLeagueId} managerId={managerId} c={c} />)}
    </div>
  )
}
