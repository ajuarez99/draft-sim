import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import PlayerLink from '../components/PlayerLink'
import PageHeader from '../components/PageHeader'
import HowThisWorks from '../components/HowThisWorks'
import GradeChip, { GradesEarlyBadge, gradesEarlySentence } from '../components/GradeChip'
import Avatar from '../components/Avatar'
import {
  getRosterManagement,
  getLeagueTransactions,
  type RosterManagement as Data,
  type RosterManagementTeam,
  type LeagueTransactions,
  type MovedPlayer,
  type TradePlayerAsset,
  type LeagueTrade,
  type TradeAssetStatus,
  type TradeStretchHow,
  type TradeReconciliation,
} from '../api'
import { useFailure } from '../useFailure'
import NotFound from '../components/NotFound'
import PersonName from '../components/PersonName'
import { hueForIndex } from '../hue'
import SeasonFallbackNote from '../components/SeasonFallbackNote'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { useSeasonLeagueIds } from '../railLeague'
import { easternDate } from '../lib/easternDate'
import { tradeCsv } from '../lib/tradeCsv'

/**
 * specs/004-ffwrapped-feature-parity US2: what each team scored, what it could
 * have scored with a perfect lineup every week, and the gap between them.
 *
 * <p>Both sports. Nothing on this page is a projection -- every number comes
 * from points already scored, which Sleeper reports for basketball exactly as
 * for football -- so unlike Analysis it carries no sport gate.
 *
 * The comparison is rendered INSIDE each standings row rather than as a
 * separate chart below the table. ffwrapped stacks a table and a bar chart that
 * carry the same twelve pairs, so the reader reads the league twice; here the
 * bar is the row, which is also what keeps the exact number beside its own mark
 * instead of on a shared axis several hundred pixels away.
 */
export default function RosterManagement() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [data, setData] = useState<Data | null>(null)
  const [tx, setTx] = useState<LeagueTransactions | null>(null)
  const { error, notFound, setError, fail } = useFailure()
  const [loading, setLoading] = useState(false)
  // Bumped by the rail when this league's background refresh finishes (specs/009-auto-data-refresh).
  const dataVersion = useLeagueDataVersion(sleeperLeagueId)
  const leagueIdForSeason = useSeasonLeagueIds(sleeperLeagueId)

  useEffect(() => {
    if (!sleeperLeagueId) return
    let cancelled = false
    setLoading(true)
    setError(null)
    getRosterManagement(sleeperLeagueId)
      .then((d) => {
        if (!cancelled) setData(d)
      })
      .catch((e) => {
        if (!cancelled) fail(e)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [sleeperLeagueId, dataVersion])

  // Transactions are a separate call on purpose: they are a separate ingest,
  // and the standings half of this page must still render when they have not
  // been pulled yet. A failure here is not a failure of the page.
  useEffect(() => {
    if (!sleeperLeagueId) return
    let cancelled = false
    getLeagueTransactions(sleeperLeagueId)
      .then((t) => {
        if (!cancelled) setTx(t)
      })
      .catch(() => {
        if (!cancelled) setTx(null)
      })
    return () => {
      cancelled = true
    }
  }, [sleeperLeagueId, dataVersion])

  // The widest potential sets the scale, so every row's pair of bars is
  // comparable across the league rather than each row normalising to itself.
  const scale = Math.max(1, ...(data?.teams ?? []).map((t) => t.potentialPoints))
  // The three largest bench gaps get the "worse" colour. A gap of zero is a
  // perfect lineup, never a candidate, so fewer than three teams may qualify.
  const gapOf = (t: RosterManagementTeam) => t.potentialPoints - t.totalPoints
  const biggestGaps = new Set(
    [...(data?.teams ?? [])]
      .filter((t) => gapOf(t) > 0.005)
      .sort((a, b) => gapOf(b) - gapOf(a))
      .slice(0, 3)
      .map((t) => t.rosterId),
  )

  if (notFound) return <NotFound what="league" />

  return (
    <div className="content">
      <PageHeader
        eyebrow={data ? `League · ${data.season}` : 'League'}
        title="Bench points"
        sub="Who left the most points on their bench, against a perfect lineup every week."
      />

      {tx && tx.available && tx.trades.length > 0 && (
        <p className="small tx-jump">
          <a href="#trades">Trades</a>
        </p>
      )}

      {error && (
        <div className="error">
          <span>{error}</span>
        </div>
      )}

      {loading && !data && <p className="muted small">Loading…</p>}

      {data && !data.available && (
        <section className="section">
          <h3 className="section-title">Nothing to measure yet</h3>
          <p className="muted small">
            {data.reason ?? 'No scored weeks for this league yet.'} Efficiency needs at least one
            completed week — before that there is no lineup decision to grade.
          </p>
        </section>
      )}

      {data && data.available && (
        <section className="section">
          <h3 className="section-title">
            Season {data.season} · {data.weeksScored} week{data.weeksScored === 1 ? '' : 's'} scored
          </h3>
          <SeasonFallbackNote season={data.season} requestedSeason={data.requestedSeason} />

          <table className="rm-table">
            <thead>
              <tr>
                <th scope="col">Team</th>
                <th scope="col" className="rm-num">Points</th>
                <th scope="col" className="rm-num">Potential</th>
                <th scope="col" className="rm-num">
                  Efficiency <GradesEarlyBadge early={data.gradesEarly} />
                </th>
                <th scope="col" className="rm-barhead">
                  Scored against potential, points
                </th>
              </tr>
            </thead>
            <tbody>
              {data.teams.map((t, i) => (
                <Row
                  key={t.rosterId}
                  team={t}
                  hue={hueForIndex(i, data.teams.length)}
                  scale={scale}
                  bigGap={biggestGaps.has(t.rosterId)}
                />
              ))}
            </tbody>
          </table>

          <ExcludedNote teams={data.teams} />

          <HowThisWorks>
            <p>
              What each team scored, against the most it could have scored if every weekly lineup
              had been perfect. Efficiency is the first divided by the second — it measures lineup
              decisions, not the roster.
            </p>
            <p>
              The letter beside efficiency is that team&apos;s rank in the league, from cutoffs
              set by hand rather than fitted to anything. {gradesEarlySentence(data.earlyThresholdWeeks)}
            </p>
          </HowThisWorks>
        </section>
      )}

      {tx && tx.available && <Transactions tx={tx} leagueId={leagueIdForSeason(tx.season)} />}
    </div>
  )
}

/**
 * The rest of ffwrapped's roster-management view: who moved, what they got,
 * and how it worked out. Sections of this page rather than a route of their
 * own, matching where ffwrapped puts them.
 */
function Transactions({ tx, leagueId }: { tx: LeagueTransactions; leagueId: string }) {
  const types = ['WAIVER', 'FREE_AGENT', 'TRADE', 'COMMISSIONER']
  const widest = Math.max(1, ...tx.byManager.map((m) => m.total))
  return (
    <>
      <section className="section">
        <h3 className="section-title">League transactions</h3>
        <ul className="tx-counts">
          {tx.byManager.map((m) => (
            <li key={m.teamName}>
              <span className="tx-name">{m.teamName}</span>
              <span className="tx-bar">
                {types.map((t) =>
                  m.counts[t] ? (
                    <span
                      key={t}
                      className={`tx-seg tx-${t.toLowerCase()}`}
                      style={{ width: `${(m.counts[t] / widest) * 100}%` }}
                    >
                      {/* The count is labelled on the segment, not left to a
                          legend and a length comparison. */}
                      {m.counts[t]}
                    </span>
                  ) : null,
                )}
              </span>
              <span className="tx-total">{m.total}</span>
            </li>
          ))}
        </ul>
        <p className="muted small tx-legend">
          <span className="tx-key tx-waiver" /> Waiver claims
          <span className="tx-key tx-free_agent" /> Free agents
          <span className="tx-key tx-trade" /> Trades
        </p>
      </section>

      <section className="section" id="trades">
        <h3 className="section-title">League trades</h3>
        {tx.trades.length === 0 ? (
          <p className="muted small">No trades have been made.</p>
        ) : (
          <>
            <ul className="tx-trades">
              {tx.trades.map((t) => (
                <TradeCard key={t.txId} trade={t} tx={tx} leagueId={leagueId} />
              ))}
            </ul>
            <p className="muted small tx-trades-note">
              Realized started points only; no projections or trade values.
            </p>
            {!tx.tradeAssetsLoaded && (
              <p className="muted small tx-trades-note">Picks and FAAB not loaded for this season yet</p>
            )}
            <p className="tx-trades-actions">
              <button type="button" className="tx-csv-btn" onClick={() => downloadTradesCsv(tx)}>
                Download CSV
              </button>
            </p>
            <HowThisWorks>
              <p>
                A trade is scored on the started points the players each side received scored for their
                new team after the trade took effect. Bench points are shown but not counted. FAAB and
                picks that have not been drafted yet cannot be scored.
              </p>
              {tx.reconciliation && <Reconciliation r={tx.reconciliation} />}
            </HowThisWorks>
          </>
        )}
      </section>

      <section className="section">
        <h3 className="section-title">Waivers and free agent adds</h3>
        <ul className="tx-adds">
          {tx.adds.map((a, i) => (
            <li key={i}>
              <span className="tx-week">Wk {a.week}</span>
              <span className="tx-added">
                {a.added.position && <em className="tx-pos">{a.added.position}</em>}
                <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={a.added.playerId} sport={tx.sport}>
                  {a.added.playerName}
                </PlayerLink>
              </span>
              <Rank player={a.added} />
              {a.dropped && (
                <span className="muted small tx-dropped">for{' '}
                  <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={a.dropped.playerId} sport={tx.sport}>
                    {a.dropped.playerName}
                  </PlayerLink>
                </span>
              )}
              <span className="tx-team muted">{a.teamName}</span>
              {a.faabBid != null && <span className="tx-faab">${a.faabBid} FAAB</span>}
            </li>
          ))}
        </ul>
        <HowThisWorks>
          <p>
            Rank is the player's average finish among others at his position since the move —{' '}
            <strong>lower is better</strong>, and the number of weeks it covers is shown beside it, so a
            single week is not mistaken for a season.
          </p>
        </HowThisWorks>
      </section>
    </>
  )
}

const weeksLabel = (n: number) => `${n} week${n === 1 ? '' : 's'}`

function verdictText(trade: LeagueTrade, seasonFinal: boolean): string {
  const v = trade.verdict
  switch (v.state) {
    case 'WINNER': {
      const w = trade.sides.find((s) => s.rosterId === v.winnerRosterId)
      return `${w?.teamName ?? 'One side'} +${(v.margin ?? 0).toFixed(1)} started points over ${weeksLabel(v.weeksCounted)}${seasonFinal ? '' : ' so far'}`
    }
    case 'EVEN':
      return trade.sides.every((s) => s.startedTotal === 0) ? 'Even: neither side started what it got' : 'Even'
    case 'NO_WEEK_YET':
      return 'No week played yet'
    case 'UNDONE':
      return 'Undone before a scored week'
    case 'NONE_COUNTED':
      return 'Neither side got a counted week from this trade'
    case 'NOT_COMPARABLE':
      return 'Not comparable by points'
    case 'RESTORED':
      return 'Restored an earlier trade before a scored week; no player changed hands'
  }
}

const STATUS_LABEL: Partial<Record<TradeAssetStatus, string>> = {
  NO_WEEK_YET: 'no week yet',
  UNDONE: 'undone',
  LEFT_BEFORE_PLAYING: 'left before playing',
  NEVER_PLAYED_FOR_THEM: 'never played for them',
  ALREADY_HELD: 'already theirs',
}

const pts = (n: number) => n.toFixed(1)

function PlayerChip({ p, tx, leagueId }: { p: TradePlayerAsset; tx: LeagueTransactions; leagueId: string }) {
  return (
    <span className="tx-chip tx-chip-player">
      <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={p.playerId} sport={tx.sport}>
        {p.playerName}
      </PlayerLink>
      {p.status === 'COUNTED' ? (
        <span className="tx-chip-note">{pts(p.startedPoints)} started</span>
      ) : (
        <span className="tx-chip-note tx-chip-status">{STATUS_LABEL[p.status] ?? ''}</span>
      )}
    </span>
  )
}

function TradeCard({ trade, tx, leagueId }: { trade: LeagueTrade; tx: LeagueTransactions; leagueId: string }) {
  const teamOf = (rosterId: number) =>
    trade.sides.find((s) => s.rosterId === rosterId)?.teamName ?? `Roster ${rosterId}`
  const v = trade.verdict
  return (
    <li className="tx-tcard">
      <div className="tx-thead">
        <span className="tx-week">{easternDate(trade.acceptedAt)}</span>
        <span className={`tx-verdict tx-verdict-${v.state.toLowerCase()}`}>{verdictText(trade, tx.seasonFinal)}</span>
        {!trade.assetsLoaded && <span className="tx-chip-note">picks and FAAB not loaded</span>}
      </div>
      <div className="tx-sides">
        {trade.sides.map((side) => (
          <div
            key={side.rosterId}
            className={`tx-tside${v.state === 'WINNER' && v.winnerRosterId === side.rosterId ? ' tx-tside-win' : ''}`}
          >
            <div className="tx-tside-head">
              <strong className="tx-tside-name">{side.teamName}</strong>
              <span className="tx-tside-totals muted small">
                started {pts(side.startedTotal)} · bench {pts(side.benchTotal)}
              </span>
            </div>
            <div className="tx-chips">
              {side.assets.map((a, i) => {
                if (a.kind === 'PLAYER') return <PlayerChip key={`p${a.playerId}`} p={a} tx={tx} leagueId={leagueId} />
                if (a.kind === 'FAAB')
                  return (
                    <span key={`f${i}`} className="tx-chip tx-chip-faab">
                      ${a.amount} FAAB from {a.fromTeamName}
                    </span>
                  )
                return (
                  <span key={`k${i}`} className="tx-chip tx-chip-pick">
                    <span>
                      {a.season} R{a.round} (from {teamOf(a.originalRosterId)})
                    </span>
                    {a.player ? (
                      <span className="tx-chip-note">
                        →{' '}
                        <span>
                          <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={a.player.playerId} sport={tx.sport}>
                            {a.player.playerName}
                          </PlayerLink>
                        </span>
                        {' · '}
                        <span>
                          {a.player.status === 'COUNTED'
                            ? `${pts(a.player.startedPoints)} started`
                            : (STATUS_LABEL[a.player.status] ?? '')}
                        </span>
                      </span>
                    ) : (
                      <span className="tx-chip-note tx-chip-status">
                        {a.status === 'PICK_NOT_MATCHED' ? 'pick not matched' : 'not drafted yet'}
                      </span>
                    )}
                  </span>
                )
              })}
            </div>
          </div>
        ))}
      </div>
      <HowCounted trade={trade} />
    </li>
  )
}

const HOW_LABEL: Record<TradeStretchHow, string> = {
  DRAFT: 'drafted',
  TRADE: 'trade',
  WAIVER: 'waiver',
  FREE_AGENT: 'free agent',
  COMMISSIONER: 'commissioner',
  UNEXPLAINED: 'unexplained',
}

const weekRange = (a: number, b: number) => (a === b ? `wk ${a}` : `wk ${a}–${b}`)

/**
 * The evidence behind a verdict: each traded player's whole season, stretch by
 * stretch. A stretch on the receiving side's roster is highlighted, because
 * only those weeks are counted.
 */
function HowCounted({ trade }: { trade: LeagueTrade }) {
  const entries: { rosterId: number; player: TradePlayerAsset }[] = []
  for (const side of trade.sides)
    for (const a of side.assets) {
      if (a.kind === 'PLAYER') entries.push({ rosterId: side.rosterId, player: a })
      else if (a.kind === 'PICK' && a.player) entries.push({ rosterId: side.rosterId, player: a.player })
    }
  if (entries.length === 0) return null
  return (
    <details className="tx-how">
      <summary>How this was counted</summary>
      {entries.map(({ rosterId, player }) => (
        <div key={`${rosterId}-${player.playerId}`} className="tx-how-player">
          <strong>{player.playerName}</strong>
          {player.stretches.length === 0 ? (
            <p className="muted small">No roster history recorded.</p>
          ) : (
            <ul className="tx-stretches">
              {player.stretches.map((st, i) => {
                // THIS trade's receiving stretch: on the receiving roster and containing the
                // first counted week. An earlier stint on the same roster is not it.
                const recv =
                  st.rosterId === rosterId &&
                  player.firstWeek != null &&
                  st.firstWeek <= player.firstWeek &&
                  player.firstWeek <= st.lastWeek
                return (
                <li key={i} className={recv ? 'tx-stretch tx-stretch-recv' : 'tx-stretch'}>
                  <span>{st.teamName}</span>
                  <span>{weekRange(st.firstWeek, st.lastWeek)}</span>
                  <span className="muted">{HOW_LABEL[st.how]}</span>
                  <span className="tx-chip-note">
                    started {pts(st.startedPoints)} · bench {pts(st.benchPoints)}
                  </span>
                  {st.duplicateWeeks.length > 0 && (
                    <span className="tx-chip-note tx-chip-status">
                      also listed on another roster in {st.duplicateWeeks.map((w) => `wk ${w}`).join(', ')}
                    </span>
                  )}
                  {recv && st.leadingUnstarted && (
                    <span className="tx-chip-note tx-chip-status">
                      listed before the trade took effect, not counted
                    </span>
                  )}
                </li>
                )
              })}
            </ul>
          )}
        </div>
      ))}
    </details>
  )
}

function downloadTradesCsv(tx: LeagueTransactions) {
  const blob = new Blob([tradeCsv(tx)], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `trades-${tx.sport}-${tx.season}.csv`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

function Reconciliation({ r }: { r: TradeReconciliation }) {
  return (
    <>
      <p>
        {r.playerWeeksChecked} player-weeks checked against the league&apos;s transactions.{' '}
        {r.unexplainedStretches} roster changes had no recorded move. {r.sameWeekInAndOut} pickups were added
        and dropped within the same week. {r.neverOnAScoredRoster} adds never appeared on a scored roster
        {r.examples.length > 0 ? ':' : '.'}
      </p>
      {r.examples.length > 0 && (
        <ul className="small">
          {r.examples.map((e, i) => (
            <li key={i}>{e}</li>
          ))}
        </ul>
      )}
    </>
  )
}

function Rank({ player }: { player: MovedPlayer }) {
  if (player.postMovePositionalRank == null) {
    return (
      <span className="muted small tx-rank" title="No week has been played since this move">
        ungraded
      </span>
    )
  }
  return (
    <span
      className="tx-rank"
      title={`Average finish among ${player.position ?? 'his position'} since the move. Lower is better.`}
    >
      {player.position ?? ''}
      {player.postMovePositionalRank.toFixed(1)}
      <span className="muted"> avg over {player.weeksCounted} wk</span>
    </span>
  )
}

function Row({
  team,
  hue,
  scale,
  bigGap,
}: {
  team: RosterManagementTeam
  hue: number
  scale: number
  bigGap: boolean
}) {
  const actualPct = (team.totalPoints / scale) * 100
  const potentialPct = (team.potentialPoints / scale) * 100
  const left = team.potentialPoints - team.totalPoints

  return (
    <tr>
      <th scope="row" className="rm-team">
        <Avatar
          avatarId={team.avatarId}
          seed={String(team.managerId ?? team.rosterId)}
          label={team.teamName}
          hue={hue}
          className="rm-avatar"
        />
        <span className="rm-name"><PersonName teamName={team.teamName} username={team.username} /></span>
      </th>
      <td className="rm-num">{team.totalPoints.toFixed(2)}</td>
      <td className="rm-num">{team.potentialPoints.toFixed(2)}</td>
      <td className="rm-num">
        {/* null means there was no potential to divide by. Printing 100% for a
            team that has not scored would be the most flattering possible
            wrong answer, so it prints nothing and says why on hover. */}
        {team.efficiency == null ? (
          <span className="muted" title="No scored week with a per-player breakdown yet">
            —
          </span>
        ) : (
          <GradeChip grade={team.grade}>
            {`${(team.efficiency * 100).toFixed(1)}%`}
          </GradeChip>
        )}
      </td>
      <td className="rm-bars">
        <div className="rm-track">
          <div className="rm-bar rm-bar-potential" style={{ width: `${potentialPct}%` }} />
          <div className="rm-bar rm-bar-actual" style={{ width: `${actualPct}%` }} />
        </div>
        <span className={`rm-left${bigGap ? ' rm-left-big' : ''}`}>
          {left <= 0.005 ? 'perfect' : `${left.toFixed(2)} left on the bench`}
        </span>
      </td>
    </tr>
  )
}

/**
 * A week with no per-player breakdown is excluded from potential, not counted
 * as zero -- so the reader has to be told, or a short season silently reads as
 * a full one.
 */
function ExcludedNote({ teams }: { teams: RosterManagementTeam[] }) {
  const affected = teams.filter((t) => t.weeksExcluded.length > 0)
  if (affected.length === 0) return null
  const weeks = [...new Set(affected.flatMap((t) => t.weeksExcluded))].sort((a, b) => a - b)
  return (
    <p className="muted small rm-excluded">
      Week{weeks.length === 1 ? '' : 's'} {weeks.join(', ')} had no per-player scoring stored for{' '}
      {affected.length} team{affected.length === 1 ? '' : 's'}, so {weeks.length === 1 ? 'it is' : 'they are'}{' '}
      left out of potential rather than counted as zero. Efficiency for those teams covers the weeks
      that remain.
    </p>
  )
}
