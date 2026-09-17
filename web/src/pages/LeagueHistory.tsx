import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import {
  backfillFinalRanks,
  getLeagueHistory,
  ingestLeagueHistory,
  type LeagueHistory as LeagueHistoryData,
  type LeagueRecords,
  type MarginRecord,
  type RankStatus,
  type StandingRow,
  type WeeklyScoreRecord,
} from '../api'
import Avatar from '../components/Avatar'
import { useLeagueLinkState } from '../railLeague'

/**
 * claude/league-suite.md Phase A: standings across every ingested season for
 * one league -- plus, since specs/002-league-history-record-book, the record
 * book those seasons add up to: the highest and lowest weeks anyone has posted,
 * the closest games and the widest, and each season's end-of-season power rank
 * on its own standings row.
 *
 * Read-only apart from two buttons, both of which FIRE the call rather than
 * telling the reader to curl it: "Load past seasons" on the error state, and
 * "Compute" in a rank cell whose season was never ranked.
 *
 * Every panel here states a reason when it has nothing to show. That is not
 * politeness -- a panel that draws nothing and says nothing is indistinguishable
 * from a broken one, and this page has a league in its own database for each
 * empty case (West Coast 2025 has scores and no pairings; NBA 2026 is ingested
 * and unplayed).
 */

/**
 * specs/002-league-history-record-book. Two panels below the standings.
 *
 * Both render a stated reason when they have nothing, never an empty container:
 * a panel that draws nothing and says nothing is indistinguishable from one
 * that is broken, which is the lesson the season-with-no-standings guard
 * further down already learned the hard way.
 */
function RecordBook({ records }: { records: LeagueRecords }) {
  const hasScores = records.highestWeeks.length > 0 || records.lowestWeeks.length > 0
  return (
    <section className="panel">
      <h3 className="cond">Record book</h3>
      {!hasScores ? (
        <p className="muted small">
          No weekly scores have been loaded for this league's seasons yet, so there are no records to show.
        </p>
      ) : (
        <div className="records-pair">
          <ScoreList title="Highest weeks" rows={records.highestWeeks} />
          <ScoreList title="Lowest weeks" rows={records.lowestWeeks} />
        </div>
      )}
    </section>
  )
}

function ScoreList({ title, rows }: { title: string; rows: WeeklyScoreRecord[] }) {
  return (
    <div className="records-col">
      <h4>{title}</h4>
      <ol className="record-list">
        {rows.map((r, i) => (
          <li className="record-row" key={`${r.season}-${r.week}-${r.rosterId}`}>
            <span className="record-rank">{i + 1}</span>
            <span className="record-who">
              <RecordWho row={r} />
            </span>
            <span className="record-points">{r.points.toFixed(2)}</span>
            <span className="record-when">
              {r.season} · Wk {r.week}
            </span>
          </li>
        ))}
      </ol>
    </div>
  )
}

/**
 * A roster-season with no manager still renders. Sleeper leaves rosters unowned
 * when someone leaves mid-season, and dropping those rows would silently edit
 * the record book rather than report it.
 */
function RecordWho({ row }: { row: { rosterId: number; managerId: number | null; manager: string | null; avatarId: string | null } }) {
  // Carries this league to the manager's own page, whose URL can't say which
  // league you came from -- without it the rail there shows no league at all.
  const linkState = useLeagueLinkState()
  if (row.managerId == null) return <span className="muted">roster {row.rosterId}</span>
  return (
    <Link to={`/managers/${row.managerId}/history`} state={linkState} className="standings-manager">
      <Avatar avatarId={row.avatarId} seed={String(row.managerId)} label={row.manager} />
      {row.manager ?? `roster ${row.rosterId}`}
    </Link>
  )
}

function MatchupMargins({ records }: { records: LeagueRecords }) {
  const has = records.closestMatchups.length > 0 || records.biggestBlowouts.length > 0
  return (
    <section className="panel">
      <h3 className="cond">Matchup margins</h3>
      {!has ? (
        <p className="muted small">
          {records.marginsUnavailableReason ??
            "Head-to-head pairings aren't available for this league's seasons."}
        </p>
      ) : (
        <div className="records-pair">
          <MarginGroup title="Closest matchups" rows={records.closestMatchups} />
          <MarginGroup title="Biggest blowouts" rows={records.biggestBlowouts} />
        </div>
      )}
    </section>
  )
}

function MarginGroup({ title, rows }: { title: string; rows: MarginRecord[] }) {
  return (
    <div className="records-col">
      <h4>{title}</h4>
      <div className="margin-cards">
        {rows.map((m) => (
          <div className="margin-card" key={`${m.season}-${m.week}-${m.winner.rosterId}-${m.loser.rosterId}`}>
            <div className="margin-card-head">
              <span>
                <span className="margin-value">{m.margin.toFixed(2)}</span>
                {/* The axis, spelled out. "0.16" alone does not say what it measures. */}
                <span className="margin-label"> Margin</span>
              </span>
              <span className="margin-when">
                {m.season} · Wk {m.week}
              </span>
            </div>
            <div className="margin-side won">
              <RecordWho row={m.winner} />
              <span className="margin-side-pts">{m.winner.points.toFixed(2)}</span>
            </div>
            <div className="margin-side lost">
              <RecordWho row={m.loser} />
              <span className="margin-side-pts">{m.loser.points.toFixed(2)}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

/**
 * The rank cell's absent states. Three different reasons, three different
 * sentences, because they ask three different things of the reader: wait, press
 * the button, or nothing at all. A bare dash would say none of that (FR-005).
 *
 * Note IN_PROGRESS is not an error. A season still being played has no final
 * rank and is not supposed to.
 */
const RANK_REASON: Record<Exclude<RankStatus, 'RANKED'>, string> = {
  IN_PROGRESS: 'season in progress',
  NOT_COMPUTED: 'not computed yet',
  UNAVAILABLE: 'no weekly scores stored',
}

function RankCell({
  row,
  onCompute,
  computing,
}: {
  row: StandingRow
  onCompute: () => void
  computing: boolean
}) {
  if (row.rankStatus === 'RANKED' && row.finalRank != null) {
    return (
      <td className="mono rank-cell" title={`Through week ${row.finalRankWeek}`}>
        {row.finalRank}
      </td>
    )
  }
  // An older server that does not send rankStatus at all -- the field is
  // optional on the wire because getManagerHistory reuses this row type.
  if (row.rankStatus == null) return <td className="mono rank-cell">—</td>
  // RANKED with no rank means this roster is missing from an otherwise-present
  // snapshot, which is the same thing as having nothing to show for it.
  const status = row.rankStatus === 'RANKED' ? 'UNAVAILABLE' : row.rankStatus
  return (
    <td className="rank-cell">
      <span className="rank-missing">{RANK_REASON[status]}</span>{' '}
      {/* A button, never the endpoint printed for the reader to run -- the
          "Load past seasons" control below was written for exactly this
          reason and the pattern came straight back in new code once already. */}
      {status === 'NOT_COMPUTED' && (
        <button className="action-button" onClick={onCompute} disabled={computing}>
          {computing ? 'Computing…' : 'Compute'}
        </button>
      )}
    </td>
  )
}

function StandingsTable({
  rows,
  onCompute,
  computing,
}: {
  rows: StandingRow[]
  onCompute: () => void
  computing: boolean
}) {
  const linkState = useLeagueLinkState()
  return (
    <div className="table-wrap">
      <table className="standings">
        <thead>
          <tr>
            <th></th>
            <th>Manager</th>
            <th className="mono" title="End-of-season power rank">Rank</th>
            <th className="mono">W</th>
            <th className="mono">L</th>
            <th className="mono">T</th>
            <th className="mono">PF</th>
            <th className="mono">PA</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            return (
              <tr key={r.rosterId}>
                <td>{r.champion && <span title="Champion">🏆</span>}</td>
                <td>
                  {r.managerId != null ? (
                    <Link
                      to={`/managers/${r.managerId}/history`}
                      state={linkState}
                      className="standings-manager"
                    >
                      <Avatar avatarId={r.avatarId} seed={String(r.managerId)} label={r.manager} />
                      {r.manager ?? `roster ${r.rosterId}`}
                    </Link>
                  ) : (
                    <span className="muted">roster {r.rosterId} (unowned)</span>
                  )}
                </td>
                <RankCell row={r} onCompute={onCompute} computing={computing} />
                <td className="mono">{r.wins ?? '—'}</td>
                <td className="mono">{r.losses ?? '—'}</td>
                <td className="mono">{r.ties ?? '—'}</td>
                <td className="mono">{r.pointsFor?.toFixed(2) ?? '—'}</td>
                <td className="mono">{r.pointsAgainst?.toFixed(2) ?? '—'}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export default function LeagueHistory() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [history, setHistory] = useState<LeagueHistoryData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [computing, setComputing] = useState(false)

  useEffect(() => {
    if (!sleeperLeagueId) return
    setHistory(null)
    setError(null)
    getLeagueHistory(sleeperLeagueId)
      .then(setHistory)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [sleeperLeagueId])

  // Backs the error state's own button. Same call the page used to print as a
  // curl line for the reader to run in a terminal.
  async function loadHistory() {
    if (!sleeperLeagueId) return
    setLoading(true)
    setError(null)
    try {
      await ingestLeagueHistory(sleeperLeagueId)
      setHistory(await getLeagueHistory(sleeperLeagueId))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  /**
   * Backs the Compute button in a NOT_COMPUTED rank cell. Runs the whole chain
   * rather than one season: the endpoint already reports per-season what it
   * skipped and why, and a reader who wants one season's rank almost always
   * wants the others too.
   */
  async function computeFinalRanks() {
    if (!sleeperLeagueId) return
    setComputing(true)
    setError(null)
    try {
      await backfillFinalRanks(sleeperLeagueId)
      setHistory(await getLeagueHistory(sleeperLeagueId))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setComputing(false)
    }
  }

  return (
    <div className="content">
      {/* Was a `.panel h2` with the explainer stuffed under it, and a
          `Power rankings →` chip beside it -- the chip moved to the rail
          (Phase 3) and the title out of the box (Phase 4). */}
      <PageHeader
        eyebrow="League"
        title={history?.seasons[0]?.name ?? 'League history'}
        sub="Standings as Sleeper reports them — wins, losses, points, the champion. What this app thinks about a draft (reach, value) lives on a manager's own history page, kept visually separate from what actually happened."
      />

      <section className="panel">

        {/* This used to print `POST /api/ingest/league-history/{id}` for the
            reader to run themselves. Step 3 of the design review removed
            exactly that pattern from the draft room's pre-start overlay and it
            came straight back in new code, so it is a convention problem: a
            button that fires the call, never the call itself. */}
        {error && (
          <div className="error history-error">
            <span>
              {error.toLowerCase().includes('not found') || error.includes('404')
                ? "This league's past seasons haven't been loaded yet."
                : error}
            </span>
            <button className="action-button" onClick={loadHistory} disabled={loading}>
              {loading ? 'Loading seasons…' : 'Load past seasons'}
            </button>
          </div>
        )}

        {/* A standings table isn't `.draft-row`-shaped, so it gets a plain wait
            rather than SkeletonRows -- see Skeleton.tsx's own convention. */}
        {!history && !error && (
          <p className="muted small" role="status" aria-busy="true">
            Loading league history…
          </p>
        )}

        {history && history.seasons.length === 0 && (
          <p className="muted">No seasons ingested for this league yet.</p>
        )}

        {history &&
          history.seasons.map((s) => (
            <div key={s.leagueId} className="history-season">
              <h3 className="cond">{s.season}</h3>
              {/* A season with no standings rendered its headers over nothing
                  -- verified on the 2026 season, which is ingested but hasn't
                  been played. `seasons.length === 0` was guarded; this wasn't. */}
              {s.standings.length === 0 ? (
                <p className="muted small">
                  No standings for {s.season} yet — Sleeper reports them once the season is under way.
                </p>
              ) : (
                <StandingsTable rows={s.standings} onCompute={computeFinalRanks} computing={computing} />
              )}
            </div>
          ))}
      </section>

      {history && <RecordBook records={history.records} />}
      {history && <MatchupMargins records={history.records} />}
    </div>
  )
}
