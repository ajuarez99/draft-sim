import { Link } from 'react-router-dom'
import Avatar from './Avatar'
import type { AnalysisRosterProjection, ProjectionUnit, SeasonFavorite, SeasonStrength } from '../api'
import { projectionNotes, reasonText, unitCaption, unitLabel, unitShort } from '../projectionCopy'

/**
 * specs/030: the league home's answer to "who has the strongest team?" in the
 * weeks between the draft and the first scored game. It is the top of the same
 * ranking the Analysis page draws (the server computes it once), cut down to
 * three rows plus the reader's own.
 *
 * Wording rule (FR-007a): this is a projected ranking, not a forecast of who
 * wins anything. Every number sits beside "projected", and no copy here talks
 * about odds -- a test scans the rendered text for those words.
 */

const pts1 = (n: number) => n.toFixed(1)

/** "A", "A and B", "A, B and C". */
function joinNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? ''
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

function Headline({ favorite, unit }: { favorite: SeasonFavorite; unit: ProjectionUnit }) {
  const names = favorite.leaders.map((l) => l.teamName?.trim() || l.username || `Roster ${l.rosterId}`)
  // The totals carry the strength's own unit (football: rest-of-season points); only the margin is per week.
  const u = unitLabel(unit)
  const next = favorite.nextTotal != null ? ` Next-best roster: ${pts1(favorite.nextTotal)} ${u}.` : ''

  if (favorite.tie) {
    return (
      <>
        <p className="lh-headline">
          Projected dead heat: {joinNames(names)}, within {favorite.minGapPct}%
        </p>
        <p className="muted small">
          {pts1(favorite.leaderTotal)} {u} at the top.{next}
        </p>
      </>
    )
  }
  return (
    <>
      <p className="lh-headline">
        Projected strongest roster: {names[0]}
        {favorite.marginPoints != null && favorite.marginPct != null && (
          <>
            , by {pts1(favorite.marginPoints)} pts/week ({pts1(favorite.marginPct)}%)
          </>
        )}
      </p>
      <p className="muted small">
        {pts1(favorite.leaderTotal)} {u}.{next}
      </p>
    </>
  )
}

function StrengthRow({
  roster,
  label,
  max,
  unit,
  detached,
}: {
  roster: AnalysisRosterProjection
  label: string
  max: number
  unit: string
  /** The reader's own row below the top three: marked with a gap, not just a tint. */
  detached?: boolean
}) {
  return (
    <li className={`lh-str-row${roster.isMe ? ' mine' : ''}${roster.rank === 1 ? ' lead' : ''}${detached ? ' detached' : ''}`}>
      <span className="lh-pos">{roster.rank}</span>
      <Avatar avatarId={roster.avatarId} seed={String(roster.managerId ?? roster.rosterId)} label={label} isMe={roster.isMe} />
      <div className="lh-str-main">
        <div className="lh-str-line">
          <span className="lh-name" title={label}>
            {label}
          </span>
          {roster.isMe && <span className="lh-yours">you</span>}
          <span className="lh-str-val">
            <span className="mono lh-str-num">{pts1(roster.total)}</span>
            <span className="muted small"> {unit}</span>
          </span>
        </div>
        {/* The bar is proportion from zero; the exact value is printed beside it. */}
        <div className="lh-str-track" aria-hidden="true">
          <span className="lh-str-fill" style={{ width: `${max > 0 ? Math.max(2, (roster.total / max) * 100) : 0}%` }} />
        </div>
      </div>
    </li>
  )
}

export default function PreseasonStrengthBlock({
  strength,
  leagueId,
  now,
}: {
  strength: SeasonStrength
  leagueId: string
  /** Only for tests: the clock the "updated N days ago" line is read against. */
  now?: Date
}) {
  if (!strength.available) {
    return (
      <section className="section lh-strength" aria-labelledby="lh-strength-h">
        <h2 className="section-title" id="lh-strength-h">
          Projected strength
        </h2>
        <p className="muted small" role="status">
          {reasonText(strength.reasonCode, strength.reason, strength.unit) ?? 'Projections are not available yet.'}
        </p>
      </section>
    )
  }

  // Every row is labelled the way the other pages label a roster: team name, else username, else "Roster N".
  const teamNames = new Map(strength.teamLabels.map((l) => [l.rosterId, l.teamName?.trim() || null]))
  const labelOf = (r: AnalysisRosterProjection) => teamNames.get(r.rosterId) || r.manager || `Roster ${r.rosterId}`

  const rows = strength.top
  const unit = unitShort(strength.unit)
  const max = Math.max(...rows.map((r) => r.total), strength.me?.total ?? 0)

  return (
    <section className="section lh-strength" aria-labelledby="lh-strength-h">
      <h2 className="section-title" id="lh-strength-h">
        Projected strength
      </h2>
      {strength.favorite && <Headline favorite={strength.favorite} unit={strength.unit} />}
      {/* "Projected" is said once here; every number below is in this unit. */}
      <p className="lh-str-caption muted small" id="lh-strength-cap">
        {unitCaption(strength.unit)}
      </p>
      <ol className="row-list lh-rows lh-str-rows" aria-describedby="lh-strength-cap">
        {rows.map((r) => (
          <StrengthRow key={r.rosterId} roster={r} label={labelOf(r)} max={max} unit={unit} />
        ))}
        {strength.me && (
          <StrengthRow key={`me-${strength.me.rosterId}`} roster={strength.me} label={labelOf(strength.me)} max={max} unit={unit} detached />
        )}
      </ol>
      {strength.me && (
        <p className="muted small">
          You rank {strength.me.rank} of {strength.teams} on projected strength.
        </p>
      )}
      <Link className="lh-link" to={`/leagues/${leagueId}/analysis`}>
        Team strength
      </Link>
      <div className="lh-str-notes muted small">
        {projectionNotes(strength, now).map((n) => (
          <p key={n}>{n}</p>
        ))}
        {strength.favorite &&
          !strength.favorite.tie &&
          strength.favorite.marginPct != null &&
          strength.favorite.marginPct < 2 * strength.favorite.minGapPct && (
            <p>A gap this size is within what these projections can't resolve.</p>
          )}
      </div>
    </section>
  )
}
