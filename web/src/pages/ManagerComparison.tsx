import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  getManagerComparison,
  type ManagerComparison as ManagerComparisonData,
  type VersusComparison,
  type VersusFigure,
  type VersusMeeting,
  type VersusSeasonExcluded,
  type VersusSport,
} from '../api'
import Avatar from '../components/Avatar'
import PageHeader from '../components/PageHeader'

/**
 * specs/006-deeper-history-both-sports US4: two managers, side by side -- the
 * head-to-head record ffwrapped leaves at a permanent 0-0, plus the same
 * career figures `/managers/:id/history` shows, read from the same service so
 * the two pages can never disagree about one manager's own numbers
 * (ManagerComparisonController's own comment).
 *
 * <p>Grouped per sport throughout, same rule as ManagerHistory's own career
 * panel (FR-002/US4.2): two managers who share both a football chain and a
 * basketball one get two blocks with two separate records, never one
 * combined header.
 */
export default function ManagerComparison() {
  const { aId, bId } = useParams<{ aId: string; bId: string }>()
  const [data, setData] = useState<ManagerComparisonData | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!aId || !bId) return
    setData(null)
    setError(null)
    getManagerComparison(Number(aId), Number(bId))
      .then(setData)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [aId, bId])

  if (error) {
    return (
      <div className="content">
        <section className="panel">
          <div className="error">{error}</div>
        </section>
      </div>
    )
  }

  if (!data) {
    return (
      <div className="content">
        <section className="panel">
          <p className="muted small">Loading comparison…</p>
        </section>
      </div>
    )
  }

  const aName = data.a.manager ?? `manager ${data.a.managerId}`
  const bName = data.b.manager ?? `manager ${data.b.managerId}`

  return (
    <div className="content">
      <PageHeader
        eyebrow="Comparison"
        title={
          <span className="versus-title">
            <span className="page-title-avatar">
              <Avatar avatarId={data.a.avatarId} seed={String(data.a.managerId)} label={aName} /> {aName}
            </span>
            <span className="versus-vs">vs</span>
            <span className="page-title-avatar">
              <Avatar avatarId={data.b.avatarId} seed={String(data.b.managerId)} label={bName} /> {bName}
            </span>
          </span>
        }
        sub="The head-to-head record and the same career figures each manager's own history page shows, side by side."
      />

      {/* US4.3: never a bare 0-0. Two managers who have never shared a league
          get an explicit reason instead of an empty panel that looks broken. */}
      {data.sharedNothing ? (
        <section className="panel">
          <p className="muted">
            {aName} and {bName} have never shared a league season in either sport -- there is nothing to compare.
          </p>
        </section>
      ) : (
        data.sports.map((s) => (
          <SportComparison key={s.sport} sport={s} aName={aName} bName={bName} />
        ))
      )}
    </div>
  )
}

function SportComparison({ sport, aName, bName }: { sport: VersusSport; aName: string; bName: string }) {
  const games = sport.aWins + sport.bWins + sport.ties
  return (
    <section className="panel">
      <div className="panel-head">
        <span className={`sport-pill ${sport.sport}`}>{sport.sport.toUpperCase()}</span>
        <span className="versus-record mono">
          {sport.aWins}-{sport.bWins}
          {sport.ties > 0 ? `-${sport.ties}` : ''}
        </span>
      </div>

      {games === 0 ? (
        <p className="muted small">
          {aName} and {bName} shared this sport, but the two have never actually been scheduled against each
          other with a scored week -- see the seasons below.
        </p>
      ) : (
        <p className="muted small">
          Head to head across {games} played game{games === 1 ? '' : 's'}.
        </p>
      )}

      <ComparisonTable comparison={sport.comparison} aName={aName} bName={bName} />
      <MeetingsList meetings={sport.meetings} aName={aName} bName={bName} />
      <SeasonsExcludedList items={sport.seasonsExcluded} />
    </section>
  )
}

/**
 * The career comparison -- each row one `CareerProfile` figure, both sides at
 * once. Reuses `.standings` rather than inventing a table style: this is
 * exactly that shape, a grid of rows with a label column and value columns,
 * not `margin-card`'s one-headline-plus-context shape.
 */
function ComparisonTable({
  comparison: c,
  aName,
  bName,
}: {
  comparison: VersusComparison
  aName: string
  bName: string
}) {
  return (
    <div className="table-wrap">
      <table className="standings versus-table">
        <thead>
          <tr>
            <th></th>
            <th className="mono" title={aName}>
              {aName}
            </th>
            <th className="mono" title={bName}>
              {bName}
            </th>
          </tr>
        </thead>
        <tbody>
          <ComparisonRow label="Titles" figure={c.titles} format={(v) => String(v)} />
          <ComparisonRow label="Record" figure={c.record} format={(v) => v} />
          <ComparisonRow label="Points for" figure={c.pointsFor} format={(v) => v.toFixed(2)} />
          <ComparisonRow label="Points per game" figure={c.pointsPerGame} format={(v) => v.toFixed(2)} />
          <ComparisonRow
            label="Wins above expected"
            figure={c.winsAboveExpected}
            format={(v) => `${v > 0 ? '+' : ''}${v.toFixed(2)}`}
          />
          <ComparisonRow
            label="Average efficiency"
            figure={c.averageEfficiency}
            format={(v) => `${(v * 100).toFixed(1)}%`}
          />
        </tbody>
      </table>
      {/* FR-007/SC-008: the divisor behind every average above, stated once for
          both sides rather than per-row -- each side's own count, since two
          managers rarely have the same number of counted seasons. */}
      <p className="muted tiny career-stat-seasons">
        {aName}: {c.seasonsCounted.a ?? 0} season{c.seasonsCounted.a === 1 ? '' : 's'} counted · {bName}:{' '}
        {c.seasonsCounted.b ?? 0} season{c.seasonsCounted.b === 1 ? '' : 's'} counted
      </p>
    </div>
  )
}

function ComparisonRow<T>({
  label,
  figure,
  format,
}: {
  label: string
  figure: VersusFigure<T>
  format: (v: T) => string
}) {
  return (
    <tr>
      <td>{label}</td>
      <td className="mono">{figure.a == null ? '—' : format(figure.a)}</td>
      <td className="mono">{figure.b == null ? '—' : format(figure.b)}</td>
    </tr>
  )
}

/**
 * Every scored meeting, reusing `margin-card`'s winner/loser shape
 * (LeagueHistory's own `MarginGroup`) rather than inventing a second card --
 * a head-to-head meeting is structurally the same thing a league's own margin
 * record is: two sides, two scores, one winner.
 */
function MeetingsList({ meetings, aName, bName }: { meetings: VersusMeeting[]; aName: string; bName: string }) {
  if (meetings.length === 0) return null
  return (
    <div className="records-col versus-meetings">
      <h4>Meetings</h4>
      <div className="margin-cards">
        {meetings.map((m) => (
          <div className="margin-card" key={`${m.season}-${m.week}`}>
            <div className="margin-card-head">
              <span>
                <span className="margin-value">{Math.abs(m.aPoints - m.bPoints).toFixed(2)}</span>
                <span className="margin-label"> Margin</span>
              </span>
              <span className="margin-when">
                {m.season} · Wk {m.week} · {m.leagueName}
              </span>
            </div>
            <div className={`margin-side ${m.winner === 'A' ? 'won' : m.winner === 'TIE' ? '' : 'lost'}`}>
              <span>{aName}</span>
              <span className="margin-side-pts">{m.aPoints.toFixed(2)}</span>
            </div>
            <div className={`margin-side ${m.winner === 'B' ? 'won' : m.winner === 'TIE' ? '' : 'lost'}`}>
              <span>{bName}</span>
              <span className="margin-side-pts">{m.bPoints.toFixed(2)}</span>
            </div>
            {m.winner === 'TIE' && <p className="muted tiny">Tied -- counted as neither a win nor a loss.</p>}
          </div>
        ))}
      </div>
    </div>
  )
}

/**
 * US4.4: a shared season that produced no meeting between these two managers
 * is named, with its own reason, rather than left silently absent. Reuses
 * `career-unavailable`'s stated-reason list style (ManagerHistory's own
 * `UnavailableList`) rather than a second empty-state convention.
 */
function SeasonsExcludedList({ items }: { items: VersusSeasonExcluded[] }) {
  if (items.length === 0) return null
  return (
    <ul className="career-unavailable versus-excluded">
      {items.map((e) => (
        <li key={`${e.season}-${e.leagueName}`} className="muted tiny">
          {e.season} ({e.leagueName}): {e.reason}
        </li>
      ))}
    </ul>
  )
}
