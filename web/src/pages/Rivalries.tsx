import { Link, useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import NotFound from '../components/NotFound'
import Avatar from '../components/Avatar'
import {
  getRivalries,
  type DiscoveredPair,
  type Rivalries as RivalriesData,
  type RivalryCell,
  type RivalryDisplaced,
  type RivalryHeldBack,
  type RivalryPairing,
  type RivalryRow,
  type RivalryWeekOption,
  type RivalryWeekRecommendation,
} from '../api'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { useBlock } from '../useBlock'
import { indexPairs, kindSplit, recordText, tallyText, viewFrom, winShareBucket } from '../rivalries'
import { finalDisagreeText } from '../rivalryText'

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
          <NotableRivalries data={data} />
          <MatrixPanel data={data} />
          <SeasonPanel data={data} />
          <RivalryWeekPanel data={data} />
        </>
      )}
    </div>
  )
}

/**
 * specs/047-hall-of-fame US2: the notable rivalries, already picked out above the grid. Four short lists, because
 * the content is four different questions with a few answers each, not a table. Every entry prints its record (the
 * left name's wins first), how many times they met, and links to the versus page. A list that has nothing says
 * why in words, naming N and labelling it hand-set; more than three tied entries collapse to a count with an
 * expander so every entry stays reachable.
 */
const TIE_LIMIT = 3

type DiscoveryKind = 'mostPlayed' | 'closest' | 'lopsided' | 'playoff'

const levelRecord = (p: DiscoveredPair) =>
  recordText(Math.max(p.aWins, p.bWins), Math.min(p.aWins, p.bWins), p.ties)

/** "16 pairs are level at 2-2": one record when every tied pair shares it, otherwise just the count. */
function tieSummary(list: DiscoveredPair[], kind: DiscoveryKind): string {
  const n = list.length
  if (kind === 'mostPlayed') return `${n} pairs have met ${list[0].meetings} times`
  if (kind === 'playoff') return `${n} pairs have met in the playoffs`
  const records = new Set(list.map(levelRecord))
  if (records.size !== 1) return `${n} pairs are tied`
  const gap = Math.abs(list[0].aWins - list[0].bWins)
  return `${n} pairs are ${gap === 0 ? 'level at' : 'tied at'} ${[...records][0]}`
}

function DiscoveredRow({ p, rows, kind }: { p: DiscoveredPair; rows: Map<number, RivalryRow>; kind: DiscoveryKind }) {
  const a = rows.get(p.aManagerId)
  const b = rows.get(p.bManagerId)
  const nameA = a?.teamName ?? `Manager ${p.aManagerId}`
  const nameB = b?.teamName ?? `Manager ${p.bManagerId}`
  return (
    <li className="rvd-row">
      <Link
        to={`/managers/${p.aManagerId}/versus/${p.bManagerId}`}
        className="rvd-link"
        aria-label={`${nameA} versus ${nameB}: ${recordText(p.aWins, p.bWins, p.ties)}, ${p.meetings} met`}>
        <span className="rvd-side">
          <Avatar avatarId={a?.avatarId ?? null} seed={String(p.aManagerId)} label={nameA} isMe={a?.isMe} className="rv-avatar" />
          <span className="rv-name">{nameA}</span>
        </span>
        <span className="rvd-rec">
          <span className="rvd-score">{recordText(p.aWins, p.bWins, p.ties)}</span>
          <span className="rvd-met">{p.meetings} met</span>
          {kind === 'playoff' && (
            <span className="rvd-po">{p.playoffMatches} playoff {p.playoffMatches === 1 ? 'match' : 'matches'}</span>
          )}
        </span>
        <span className="rvd-side rvd-side-b">
          <span className="rv-name">{nameB}</span>
          <Avatar avatarId={b?.avatarId ?? null} seed={String(p.bManagerId)} label={nameB} isMe={b?.isMe} className="rv-avatar" />
        </span>
      </Link>
    </li>
  )
}

function DiscoveryList({ title, kind, list, empty, rows, note }: {
  title: string
  kind: DiscoveryKind
  list: DiscoveredPair[]
  empty: string
  rows: Map<number, RivalryRow>
  note?: string | null
}) {
  const entries = list.map((p) => <DiscoveredRow key={`${p.aManagerId}-${p.bManagerId}`} p={p} rows={rows} kind={kind} />)
  return (
    <div className={`rvd-card rvd-${kind}`}>
      <h3 className="rvd-title">{title}</h3>
      {list.length === 0 ? (
        <p className="muted small rvd-empty">{empty}</p>
      ) : list.length > TIE_LIMIT ? (
        <details className="rvd-more">
          <summary>{tieSummary(list, kind)}</summary>
          <ul className="rvd-list">{entries}</ul>
        </details>
      ) : (
        <ul className="rvd-list">{entries}</ul>
      )}
      {note && <p className="muted small rvd-note">{note}</p>}
    </div>
  )
}

export function NotableRivalries({ data }: { data: RivalriesData }) {
  const d = data.discovery
  // An older backend omits `discovery`, and an unloaded rivalry config sends no N: either way there is nothing to claim.
  if (!d || d.minMeetings == null) return null
  const rows = new Map(data.matrix.rows.map((r) => [r.managerId, r]))
  const n = d.minMeetings
  const unmet = `No pair has met ${n} times yet (${n} is hand-set).`
  // The true reason per season is already on the payload (the matrix's split notes, the same text the grid prints).
  // Never name a cause here that the server didn't send: a season can be unsplit for five different reasons.
  const unsplit =
    d.playoffUnsplitSeasons.length > 0
      ? d.playoffUnsplitSeasons
          .map((season) => {
            const note = data.matrix.splitNotes.find((x) => x.season === season)
            return note ? note.text : `The ${season} playoff weeks couldn't be split into playoff and consolation games.`
          })
          .join(' ')
      : null
  return (
    <section className="section rv-section" aria-labelledby="rv-notable-h">
      <h2 id="rv-notable-h" className="rv-h">Notable rivalries</h2>
      <p className="muted small">
        Picked out of the grid below. Most played, closest and most one-sided only count pairs that have met at least {n} times
        ({n} is a hand-set threshold, not a measured one). The left name's wins are printed first.
      </p>
      <div className="rvd-grid">
        <DiscoveryList title="Most played" kind="mostPlayed" list={d.mostPlayed} empty={unmet} rows={rows} />
        <DiscoveryList title="Closest" kind="closest" list={d.closest} empty={unmet} rows={rows} />
        <DiscoveryList
          title="Most one-sided"
          kind="lopsided"
          list={d.lopsided}
          empty={d.closest.length === 0 ? unmet : 'No pair is one-sided yet.'}
          rows={rows}
        />
        <DiscoveryList
          title="Playoff meetings"
          kind="playoff"
          list={d.playoff}
          empty={unsplit ?? 'No playoff meetings yet.'}
          rows={rows}
          note={d.playoff.length > 0 ? unsplit : null}
        />
      </div>
    </section>
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

const WEEK_STATUS_TEXT: Record<string, string> = {
  CONFIG_MISSING: "A week can't be recommended because this app's meeting-limit setting didn't load.",
  NOT_PUBLISHED: "A week can't be recommended until the schedule is published.",
  UNSUPPORTED_ROSTERS: "A week can't be recommended because not every team has exactly one manager this season.",
  NO_SEASON_FORMAT: "This league's settings don't say where the regular season ends, so no week is recommended.",
  NO_ELIGIBLE_WEEK: 'No regular-season week is left to recommend.',
}

/** The NO_WEEK_FITS sentence names the league's real limit; without a cap it stays generic. */
function noWeekFitsText(cap: number | null): string {
  return cap != null
    ? `Every remaining week would make someone play the same manager ${moreThanText(cap)}, so no week is recommended.`
    : 'Every remaining week would break the meeting limit, so no week is recommended.'
}

/** [3] -> "week 3"; [3, 11] -> "weeks 3 and 11"; [3, 5, 11] -> "weeks 3, 5 and 11". */
function weeksText(weeks: number[]): string {
  if (weeks.length === 1) return `week ${weeks[0]}`
  return `weeks ${weeks.slice(0, -1).join(', ')} and ${weeks[weeks.length - 1]}`
}

const NTH = ['', 'first', 'second', 'third', 'fourth', 'fifth', 'sixth']

function PairRow({
  p, rows, already,
}: { p: RivalryPairing; rows: Map<number, RivalryRow>; already?: boolean }) {
  const a = rows.get(p.aManagerId)
  const b = rows.get(p.bManagerId)
  return (
    <li className="rv-pair">
      <span className="rv-pair-who">
        {a ? <Who row={a} /> : `Manager ${p.aManagerId}`}
        <span className="rv-vs">vs</span>
        {b ? <Who row={b} /> : `Manager ${p.bManagerId}`}
      </span>
      <span className="rv-pair-why">{p.reason.text}</span>
      <span className="rv-pair-meta">
        <span className="rv-met">{p.meetings} {p.meetings === 1 ? 'meeting' : 'meetings'}</span>
        {p.thin && <span className="rv-thin">thin history</span>}
        {already && <span className="rv-sched">already on the schedule</span>}
      </span>
    </li>
  )
}

function ThinHeadline({ thin, total }: { thin: number; total: number }) {
  if (total === 0 || thin * 2 <= total) return null
  return (
    <p className="rv-headline">
      Most of these pairings ({thin} of {total}) rest on thin history, so treat the reasons as a starting point
      rather than a verdict.
    </p>
  )
}

function vsText(rows: Map<number, RivalryRow>, a: number, b: number): string {
  return `${nameOf(rows, a)} vs ${nameOf(rows, b)}`
}

/** "more than once" / "more than 2 times", the backend's own wording for the cap. */
function moreThanText(cap: number): string {
  return cap === 1 ? 'more than once' : `more than ${cap} times`
}

/**
 * A held-back pair's other meetings, split at the as-of week the way `displacedText` splits them: weeks up to it
 * were played, later ones are only scheduled. Without an as-of week nothing has been played yet.
 */
function heldBackText(rows: Map<number, RivalryRow>, h: RivalryHeldBack, asOfWeek: number | null): string {
  const played = h.meetsInWeeks.filter((w) => asOfWeek != null && w <= asOfWeek)
  const ahead = h.meetsInWeeks.filter((w) => asOfWeek == null || w > asOfWeek)
  const bits: string[] = []
  if (played.length > 0) bits.push(`met in ${weeksText(played)}`)
  if (ahead.length > 0) bits.push(`are scheduled to meet in ${weeksText(ahead)}`)
  return `${nameOf(rows, h.aManagerId)} and ${nameOf(rows, h.bManagerId)} ${bits.join(' and ')}, so they'd play a `
    + `${NTH[h.wouldMeet] ?? `${h.wouldMeet}th`} time.`
}

function displacedText(d: RivalryDisplaced): string {
  const bits: string[] = []
  if (d.metInWeeks.length > 0) bits.push(`already met in ${weeksText(d.metInWeeks)}`)
  if (d.stillMeetsInWeeks.length > 0) bits.push(`still meet in ${weeksText(d.stillMeetsInWeeks)}`)
  return bits.length > 0 ? bits.join('; ') : "they wouldn't meet otherwise this season"
}

function RecommendedWeek({
  week, rows,
}: { week: RivalryWeekRecommendation; rows: Map<number, RivalryRow> }) {
  const r = week.recommended as RivalryWeekOption
  const thin = r.pairings.filter((p) => p.pairing.thin).length
  const cap = week.cap
  return (
    <>
      <h3 className="rv-week-h">Recommended: Week {r.week}</h3>
      {week.asOfWeek != null && <p className="muted small">as of week {week.asOfWeek}</p>}
      <p className="rv-headline">{r.reason.text}</p>
      {cap != null && (
        <p className="muted small">
          Nobody plays the same manager {moreThanText(cap)} in the regular season
          {week.capLabel ? ` (${week.capLabel})` : ''}.
        </p>
      )}
      {week.overCapNote && <p className="muted small">{week.overCapNote}</p>}
      <ThinHeadline thin={thin} total={r.pairings.length} />
      <ul className="rv-pairs">
        {r.pairings.map((wp) => (
          <PairRow
            key={`${wp.pairing.aManagerId}:${wp.pairing.bManagerId}`}
            p={wp.pairing} rows={rows} already={wp.alreadyScheduled}
          />
        ))}
      </ul>
      {r.unpaired && (
        <p className="muted small">
          <strong>{nameOf(rows, r.unpaired.managerId)}</strong> sits out. {r.unpaired.why}
        </p>
      )}
      {r.heldBack.length > 0 && (
        <ul className="rv-held">
          {r.heldBack.map((h) => (
            <li key={`${h.aManagerId}:${h.bManagerId}`} className="small">
              {heldBackText(rows, h, week.asOfWeek)}
            </li>
          ))}
        </ul>
      )}
      {r.displaced.length > 0 && (
        <details className="rv-details">
          <summary>What this replaces</summary>
          <ul className="rv-held">
            {r.displaced.map((d) => (
              <li key={`${d.aManagerId}:${d.bManagerId}`} className="small">
                <strong>{vsText(rows, d.aManagerId, d.bManagerId)}</strong>: {displacedText(d)}
              </li>
            ))}
          </ul>
        </details>
      )}
      {week.unfitWeeks.length > 0 && (
        <p className="muted small">
          No full set fits in {weeksText(week.unfitWeeks)}.
        </p>
      )}
      {week.others.length > 0 && (
        <details className="rv-details">
          <summary>Other weeks</summary>
          {week.others.map((o) => (
            <details key={o.week} className="rv-details rv-other">
              <summary>
                Week {o.week}: {o.total} of {o.bestTotal}
                {o.heldBack.length > 0 ? ` · ${o.heldBack.length} held back` : ''}
              </summary>
              <p className="muted small">{o.reason.text}</p>
              <ul className="rv-pairs">
                {o.pairings.map((wp) => (
                  <PairRow
                    key={`${wp.pairing.aManagerId}:${wp.pairing.bManagerId}`}
                    p={wp.pairing} rows={rows} already={wp.alreadyScheduled}
                  />
                ))}
              </ul>
            </details>
          ))}
        </details>
      )}
    </>
  )
}

export function RivalryWeekPanel({ data }: { data: RivalriesData }) {
  const rec = data.recommendation
  const rows = new Map(data.matrix.rows.map((r) => [r.managerId, r]))
  const minText =
    rec.minMeetings != null ? `at least ${rec.minMeetings} meetings (${rec.minMeetingsLabel ?? 'hand-set'})` : null
  // Loose equality on purpose: an older backend omits `week` (undefined) and must render spec 034's view.
  const week = rec.week != null ? rec.week : null
  const picked = week != null && week.status === 'OK' && week.recommended != null
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
          {picked ? (
            <RecommendedWeek week={week as RivalryWeekRecommendation} rows={rows} />
          ) : (
            <>
              {week != null && (
                <>
                  <p className="muted small">
                    {week.status === 'NO_WEEK_FITS'
                      ? noWeekFitsText(week.cap)
                      : WEEK_STATUS_TEXT[week.status] ?? 'A rivalry week recommendation is not available.'}
                  </p>
                  {week.overCapNote && <p className="muted small">{week.overCapNote}</p>}
                  <h3 className="rv-week-h">Best pairings from history</h3>
                </>
              )}
              <ThinHeadline thin={rec.thinCount} total={rec.pairings.length} />
              <ul className="rv-pairs">
                {rec.pairings.map((p: RivalryPairing) => (
                  <PairRow key={`${p.aManagerId}:${p.bManagerId}`} p={p} rows={rows} />
                ))}
              </ul>
              {rec.unpaired && (
                <p className="muted small">
                  <strong>{nameOf(rows, rec.unpaired.managerId)}</strong> sits out. {rec.unpaired.why}
                </p>
              )}
            </>
          )}
        </>
      )}
      <p className="muted small">{rec.note}</p>
    </section>
  )
}
