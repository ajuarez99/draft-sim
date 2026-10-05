import { useEffect, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import HowThisWorks from '../components/HowThisWorks'
import Avatar from '../components/Avatar'
import { computePowerRankings, getSeasonForecast, type SeasonForecast as Data, type ForecastTeam } from '../api'
import CommissionerHonourNote from '../components/CommissionerHonourNote'
import { useFailure } from '../useFailure'
import NotFound from '../components/NotFound'
import PersonName from '../components/PersonName'
import { hueForIndex } from '../hue'
import SeasonFallbackNote from '../components/SeasonFallbackNote'
import { useLeagueDataVersion } from '../leagueDataVersion'

/**
 * specs/004-ffwrapped-feature-parity US4: where the season is heading.
 *
 * Every number here is read off ONE stored simulation snapshot. Nothing is
 * computed on load, which is both the existing rule and the reason this page
 * and the playoff-odds figure in the Record cell cannot disagree.
 *
 * The win range is drawn as a span rather than reported as two more columns:
 * the interesting thing about "8.0–12.0" is its width, and a pair of numbers in
 * separate cells hides that. The exact endpoints still sit beside the span.
 */
export default function SeasonForecast() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [data, setData] = useState<Data | null>(null)
  const { error, notFound, setError, fail } = useFailure()
  const [loading, setLoading] = useState(false)
  // Bumped after a commissioner recompute so the page re-reads the new snapshot.
  const [reloads, setReloads] = useState(0)
  const [computing, setComputing] = useState(false)
  // Bumped by the rail when this league's background refresh finishes (specs/009-auto-data-refresh).
  const dataVersion = useLeagueDataVersion(sleeperLeagueId)

  useEffect(() => {
    if (!sleeperLeagueId) return
    let cancelled = false
    setLoading(true)
    setError(null)
    getSeasonForecast(sleeperLeagueId)
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
  }, [sleeperLeagueId, dataVersion, reloads])

  const maxWins = Math.max(
    1,
    ...(data?.teams ?? []).map((t) => t.winRange.p90 ?? t.averageWins),
  )

  async function recompute(season: number, throughWeek: number) {
    if (!sleeperLeagueId) return
    setComputing(true)
    setError(null)
    try {
      await computePowerRankings(sleeperLeagueId, season, throughWeek)
      setReloads((n) => n + 1)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setComputing(false)
    }
  }

  if (notFound) return <NotFound what="league" />

  return (
    <div className="content">
      <PageHeader
        eyebrow={data ? `League · ${data.season}` : 'League'}
        title="Playoff odds"
        sub="Every team's playoff chances and projected wins, simulated from how everyone has scored so far."
      />

      {error && (
        <div className="error">
          <span>{error}</span>
        </div>
      )}

      {loading && !data && <p className="muted small">Loading…</p>}

      {data && !data.available && (
        <Refusal
          reason={data.reason ?? null}
          season={data.season}
          requestedSeason={data.requestedSeason}
          // Never offer a recompute for a finished season: there is nothing left
          // to forecast, so the button would contradict the season-over line.
          notice={
            data.seasonComplete ? (
              <SeasonOver season={data.season} sleeperLeagueId={sleeperLeagueId} />
            ) : data.reason === 'NOT_COMPUTED' && data.latestFinalWeek > 0 ? (
              <RecomputeControl
                data={data}
                computing={computing}
                onRecompute={recompute}
                label={`Recompute through week ${data.latestFinalWeek}`}
              />
            ) : null
          }
        />
      )}

      {data && data.available && data.seasonComplete && (
        <section className="section">
          <h3 className="section-title">Nothing left to forecast</h3>
          <SeasonFallbackNote season={data.season} requestedSeason={data.requestedSeason} />
          <SeasonOver season={data.season} sleeperLeagueId={sleeperLeagueId} />
        </section>
      )}

      {data && data.available && !data.seasonComplete && (
        <section className="section">
          <h3 className="section-title">
            Through week {data.week} · {(data.iterations ?? 0).toLocaleString()} simulated seasons
          </h3>
          <SeasonFallbackNote season={data.season} requestedSeason={data.requestedSeason} />
          <StaleNotice data={data} computing={computing} onRecompute={recompute} />

          <table className="sf-table">
            <thead>
              <tr>
                <th scope="col">Team</th>
                <th scope="col" className="sf-num">Playoffs</th>
                <th scope="col" className="sf-num">Avg. wins</th>
                <th scope="col" className="sf-rangehead">Win range, 10th–90th percentile</th>
                <th scope="col" className="sf-num">Avg. seed</th>
                <th scope="col" className="sf-num">No. 1</th>
              </tr>
            </thead>
            <tbody>
              {data.teams.map((t, i) => (
                <Row key={t.rosterId} team={t} hue={hueForIndex(i, data.teams.length)} maxWins={maxWins} />
              ))}
            </tbody>
          </table>

          <p className="muted small sf-note">
            A range is the middle 80% of simulated outcomes — a wide one means the schedule still
            decides this team's season.
          </p>

          <HowThisWorks>
            <p>
              The rest of the schedule is simulated from every team's scoring so far. These are the
              odds as of the last update: the page reads a stored simulation rather than running a new
              one.
            </p>
            <p>
              Odds come from the snapshot taken at the last commissioner recompute, so they match the
              playoff odds shown on the power rankings exactly.
            </p>
          </HowThisWorks>
        </section>
      )}
    </div>
  )
}

/** The finished-season line, shared by the available panel and a refusal on a complete season. */
function SeasonOver({ season, sleeperLeagueId }: { season: number; sleeperLeagueId?: string }) {
  return (
    <>
      <p>The {season} season is over, so there is nothing left to forecast.</p>
      <p className="muted small">
        How it ended — the champion and the final standings — is in{' '}
        <Link to={`/leagues/${sleeperLeagueId}/history`}>History</Link>.
      </p>
    </>
  )
}

/** Different refusals need different words; they are not interchangeable. */
function Refusal({
  reason,
  season,
  requestedSeason,
  notice,
}: {
  reason: string | null
  season: number
  requestedSeason?: number | null
  notice?: ReactNode
}) {
  const body =
    reason === 'UNMODELLED_SEEDING'
      ? "This league seeds its playoffs in a way this app doesn't model — divisions, or a non-default seeding rule. Rather than show odds computed under the wrong bracket, it shows none."
      : reason === 'NOT_COMPUTED'
        ? 'Odds appear when the commissioner updates them.'
        : 'No week has been scored yet, so there is nothing to project from. Odds appear once the first week is final.'
  return (
    <section className="section">
      <h3 className="section-title">No forecast for this league</h3>
      <SeasonFallbackNote season={season} requestedSeason={requestedSeason} />
      <p className="muted small">{body}</p>
      {notice}
      {reason === 'NOT_COMPUTED' && (
        <HowThisWorks>
          <p>
            This season has been played, but no odds have been computed for it yet. A commissioner
            recompute produces them — the page reads a stored simulation rather than running one on
            load.
          </p>
        </HowThisWorks>
      )}
    </section>
  )
}

/**
 * The stored snapshot is only rewritten by a commissioner recompute, never on a
 * page load (claude/playoff-odds.md), so it can trail the league. Say by how much.
 * Nothing here recomputes; the button is the commissioner's own explicit action.
 */
function StaleNotice({
  data,
  computing,
  onRecompute,
}: {
  data: Data
  computing: boolean
  onRecompute: (season: number, throughWeek: number) => void
}) {
  const snapshotWeek = data.week ?? 0
  const behind = data.latestFinalWeek - snapshotWeek
  if (behind <= 0) return null
  return (
    <div className="sf-stale" role="status">
      <p className="small">
        {behind} {behind === 1 ? 'week' : 'weeks'} scored since this forecast. The odds below are as
        of week {snapshotWeek}.
      </p>
      <RecomputeControl
        data={data}
        computing={computing}
        onRecompute={onRecompute}
        label={`Recompute through week ${data.latestFinalWeek}`}
      />
    </div>
  )
}

/**
 * Only for a commissioner, and only on the season the page was asked for: a fallback
 * season is a different league row than the one the recompute route would act on.
 */
function RecomputeControl({
  data,
  computing,
  onRecompute,
  label,
}: {
  data: Data
  computing: boolean
  onRecompute: (season: number, throughWeek: number) => void
  label: string
}) {
  const onRequestedSeason = data.requestedSeason == null || data.requestedSeason === data.season
  if (!data.canCommission || !onRequestedSeason) return null
  return (
    <p className="small">
      <button
        type="button"
        className="action-button"
        disabled={computing}
        onClick={() => onRecompute(data.season, data.latestFinalWeek)}
      >
        {computing ? 'Computing…' : label}
      </button>{' '}
      <CommissionerHonourNote />
    </p>
  )
}

function Row({ team, hue, maxWins }: { team: ForecastTeam; hue: number; maxWins: number }) {
  const { p10, p90 } = team.winRange
  const hasRange = p10 != null && p90 != null
  const left = hasRange ? (p10 / maxWins) * 100 : 0
  const width = hasRange ? Math.max(1.5, ((p90 - p10) / maxWins) * 100) : 0
  const meanPos = (team.averageWins / maxWins) * 100

  return (
    <tr>
      <th scope="row" className="sf-team">
        <Avatar
          avatarId={team.avatarId}
          seed={String(team.managerId ?? team.rosterId)}
          label={team.teamName}
          hue={hue}
          className="sf-avatar"
        />
        <span className="sf-name"><PersonName teamName={team.teamName} username={team.username} /></span>
      </th>
      <td className="sf-num sf-odds">{(team.playoffOdds).toFixed(1)}%</td>
      <td className="sf-num">{team.averageWins.toFixed(2)}</td>
      <td className="sf-range">
        {hasRange ? (
          <>
            <div className="sf-track">
              <div className="sf-span" style={{ left: `${left}%`, width: `${width}%`, ['--hue' as string]: hue }} />
              <div className="sf-mean" style={{ left: `${meanPos}%` }} />
            </div>
            <span className="sf-range-label">
              {p10.toFixed(0)}–{p90.toFixed(0)}
            </span>
          </>
        ) : (
          <span className="muted small">no distribution stored</span>
        )}
      </td>
      <td className="sf-num">{team.averageSeed == null ? '—' : `#${team.averageSeed.toFixed(1)}`}</td>
      <td className="sf-num">{team.seedOnePct.toFixed(1)}%</td>
    </tr>
  )
}
