import { useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import NotFound from '../components/NotFound'
import PlayerLink from '../components/PlayerLink'
import { getPlayerTrends, type PlayerTrends as Trends, type PlayerTrendsReason, type TrendRow } from '../api'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { useSeasonLeagueIds } from '../railLeague'
import { useBlock } from '../useBlock'
import { fallbackLabel, oneGameNote, rolesWindowLabel, streamingReasonSentence } from '../playerTrends'

/**
 * specs/019-minutes-streaming US2: who is playing more or fewer minutes, and
 * which players nobody owns are worth streaming. Streaming comes first.
 * Every number names its window (from `windows`), and each table scrolls
 * inside its own card so the page never scrolls sideways at phone width.
 */

const REASON_COPY: Record<PlayerTrendsReason, string> = {
  NOT_BASKETBALL: 'Trends are for basketball leagues.',
  NO_GAMES: 'No games have been played yet, so there are no minutes to compare.',
  NOT_CONFIGURED: 'Trends aren’t set up on this server.',
}

const fmt = (n: number | null, digits = 1) => (n == null ? '–' : n.toFixed(digits))

function DeltaPill({ delta }: { delta: number | null }) {
  if (delta == null) return null
  const cls = delta > 0 ? 'pt-up' : delta < 0 ? 'pt-down' : 'pt-flat'
  const sign = delta > 0 ? '+' : delta < 0 ? '−' : ''
  return <span className={`pt-pill ${cls}`}>{`${sign}${Math.abs(delta).toFixed(1)} min`}</span>
}

function PlayerCell({ row, d, leagueId }: { row: TrendRow; d: Trends; leagueId: string }) {
  return (
    <td>
      <span className="pt-name">
        <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={row.sleeperPlayerId} sport={d.sport}>
          {row.name}
        </PlayerLink>
      </span> <span className="muted small">{row.team ?? ''}</span>
      <span className="pt-meta">{row.positions.join('/')}</span>
    </td>
  )
}

function MinutesCell({ row }: { row: TrendRow }) {
  return (
    <td>
      <span className="pt-num">{fmt(row.recentMin)}</span> recent · {fmt(row.seasonMin)} season
      <DeltaPill delta={row.minDelta} />
      {row.ptsPerMin != null && <span className="pt-meta">{`${row.ptsPerMin.toFixed(2)} pts/min, season`}</span>}
    </td>
  )
}

function StreamingTable({ rows, d, leagueId }: { rows: TrendRow[]; d: Trends; leagueId: string }) {
  return (
    <div className="pt-wrap">
      <table className="pt-table">
        <thead>
          <tr>
            <th scope="col">Player</th>
            <th scope="col">Season avg (pts per game)</th>
            <th scope="col">{`Last ${d.windows.formGames} (pts per game)`}</th>
            <th scope="col">{`Minutes (last ${d.windows.recentGames} games · season)`}</th>
            <th scope="col">Games this week (incl. played) / next</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.sleeperPlayerId}>
              <PlayerCell row={r} d={d} leagueId={leagueId} />
              <td className="pt-num">{fmt(r.seasonPts)}</td>
              <td>{fmt(r.formPts)}</td>
              <MinutesCell row={r} />
              <td>
                {r.gamesThisWeek ?? '–'} / {r.gamesNextWeek ?? '–'}
                {r.missedTeamGames > 0 && (
                  <span className="pt-meta pt-missed">{`Missed last ${r.missedTeamGames} team games`}</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function RoleTable({ rows, d, showOwner, leagueId }: { rows: TrendRow[]; d: Trends; showOwner: boolean; leagueId: string }) {
  return (
    <div className="pt-wrap">
      <table className="pt-table">
        <thead>
          <tr>
            <th scope="col">Player</th>
            <th scope="col">{`Minutes (last ${d.windows.recentGames} games · season)`}</th>
            <th scope="col">Usage rate (pooled over the window)</th>
            {showOwner && <th scope="col">Rostered by</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.sleeperPlayerId}>
              <PlayerCell row={r} d={d} leagueId={leagueId} />
              <MinutesCell row={r} />
              <td>
                {r.recentUsg == null ? '–' : `${fmt(r.recentUsg)}%`} recent ·{' '}
                {r.seasonUsg == null ? '–' : `${fmt(r.seasonUsg)}%`} season
              </td>
              {showOwner && <td>{r.rosteredBy ?? '–'}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function MoreLine({ shown, total }: { shown: number; total: number }) {
  return total > shown ? <p className="muted small">{`+${total - shown} more`}</p> : null
}

type RoleTotals = { total: number; freeAgents: number; rostered: number }

/** One direction (risers or fallers), split by ownership only when it is known. */
function RoleList({ title, rows, totals, split, d, leagueId }: { title: string; rows: TrendRow[]; totals: RoleTotals; split: boolean; d: Trends; leagueId: string }) {
  const parts = [
    { label: 'Free agents', rows: rows.filter((r) => r.rostered === false), owner: false, total: totals.freeAgents },
    { label: 'Rostered', rows: rows.filter((r) => r.rostered === true), owner: true, total: totals.rostered },
  ]
  return (
    <div className="pt-list">
      <h3>{title}</h3>
      {rows.length === 0 && !split && <p className="muted small">None in this window.</p>}
      {split ? (
        parts.map((p) => (
          <div key={p.label}>
            <p className="pt-sub muted">{p.label}</p>
            {p.rows.length === 0 ? (
              <p className="muted small">None.</p>
            ) : (
              <RoleTable rows={p.rows} d={d} showOwner={p.owner} leagueId={leagueId} />
            )}
            <MoreLine shown={p.rows.length} total={p.total} />
          </div>
        ))
      ) : (
        <>
          {rows.length > 0 && <RoleTable rows={rows} d={d} showOwner={false} leagueId={leagueId} />}
          <MoreLine shown={rows.length} total={totals.total} />
        </>
      )}
    </div>
  )
}

const shortDate = (iso: string) =>
  new Date(iso.length === 10 ? `${iso}T12:00:00` : iso).toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  })

export default function PlayerTrends() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const id = sleeperLeagueId ?? ''
  const version = useLeagueDataVersion(sleeperLeagueId)
  const leagueIdForSeason = useSeasonLeagueIds(sleeperLeagueId)
  const block = useBlock(id ? () => getPlayerTrends(id) : null, [id, version])

  if (block.status === 'error' && block.notFound) return <NotFound what="league" />

  const d = block.status === 'ok' ? block.data : null
  const note = d ? oneGameNote(d.oneGameCredit) : null
  // The streaming and the risers/fallers tables can come from different seasons (each has its own
  // fallback), so each links to the league of the season its rows were measured in.
  const streamingLeagueId = leagueIdForSeason(d?.streamingSeason ?? d?.season)
  const rolesLeagueId = leagueIdForSeason(d?.rolesSeason ?? d?.season)
  const ownershipKnown = d != null && d.streamingReason == null
  const endOfSeason = d != null && (d.rolesFallback || d.streamingReason === 'SEASON_COMPLETE')

  return (
    <div className="content">
      <PageHeader
        eyebrow={d ? `League · ${d.season}` : 'League'}
        title="Trends"
        sub="Who is getting more or fewer minutes, and who is worth streaming."
      />

      {block.status === 'loading' && <p className="muted small">Loading…</p>}
      {block.status === 'error' && <p className="muted small">Couldn't load trends.</p>}

      {d && !d.available && (
        <section className="section">
          <p className="muted small">{d.reason ? REASON_COPY[d.reason] : 'Trends aren’t available.'}</p>
        </section>
      )}

      {d && d.available && (
        <>
          <section className="section pt-section" aria-label="Streaming candidates">
            <h2>Streaming candidates</h2>
            {d.streamingSeason != null && (
              <p className="muted small pt-window">
                {d.streamingFallback
                  ? fallbackLabel(d.season, d.streamingSeason, d.windows.formMinGames)
                  : `${d.streamingSeason} season`}
              </p>
            )}
            {d.streamingReason ? (
              <p className="muted small">{streamingReasonSentence(d.streamingReason)}</p>
            ) : (
              <>
                {note && <p className="small pt-note">{note}</p>}
                <p className="muted small pt-window">
                  Players not on a roster (includes players on waivers), best season average first.
                  {d.rostersFetchedAt ? ` Rosters as of ${shortDate(d.rostersFetchedAt)}.` : ''}
                </p>
                {d.streaming.length === 0 ? (
                  <p className="muted small">No unrostered players qualify.</p>
                ) : (
                  <StreamingTable rows={d.streaming} d={d} leagueId={streamingLeagueId} />
                )}
              </>
            )}
          </section>

          <section className="section pt-section" aria-label="Risers and fallers">
            <h2>Minutes risers and fallers</h2>
            <p className="muted small pt-window">
              {d.rolesFallback && d.rolesSeason != null
                ? `${fallbackLabel(d.season, d.rolesSeason, d.windows.minSeasonGames)}; `
                : ''}
              {rolesWindowLabel(endOfSeason, d.rolesSeason, d.season, d.windows.recentGames)}. A change of{' '}
              {d.roleThresholdMinutes ?? '–'} minutes or more counts.
            </p>
            {!ownershipKnown && (
              <p className="muted small pt-window">
                Who owns these players isn’t known for this season, so the lists aren’t split into free agents and
                rostered.
              </p>
            )}
            <div className="pt-lists">
              <RoleList
                title="Risers"
                rows={d.risers}
                totals={{ total: d.risersTotal, freeAgents: d.risersFreeAgentTotal, rostered: d.risersRosteredTotal }}
                split={ownershipKnown}
                d={d}
                leagueId={rolesLeagueId}
              />
              <RoleList
                title="Fallers"
                rows={d.fallers}
                totals={{ total: d.fallersTotal, freeAgents: d.fallersFreeAgentTotal, rostered: d.fallersRosteredTotal }}
                split={ownershipKnown}
                d={d}
                leagueId={rolesLeagueId}
              />
            </div>
            <p className="muted small">
              {`${d.excludedStale} not shown: no game in the ${d.windows.recencyDays} days before ${
                d.staleReferenceDate ? shortDate(d.staleReferenceDate) : 'the last game'
              }. ${d.excludedNoTeam} not shown: no NBA team.`}
            </p>
          </section>
        </>
      )}
    </div>
  )
}
