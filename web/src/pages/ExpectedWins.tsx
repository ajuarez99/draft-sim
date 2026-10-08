import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import HowThisWorks from '../components/HowThisWorks'
import Avatar from '../components/Avatar'
import { getExpectedWins, type ExpectedWins as Data, type ExpectedWinsTeam } from '../api'
import { useFailure } from '../useFailure'
import NotFound from '../components/NotFound'
import PersonName from '../components/PersonName'
import { hueForIndex } from '../hue'
import SeasonFallbackNote from '../components/SeasonFallbackNote'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { ordinal } from '../format'

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
/**
 * Whether the numbers are still early. The threshold lives only on the server
 * (SeasonWindow.EARLY_THRESHOLD_WEEKS, hand-set); a missing flag from an older
 * backend counts as early, so the page never names a "luckiest" team on
 * thin data by accident.
 */
const isEarly = (data: Data) => data.early ?? true

/**
 * ARBITRARY, hand-set: under a quarter of a win above/below expected is not worth
 * calling lucky or unlucky. Not fitted to anything; the one copy of this cutoff.
 */
export const LUCK_NOTABLE_WINS = 0.25

/** The one-sentence takeaway. Names the luckiest team only once enough weeks
 *  are scored for the name to mean something; before that it stays neutral. */
function luckSubtitle(data: Data | null): string {
  const neutral = "Who the schedule has helped, and who it has hurt."
  if (!data || !data.available || isEarly(data)) return neutral
  const top = [...data.teams].sort((a, b) => b.winsAboveExpected - a.winsAboveExpected)[0]
  // Under LUCK_NOTABLE_WINS above expected nobody is meaningfully lucky.
  if (!top || top.winsAboveExpected < LUCK_NOTABLE_WINS) return neutral
  return `${top.teamName} has been the luckiest, ${top.winsAboveExpected.toFixed(2)} wins above what their scoring earned.`
}

export default function ExpectedWins() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [data, setData] = useState<Data | null>(null)
  const { error, notFound, setError, fail } = useFailure()
  const [loading, setLoading] = useState(false)
  // Bumped by the rail when this league's background refresh finishes (specs/009-auto-data-refresh).
  const dataVersion = useLeagueDataVersion(sleeperLeagueId)

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
        if (!cancelled) fail(e)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [sleeperLeagueId, dataVersion])

  const widest = Math.max(
    0.5,
    ...(data?.teams ?? []).map((t) => Math.abs(t.winsAboveExpected)),
  )

  if (notFound) return <NotFound what="league" />

  return (
    <div className="content">
      <PageHeader
        eyebrow={data ? `League · ${data.season}` : 'League'}
        title="Luck"
        sub={luckSubtitle(data)}
      />

      {error && (
        <div className="error">
          <span>{error}</span>
        </div>
      )}

      {loading && !data && <p className="muted small">Loading…</p>}

      {data && !data.available && (
        <section className="section">
          <h3 className="section-title">No games to measure yet</h3>
          <p className="muted small">
            {data.reason ?? 'No completed games for this league yet.'} Expected wins compares a team
            against the rest of the league in the same week, so it needs at least one played week.
          </p>
        </section>
      )}

      {data && data.available && (
        <>
          <section className="section">
            <h3 className="section-title">
              Season {data.season} · {data.weeksScored} week{data.weeksScored === 1 ? '' : 's'} ·
              league average {data.leagueAveragePpg.toFixed(2)} points per game
              {isEarly(data) && (
                <>
                  {' '}
                  <span className="sl-early small">early — this is mostly noise</span>
                </>
              )}
            </h3>
            <SeasonFallbackNote season={data.season} requestedSeason={data.requestedSeason} />

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

            <HowThisWorks>
              <p>
                What each team's scoring would have earned against a random opponent every week,
                against what the schedule actually gave them. The gap is luck, not skill.
              </p>
              <p>
                Schedule is the average of a team's opponents' points per game minus the league-wide
                average. <strong>Positive means a harder schedule</strong> — they faced better scoring
                than everyone else did.
              </p>
            </HowThisWorks>
          </section>

          <section className="section">
            <h3 className="section-title">Where the luck came from</h3>
            <div className="ew-luck">
              {data.teams
                .filter((t) => Math.abs(t.winsAboveExpected) >= LUCK_NOTABLE_WINS)
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
        <span className="ew-name"><PersonName teamName={team.teamName} username={team.username} /></span>
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
        <span className="ew-luck-name"><PersonName teamName={team.teamName} username={team.username} /></span>
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
        <p className="muted small">{noSwingCopy(wae, team.strengthOfSchedule)}</p>
      )}
    </article>
  )
}

/** Hand-set, not principled: below this a schedule number is too small to name a direction. */
const SOS_NEAR_ZERO = 1

/**
 * No swing week means luck has no single cause, and the sign of luck says
 * nothing about the schedule, so the direction comes from strengthOfSchedule
 * (positive = harder). When the two disagree the schedule isn't blamed.
 */
function noSwingCopy(wae: number, sos: number): string {
  const lead = 'No single week did this'
  if (Math.abs(sos) < SOS_NEAR_ZERO) {
    return `${lead}, and the schedule was close to average (${signed(sos)}). The gap came from close weeks near the middle of the league's scores.`
  }
  const harder = sos > 0
  const agrees = wae >= 0 ? !harder : harder
  if (agrees) {
    return `${lead} — their opponents averaged ${Math.abs(sos).toFixed(1)} ${harder ? 'more' : 'fewer'} points than the league (${signed(sos)}, ${harder ? 'harder' : 'easier'} schedule).`
  }
  return `${lead}, and the schedule ran the other way (${signed(sos)}, ${harder ? 'harder' : 'easier'}). The ${wae >= 0 ? 'edge' : 'shortfall'} came from ${wae >= 0 ? 'winning' : 'losing'} close weeks near the middle of the league's scores.`
}

function signed(n: number): string {
  return `${n >= 0 ? '+' : ''}${n.toFixed(1)}`
}
