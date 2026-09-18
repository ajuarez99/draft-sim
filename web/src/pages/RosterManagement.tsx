import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import Avatar from '../components/Avatar'
import { getRosterManagement, type RosterManagement as Data, type RosterManagementTeam } from '../api'
import { hueForIndex } from '../hue'

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
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

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
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [sleeperLeagueId])

  // The widest potential sets the scale, so every row's pair of bars is
  // comparable across the league rather than each row normalising to itself.
  const scale = Math.max(1, ...(data?.teams ?? []).map((t) => t.potentialPoints))

  return (
    <div className="content">
      <PageHeader
        eyebrow="League"
        title="Roster management"
        sub="What each team scored, against the most it could have scored if every weekly lineup had been perfect. Efficiency is the first divided by the second — it measures lineup decisions, not the roster."
      />

      {error && (
        <div className="error">
          <span>{error.includes('404') ? "This league hasn't been loaded yet." : error}</span>
        </div>
      )}

      {loading && !data && <p className="muted small">Loading…</p>}

      {data && !data.available && (
        <section className="panel">
          <h3 className="cond">Nothing to measure yet</h3>
          <p className="muted small">
            {data.reason ?? 'No scored weeks for this league yet.'} Efficiency needs at least one
            completed week — before that there is no lineup decision to grade.
          </p>
        </section>
      )}

      {data && data.available && (
        <section className="panel">
          <h3 className="cond">
            Season {data.season} · {data.weeksScored} week{data.weeksScored === 1 ? '' : 's'} scored
          </h3>

          <table className="rm-table">
            <thead>
              <tr>
                <th scope="col">Team</th>
                <th scope="col" className="rm-num">Points</th>
                <th scope="col" className="rm-num">Potential</th>
                <th scope="col" className="rm-num">Efficiency</th>
                <th scope="col" className="rm-barhead">
                  Scored against potential, points
                </th>
              </tr>
            </thead>
            <tbody>
              {data.teams.map((t, i) => (
                <Row key={t.rosterId} team={t} hue={hueForIndex(i, data.teams.length)} scale={scale} />
              ))}
            </tbody>
          </table>

          <ExcludedNote teams={data.teams} />
        </section>
      )}
    </div>
  )
}

function Row({ team, hue, scale }: { team: RosterManagementTeam; hue: number; scale: number }) {
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
        <span className="rm-name">{team.teamName}</span>
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
          `${(team.efficiency * 100).toFixed(1)}%`
        )}
      </td>
      <td className="rm-bars">
        <div className="rm-track" style={{ ['--hue' as string]: hue }}>
          <div className="rm-bar rm-bar-potential" style={{ width: `${potentialPct}%` }} />
          <div className="rm-bar rm-bar-actual" style={{ width: `${actualPct}%` }} />
        </div>
        <span className="rm-left">
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
