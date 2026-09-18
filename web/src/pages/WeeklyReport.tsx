import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import Avatar from '../components/Avatar'
import SeasonFallbackNote from '../components/SeasonFallbackNote'
import {
  getWeeklyReport,
  type WeeklyReport as Data,
  type WeeklyMatchup,
  type WeeklySide,
} from '../api'

/**
 * specs/004-ffwrapped-feature-parity US5: one week, read back.
 *
 * Matchups are cards rather than table rows on purpose — a matchup is a pair
 * facing each other, not a row of columns, and the layout should look like the
 * thing it describes. The scoreboard and the awards are different shapes of
 * content and get different shapes of container.
 *
 * Awards that could not be computed are RENDERED, not dropped. An award that
 * simply fails to appear is indistinguishable from one where nobody qualified,
 * and only one of those is worth knowing about.
 */
export default function WeeklyReport() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [week, setWeek] = useState(1)
  const [data, setData] = useState<Data | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!sleeperLeagueId) return
    let cancelled = false
    setLoading(true)
    setError(null)
    getWeeklyReport(sleeperLeagueId, week)
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
  }, [sleeperLeagueId, week])

  return (
    <div className="content">
      <PageHeader
        eyebrow="League"
        title="Weekly report"
        sub="Every matchup, the week's best performances, and the awards nobody wants — read back from what actually happened."
        actions={
          <div className="wr-weekpick">
            <label htmlFor="wr-week">Week</label>
            <input
              id="wr-week"
              type="number"
              min={1}
              max={18}
              value={week}
              onChange={(e) => setWeek(Math.max(1, Number(e.target.value) || 1))}
            />
          </div>
        }
      />

      {error && (
        <div className="error">
          <span>{error.includes('404') ? "This league hasn't been loaded yet." : error}</span>
        </div>
      )}

      {loading && !data && <p className="muted small">Loading…</p>}

      {data && !data.available && (
        <section className="panel">
          <h3 className="cond">Week {data.week} has not been scored</h3>
          <p className="muted small">
            {data.reason ?? 'No results stored for this week yet.'}
          </p>
        </section>
      )}

      {data && data.available && (
        <>
          <section className="panel">
            <h3 className="cond">Week {data.week} matchups</h3>
            <SeasonFallbackNote season={data.season} requestedSeason={data.requestedSeason} />
            <div className="wr-games">
              {data.matchups.map((m, i) => (
                <Game key={i} game={m} />
              ))}
            </div>
          </section>

          <section className="panel">
            <h3 className="cond">Weekly awards</h3>
            {data.awards.length === 0 ? (
              <p className="muted small">Nobody qualified for an award this week.</p>
            ) : (
              <div className="wr-awards">
                {data.awards.map((a) => (
                  <article key={a.kind} className="wr-award">
                    <h4>{titleOf(a.kind)}</h4>
                    <p className="wr-award-team">{a.teamName}</p>
                    <p className="muted small">{a.detail}</p>
                  </article>
                ))}
              </div>
            )}

            {data.awardsOmitted.length > 0 && (
              <ul className="muted small wr-omitted">
                {data.awardsOmitted.map((o) => (
                  <li key={o.kind}>
                    <strong>{titleOf(o.kind)}</strong> could not be worked out for this week:{' '}
                    {o.reason === 'STARTERS_NOT_STORED'
                      ? 'this week was stored before the app recorded which players were actually started, so naming the swap would be a guess.'
                      : o.reason}
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="panel">
            <h3 className="cond">Top performers</h3>
            <ol className="wr-performers">
              {data.topPerformers.map((p) => (
                <li key={p.playerId}>
                  <span className="wr-pname">{p.playerName}</span>
                  <span className="wr-ppos">{p.position}</span>
                  <span className="wr-pteam muted">{p.teamName}</span>
                  <span className="wr-ppts">{p.points.toFixed(2)}</span>
                </li>
              ))}
            </ol>
          </section>
        </>
      )}
    </div>
  )
}

function Game({ game }: { game: WeeklyMatchup }) {
  const homeWon = game.home.points > game.away.points
  const awayWon = game.away.points > game.home.points
  return (
    <article className="wr-game">
      <TeamLine side={game.home} won={homeWon} />
      <TeamLine side={game.away} won={awayWon} />
    </article>
  )
}

function TeamLine({ side, won }: { side: WeeklySide; won: boolean }) {
  return (
    <div className={won ? 'wr-side wr-won' : 'wr-side'}>
      <Avatar
        avatarId={side.avatarId}
        seed={String(side.rosterId)}
        label={side.teamName}
        className="wr-avatar"
      />
      <span className="wr-team">{side.teamName}</span>
      <span className="wr-record muted">({side.record})</span>
      <span className="wr-points">{side.points.toFixed(2)}</span>
    </div>
  )
}

/** SCREAMING_SNAKE on the wire, sentence case on the page. */
function titleOf(kind: string): string {
  const known: Record<string, string> = {
    GOT_AWAY_WITH_IT: 'Got away with it',
    DESERVED_BETTER: 'Deserved better',
    ONE_PLAYER_CARRY: 'One-player carry',
    SELF_INFLICTED_WOUND: 'Self-inflicted wound',
  }
  return known[kind] ?? kind.toLowerCase().replace(/_/g, ' ')
}
