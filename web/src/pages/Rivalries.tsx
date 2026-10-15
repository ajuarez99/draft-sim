import { Link, useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import NotFound from '../components/NotFound'
import Avatar from '../components/Avatar'
import {
  getRivalries,
  type Rivalries as RivalriesData,
  type RivalryCell,
  type RivalryPairing,
  type RivalryRow,
} from '../api'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { useBlock } from '../useBlock'
import { indexPairs, kindSplit, recordText, tallyText, viewFrom, winShareBucket, type PairView } from '../rivalries'

/**
 * specs/034-rivalry-schedule-grid: three panels on one page.
 *
 *   1. All-time head-to-head matrix (managers x managers)
 *   2. This season's schedule (managers x weeks, with the record going into each game)
 *   3. A suggested rivalry week (pairings, and why)
 *
 * Both grids are grids, so they are tables, each inside its own horizontal scroll container with a
 * sticky first column: the page itself never scrolls sideways at 375px. A matrix cell prints the exact
 * record; the tint only repeats the win share it already prints (one encoding per mark).
 */

const STATUS_TEXT: Record<string, string> = {
  CONFIG_MISSING: "Rivalry week suggestions aren't available because this app's rivalry settings didn't load.",
  TOO_FEW_MANAGERS: 'A rivalry week needs at least two managers.',
  TOO_MANY_MANAGERS: 'This league is larger than the rivalry week suggestion can pair up.',
  NO_HISTORY:
    'This league has no earlier season, so there is no history to pair rivals from yet. The schedule above is all there is so far.',
}

function nameOf(rows: Map<number, RivalryRow>, id: number | null): string {
  // A null opponent is a roster with no manager in the payload: an unowned seat, not someone who left (B6).
  if (id == null) return 'an unowned roster'
  return rows.get(id)?.teamName ?? `Manager ${id}`
}

function Who({ row }: { row: RivalryRow }) {
  return (
    <span className="rv-who">
      <Avatar avatarId={row.avatarId} seed={String(row.managerId)} label={row.teamName} isMe={row.isMe} className="rv-avatar" />
      <span className="rv-name">{row.teamName}</span>
    </span>
  )
}

export default function Rivalries() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const id = sleeperLeagueId ?? ''
  const version = useLeagueDataVersion(sleeperLeagueId)
  const block = useBlock(id ? () => getRivalries(id) : null, [id, version])

  if (block.status === 'error' && block.notFound) return <NotFound what="league" />
  const data = block.status === 'ok' ? block.data : null

  return (
    <div className="content">
      <PageHeader
        eyebrow={data ? `League · ${data.season}` : 'League'}
        title="Rivalries"
        sub="Who has beaten whom, who you play when, and which matchups would make a good rivalry week."
      />
      {block.status === 'loading' && <p className="muted small">Loading…</p>}
      {block.status === 'error' && <p className="muted small">Couldn't load the rivalries.</p>}
      {data && (
        <>
          <MatrixPanel data={data} />
          <SeasonPanel data={data} />
          <RivalryWeekPanel data={data} />
        </>
      )}
    </div>
  )
}

export function MatrixPanel({ data }: { data: RivalriesData }) {
  const { rows, pairs, splitNotes } = data.matrix
  const index = indexPairs(pairs)
  const covered = data.seasonsCovered
  return (
    <section className="section rv-section" aria-labelledby="rv-matrix-h">
      <h2 id="rv-matrix-h" className="rv-h">All-time head-to-head</h2>
      <p className="muted small">
        Each cell is the row manager's record against the column manager, then how many times they have met
        {covered.length > 1 ? `, across ${covered.length} seasons (${Math.min(...covered)}–${Math.max(...covered)})` : ''}.
        Open a cell for the full comparison; that page counts every league the two have shared.
      </p>
      <div className="rv-wrap">
        <table className="rv-table">
          <thead>
            <tr>
              <th scope="col" className="rv-first rv-corner">Row vs column</th>
              {rows.map((c) => (
                <th key={c.managerId} scope="col" className="rv-colhead"><Who row={c} /></th>
              ))}
              <th scope="col" className="rv-colhead rv-all">Vs everyone</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.managerId} className={r.isMe ? 'rv-me' : undefined}>
                <th scope="row" className="rv-first"><Who row={r} /></th>
                {rows.map((c) => {
                  if (c.managerId === r.managerId) {
                    return <td key={c.managerId} className="rv-cell rv-self" aria-hidden="true">–</td>
                  }
                  const v = viewFrom(index, r.managerId, c.managerId)
                  if (!v) return <td key={c.managerId} className="rv-cell rv-none">no history</td>
                  const href = `/managers/${r.managerId}/versus/${c.managerId}`
                  const disagreeing = v.pair.finals.find((f) => f.scoresAgreeWithWinner === false)
                  const note = disagreeing ? finalDisagreeText(disagreeing.season, disagreeing.winner, v, r, c) : ''
                  if (v.pair.status === 'MET') {
                    const bucket = winShareBucket(v)
                    return (
                      <td key={c.managerId} className={`rv-cell${bucket != null ? ` rv-w${bucket}` : ''}`}>
                        <Link to={href} className="rv-link" title={[kindSplit(v), note].filter(Boolean).join('. ') || undefined}>
                          <span className="rv-rec">{recordText(v.wins, v.losses, v.ties)}</span>
                          <span className="rv-met"> · {v.pair.meetings} met</span>
                          {note && <span className="rv-fine rv-disagree" aria-label={note}>final disputed</span>}
                        </Link>
                      </td>
                    )
                  }
                  const text =
                    v.pair.status === 'NEVER_MET' ? 'never met'
                    : v.pair.status === 'NOT_YET_MET' ? 'not yet — scheduled'
                    : 'no history'
                  return (
                    <td key={c.managerId} className="rv-cell rv-none">
                      <Link to={href} className="rv-link">{text}</Link>
                    </td>
                  )
                })}
                <td className="rv-cell rv-all">
                  <span className="rv-rec">{tallyText(r.allOpponents)}</span>
                  <span className="rv-met"> · {r.allOpponents.meetings} met</span>
                  {r.formerOpponentMeetings > 0 && (
                    <span className="rv-fine">
                      {r.formerOpponentMeetings} of those against managers no longer in the league
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="rv-legend small muted">
        Tint: the row manager's share of wins in that cell (greener means more wins). The record is always printed.
      </p>
      {splitNotes.map((n) => (
        <p key={n.season} className="muted small">{n.text}</p>
      ))}
    </section>
  )
}

/** B2: Sleeper's bracket gave the final to the side the stored scores say lost. */
function disagreeText(c: RivalryCell, rows: Map<number, RivalryRow>): string {
  const out = c.result?.outcome
  const winner =
    out === 'W' ? nameOf(rows, c.opponentManagerId)
    : out === 'L' ? (rows.get(c.managerId)?.teamName ?? `Manager ${c.managerId}`)
    : 'the other side'
  return `Sleeper's bracket gives this final to ${winner}; the stored scores say otherwise.`
}

function finalDisagreeText(season: number, winner: 'A' | 'B' | null, v: PairView, r: RivalryRow, c: RivalryRow): string {
  const rowWon = winner != null && (winner === 'A') === v.rowIsA
  const who = winner == null ? 'one side' : rowWon ? r.teamName : c.teamName
  return `${season}: Sleeper's bracket gives the final to ${who}; the stored scores say otherwise.`
}

function cellText(c: RivalryCell, rows: Map<number, RivalryRow>) {
  switch (c.state) {
    case 'BYE': return <span className="rv-state">BYE</span>
    case 'NO_GAME': return <span className="rv-state">no game</span>
    case 'TBD': return <span className="rv-state">TBD</span>
    default: break
  }
  const opp = nameOf(rows, c.opponentManagerId)
  return (
    <>
      <span className="rv-opp">{opp}</span>
      {c.priorRecord && (
        <span className="rv-fine">
          {c.priorRecord.meetings === 0 ? 'no earlier meetings' : `${tallyText(c.priorRecord)} before`}
        </span>
      )}
      {c.result && (
        <span
          title={c.result.bracketDisagrees ? disagreeText(c, rows) : undefined}
          className={`rv-result rv-${c.result.outcome}`}>
          {c.result.outcome} {c.result.points.toFixed(2)}–{c.result.opponentPoints.toFixed(2)}
        </span>
      )}
      {c.result?.bracketDisagrees && (
        <span className="rv-fine rv-disagree">{disagreeText(c, rows)}</span>
      )}
    </>
  )
}

export function SeasonPanel({ data }: { data: RivalriesData }) {
  const g = data.seasonGrid
  const rowsById = new Map(data.matrix.rows.map((r) => [r.managerId, r]))
  const cellMap = new Map(g.cells.map((c) => [`${c.managerId}:${c.week}`, c]))
  return (
    <section className="section rv-section" aria-labelledby="rv-season-h">
      <h2 id="rv-season-h" className="rv-h">{data.season} schedule</h2>
      {g.scheduleStatus === 'NOT_PUBLISHED' ? (
        <p className="muted small">Sleeper hasn't published this season's schedule yet, so there is no grid to show.</p>
      ) : (
        <>
          <p className="muted small">
            Each cell is the opponent and the record against them going in, then the result once it is played.
          </p>
          <div className="rv-wrap">
            <table className="rv-table rv-season">
              <thead>
                <tr>
                  <th scope="col" className="rv-first rv-corner">Manager</th>
                  {g.weeks.map((w) => (
                    <th
                      key={w.week}
                      scope="col"
                      className={`rv-weekhead${w.week === g.currentWeek ? ' rv-now' : ''}`}
                    >
                      <span className="rv-wk">Week {w.week}</span>
                      {w.week === g.currentWeek && <span className="rv-tag">this week</span>}
                      {w.isPlayoffWeek && <span className="rv-tag rv-tag-po">playoffs</span>}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.matrix.rows.map((r) => (
                  <tr key={r.managerId} className={r.isMe ? 'rv-me' : undefined}>
                    <th scope="row" className="rv-first"><Who row={r} /></th>
                    {g.weeks.map((w) => {
                      const c = cellMap.get(`${r.managerId}:${w.week}`)
                      return (
                        <td
                          key={w.week}
                          className={`rv-cell rv-sc${w.week === g.currentWeek ? ' rv-now' : ''}${w.isPlayoffWeek ? ' rv-po' : ''}`}
                        >
                          {c ? cellText(c, rowsById) : <span className="rv-state">–</span>}
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {g.tbdNote && <p className="muted small">{g.tbdNote}</p>}
          {g.playoffWindowNote && <p className="muted small">{g.playoffWindowNote}</p>}
        </>
      )}
      {g.medianNote && <p className="muted small">{g.medianNote}</p>}
      {g.rosterNote && <p className="muted small">{g.rosterNote}</p>}
    </section>
  )
}

export function RivalryWeekPanel({ data }: { data: RivalriesData }) {
  const rec = data.recommendation
  const rows = new Map(data.matrix.rows.map((r) => [r.managerId, r]))
  const minText =
    rec.minMeetings != null ? `at least ${rec.minMeetings} meetings (${rec.minMeetingsLabel ?? 'hand-set'})` : null
  return (
    <section className="section rv-section" aria-labelledby="rv-week-h">
      <h2 id="rv-week-h" className="rv-h">Rivalry week</h2>
      {rec.status !== 'OK' ? (
        <p className="muted small">{STATUS_TEXT[rec.status] ?? 'Rivalry week suggestions are not available.'}</p>
      ) : (
        <>
          {minText && (
            <p className="muted small">A pairing is marked thin history unless the two have met {minText}.</p>
          )}
          {rec.pairings.length > 0 && rec.thinCount * 2 > rec.pairings.length && (
            <p className="rv-headline">
              Most of these pairings ({rec.thinCount} of {rec.pairings.length}) rest on thin history, so treat the
              reasons as a starting point rather than a verdict.
            </p>
          )}
          <ul className="rv-pairs">
            {rec.pairings.map((p: RivalryPairing) => {
              const a = rows.get(p.aManagerId)
              const b = rows.get(p.bManagerId)
              return (
                <li key={`${p.aManagerId}:${p.bManagerId}`} className="rv-pair">
                  <span className="rv-pair-who">
                    {a ? <Who row={a} /> : `Manager ${p.aManagerId}`}
                    <span className="rv-vs">vs</span>
                    {b ? <Who row={b} /> : `Manager ${p.bManagerId}`}
                  </span>
                  <span className="rv-pair-why">{p.reason.text}</span>
                  <span className="rv-pair-meta">
                    <span className="rv-met">{p.meetings} {p.meetings === 1 ? 'meeting' : 'meetings'}</span>
                    {p.thin && <span className="rv-thin">thin history</span>}
                  </span>
                </li>
              )
            })}
          </ul>
          {rec.unpaired && (
            <p className="muted small">
              <strong>{nameOf(rows, rec.unpaired.managerId)}</strong> sits out. {rec.unpaired.why}
            </p>
          )}
        </>
      )}
      <p className="muted small">{rec.note}</p>
    </section>
  )
}
