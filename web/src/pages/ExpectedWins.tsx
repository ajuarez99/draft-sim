import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import Avatar from '../components/Avatar'
import { getExpectedWins, type ExpectedWins as Data, type ExpectedWinsTeam } from '../api'
import { hueForIndex } from '../hue'

/**
 * specs/004-ffwrapped-feature-parity US3: expected wins, schedule luck and
 * strength of schedule.
 *
 * Expected wins is the all-play record -- the share of the league a team
 * outscored each week. The gap between that and the real record is schedule
 * luck, so the page leads with the gap rather than with either number on its
 * own: "9 wins" and "8.2 expected" are only interesting together.
 *
 * Both sports, with no gate: nothing here reads a position, a lineup or a
 * projection.
 */
export default function ExpectedWins() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [data, setData] = useState<Data | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!sleeperLeagueId) return
    let cancelled = false
    setLoading(true)
    setError(null)
    getExpectedWins(sleeperLeagueId)
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

  const widest = Math.max(
    0.5,
    ...(data?.teams ?? []).map((t) => Math.abs(t.winsAboveExpected)),
  )

  return (
    <div className="content">
      <PageHeader
        eyebrow="League"
        title="Expected wins"
        sub="What each team's scoring would have earned against a random opponent every week, against what the schedule actually gave them. The gap is luck, not skill."
      />

      {error && (
        <div className="error">
          <span>{error.includes('404') ? "This league hasn't been loaded yet." : error}</span>
        </div>
      )}

      {loading && !data && <p className="muted small">Loading…</p>}

      {data && !data.available && (
        <section className="panel">
          <h3 className="cond">No games to measure yet</h3>
          <p className="muted small">
            {data.reason ?? 'No completed games for this league yet.'} Expected wins compares a team
            against the rest of the league in the same week, so it needs at least one played week.
          </p>
        </section>
      )}

      {data && data.available && (
        <>
          <section className="panel">
            <h3 className="cond">
              Season {data.season} · {data.weeksScored} week{data.weeksScored === 1 ? '' : 's'} ·
              league average {data.leagueAveragePpg.toFixed(2)} points per game
            </h3>

            <table className="ew-table">
              <thead>
                <tr>
                  <th scope="col">Team</th>
                  <th scope="col" className="ew-num">Wins</th>
                  <th scope="col" className="ew-num">Expected</th>
                  <th scope="col" className="ew-barhead">Luck, in wins above expected</th>
                  <th scope="col" className="ew-num" title="Opponents' points per game minus the league's">
                    Schedule
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.teams.map((t, i) => (
                  <Row key={t.rosterId} team={t} hue={hueForIndex(i, data.teams.length)} widest={widest} />
                ))}
              </tbody>
            </table>

            <p className="muted small ew-note">
              Schedule is the average of a team's opponents' points per game minus the league-wide
              average. <strong>Positive means a harder schedule</strong> — they faced better scoring
              than everyone else did.
            </p>
          </section>

          <section className="panel">
            <h3 className="cond">Where the luck came from</h3>
            <div className="ew-luck">
              {data.teams
                .filter((t) => Math.abs(t.winsAboveExpected) >= 0.25)
                .map((t) => (
                  <LuckCard key={t.rosterId} team={t} />
                ))}
            </div>
          </section>
        </>
      )}
    </div>
  )
}

function Row({ team, hue, widest }: { team: ExpectedWinsTeam; hue: number; widest: number }) {
  const wae = team.winsAboveExpected
  // A diverging bar around a zero line: the sign is the story, so it gets the
  // axis rather than being left to a minus sign in a column of numbers.
  const pct = (Math.abs(wae) / widest) * 50

  return (
    <tr>
      <th scope="row" className="ew-team">
        <Avatar
          avatarId={team.avatarId}
          seed={String(team.managerId ?? team.rosterId)}
          label={team.teamName}
          hue={hue}
          className="ew-avatar"
        />
        <span className="ew-name">{team.teamName}</span>
      </th>
      <td className="ew-num">{team.actualWins}</td>
      <td className="ew-num">{team.expectedWins.toFixed(2)}</td>
      <td className="ew-bars">
        <div className="ew-track">
          <div className="ew-zero" />
          <div
            className={wae >= 0 ? 'ew-bar ew-bar-lucky' : 'ew-bar ew-bar-unlucky'}
            style={wae >= 0 ? { left: '50%', width: `${pct}%` } : { right: '50%', width: `${pct}%` }}
          />
        </div>
        <span className={wae >= 0 ? 'ew-delta ew-up' : 'ew-delta ew-down'}>
          {wae >= 0 ? '+' : ''}
          {wae.toFixed(2)}
        </span>
      </td>
      <td className="ew-num">
        {team.strengthOfSchedule >= 0 ? '+' : ''}
        {team.strengthOfSchedule.toFixed(1)}
      </td>
    </tr>
  )
}

/**
 * One explanation or the other, never both. `luckSource` is a discriminator on
 * the wire precisely so this cannot drift into rendering an empty "key
 * matchups" heading for a team whose luck had no single cause.
 */
function LuckCard({ team }: { team: ExpectedWinsTeam }) {
  const wae = team.winsAboveExpected
  return (
    <article className="ew-luck-card">
      <header>
        <span className="ew-luck-name">{team.teamName}</span>
        <span className={wae >= 0 ? 'ew-delta ew-up' : 'ew-delta ew-down'}>
          {wae >= 0 ? '+' : ''}
          {wae.toFixed(2)}
        </span>
      </header>
      {team.luckSource === 'SWING_WEEKS' ? (
        <ul className="ew-swings">
          {team.swingWeeks.map((s) => (
            <li key={s.week}>
              <strong>Week {s.week}</strong>{' '}
              {s.result === 'WON' ? 'won' : 'lost'} with {s.points.toFixed(2)} points ({ordinal(s.weeklyRank)}{' '}
              that week) against {s.opponent}
            </li>
          ))}
        </ul>
      ) : (
        <p className="muted small">
          No single week did this — they consistently faced{' '}
          {wae >= 0 ? 'lower-scoring' : 'higher-scoring'} opponents than the rest of the league.
        </p>
      )}
    </article>
  )
}

function ordinal(n: number): string {
  const s = ['th', 'st', 'nd', 'rd']
  const v = n % 100
  return n + (s[(v - 20) % 10] ?? s[v] ?? s[0])
}
