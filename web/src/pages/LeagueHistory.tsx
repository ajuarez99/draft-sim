import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import HowThisWorks from '../components/HowThisWorks'
import {
  backfillFinalRanks,
  getExpectedWins,
  getLeagueHistory,
  type ExpectedWins,
  type ExpectedWinsTeam,
  ingestLeagueHistory,
  type LeagueHistory as LeagueHistoryData,
  type LeagueRecords,
  type MarginRecord,
  type RankStatus,
  type StandingRow,
  type StreakRecord,
  type WeeklyScoreRecord,
} from '../api'
import { useFailure } from '../useFailure'
import PersonName from '../components/PersonName'
import Avatar from '../components/Avatar'
import { useLeagueLinkState } from '../railLeague'
import { useLeagueDataVersion } from '../leagueDataVersion'

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
 * specs/002-league-history-record-book. Two panels below the standings --
 * joined, since specs/006-deeper-history-both-sports US5, by two more:
 * all-time points leaders and the longest win/loss streaks.
 *
 * All four render a stated reason when they have nothing, never an empty
 * container: a panel that draws nothing and says nothing is indistinguishable
 * from one that is broken, which is the lesson the season-with-no-standings
 * guard further down already learned the hard way.
 */
function RecordBook({ records }: { records: LeagueRecords }) {
  const hasScores = records.highestWeeks.length > 0 || records.lowestWeeks.length > 0
  return (
    <section className="section">
      <h3 className="section-title">Record book</h3>
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
    <section className="section">
      <h3 className="section-title">Matchup margins</h3>
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
 * specs/006-deeper-history-both-sports T065 (US5): all-time points scored,
 * summed across the whole chain per manager. A RANKED LIST, same shape as
 * ScoreList above and for the same reason -- there is an order and the order
 * is the point -- rather than the margin-card pairing, which has no
 * "opponent" side here to pair against.
 *
 * US5.4: the empty state names the same cause `RecordBook` already does for
 * `highestWeeks`/`lowestWeeks`, because `pointsLeaders` is empty under
 * exactly that condition -- no stored weekly scores.
 */
function PointsLeaders({ records }: { records: LeagueRecords }) {
  const rows = records.pointsLeaders
  return (
    <section className="section">
      <h3 className="section-title">All-time points leaders</h3>
      {rows.length === 0 ? (
        // Deliberately reworded rather than the RecordBook panel's identical
        // sentence: both panels are empty for the same underlying reason (no
        // stored weekly scores), but printing the exact same sentence twice
        // on one page reads as a copy-paste rather than two honest panels.
        <p className="muted small">
          No weekly scores are stored for this league's seasons yet, so there's no all-time leaderboard
          to show.
        </p>
      ) : (
        <ol className="record-list">
          {rows.map((r, i) => (
            <li
              className="record-row"
              key={r.managerId != null ? `m-${r.managerId}` : `u-${r.rosterId}-${r.spanSeasons[0]}`}
            >
              <span className="record-rank">{i + 1}</span>
              <span className="record-who">
                <RecordWho row={r} />
              </span>
              <span className="record-points">{r.points.toFixed(2)}</span>
              {/* US5.4: the span is named beside the total, not assumed --
                  with one or two played seasons in this database, an
                  unlabeled "all-time" figure reads as a single season's. */}
              <span className="record-when">
                over {r.spanSeasons.length} season{r.spanSeasons.length === 1 ? '' : 's'}
              </span>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

/**
 * specs/006-deeper-history-both-sports T065 (US5): the longest runs of wins
 * and losses. Paired like `RecordBook`'s own two lists -- win streaks and
 * loss streaks only mean anything read side by side, the same reasoning that
 * gave the score lists a `records-pair` rather than one long list.
 *
 * The empty state reuses `marginsUnavailableReason`: both streak lists and
 * the margin lists are built from the same paired-fixture data
 * (`LeagueMatchupRepository#pairedWithScores`), so they are empty under
 * exactly the same condition, and stating the cause twice in two different
 * sentences would only risk the two drifting apart.
 */
function Streaks({ records }: { records: LeagueRecords }) {
  const rows = [...records.winStreaks, ...records.lossStreaks]
  return (
    <section className="section">
      <h3 className="section-title">Streaks</h3>
      {rows.length === 0 ? (
        <p className="muted small">
          {records.marginsUnavailableReason ??
            "Head-to-head pairings aren't available for this league's seasons."}
        </p>
      ) : (
        <>
          {/* research R7 / US5.2: the rule is stated once for the panel,
              rather than left for the reader to assume streaks can cross a
              season boundary. Every entry on the wire carries its own
              withinSeasonOnly, so a future cross-season streak still gets a
              true sentence -- this one just covers the common case today. */}
          <p className="muted tiny">
            {rows.every((r) => r.withinSeasonOnly)
              ? 'Longest run within a single season — a streak never carries across the offseason into the next year.'
              : 'Some streaks below cross a season boundary; each row states its own span.'}
          </p>
          <div className="records-pair">
            <StreakList title="Longest win streaks" rows={records.winStreaks} />
            <StreakList title="Longest loss streaks" rows={records.lossStreaks} />
          </div>
        </>
      )}
    </section>
  )
}

function StreakList({ title, rows }: { title: string; rows: StreakRecord[] }) {
  return (
    <div className="records-col">
      <h4>{title}</h4>
      <ol className="record-list">
        {rows.map((r, i) => (
          <li className="record-row" key={`${r.rosterId}-${r.spanSeasons[0]}-${r.startWeek}`}>
            <span className="record-rank">{i + 1}</span>
            <span className="record-who">
              <RecordWho row={r} />
            </span>
            <span className="record-points">
              {r.length} game{r.length === 1 ? '' : 's'}
            </span>
            <span className="record-when">
              {r.spanSeasons.join('–')} · Wk {r.startWeek}
              {r.endWeek !== r.startWeek ? `–${r.endWeek}` : ''}
              {!r.withinSeasonOnly && ' (crosses seasons)'}
            </span>
          </li>
        ))}
      </ol>
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
  canCommission,
}: {
  row: StandingRow
  onCompute: () => void
  computing: boolean
  canCommission: boolean
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
  // spec 013: "season in progress" is said once, in the season's section title,
  // not repeated in every row. The dash here is explained by that title.
  if (row.rankStatus === 'IN_PROGRESS') {
    return <td className="mono rank-cell" title="Season in progress">—</td>
  }
  // RANKED with no rank means this roster is missing from an otherwise-present
  // snapshot, which is the same thing as having nothing to show for it.
  const status = row.rankStatus === 'RANKED' ? 'UNAVAILABLE' : row.rankStatus
  return (
    <td className="rank-cell">
      <span className="rank-missing">{RANK_REASON[status]}</span>{' '}
      {/* A button, never the endpoint printed for the reader to run -- the
          "Load past seasons" control below was written for exactly this
          reason and the pattern came straight back in new code once already. */}
      {status === 'NOT_COMPUTED' && canCommission && (
        <button className="action-button" onClick={onCompute} disabled={computing}>
          {computing ? 'Computing…' : 'Compute'}
        </button>
      )}
    </td>
  )
}

type Rec = { wins: number; losses: number; ties: number }

function recText(r: Rec): string {
  return r.ties > 0 ? `${r.wins}-${r.losses}-${r.ties}` : `${r.wins}-${r.losses}`
}

/** Win share with a tie as half a win; null with no games. */
function recShare(r: Rec): number | null {
  const g = r.wins + r.losses + r.ties
  return g === 0 ? null : (r.wins + 0.5 * r.ties) / g
}

type Extremes = { best: number; worst: number } | null

/**
 * Best and worst value in one column. Null when there is no spread (every row
 * equal, or fewer than two rows with a value): marking a best and a worst among
 * identical numbers would state a difference that is not there.
 */
function extremes(values: (number | null | undefined)[], higherIsBetter: boolean): Extremes {
  const v = values.filter((x): x is number => x != null)
  if (v.length < 2) return null
  const hi = Math.max(...v)
  const lo = Math.min(...v)
  if (hi === lo) return null
  return higherIsBetter ? { best: hi, worst: lo } : { best: lo, worst: hi }
}

/**
 * Best/worst marker: a tint (--up/--down) AND a glyph with a text label, so the
 * colour is never the only signal (contracts/ui-rules.md, Color).
 */
function Mark({ value, ex }: { value: number | null | undefined; ex: Extremes }) {
  if (ex == null || value == null) return null
  if (value === ex.best) {
    return <span role="img" aria-label="Best in the league" title="Best in the league" className="stand-mark best">▲</span>
  }
  if (value === ex.worst) {
    return <span role="img" aria-label="Worst in the league" title="Worst in the league" className="stand-mark worst">▼</span>
  }
  return null
}

function StandingsTable({
  rows,
  season,
  expected,
  onCompute,
  computing,
  canCommission,
}: {
  rows: StandingRow[]
  season: number
  /** Expected-wins response, or null when unavailable. Used only where its own `season` equals this table's. */
  expected: ExpectedWins | null
  onCompute: () => void
  computing: boolean
  canCommission: boolean
}) {
  const linkState = useLeagueLinkState()
  // The expected-wins endpoint answers for ONE season (it may fall back from the
  // one asked for). Its figures belong to that season only, so another season's
  // table shows a dash rather than this season's numbers.
  const teams = new Map<number, ExpectedWinsTeam>()
  if (expected?.available && expected.season === season) {
    for (const t of expected.teams) teams.set(t.rosterId, t)
  }
  const exW = extremes(rows.map((r) => r.wins), true)
  const exL = extremes(rows.map((r) => r.losses), false)
  const exPF = extremes(rows.map((r) => r.pointsFor), true)
  const exPA = extremes(rows.map((r) => r.pointsAgainst), false)
  const allShare = (r: StandingRow) => {
    const t = teams.get(r.rosterId)
    return t?.allPlay ? recShare(t.allPlay) : null
  }
  const medShare = (r: StandingRow) => {
    const t = teams.get(r.rosterId)
    return t?.median ? recShare(t.median) : null
  }
  const exAll = extremes(rows.map(allShare), true)
  const exMed = extremes(rows.map(medShare), true)
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
            <th className="mono" title="Regular season: each week's score against every other team's score that week">
              Record vs all (reg. season)
            </th>
            <th className="mono" title="Regular season: each week's score against that week's median. Median games only, not added to the real record">
              Vs weekly median (reg. season)
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const t = teams.get(r.rosterId)
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
                      <Avatar avatarId={r.avatarId} seed={String(r.managerId)} label={r.teamName ?? r.manager} />
                      <PersonName teamName={r.teamName} username={r.manager} fallback={`roster ${r.rosterId}`} />
                    </Link>
                  ) : (
                    <span className="muted">roster {r.rosterId} (unowned)</span>
                  )}
                </td>
                <RankCell row={r} onCompute={onCompute} computing={computing} canCommission={canCommission} />
                <td className="mono">{r.wins ?? '—'}<Mark value={r.wins} ex={exW} /></td>
                <td className="mono">{r.losses ?? '—'}<Mark value={r.losses} ex={exL} /></td>
                <td className="mono">{r.ties ?? '—'}</td>
                <td className="mono">{r.pointsFor?.toFixed(2) ?? '—'}<Mark value={r.pointsFor} ex={exPF} /></td>
                <td className="mono">{r.pointsAgainst?.toFixed(2) ?? '—'}<Mark value={r.pointsAgainst} ex={exPA} /></td>
                <td className="mono">
                  {t?.allPlay ? recText(t.allPlay) : '—'}
                  <Mark value={allShare(r)} ex={exAll} />
                </td>
                <td className="mono">
                  {t?.median ? recText(t.median) : '—'}
                  <Mark value={medShare(r)} ex={exMed} />
                </td>
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
  const { error, notFound, setError, fail } = useFailure()
  const [loading, setLoading] = useState(false)
  const [computing, setComputing] = useState(false)
  const [expected, setExpected] = useState<ExpectedWins | null>(null)
  // Bumped by the rail when this league's background refresh finishes
  // (specs/009-auto-data-refresh): refetch, but keep what is on screen.
  const dataVersion = useLeagueDataVersion(sleeperLeagueId)
  const loadedFor = useRef<string | null>(null)
  // Sleeper's own commissioner flag for THIS league, from the payload. Never inferred client-side.
  const canCommission = history?.canCommission === true

  useEffect(() => {
    if (!sleeperLeagueId) return
    if (loadedFor.current !== sleeperLeagueId) {
      loadedFor.current = sleeperLeagueId
      setHistory(null)
    }
    setError(null)
    getLeagueHistory(sleeperLeagueId)
      .then(setHistory)
      .catch((e) => fail(e))
    // Optional extra columns: a failure here leaves them as dashes and never
    // blocks the standings themselves.
    let cancelled = false
    ;(async () => {
      try {
        const ew = await getExpectedWins(sleeperLeagueId)
        if (!cancelled) setExpected(ew)
      } catch {
        if (!cancelled) setExpected(null)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [sleeperLeagueId, dataVersion])

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
      fail(e)
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
      fail(e)
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
        eyebrow="Standings"
        title={history?.seasons[0]?.name ?? 'League history'}
        sub="Standings as Sleeper reports them — wins, losses, points, the champion. What this app thinks about a draft (reach, value) lives on a manager's own history page, kept visually separate from what actually happened."
      />

      <section className="section">

        {/* This used to print `POST /api/ingest/league-history/{id}` for the
            reader to run themselves. Step 3 of the design review removed
            exactly that pattern from the draft room's pre-start overlay and it
            came straight back in new code, so it is a convention problem: a
            button that fires the call, never the call itself. */}
        {error && (
          <div className="error history-error">
            <span>
              {notFound
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
          <p className="muted">No seasons loaded for this league yet.</p>
        )}

        {history &&
          history.seasons.map((s) => (
            <div key={s.leagueId} className="history-season">
              <h3 className="section-title">
                {s.season}
                {/* Said once per season, here, rather than in every row's rank cell. */}
                {s.standings.some((r) => r.rankStatus === 'IN_PROGRESS') && (
                  <span className="muted stand-inprogress"> · season in progress</span>
                )}
              </h3>
              {/* A season with no standings rendered its headers over nothing
                  -- verified on the 2026 season, which is ingested but hasn't
                  been played. `seasons.length === 0` was guarded; this wasn't. */}
              {s.standings.length === 0 ? (
                <p className="muted small">
                  No standings for {s.season} yet — Sleeper reports them once the season is under way.
                </p>
              ) : (
                <>
                  {/* Not a commissioner: the Compute button is hidden (the server
                      would refuse it anyway), so say once per season what the
                      "not computed yet" cells are waiting on. A missing
                      canCommission (older backend) counts as false. */}
                  {!canCommission && s.standings.some((r) => r.rankStatus === 'NOT_COMPUTED') && (
                    <p className="muted small">Final ranks appear once the commissioner computes them.</p>
                  )}
                  <StandingsTable
                    rows={s.standings}
                    season={s.season}
                    expected={expected}
                    onCompute={computeFinalRanks}
                    computing={computing}
                    canCommission={canCommission}
                  />
                </>
              )}
            </div>
          ))}

        {history && history.seasons.length > 0 && (
          <HowThisWorks>
            <p>
              Standings as Sleeper reports them — wins, losses, points, the champion. What this app
              thinks about a draft (reach, value) lives on a manager's own history page, kept
              visually separate from what actually happened.
            </p>
            <p>
              <strong>Record vs all (reg. season)</strong> plays each week's score against every other
              team's score that week, regular season only. In a 12-team league that is 11 games a week.
              It matches ffwrapped's "Record vs all".
            </p>
            <p>
              <strong>Vs weekly median (reg. season)</strong> counts one game a week: above that week's
              median score is a win, below it a loss (with an odd number of teams, the median team ties).
              Those games are kept apart and are <em>not</em> added to the real W-L record. ffwrapped's
              "Median record" does add them, so the same team can read 3-0 here and 6-0 there (3 real
              wins plus 3 median wins). Neither is wrong; they count different things.
            </p>
            <p>
              Both columns come from the Luck page's calculation, which covers one season at a time.
              Any season it did not compute shows a dash. In each number column, ▲ marks the best value
              and ▼ the worst (fewest losses and points against count as best); nothing is marked when
              every team is level.
            </p>
          </HowThisWorks>
        )}
      </section>

      {history && <RecordBook records={history.records} />}
      {history && <MatchupMargins records={history.records} />}
      {history && <PointsLeaders records={history.records} />}
      {history && <Streaks records={history.records} />}
    </div>
  )
}
