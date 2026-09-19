import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import Avatar from '../components/Avatar'
import { getSeasonForecast, type SeasonForecast as Data, type ForecastTeam } from '../api'
import { hueForIndex } from '../hue'
import SeasonFallbackNote from '../components/SeasonFallbackNote'

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
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

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
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [sleeperLeagueId])

  const maxWins = Math.max(
    1,
    ...(data?.teams ?? []).map((t) => t.winRange.p90 ?? t.averageWins),
  )

  return (
    <div className="content">
      <PageHeader
        eyebrow="League"
        title="Season forecast"
        sub="The rest of the schedule, simulated from every team's scoring so far. These are the odds as of the last recompute — the page reads a stored simulation rather than running a new one."
      />

      {error && (
        <div className="error">
          <span>{error.includes('404') ? "This league hasn't been loaded yet." : error}</span>
        </div>
      )}

      {loading && !data && <p className="muted small">Loading…</p>}

      {data && !data.available && (
        <Refusal
          reason={data.reason ?? null}
          season={data.season}
          requestedSeason={data.requestedSeason}
        />
      )}

      {data && data.available && (
        <section className="panel">
          <h3 className="cond">
            Through week {data.week} · {(data.iterations ?? 0).toLocaleString()} simulated seasons
          </h3>
          <SeasonFallbackNote season={data.season} requestedSeason={data.requestedSeason} />

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
            Odds come from the snapshot taken at the last commissioner recompute, so they match the
            playoff odds shown on the power rankings exactly. A range is the middle 80% of simulated
            outcomes — a wide one means the schedule still decides this team's season.
          </p>
        </section>
      )}
    </div>
  )
}

/** Different refusals need different words; they are not interchangeable. */
function Refusal({
  reason,
  season,
  requestedSeason,
}: {
  reason: string | null
  season: number
  requestedSeason?: number | null
}) {
  const body =
    reason === 'UNMODELLED_SEEDING'
      ? "This league seeds its playoffs in a way this app doesn't model — divisions, or a non-default seeding rule. Rather than show odds computed under the wrong bracket, it shows none."
      : reason === 'NOT_COMPUTED'
        ? 'This season has been played, but no odds have been computed for it yet. A commissioner recompute produces them — the page reads a stored simulation rather than running one on load.'
        : 'No week has been scored yet, so there is nothing to project from. Odds appear once the first week is final.'
  return (
    <section className="panel">
      <h3 className="cond">No forecast for this league</h3>
      <SeasonFallbackNote season={season} requestedSeason={requestedSeason} />
      <p className="muted small">{body}</p>
    </section>
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
        <span className="sf-name">{team.teamName}</span>
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
