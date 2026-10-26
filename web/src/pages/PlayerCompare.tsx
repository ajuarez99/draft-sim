import { useEffect, useState, type ReactNode } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import PlayerChooser from '../components/PlayerChooser'
import PlayerFace from '../components/PlayerFace'
import { OwnerList, TeamLines, UnknownPlayerNote } from '../components/PlayerHeaderParts'
import { getPlayerStats, type PlayerStatsPage, type PlayerWindowKind } from '../api'
import { TOTAL_ROW, WINDOW_LABELS, WINDOW_ORDER, boxRows, markRow, windowNotes, windowRows, type Row, type Side } from '../compare'
import { comparePagesFor } from '../destinations'
import { fetchDraftStats } from '../draftStatsCache'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { fallbackNote, longDate, reasonSentence, seasonLabel, statusAsOfLabel } from '../statCopy'
import { useBlock, type Block } from '../useBlock'

/**
 * specs/049-player-compare: two players' existing player-page figures in two columns, the better value marked
 * per row where the data can back it.
 *
 * The page composes two calls to the player endpoint, so every figure is the player page's own (FR-002). It
 * holds no verdict: no winner, no tally, no recommendation (FR-006). Which rows are marked, and when a mark is
 * withheld, is `compare.ts`'s one rule (`markRow`); this file only draws its answer.
 */

const INTRO =
  'The marked value is the higher one (lower for turnover rate). Small samples, part-filled or stale windows, and shooting percentages aren\'t marked.'
const THIS_WEEK_NOTE =
  'This league credits one game per player each week: the one you lock in, or his last. More games means more chances at a good one, not more games\' points.'
const FOOTBALL_LINE = 'Player comparison is available for basketball leagues only.'

const BOX_WINDOWS: PlayerWindowKind[] = ['SEASON', 'LAST_10', 'LAST_5']

type Pages = { A: PlayerStatsPage | null; B: PlayerStatsPage | null }

const pageOf = (b: Block<PlayerStatsPage>): PlayerStatsPage | null => (b.status === 'ok' ? b.data : null)

/** The older of two responses: the earlier season, else the earlier refresh (null counts as oldest). */
function olderSide(a: PlayerStatsPage, b: PlayerStatsPage): Side {
  if (a.season !== b.season) return a.season < b.season ? 'A' : 'B'
  return (a.dataAsOf ?? '') <= (b.dataAsOf ?? '') ? 'A' : 'B'
}

export default function PlayerCompare() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const leagueId = sleeperLeagueId ?? ''
  const [params, setParams] = useSearchParams()
  const aId = params.get('a') || null
  const rawB = params.get('b') || null
  // The same player twice is one column and a chooser, not a comparison of him with himself.
  const bId = rawB && rawB !== aId ? rawB : null
  const version = useLeagueDataVersion(sleeperLeagueId)
  const [bump, setBump] = useState<Record<Side, number>>({ A: 0, B: 0 })
  // The one retry of a mismatched pair: which pair, which side, and the response it replaced.
  const [retry, setRetry] = useState<{ key: string; side: Side; stale: PlayerStatsPage } | null>(null)
  const [window, setWindow] = useState<PlayerWindowKind>('SEASON')

  const blockA = useBlock(leagueId && aId ? () => getPlayerStats(leagueId, aId) : null, [leagueId, aId, version, bump.A])
  // Column B starts once A has answered (or there is no A), so a football league costs one request, not two: the
  // page has no league sport before its first response, and the player endpoint is what refuses football.
  // Sticky, so swapping A later never drops B's data.
  const [bGate, setBGate] = useState(false)
  const aSport = blockA.status === 'ok' ? blockA.data.sport : undefined
  const bOn = !(aSport != null && !comparePagesFor(aSport)) && (bGate || aId == null || blockA.status === 'ok' || blockA.status === 'error')
  useEffect(() => {
    if (bOn) setBGate(true)
  }, [bOn])
  const blockB = useBlock(leagueId && bId && bOn ? () => getPlayerStats(leagueId, bId) : null, [leagueId, bId, bOn, version, bump.B])
  // With neither player chosen the only response there is to learn the sport from is the chooser's leaderboard,
  // which is the same cached request the chooser makes when it opens.
  // It is also the fallback when every named player failed to load (an unknown id), so the page still learns the
  // sport (review N3).
  const noPlayerLoaded = (!aId || blockA.status === 'error') && (!bId || blockB.status === 'error')
  const board = useBlock(
    leagueId && noPlayerLoaded ? () => fetchDraftStats(leagueId, 'SEASON', version) : null,
    [leagueId, noPlayerLoaded, version],
  )

  const pages: Pages = { A: pageOf(blockA), B: pageOf(blockB) }
  const sports = [pages.A?.sport, pages.B?.sport, board.status === 'ok' ? board.data.sport : undefined]
  const football = sports.some((s) => s != null && !comparePagesFor(s))

  // A14: two responses from different refreshes (or seasons) are refetched once, the older one; if they still
  // differ, every mark is withheld and both as-ofs are printed.
  const mismatch = pages.A != null && pages.B != null && (pages.A.season !== pages.B.season || pages.A.dataAsOf !== pages.B.dataAsOf)
  const pairKey = `${aId}|${bId}|${version}`
  useEffect(() => {
    if (!mismatch || !pages.A || !pages.B || retry?.key === pairKey) return
    const side = olderSide(pages.A, pages.B)
    setRetry({ key: pairKey, side, stale: pages[side]! })
    setBump((b) => ({ ...b, [side]: b[side] + 1 }))
  }, [mismatch, pages.A, pages.B, retry, pairKey])
  // Said only once the refetched response is in: the render between "retry started" and "loading" still holds
  // the old one, and a note shown for that render would flash.
  const stillDiffers = mismatch && retry?.key === pairKey && pages[retry.side] !== retry.stale

  function pick(side: Side, id: string) {
    const next = new URLSearchParams(params)
    next.set(side === 'A' ? 'a' : 'b', id)
    setParams(next, { replace: true })
  }

  const any = pages.A ?? pages.B
  const fallback = [pages.A, pages.B].find((p) => p?.requestedSeason != null)
  const eyebrow = any ? `League · ${seasonLabel(any.season)}` : 'League'

  return (
    <div className="content">
      <PageHeader eyebrow={eyebrow} title="Compare players" sub="Two players’ real stats and this league’s fantasy points, side by side." />
      {football ? (
        <section className="section pp-reason">
          <p className="muted small">{FOOTBALL_LINE}</p>
        </section>
      ) : (
        <>
          <p className="muted small pc-intro">{INTRO}</p>
          {fallback && fallback.requestedSeason != null && (
            <p className="small pt-note" role="note">
              {fallbackNote(seasonLabel(fallback.requestedSeason), seasonLabel(fallback.season))}
            </p>
          )}
          {stillDiffers && pages.A && pages.B && (
            <p className="small pt-note" role="note">
              {`These two come from different refreshes (${asOf(pages.A)} and ${asOf(pages.B)}), so nothing is marked until they match.`}
            </p>
          )}
          <MainTable
            leagueId={leagueId}
            ids={{ A: aId, B: bId }}
            blocks={{ A: blockA, B: blockB }}
            pages={pages}
            onPick={pick}
          />
          <PerGame window={window} onWindow={setWindow} pages={pages} />
          {pages.A?.dataAsOf && pages.B?.dataAsOf && !mismatch && (
            <p className="muted small">{`Data refreshed ${longDate(pages.A.dataAsOf)}.`}</p>
          )}
        </>
      )}
    </div>
  )
}

const asOf = (p: PlayerStatsPage) => `${seasonLabel(p.season)}, ${statusAsOfLabel(p.dataAsOf) ?? 'time unknown'}`

/** One column's header: who he is, or why there is no one (loading, failed, or a chooser to pick). */
function ColumnHead({
  side, leagueId, id, otherId, block, onPick,
}: {
  side: Side
  leagueId: string
  id: string | null
  otherId: string | null
  block: Block<PlayerStatsPage>
  onPick: (side: Side, id: string) => void
}) {
  // A filled column can be swapped: "Change" reopens the chooser for this side only (review B1).
  const [changing, setChanging] = useState(false)
  let body: ReactNode
  if (!id) {
    body = <PlayerChooser leagueId={leagueId} excludeId={otherId} onPick={(pid) => onPick(side, pid)} />
  } else if (block.status === 'loading' || block.status === 'idle') {
    body = (
      <p className="muted small" role="status">
        Loading…
      </p>
    )
  } else if (block.status === 'error') {
    body = (
      <p className="muted small">
        {block.notFound ? 'We have no player with that id in this league.' : 'Couldn’t load this player.'}
      </p>
    )
  } else if (!block.data.available) {
    body = <p className="muted small">{block.data.reason ? reasonSentence(block.data.reason) : 'Player pages aren’t available.'}</p>
  } else {
    const d = block.data
    const p = d.player
    const name = p.name ?? 'Unknown player'
    body = (
      <>
        <PlayerFace sport={d.sport} sleeperId={p.sleeperPlayerId} team={p.team} position={p.positions[0] ?? ''} name={name} size={56} />
        <h3 className="pc-name">{name}</h3>
        <p className="pp-id">
          {p.positions.map((pos) => (
            <span key={pos} className="pp-pos">
              {pos}
            </span>
          ))}
          {p.team && <span className="pp-team">{p.team}</span>}
          <UnknownPlayerNote d={d} />
        </p>
        {d.reason && <p className="muted small pp-line">{reasonSentence(d.reason)}</p>}
        <TeamLines d={d} />
        <div className="pc-own">
          <OwnerList d={d} />
        </div>
      </>
    )
  }
  // Any column with a player in it (even one that failed to load) can be swapped for another.
  const swappable = !!id
  return (
    <th scope="col" className="pc-col" aria-label={`Player ${side}`}>
      {body}
      {swappable &&
        (changing ? (
          <PlayerChooser
            leagueId={leagueId}
            excludeId={otherId}
            startOpen
            label={`Change player ${side}`}
            onCancel={() => setChanging(false)}
            onPick={(pid) => {
              setChanging(false)
              onPick(side, pid)
            }}
          />
        ) : (
          <button type="button" className="pc-change" aria-label={`Change player ${side}`} onClick={() => setChanging(true)}>
            Change
          </button>
        ))}
    </th>
  )
}

/** Games and fantasy output per window, the season total, and this week. */
function MainTable({
  leagueId, ids, blocks, pages, onPick,
}: {
  leagueId: string
  ids: Record<Side, string | null>
  blocks: Record<Side, Block<PlayerStatsPage>>
  pages: Pages
  onPick: (side: Side, id: string) => void
}) {
  const weekPages = [pages.A, pages.B]
  const showWeek = weekPages.some((p) => p?.thisWeek != null)
  const scheduleAsOf = weekPages.map((p) => p?.scheduleAsOf).find((s) => s != null) ?? null
  const scheduleLine = weekPages.some((p) => p?.thisWeek?.scheduleStored === false) && scheduleAsOf == null
    ? 'no schedule is stored for this season'
    : `schedule as of ${statusAsOfLabel(scheduleAsOf) ?? 'time unknown'}`
  return (
    <section className="pp-card pc-card" aria-label="Comparison">
      <table className="pc-table">
        <thead>
          <tr>
            <td className="pc-corner" />
            <ColumnHead side="A" leagueId={leagueId} id={ids.A} otherId={ids.B} block={blocks.A} onPick={onPick} />
            <ColumnHead side="B" leagueId={leagueId} id={ids.B} otherId={ids.A} block={blocks.B} onPick={onPick} />
          </tr>
        </thead>
        {showWeek && (
          <tbody>
            <tr>
              <th scope="row" className="pc-label">
                This week
              </th>
              <ThisWeekCell page={pages.A} />
              <ThisWeekCell page={pages.B} />
            </tr>
            <tr>
              <td colSpan={3} className="pc-note muted small">
                {THIS_WEEK_NOTE}
                {` Games played lag the box-score ingest; ${scheduleLine}.`}
              </td>
            </tr>
          </tbody>
        )}
        {WINDOW_ORDER.map((w) => (
          <tbody key={w}>
            <GroupRow label={WINDOW_LABELS[w]} notes={windowNotes(pages.A, pages.B, w)} />
            {windowRows(w).map((r) => (
              <CompareRow key={r.key} row={r} pages={pages} />
            ))}
          </tbody>
        ))}
        <tbody>
          <CompareRow row={TOTAL_ROW} pages={pages} />
        </tbody>
      </table>
    </section>
  )
}

/** A window's header row. Why a side's window isn't compared is said here once, not on every row (review N4). */
function GroupRow({ label, notes }: { label: string; notes: { A?: string; B?: string } }) {
  return (
    <tr>
      <th scope="rowgroup" className="pc-group">
        {label}
      </th>
      <td className="pc-group pc-group-note">{notes.A ? `${notes.A}; not compared` : ''}</td>
      <td className="pc-group pc-group-note">{notes.B ? `${notes.B}; not compared` : ''}</td>
    </tr>
  )
}

function ThisWeekCell({ page }: { page: PlayerStatsPage | null }) {
  if (!page || !page.available) return <td className="pc-cell" />
  const t = page.thisWeek
  // No week to name: a player with no team has no games, and the cell says so rather than reading as missing data (N5).
  if (!t) return <td className="pc-cell"><span className="muted small">{page.player.team ? 'No week to show' : 'No current team'}</span></td>
  // With no stored schedule `remaining` is 0 for want of data, so "no game" would be a claim nobody checked (S3).
  const text = !t.scheduleStored
    ? `${t.played} played · schedule not stored`
    : t.played === 0 && t.remaining === 0 ? 'No game this week' : `${t.played} played · ${t.remaining} left`
  return (
    <td className="pc-cell">
      <span className="pc-val">{text}</span>
    </td>
  )
}

/** The per-game box score, turnover rate and shooting for the chosen window. */
function PerGame({
  window, onWindow, pages,
}: {
  window: PlayerWindowKind
  onWindow: (w: PlayerWindowKind) => void
  pages: Pages
}) {
  return (
    <section className="pp-card pc-card" aria-label="Per game">
      <div className="pc-per-head">
        <h3 className="pp-h">Per game</h3>
        <div className="segmented" role="group" aria-label="Window">
          {BOX_WINDOWS.map((w) => (
            <button key={w} type="button" className={`segment${w === window ? ' on' : ''}`} aria-pressed={w === window} onClick={() => onWindow(w)}>
              {WINDOW_LABELS[w]}
            </button>
          ))}
        </div>
      </div>
      <table className="pc-table">
        <thead>
          <tr>
            <td className="pc-corner" />
            <NameHead page={pages.A} />
            <NameHead page={pages.B} />
          </tr>
        </thead>
        <tbody>
          <GroupRow label={WINDOW_LABELS[window]} notes={windowNotes(pages.A, pages.B, window)} />
          {boxRows(window).map((r) => (
            <CompareRow key={r.key} row={r} pages={pages} />
          ))}
        </tbody>
      </table>
    </section>
  )
}

function NameHead({ page }: { page: PlayerStatsPage | null }) {
  return (
    <th scope="col" className="pc-col pc-col-slim">
      {page?.available ? (page.player.name ?? 'Unknown player') : ''}
    </th>
  )
}

/** One row: the label, both values, and the mark (or the reason one side's window was withheld). */
function CompareRow({ row, pages }: { row: Row; pages: Pages }) {
  const m = markRow(row, pages.A, pages.B)
  return (
    <tr>
      <th scope="row" className="pc-label">
        {row.label}
      </th>
      {(['A', 'B'] as const).map((side) => {
        const p = pages[side]
        if (!p || !p.available) return <td key={side} className="pc-cell" />
        const sub = row.sub?.(p) ?? null
        const why = m.withheld[side]
        const won = m.mark === side
        return (
          <td key={side} className={`pc-cell${won ? ' pc-win' : ''}`}>
            <span className="pc-val">{row.display(p)}</span>
            {won && <span className="pc-sr">{row.direction === 'LOWER' ? ' lower' : ' higher'}</span>}
            {sub && <span className="pc-sub muted small">{sub}</span>}
            {why && <span className="pc-sr"> not compared</span>}
          </td>
        )
      })}
    </tr>
  )
}
