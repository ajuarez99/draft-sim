import { useState } from 'react'
import { useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import NotFound from '../components/NotFound'
import { getLeagueSchedule, type LeagueSchedule, type ScheduleTeam, type ScheduleWeek } from '../api'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { useBlock } from '../useBlock'

/**
 * specs/017-nba-schedule-grid US1/US2: how many games each NBA team plays in
 * each league week. A basketball league scores a week's total, so the team
 * with five games this week has a real edge over the team with two.
 *
 * It is a grid, so it is a table: teams down, weeks across. Every cell prints
 * its count and its tint tracks the same count (one encoding, number spelled
 * out). The team column is sticky and the table scrolls inside its own
 * container, so the page itself never scrolls sideways at phone width.
 *
 * Weeks are always looked up BY WEEK NUMBER in `weeks` (N5): the stored schedule
 * can start before the league's current week or skip one, so an index offset
 * from `currentWeek` would silently read the wrong column.
 */

/** UI choice, not a modelled quantity: how far ahead the sort looks. */
const AHEAD_CHOICES = [1, 2, 3, 4] as const
type View = { kind: 'ahead'; n: number } | { kind: 'playoff' }

/** A week's games for one team, or null when that week isn't in the stored schedule. */
export function gamesIn(data: LeagueSchedule, team: ScheduleTeam, week: number): number | null {
  const i = data.weeks.findIndex((w) => w.week === week)
  return i < 0 ? null : (team.games[i] ?? null)
}

/** `from` ... `to` inclusive, as week numbers. */
function range(from: number, to: number): number[] {
  const out: number[] = []
  for (let w = from; w <= to; w++) out.push(w)
  return out
}

/**
 * The upcoming columns: `currentWeek` ... `lastLeagueWeek`. A missing current
 * week falls back to the first stored week, and a missing last league week to
 * the last stored one, so a league whose settings are thin still gets a grid.
 */
export function upcomingWeeks(data: LeagueSchedule): number[] {
  if (data.weeks.length === 0) return []
  const first = data.currentWeek ?? data.weeks[0].week
  const lastStored = data.weeks[data.weeks.length - 1].week
  let last = data.lastLeagueWeek ?? lastStored
  // A league mid-playoffs whose playoff end couldn't be worked out has its
  // current week past the fallback "last league week" (start - 1) without being
  // over: run the columns to the end of the stored schedule instead (review R2).
  if (first > last && !data.seasonOver) last = lastStored
  return range(first, last)
}

/** The playoff window's weeks, or [] when the league's window can't be worked out. */
export function playoffWeeks(data: LeagueSchedule): number[] {
  const { startWeek, endWeek } = data.playoff
  return startWeek != null && endWeek != null ? range(startWeek, endWeek) : []
}

/** Sum over the given weeks; a week not in the schedule contributes nothing. */
function sumOver(data: LeagueSchedule, team: ScheduleTeam, weeks: number[]): number {
  return weeks.reduce((acc, w) => acc + (gamesIn(data, team, w) ?? 0), 0)
}

/**
 * Most games first, ties by team code. Stated here rather than inherited from
 * the server's order, so the ranking has one owner.
 */
export function rankTeams(data: LeagueSchedule, weeks: number[]): { team: ScheduleTeam; total: number }[] {
  return data.teams
    .map((team) => ({ team, total: sumOver(data, team, weeks) }))
    .sort((a, b) => b.total - a.total || (a.team.team < b.team.team ? -1 : a.team.team > b.team.team ? 1 : 0))
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

function shortDate(iso: string): string {
  const [, m, d] = iso.split('-')
  const month = MONTHS[Number(m) - 1]
  return month ? `${month} ${Number(d)}` : iso
}

function dateRange(w: ScheduleWeek | undefined): string | null {
  if (!w || !w.firstDate || !w.lastDate) return null
  return w.firstDate === w.lastDate ? shortDate(w.firstDate) : `${shortDate(w.firstDate)} – ${shortDate(w.lastDate)}`
}

function relativeTime(iso: string | null, now = Date.now()): string | null {
  if (!iso) return null
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return null
  const mins = Math.max(0, Math.round((now - t) / 60000))
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins} minute${mins === 1 ? '' : 's'} ago`
  const hours = Math.round(mins / 60)
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`
  const days = Math.round(hours / 24)
  return `${days} day${days === 1 ? '' : 's'} ago`
}

/** Tint bucket: the count itself, capped, so the colour and the number agree. */
const tintClass = (n: number) => `sg-c${Math.min(n, 6)}`

function excludedLine(e: LeagueSchedule['excluded']): string | null {
  const parts: string[] = []
  if (e.postponed > 0) parts.push(`${e.postponed} postponed`)
  if (e.canceled > 0) parts.push(`${e.canceled} canceled`)
  if (e.exhibition > 0) parts.push(`${e.exhibition} exhibition`)
  if (parts.length === 0) return null
  const total = e.postponed + e.canceled + e.exhibition
  const list = parts.length === 1 ? parts[0] : `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`
  return `${list} game${total === 1 ? '' : 's'} ${total === 1 ? "isn't" : "aren't"} counted.`
}

export default function ScheduleGrid() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const id = sleeperLeagueId ?? ''
  // Bumped by the rail when this league's background refresh finishes, so a
  // first visit to an empty grid fills in without a reload (F8).
  const version = useLeagueDataVersion(sleeperLeagueId)
  const block = useBlock(id ? () => getLeagueSchedule(id) : null, [id, version])
  const [view, setView] = useState<View>({ kind: 'ahead', n: 1 })

  if (block.status === 'error' && block.notFound) return <NotFound what="league" />

  const data = block.status === 'ok' ? block.data : null
  // How many weeks the Next-N view can really sum; larger N are disabled (review R3).
  const remaining = data && data.available ? upcomingWeeks(data).length : 0
  const excluded = data ? excludedLine(data.excluded) : null

  return (
    <div className="content">
      <PageHeader
        eyebrow={data ? `League · ${data.season}` : 'League'}
        title="Schedule grid"
        sub="Games each NBA team plays in every week of your league."
      />

      {block.status === 'loading' && <p className="muted small">Loading…</p>}
      {block.status === 'error' && <p className="muted small">Couldn't load the schedule.</p>}

      {data && !data.available && (
        <section className="section">
          <p className="muted small">{data.reason}</p>
        </section>
      )}

      {data && data.available && (
        <section className="section sg-section">
          <p className="muted small sg-fresh">
            Schedule from Sleeper
            {relativeTime(data.fetchedAt) ? `, fetched ${relativeTime(data.fetchedAt)}` : ''}. The NBA adds and moves
            games during the season.
            {excluded && <> {excluded}</>}
          </p>

          <div className="sg-controls" role="group" aria-label="Rank teams by">
            {AHEAD_CHOICES.map((n) => {
              const disabled = n > remaining
              return (
                <button
                  key={n}
                  type="button"
                  className={`segment${view.kind === 'ahead' && view.n === n ? ' on' : ''}${disabled ? ' disabled' : ''}`}
                  aria-pressed={view.kind === 'ahead' && view.n === n}
                  disabled={disabled}
                  title={disabled ? `Only ${remaining} ${remaining === 1 ? 'week is' : 'weeks are'} left` : undefined}
                  onClick={() => setView({ kind: 'ahead', n })}
                >
                  Next {n} {n === 1 ? 'week' : 'weeks'}
                </button>
              )
            })}
            <button
              type="button"
              className={`segment${view.kind === 'playoff' ? ' on' : ''}`}
              aria-pressed={view.kind === 'playoff'}
              onClick={() => setView({ kind: 'playoff' })}
            >
              Playoff weeks
            </button>
          </div>

          {view.kind === 'ahead' && data.seasonOver && <p className="sg-over">This league's season is over.</p>}
          {view.kind === 'ahead' && !data.seasonOver && <AheadTable data={data} n={view.n} />}

          {view.kind === 'playoff' &&
            (data.playoff.endWeek == null ? (
              <p className="muted small">
                {data.playoff.reason ?? "This league's playoff weeks couldn't be worked out."}
              </p>
            ) : (
              <PlayoffTable data={data} />
            ))}
        </section>
      )}
    </div>
  )
}

function WeekHead({
  data,
  week,
  current,
  playoff,
}: {
  data: LeagueSchedule
  week: number
  current: boolean
  playoff: boolean
}) {
  const dates = dateRange(data.weeks.find((w) => w.week === week))
  return (
    <th scope="col" className={`sg-week${current ? ' sg-now' : ''}`}>
      <span className="sg-wk">Week {week}</span>
      {dates && <span className="sg-dates">{dates}</span>}
      {current && <span className="sg-tag">this week (incl. played)</span>}
      {playoff && <span className="sg-tag sg-tag-po">playoffs</span>}
    </th>
  )
}

function Cell({ n }: { n: number | null }) {
  if (n == null) {
    return (
      <td className="sg-cell sg-missing" title="This week isn't in Sleeper's schedule">
        not in Sleeper's schedule
      </td>
    )
  }
  return <td className={`sg-cell ${tintClass(n)}`}>{n}</td>
}

function AheadTable({ data, n }: { data: LeagueSchedule; n: number }) {
  const cols = upcomingWeeks(data)
  if (cols.length === 0) return <p className="muted small">No weeks left to show.</p>
  const used = Math.min(n, cols.length)
  const ranked = rankTeams(data, cols.slice(0, used))
  const { startWeek, endWeek } = data.playoff
  const inPlayoff = (w: number) => startWeek != null && endWeek != null && w >= startWeek && w <= endWeek
  return (
    <div className="sg-wrap">
      <table className="sg-table">
        <thead>
          <tr>
            <th scope="col" className="sg-team sg-corner">
              Team
            </th>
            <th scope="col" className="sg-total">
              Next {used} {used === 1 ? 'week' : 'weeks'}
            </th>
            {cols.map((w) => (
              <WeekHead key={w} data={data} week={w} current={w === data.currentWeek} playoff={inPlayoff(w)} />
            ))}
          </tr>
        </thead>
        <tbody>
          {ranked.map(({ team, total }) => (
            <tr key={team.team}>
              <th scope="row" className="sg-team">
                {team.team}
              </th>
              <td className="sg-total sg-sum">{total}</td>
              {cols.map((w) => (
                <Cell key={w} n={gamesIn(data, team, w)} />
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function PlayoffTable({ data }: { data: LeagueSchedule }) {
  const cols = playoffWeeks(data)
  const ranked = rankTeams(data, cols)
  return (
    <div className="sg-wrap">
      <table className="sg-table">
        <thead>
          <tr>
            <th scope="col" className="sg-team sg-corner">
              Team
            </th>
            <th scope="col" className="sg-total">
              Playoff total
            </th>
            {cols.map((w) => (
              <WeekHead key={w} data={data} week={w} current={w === data.currentWeek} playoff />
            ))}
          </tr>
        </thead>
        <tbody>
          {ranked.map(({ team, total }) => (
            <tr key={team.team}>
              <th scope="row" className="sg-team">
                {team.team}
              </th>
              <td className="sg-total sg-sum">{total}</td>
              {cols.map((w) => (
                <Cell key={w} n={gamesIn(data, team, w)} />
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
