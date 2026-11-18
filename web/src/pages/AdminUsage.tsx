import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import PageHeader from '../components/PageHeader'
import { getAdminUsage, type UsageFunnelRow, type UsageReport, type UsageSite } from '../api'
import { AdminTokenRequiredError } from '../apiError'
import { readToken, writeToken } from '../adminToken'
import { dayCounts, daysBetween, finishRate, funnelTotals, routeLabel, sharesBySport, sinceFor, type FunnelTotals } from '../usageSummary'

/**
 * Spec 076: the owner's usage page. Unlisted like /admin/digest (no nav link); the backend's admin token is the
 * gate. Two sources, kept visibly apart: the page, action and league counts come from a counter the browsers
 * send to (a floor, never exact); the Redraft funnel is read from the saved games (exact since `gamesSince`).
 */

const RANGES = [7, 30, 90] as const

const SITE_NAME: Record<UsageSite, string> = {
  timemachine: 'Redraft site',
  leagues: 'League site',
  combined: 'Single combined site',
}

/** The sites listed in the caveats; the combined site only when it has sent something. */
const COUNTED_SITES: UsageSite[] = ['timemachine', 'leagues', 'combined']

const SPORT_NAME ={ nba: 'Basketball', nfl: 'Football' } as const

const fmt = (n: number) => n.toLocaleString('en-US')
const plural = (n: number, one: string, many = `${one}s`) => `${fmt(n)} ${n === 1 ? one : many}`

/** Today's date in US Eastern, the same day boundary the server counts by. */
function todayEastern(): string {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'America/New_York' })
}

function shiftDay(day: string, by: number): string {
  return new Date(Date.parse(`${day}T00:00:00Z`) + by * 86_400_000).toISOString().slice(0, 10)
}

function friendlyDay(iso: string): string {
  return new Date(`${iso}T12:00:00Z`).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' })
}

function weekdayDay(iso: string): string {
  return new Date(`${iso}T12:00:00Z`).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' })
}

function propsText(props: Record<string, string>): string {
  const keys = Object.keys(props).sort()
  return keys.length === 0 ? 'none' : keys.map((k) => `${k}: ${props[k]}`).join(', ')
}

// ---- Caveats ---------------------------------------------------------------------------------

function Caveats({ report }: { report: UsageReport }) {
  return (
    <section className="panel us-caveats" aria-label="How to read these counts">
      <h2>How to read the counts</h2>
      <ul className="us-notes">
        {report.countingSince.length === 0 ? (
          <li>Page counting has not started: nothing has been recorded yet.</li>
        ) : (
          COUNTED_SITES.filter((s) => s !== 'combined' || sinceFor(report, s) != null).map((s) => {
            const since = sinceFor(report, s)
            return (
              <li key={s}>
                {since
                  ? `${SITE_NAME[s]}: counting began on ${friendlyDay(since)}. Earlier days are not counted, which is different from zero.`
                  : `${SITE_NAME[s]}: nothing counted yet.`}
              </li>
            )
          })
        )}
        <li>Days are US Eastern days.</li>
        <li>
          Page, action and league counts are a floor. A visitor whose browser or blocker stops the send is not counted, and
          the send can be lost when a page closes.
        </li>
        <li>
          The count box is open to anyone who knows its address, so a script could inflate these numbers. Treat a sudden spike
          with suspicion. The limit on sends per address is best effort, so a very busy minute can under-count.
        </li>
        <li>A view is a page opened, not a person. One visitor opening ten pages is ten views.</li>
        <li>
          Turning counting off in a browser (the opt-out) is per browser and per site. It stops page, action and league counts
          for that browser and never applies to the Redraft funnel, which reads saved games.
        </li>
      </ul>
    </section>
  )
}

// ---- Funnel ----------------------------------------------------------------------------------

function FunnelSport({
  sport,
  rows,
  report,
  shares,
}: {
  sport: 'nba' | 'nfl'
  rows: UsageFunnelRow[]
  report: UsageReport
  shares: number
}) {
  const t: FunnelTotals = funnelTotals(rows, report.ownerConfigured)
  const rate = finishRate(t)
  const byDay = [...rows].sort((a, b) => b.day.localeCompare(a.day))
  return (
    <section className="panel us-funnel" aria-label={`${SPORT_NAME[sport]} Redraft games`}>
      <div className="panel-head">
        <h2>{SPORT_NAME[sport]} Redraft games</h2>
        <span className="muted small">{`games started ${friendlyDay(report.from)} to ${friendlyDay(report.to)}`}</span>
      </div>
      {t.started === 0 && t.ownerStarted === 0 ? (
        <p className="muted">No {SPORT_NAME[sport].toLowerCase()} games were started in this range.</p>
      ) : (
        <>
          <div className="us-headline">
            <div>
              <div className="us-big mono">{fmt(t.started)}</div>
              <div className="muted small">games started</div>
            </div>
            <div>
              <div className="us-big mono">{fmt(t.finished)}</div>
              <div className="muted small">{rate == null ? 'finished' : `finished (${rate}% of started)`}</div>
            </div>
            <div>
              <div className="us-big mono us-big-warn">{fmt(t.notFinished)}</div>
              <div className="muted small">NOT FINISHED</div>
            </div>
          </div>
          <table className="us-table">
            <caption className="muted small">Where the games that were not finished stand, and who started them</caption>
            <thead>
              <tr>
                <th scope="col">Group</th>
                <th scope="col" className="us-num">
                  Games
                </th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <th scope="row">Gave up (pressed abandon)</th>
                <td className="us-num mono">{fmt(t.abandoned)}</td>
              </tr>
              <tr>
                <th scope="row">Left open (never finished, never abandoned)</th>
                <td className="us-num mono">{fmt(t.drafting)}</td>
              </tr>
              <tr>
                <th scope="row">Started as a guest</th>
                <td className="us-num mono">{`${fmt(t.guestStarted)} (${fmt(t.guestFinished)} finished)`}</td>
              </tr>
              <tr>
                <th scope="row">Started signed in</th>
                <td className="us-num mono">{fmt(t.signedInStarted)}</td>
              </tr>
              <tr>
                <th scope="row">Not recorded as guest or signed in</th>
                <td className="us-num mono">{fmt(t.unknownStarted)}</td>
              </tr>
              <tr>
                <th scope="row">Game of the day</th>
                <td className="us-num mono">{`${fmt(t.dailyStarted)} started, ${fmt(t.dailyFinished)} finished`}</td>
              </tr>
              <tr>
                <th scope="row">Results shared (browser-reported)</th>
                <td className="us-num mono">{fmt(shares)}</td>
              </tr>
            </tbody>
          </table>
          <p className="muted small">
            Shares are a floor reported by browsers when someone taps Share. They are not part of the finish rate.
          </p>
          {report.ownerConfigured ? (
            <p className="muted small">
              {`Your signed-in games are left out of every game count in this section, the per-day table included: ${plural(t.ownerStarted, 'game')} started, ${fmt(t.ownerFinished)} finished, ${fmt(t.ownerAbandoned)} gave up, ${fmt(t.ownerDrafting)} left open. Shares cannot be told apart, so the share count includes yours.`}
            </p>
          ) : (
            <p className="muted small">
              The owner id is not set on the server, so your own games are counted with everyone&rsquo;s.
            </p>
          )}
          <table className="us-table">
            <caption className="muted small">
              {report.ownerConfigured
                ? 'Games per start day (US Eastern), newest first, your signed-in games left out'
                : 'Games per start day (US Eastern), newest first'}
            </caption>
            <thead>
              <tr>
                <th scope="col">Start day</th>
                <th scope="col" className="us-num">
                  Started
                </th>
                <th scope="col" className="us-num">
                  Finished
                </th>
                <th scope="col" className="us-num">
                  Not finished
                </th>
              </tr>
            </thead>
            <tbody>
              {byDay.map((r) => {
                const d = dayCounts(r, report.ownerConfigured)
                return (
                  <tr key={r.day}>
                    <th scope="row">{weekdayDay(r.day)}</th>
                    <td className="us-num mono">{fmt(d.started)}</td>
                    <td className="us-num mono">{fmt(d.finished)}</td>
                    <td className="us-num mono">{fmt(d.notFinished)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </>
      )}
    </section>
  )
}

function Funnel({ report }: { report: UsageReport }) {
  const shares = sharesBySport(report.events)
  return (
    <section className="section" aria-label="Redraft funnel">
      <h2 className="section-title">Redraft games: started and finished</h2>
      <p className="muted small">
        {report.gamesSince
          ? `Read from saved games, the oldest dated ${friendlyDay(report.gamesSince)}, so these numbers are exact for that period and do not depend on browsers reporting. `
          : 'No Redraft game has been saved yet. '}
        Finished includes drafts the app finished with one press. A game left open stays left open for ever: it never
        times out, so a game started today may still finish. The guest split is slightly off for games a guest started and
        claimed before this shipped, which read as signed in. Your own guest-mode games cannot be told apart from anyone&rsquo;s,
        so they stay in the numbers.
      </p>
      {(['nba', 'nfl'] as const).map((sport) => (
        <FunnelSport
          key={sport}
          sport={sport}
          rows={report.funnel.filter((r) => r.sport === sport)}
          report={report}
          shares={shares[sport]}
        />
      ))}
    </section>
  )
}

// ---- Counters --------------------------------------------------------------------------------

function ViewsPerDay({ report }: { report: UsageReport }) {
  const sites = [...new Set(report.viewsByDay.map((d) => d.site))].sort()
  const days = daysBetween(report.from, report.to).reverse()
  return (
    <>
      {sites.map((site) => {
        const since = sinceFor(report, site) // this site's own first counted day, not the earliest of any site
        const bySite = new Map(report.viewsByDay.filter((d) => d.site === site).map((d) => [d.day, d.views]))
        const max = Math.max(1, ...bySite.values())
        // Live verification V-03: one line for the days before counting began, not a row each (267 of 270 at 90 days).
        const counted = since == null ? [] : days.filter((day) => day >= since)
        const uncounted = days.length - counted.length
        return (
          <section className="panel" key={site} aria-label={`${SITE_NAME[site]} page views per day`}>
            <div className="panel-head">
              <h2>{`${SITE_NAME[site]}: page views per day`}</h2>
              <span className="muted small">
                {since
                  ? `newest day first; bar length is views that day; counting since ${friendlyDay(since)}`
                  : 'nothing counted yet for this site'}
              </span>
            </div>
            <ol className="us-days row-list">
              {counted.map((day) => {
                const views = bySite.get(day) ?? 0
                return (
                  <li key={day} className="us-day">
                    <span className="us-day-label">{weekdayDay(day)}</span>
                    <span className="us-bar-track" aria-hidden="true">
                      <span className="us-bar" style={{ width: `${(views / max) * 100}%` }} />
                    </span>
                    <span className="us-day-num mono">{plural(views, 'view')}</span>
                  </li>
                )
              })}
            </ol>
            {uncounted > 0 && (
              <p className="muted small us-rejected">
                {`${plural(uncounted, 'earlier day')} in this range: not counted, which is different from zero.`}
              </p>
            )}
          </section>
        )
      })}
    </>
  )
}

function ViewsPerRoute({ report }: { report: UsageReport }) {
  const sites = [...new Set(report.viewsByRoute.map((r) => r.site))].sort()
  return (
    <>
      {sites.map((site) => (
        <section className="panel" key={site} aria-label={`${SITE_NAME[site]} page views per page`}>
          <div className="panel-head">
            <h2>{`${SITE_NAME[site]}: page views per page`}</h2>
            <span className="muted small">summed over the range</span>
          </div>
          <table className="us-table">
            <thead>
              <tr>
                <th scope="col">Page</th>
                <th scope="col">Address template</th>
                <th scope="col" className="us-num">
                  Views
                </th>
              </tr>
            </thead>
            <tbody>
              {report.viewsByRoute
                .filter((r) => r.site === site)
                .map((r) => (
                  <tr key={r.route}>
                    <th scope="row">{routeLabel(site, r.route)}</th>
                    <td className="code muted">{r.route}</td>
                    <td className="us-num mono">{fmt(r.views)}</td>
                  </tr>
                ))}
            </tbody>
          </table>
        </section>
      ))}
    </>
  )
}

function Counters({ report }: { report: UsageReport }) {
  const nothingEver = report.countingSince.length === 0
  const nothingInRange =
    report.viewsByDay.length === 0 && report.viewsByRoute.length === 0 && report.events.length === 0 && report.leagueViews.length === 0
  return (
    <section className="section" aria-label="Page counts">
      <h2 className="section-title">Pages and actions</h2>
      {nothingEver && (
        <p className="us-empty">
          Nothing has been counted yet. Page views, actions and league views start to appear after the site that sends them is
          deployed and someone opens a page on a real address.
        </p>
      )}
      {!nothingEver && nothingInRange && (
        <p className="us-empty">{`No page views, actions or league views were counted from ${friendlyDay(report.from)} to ${friendlyDay(report.to)}.`}</p>
      )}
      {!nothingEver && !nothingInRange && (
        <>
          <ViewsPerDay report={report} />
          <ViewsPerRoute report={report} />
          {report.events.length > 0 && (
            <section className="panel" aria-label="Key actions">
              <div className="panel-head">
                <h2>Key actions</h2>
                <span className="muted small">times the action succeeded, summed over the range</span>
              </div>
              <table className="us-table">
                <thead>
                  <tr>
                    <th scope="col">Action</th>
                    <th scope="col">Site</th>
                    <th scope="col">Detail</th>
                    <th scope="col" className="us-num">
                      Times
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {report.events.map((e) => (
                    <tr key={`${e.site}|${e.name}|${propsText(e.props)}`}>
                      <th scope="row">{e.name}</th>
                      <td>{SITE_NAME[e.site]}</td>
                      <td className="muted">{propsText(e.props)}</td>
                      <td className="us-num mono">{fmt(e.count)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}
          {report.leagueViews.length > 0 && (
            <section className="panel" aria-label="League views">
              <div className="panel-head">
                <h2>League views</h2>
                <span className="muted small">league pages opened, summed over the range</span>
              </div>
              <p className="muted small">
                The league site is locked to you, so these are mostly your own visits.
              </p>
              <table className="us-table">
                <thead>
                  <tr>
                    <th scope="col">League</th>
                    <th scope="col">Sport</th>
                    <th scope="col" className="us-num">
                      Views
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {report.leagueViews.map((l) => (
                    <tr key={l.leagueId}>
                      <th scope="row">{l.leagueName ?? l.leagueId}</th>
                      <td>{l.sport ? l.sport.toUpperCase() : 'unknown'}</td>
                      <td className="us-num mono">{fmt(l.views)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}
        </>
      )}
      <p className="muted small us-rejected">
        {report.rejected === 0
          ? 'Sends turned away in this range: none.'
          : `Sends turned away in this range: ${fmt(report.rejected)}. These came from a known site but named a page or action the server does not accept, most often an old browser tab after a release, or someone probing the address.`}
      </p>
    </section>
  )
}

// ---- Page ------------------------------------------------------------------------------------

export default function AdminUsage() {
  const [token, setToken] = useState<string | null>(() => readToken())
  const [draftToken, setDraftToken] = useState('')
  const [refused, setRefused] = useState(false)
  const [days, setDays] = useState<(typeof RANGES)[number]>(30)
  const [report, setReport] = useState<UsageReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const latestLoad = useRef(0)

  const load = useCallback(async (t: string, n: number) => {
    const id = ++latestLoad.current
    setLoading(true)
    setError(null)
    setReport(null) // never leave the last range's numbers up under the new range's label
    try {
      const to = todayEastern()
      const result = await getAdminUsage(t, shiftDay(to, -(n - 1)), to)
      if (id === latestLoad.current) setReport(result)
    } catch (e) {
      if (id !== latestLoad.current) return
      if (e instanceof AdminTokenRequiredError) {
        writeToken(null)
        setToken(null)
        setRefused(true)
      } else {
        setError(e instanceof Error ? e.message : 'Something went wrong.')
      }
    } finally {
      if (id === latestLoad.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (token) void load(token, days)
  }, [token, days, load])

  function submitToken(e: FormEvent) {
    e.preventDefault()
    const t = draftToken.trim()
    if (!t) return
    writeToken(t)
    setRefused(false)
    setDraftToken('')
    setToken(t)
  }

  const header = (
    <PageHeader
      eyebrow="Admin"
      title="Usage"
      sub="What people open on each site, and how Redraft games end."
    />
  )

  if (!token) {
    return (
      <div className="content">
        {header}
        <section className="panel dg-token">
          <form onSubmit={submitToken}>
            <label htmlFor="us-token-input">Admin token</label>
            <input
              id="us-token-input"
              type="password"
              autoComplete="off"
              value={draftToken}
              onChange={(e) => setDraftToken(e.target.value)}
            />
            <button type="submit" className="action-button">
              Continue
            </button>
          </form>
          {refused && <p className="dg-status">That token was refused. Enter it again.</p>}
        </section>
      </div>
    )
  }

  return (
    <div className="content us-page">
      {header}
      <section className="panel us-toolbar" aria-label="Range">
        <span className="muted small">Show the last</span>
        <div className="us-ranges" role="group" aria-label="Range in days">
          {RANGES.map((n) => (
            <button
              key={n}
              type="button"
              className={`chip${days === n ? ' on' : ''}`}
              aria-pressed={days === n}
              onClick={() => setDays(n)}
            >
              {`${n} days`}
            </button>
          ))}
        </div>
        {report && <span className="muted small">{`${friendlyDay(report.from)} to ${friendlyDay(report.to)}, US Eastern`}</span>}
      </section>

      {error && <div className="error">{error}</div>}
      {loading && !report && <p className="muted">Loading&hellip;</p>}

      {report && (
        <>
          <Caveats report={report} />
          <Funnel report={report} />
          <Counters report={report} />
        </>
      )}
    </div>
  )
}
