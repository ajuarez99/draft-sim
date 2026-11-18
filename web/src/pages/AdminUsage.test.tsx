import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminUsage from './AdminUsage'
import { AdminTokenRequiredError } from '../apiError'
import type { UsageFunnelRow, UsageReport } from '../api'

const getAdminUsage = vi.fn()
vi.mock('../api', () => ({
  getAdminUsage: (...args: unknown[]) => getAdminUsage(...args),
}))

function frow(over: Partial<UsageFunnelRow> = {}): UsageFunnelRow {
  return {
    day: '2026-11-15', sport: 'nba', started: 0, finished: 0, abandoned: 0, drafting: 0,
    guestStarted: 0, guestFinished: 0, unknownStarted: 0, dailyStarted: 0, dailyFinished: 0, ownerStarted: 0, ownerFinished: 0, ownerAbandoned: 0, ownerDrafting: 0, ownerDailyStarted: 0, ownerDailyFinished: 0,
    ...over,
  }
}

function report(over: Partial<UsageReport> = {}): UsageReport {
  return {
    timezone: 'America/New_York',
    from: '2026-11-13',
    to: '2026-11-17',
    countingSince: [],
    gamesSince: '2026-11-01',
    ownerConfigured: false,
    viewsByDay: [],
    viewsByRoute: [],
    leagueViews: [],
    events: [],
    rejected: 0,
    funnel: [],
    ...over,
  }
}

/** The three big numbers of a sport's funnel, in reading order. */
function headline(el: HTMLElement): string[] {
  return [...el.querySelectorAll('.us-big')].map((n) => n.textContent ?? '')
}

function daysApart(a: string, b: string): number {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86_400_000)
}

async function open(r: UsageReport) {
  sessionStorage.setItem('bk.adminToken', 'secret')
  getAdminUsage.mockResolvedValue(r)
  render(<AdminUsage />)
  await screen.findByText('How to read the counts')
}

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
})

describe('AdminUsage', () => {
  it('asks for the token first and does not call the API', () => {
    render(<AdminUsage />)
    expect(screen.getByLabelText('Admin token')).toBeInTheDocument()
    expect(getAdminUsage).not.toHaveBeenCalled()
  })

  it('a refused token is forgotten and asked for again', async () => {
    sessionStorage.setItem('bk.adminToken', 'stale')
    getAdminUsage.mockRejectedValue(new AdminTokenRequiredError())
    render(<AdminUsage />)
    expect(await screen.findByText('That token was refused. Enter it again.')).toBeInTheDocument()
    expect(sessionStorage.getItem('bk.adminToken')).toBeNull()
    expect(screen.getByLabelText('Admin token')).toBeInTheDocument()
  })

  it('stores a typed token for the tab and asks for the last 30 days', async () => {
    getAdminUsage.mockResolvedValue(report())
    const user = userEvent.setup()
    render(<AdminUsage />)
    await user.type(screen.getByLabelText('Admin token'), 'secret')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByText('How to read the counts')
    expect(sessionStorage.getItem('bk.adminToken')).toBe('secret')
    const [token, from, to] = getAdminUsage.mock.calls[0]
    expect(token).toBe('secret')
    expect(daysApart(from as string, to as string)).toBe(29)
  })

  it('the range chips ask the server again for 7 and 90 days', async () => {
    await open(report())
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '7 days' }))
    await waitFor(() => expect(getAdminUsage).toHaveBeenCalledTimes(2))
    expect(daysApart(getAdminUsage.mock.calls[1][1] as string, getAdminUsage.mock.calls[1][2] as string)).toBe(6)
    expect(screen.getByRole('button', { name: '7 days' })).toHaveAttribute('aria-pressed', 'true')
    await user.click(screen.getByRole('button', { name: '90 days' }))
    await waitFor(() => expect(getAdminUsage).toHaveBeenCalledTimes(3))
    expect(daysApart(getAdminUsage.mock.calls[2][1] as string, getAdminUsage.mock.calls[2][2] as string)).toBe(89)
  })

  it('an empty counter store says so in words, shows no zero tables, and still renders the funnel', async () => {
    await open(
      report({
        funnel: [frow({ started: 8, finished: 5, abandoned: 1, drafting: 2, guestStarted: 6, guestFinished: 3 })],
      }),
    )
    expect(screen.getByText(/Nothing has been counted yet/)).toBeInTheDocument()
    expect(screen.getByText('Page counting has not started: nothing has been recorded yet.')).toBeInTheDocument()
    expect(screen.queryByLabelText(/page views per day/i)).toBeNull()
    expect(screen.queryByLabelText(/page views per page/i)).toBeNull()
    // The funnel is read from saved games, so it is there anyway.
    const nba = screen.getByRole('region', { name: 'Basketball Redraft games' })
    expect(within(nba).getByText('NOT FINISHED')).toBeInTheDocument()
  })

  it('a populated report: per-day bars with "not counted" before countingSince, routes with labels, templates as the fallback', async () => {
    await open(
      report({
        countingSince: [{ site: 'timemachine', since: '2026-11-15' }],
        viewsByDay: [
          { day: '2026-11-15', site: 'timemachine', views: 4 },
          { day: '2026-11-17', site: 'timemachine', views: 10 },
        ],
        viewsByRoute: [
          { site: 'timemachine', route: '/daily', views: 9 },
          { site: 'timemachine', route: '/retired/page', views: 5 },
        ],
        events: [{ site: 'timemachine', name: 'result-shared', props: { sport: 'nba', who: 'guest', daily: 'no' }, count: 3 }],
        rejected: 2,
      }),
    )
    expect(screen.getByText('Redraft site: counting began on Nov 15, 2026. Earlier days are not counted, which is different from zero.')).toBeInTheDocument()
    const days = screen.getByRole('region', { name: 'Redraft site page views per day' })
    // Nov 13 and 14 are before counting began: not zero.
    expect(within(days).getByText(/^2 earlier days in this range: not counted/)).toBeInTheDocument()
    expect(within(days).getByText('10 views')).toBeInTheDocument()
    expect(within(days).getByText('4 views')).toBeInTheDocument()
    expect(within(days).getByText('0 views')).toBeInTheDocument() // Nov 16: counted, none
    const routes = screen.getByRole('region', { name: 'Redraft site page views per page' })
    expect(within(routes).getByText('Game of the day')).toBeInTheDocument()
    expect(within(routes).getAllByText('/retired/page')).toHaveLength(2) // no label left: the template is the label and the address
    const actions = screen.getByRole('region', { name: 'Key actions' })
    expect(within(actions).getByText('result-shared')).toBeInTheDocument()
    expect(within(actions).getByText('daily: no, sport: nba, who: guest')).toBeInTheDocument()
    expect(screen.getByText(/Sends turned away in this range: 2\./)).toBeInTheDocument()
  })

  it('counting started but nothing in this range reads as that, not as an empty store', async () => {
    await open(report({ countingSince: [{ site: 'timemachine', since: '2026-10-01' }] }))
    expect(screen.getByText(/No page views, actions or league views were counted from/)).toBeInTheDocument()
    expect(screen.queryByText(/Nothing has been counted yet/)).toBeNull()
  })

  it('the funnel headline is finished against NOT FINISHED, with the split under it', async () => {
    await open(
      report({
        funnel: [
          frow({ day: '2026-11-15', started: 10, finished: 6, abandoned: 1, drafting: 3, guestStarted: 4, guestFinished: 2, unknownStarted: 1 }),
          frow({ day: '2026-11-16', started: 10, finished: 4, abandoned: 2, drafting: 4, guestStarted: 6, guestFinished: 1, dailyStarted: 5, dailyFinished: 3 }),
        ],
        events: [{ site: 'timemachine', name: 'result-shared', props: { sport: 'nba', who: 'member', daily: 'yes' }, count: 7 }],
      }),
    )
    const nba = screen.getByRole('region', { name: 'Basketball Redraft games' })
    expect(headline(nba)).toEqual(['20', '10', '10']) // started, finished, not finished (20 - 10)
    expect(within(nba).getByText('finished (50% of started)')).toBeInTheDocument()
    expect(within(nba).getByText('NOT FINISHED')).toBeInTheDocument()
    expect(within(nba).getByText('Gave up (pressed abandon)').nextSibling).toHaveTextContent('3')
    expect(within(nba).getByText(/Left open/).nextSibling).toHaveTextContent('7')
    expect(within(nba).getByText('Started as a guest').nextSibling).toHaveTextContent('10 (3 finished)')
    expect(within(nba).getByText('Started signed in').nextSibling).toHaveTextContent('9')
    expect(within(nba).getByText('Not recorded as guest or signed in').nextSibling).toHaveTextContent('1')
    expect(within(nba).getByText('Game of the day').nextSibling).toHaveTextContent('5 started, 3 finished')
    // Shares sit beside the funnel, labelled, and are not in the rate.
    expect(within(nba).getByText('Results shared (browser-reported)').nextSibling).toHaveTextContent('7')
    expect(within(nba).getByText(/floor reported by browsers/)).toBeInTheDocument()
    // Football has no games here.
    expect(within(screen.getByRole('region', { name: 'Football Redraft games' })).getByText(/No football games were started/)).toBeInTheDocument()
  })

  it('leaves the owner out of the headline totals and says how many', async () => {
    await open(
      report({
        ownerConfigured: true,
        funnel: [frow({ started: 12, finished: 7, abandoned: 2, drafting: 3, ownerStarted: 4, ownerFinished: 3, ownerDrafting: 1 })],
      }),
    )
    const nba = screen.getByRole('region', { name: 'Basketball Redraft games' })
    expect(headline(nba)).toEqual(['8', '4', '4']) // 12 - 4 started, 7 - 3 finished
    expect(within(nba).getByText('finished (50% of started)')).toBeInTheDocument() // 4 / 8
    expect(within(nba).getByText(/left out of every game count in this section, the per-day table included: 4 games started, 3 finished, 0 gave up, 1 left open\./)).toBeInTheDocument()
    expect(within(nba).getByText(/Shares cannot be told apart, so the share count includes yours./)).toBeInTheDocument()
    expect(within(nba).queryByText(/cannot be split/)).toBeNull()
  })

  it('with the owner configured, game of the day and the per-day table leave him out too', async () => {
    // the owner and one guest both play today's game of the day, and both finish
    await open(
      report({
        ownerConfigured: true,
        funnel: [
          frow({
            day: '2026-11-16', started: 2, finished: 2, dailyStarted: 2, dailyFinished: 2, guestStarted: 1, guestFinished: 1,
            ownerStarted: 1, ownerFinished: 1, ownerDailyStarted: 1, ownerDailyFinished: 1,
          }),
        ],
      }),
    )
    const nba = screen.getByRole('region', { name: 'Basketball Redraft games' })
    expect(headline(nba)).toEqual(['1', '1', '0'])
    expect(within(nba).getByText('Game of the day').nextSibling).toHaveTextContent('1 started, 1 finished')
    const day = within(nba).getByText('Mon, Nov 16').closest('tr') as HTMLElement
    expect([...day.querySelectorAll('td')].map((c) => c.textContent)).toEqual(['1', '1', '0'])
    expect(within(nba).getByText(/Games per start day.*your signed-in games left out/)).toBeInTheDocument()
    // no number in the section is negative
    expect(nba.textContent).not.toMatch(/-\d/)
  })

  it('a game the owner claimed from guest mode stays a guest game: no negative number anywhere', async () => {
    await open(
      report({
        ownerConfigured: true,
        funnel: [frow({ started: 1, finished: 1, guestStarted: 1, guestFinished: 1 })], // the server does not count it as the owner's
      }),
    )
    const nba = screen.getByRole('region', { name: 'Basketball Redraft games' })
    expect(headline(nba)).toEqual(['1', '1', '0'])
    expect(within(nba).getByText('Started as a guest').nextSibling).toHaveTextContent('1 (1 finished)')
    expect(within(nba).getByText('Started signed in').nextSibling).toHaveTextContent('0')
    expect(nba.textContent).not.toMatch(/-\d/)
  })

  it('each site counts from its own first day, and a site with no entry says nothing counted yet', async () => {
    await open(
      report({
        countingSince: [
          { site: 'timemachine', since: '2026-11-13' },
          { site: 'leagues', since: '2026-11-16' },
        ],
        viewsByDay: [
          { day: '2026-11-15', site: 'timemachine', views: 4 },
          { day: '2026-11-17', site: 'leagues', views: 2 },
        ],
      }),
    )
    const tm = screen.getByRole('region', { name: 'Redraft site page views per day' })
    expect(within(tm).queryByText(/earlier days? in this range/)).toBeNull() // all five days are on or after Nov 13
    const lg = screen.getByRole('region', { name: 'League site page views per day' })
    expect(within(lg).getByText(/^3 earlier days in this range: not counted/)).toBeInTheDocument() // Nov 13, 14 and 15 come before the league site's own first day
    expect(within(lg).getByText(/counting since Nov 16, 2026/)).toBeInTheDocument()
    expect(screen.getByText(/Redraft site: counting began on Nov 13, 2026/)).toBeInTheDocument()
    expect(screen.getByText(/League site: counting began on Nov 16, 2026/)).toBeInTheDocument()
  })

  it('names a site that has sent nothing yet', async () => {
    await open(report({ countingSince: [{ site: 'timemachine', since: '2026-11-13' }] }))
    expect(screen.getByText('League site: nothing counted yet.')).toBeInTheDocument()
  })

  it('says the owner id is not set when it is not', async () => {
    await open(report({ ownerConfigured: false, funnel: [frow({ started: 3, finished: 1 })] }))
    expect(screen.getByText(/The owner id is not set on the server/)).toBeInTheDocument()
    expect(screen.queryByText(/left out of the totals above/)).toBeNull()
  })

  it('shows the caveats a reader needs, in plain words, with no em dash', async () => {
    await open(report({ countingSince: [{ site: 'timemachine', since: '2026-11-01' }] }))
    const c = screen.getByRole('region', { name: 'How to read these counts' })
    for (const re of [/US Eastern/, /a floor/, /could inflate/, /best effort/, /not a person/, /per browser and per site/, /never applies to the Redraft funnel/]) {
      expect(within(c).getByText(re)).toBeInTheDocument()
    }
    expect(document.body.textContent).not.toContain('—')
    const funnel = screen.getByRole('region', { name: 'Redraft funnel' })
    for (const re of [/saved games/, /one press/, /left open for ever/, /claimed before this shipped/, /guest-mode games cannot be told apart/]) {
      expect(within(funnel).getByText(re)).toBeInTheDocument()
    }
  })

  it('a league view with no league name shows its id, with the sport', async () => {
    await open(
      report({
        countingSince: [{ site: 'leagues', since: '2026-11-01' }],
        leagueViews: [
          { leagueId: '1122386008709910528', leagueName: null, sport: 'nba', views: 6 },
          { leagueId: '55', leagueName: '"Ball Knowers"', sport: 'nfl', views: 2 },
        ],
      }),
    )
    const lv = screen.getByRole('region', { name: 'League views' })
    expect(within(lv).getByText('1122386008709910528')).toBeInTheDocument()
    expect(within(lv).getByText('"Ball Knowers"')).toBeInTheDocument()
    expect(within(lv).getByText('NBA')).toBeInTheDocument()
    expect(within(lv).getByText(/locked to you/)).toBeInTheDocument()
  })
})
