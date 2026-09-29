import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useLeagueDataVersion } from '../leagueDataVersion'
import {
  draftSummary,
  getLeagueRefreshSpy,
  refreshLeagueSpy,
  refreshStatus,
  renderAtPath,
  resetRail,
} from '../testRailHelpers'
import { REFRESH_POLL_MS } from './LeagueRailSection'

/*
 * The question this whole feature is about: standing on this URL, does the rail
 * still know which league you are in?
 *
 * Three of these routes answered "no" before 003. The sharpest was Analysis --
 * the rail linked you to it and then, because the matcher did not recognise the
 * route the rail had just sent you to, deleted the League section that linked
 * you.
 */

afterEach(resetRail)

const NFL = draftSummary({
  sleeperDraftId: 'D_NFL',
  sleeperLeagueId: 'L_NFL',
  leagueName: 'Football League',
  sport: 'nfl',
  status: 'complete',
})

const NBA = draftSummary({
  id: 2,
  leagueId: 2,
  sleeperDraftId: 'D_NBA',
  sleeperLeagueId: 'L_NBA',
  leagueName: 'Hoops League',
  sport: 'nba',
  status: 'complete',
})

describe('league context per route', () => {
  it.each([
    ['/leagues/L_NFL/history', 'History'],
    ['/leagues/L_NFL/power', 'Power rankings'],
    ['/leagues/L_NFL/analysis', 'Analysis'],
    ['/drafts/D_NFL/board', 'Draft board'],
  ])('shows the league on %s and marks %s current', async (path, current) => {
    const { leagueSection, currentRowLabels } = renderAtPath(path, { drafts: [NFL] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(currentRowLabels()).toEqual([current])
  })

  it('shows the league on a manager history page reached from a league', async () => {
    const { leagueSection, rowLabels } = renderAtPath('/managers/12/history', {
      drafts: [NFL],
      state: { railLeagueId: 'L_NFL' },
    })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(rowLabels()).toContain('History')
  })

  it('marks nothing current on a manager history page', async () => {
    const { leagueSection, currentRowLabels } = renderAtPath('/managers/12/history', {
      drafts: [NFL],
      state: { railLeagueId: 'L_NFL' },
    })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    // You are inside the league but on none of its pages. Marking one would
    // claim you are somewhere you are not.
    expect(currentRowLabels()).toEqual([])
  })

  // The honest-absence half of the same rule. A direct visit genuinely has no
  // league, and inventing one would be worse than showing none.
  it('shows no league on a manager history page visited directly', async () => {
    const { leagueSection, rail } = renderAtPath('/managers/12/history', { drafts: [NFL] })

    await waitFor(() => expect(rail()).not.toBeNull())
    expect(leagueSection()).toBeNull()
  })

  it('shows no league on a mock with no seeding league', async () => {
    const { leagueSection, rail } = renderAtPath('/mock/4', { drafts: [NFL] })

    await waitFor(() => expect(rail()).not.toBeNull())
    expect(leagueSection()).toBeNull()
  })

  it('shows the seeding league on a mock that has one', async () => {
    const { leagueSection, rowLabels } = renderAtPath('/mock/4', {
      drafts: [NFL],
      state: { railLeagueId: 'L_NFL' },
    })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(rowLabels()).toContain('History')
  })
})

describe('what a league is offered', () => {
  it('offers Analysis to a football league', async () => {
    const { leagueSection, rowLabels } = renderAtPath('/leagues/L_NFL/history', { drafts: [NFL] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(rowLabels()).toEqual([
      'Draft board',
      'History',
      'Power rankings',
      'Analysis',
      'Roster management',
      'Expected wins',
      'Season forecast',
      'Weekly report',
      'Superlatives',
      'Mock it',
    ])
  })

  // The sport gate is one-way, and it now lives in one place rather than in
  // this component's JSX -- so the rail and the palette cannot disagree about
  // whether basketball has an Analysis page.
  it('does not offer Analysis to a basketball league', async () => {
    const { leagueSection, rowLabels } = renderAtPath('/leagues/L_NBA/history', { drafts: [NBA] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(rowLabels()).not.toContain('Analysis')
    expect(rowLabels()).toContain('Power rankings')
    // Roster management IS offered to basketball, and the contrast with
    // Analysis one line up is the whole point: Analysis is gated because two
    // of its blocks are projections and there is no basketball projection
    // source, while nothing on Roster management is a projection -- it reads
    // points already scored, which Sleeper reports for both sports.
    expect(rowLabels()).toContain('Roster management')
    expect(rowLabels()).toContain('Expected wins')
    expect(rowLabels()).toContain('Season forecast')
    expect(rowLabels()).toContain('Weekly report')
  })

  it('offers Follow live only while a draft is running', async () => {
    const drafting = draftSummary({ sleeperDraftId: 'D_LIVE', sleeperLeagueId: 'L_LIVE', status: 'drafting' })
    const { leagueSection, rowLabels } = renderAtPath('/drafts/D_LIVE', { drafts: [drafting] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(rowLabels()).toContain('Follow live')
    // A running draft opens the room, not a board of picks that don't exist.
    expect(rowLabels()).toContain('Draft room')
  })

  it('marks Follow live current on the live route', async () => {
    const drafting = draftSummary({ sleeperDraftId: 'D_LIVE', sleeperLeagueId: 'L_LIVE', status: 'drafting' })
    const { leagueSection, currentRowLabels } = renderAtPath('/drafts/D_LIVE/live', { drafts: [drafting] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(currentRowLabels()).toEqual(['Follow live'])
  })
})

describe('routes with no league at all', () => {
  it.each([['/'], ['/managers'], ['/mock/new']])('shows no league section on %s', async (path) => {
    const { leagueSection, rail } = renderAtPath(path, { drafts: [NFL] })

    await waitFor(() => expect(rail()).not.toBeNull())
    expect(leagueSection()).toBeNull()
  })
})


/*
 * specs/009-auto-data-refresh T026/T028: the rail starts a refresh when it lands
 * in a league, follows it while it runs, and tells the pages when it is done.
 */
describe('refresh on visit', () => {
  function VersionProbe() {
    return <p data-testid="version">{useLeagueDataVersion('L_NFL')}</p>
  }

  const flush = () =>
    act(async () => {
      await Promise.resolve()
    })

  it('calls refreshLeague once per league, and again when the league changes', async () => {
    renderAtPath('/leagues/L_NFL/history', { drafts: [NFL, NBA] })

    await waitFor(() => expect(refreshLeagueSpy).toHaveBeenCalledTimes(1))
    expect(refreshLeagueSpy).toHaveBeenCalledWith('L_NFL')

    // Same league, further renders (the switcher opening): still one.
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /switch/i }))
    expect(refreshLeagueSpy).toHaveBeenCalledTimes(1)

    await user.click(await screen.findByRole('menuitem', { name: /Hoops League/ }))
    await waitFor(() => expect(refreshLeagueSpy).toHaveBeenCalledTimes(2))
    expect(refreshLeagueSpy).toHaveBeenLastCalledWith('L_NBA')
  })

  it('polls while RUNNING, bumps the data version on FRESH, and stops polling', async () => {
    const running = refreshStatus({ state: 'RUNNING', leagueSleeperId: 'L_NFL' })
    const fresh = refreshStatus({
      state: 'FRESH',
      leagueSleeperId: 'L_NFL',
      lastSuccessAt: new Date().toISOString(),
    })

    // The rail's poll is one setTimeout(REFRESH_POLL_MS) at a time. Catch just
    // those and fire them by hand: fake timers would also stall waitFor and
    // testing-library's own scheduling, and a real 3 s wait is a slow test.
    const pending: Array<() => void> = []
    const realSetTimeout = globalThis.setTimeout
    vi.spyOn(globalThis, 'setTimeout').mockImplementation(((fn: () => void, ms?: number, ...rest: unknown[]) => {
      if (ms === REFRESH_POLL_MS) {
        pending.push(fn)
        return 0 as unknown as ReturnType<typeof setTimeout>
      }
      return realSetTimeout(fn, ms, ...rest)
    }) as unknown as typeof setTimeout)
    const firePoll = () =>
      act(async () => {
        pending.shift()!()
        await Promise.resolve()
      })

    const { leagueSection } = renderAtPath('/leagues/L_NFL/history', {
      drafts: [NFL],
      refresh: running,
      polls: [running, fresh],
      page: <VersionProbe />,
    })
    await waitFor(() => expect(leagueSection()?.textContent).toContain('Updating…'))
    expect(pending).toHaveLength(1)
    expect(getLeagueRefreshSpy).not.toHaveBeenCalled()
    expect(screen.getByTestId('version')).toHaveTextContent('0')

    await firePoll()
    await waitFor(() => expect(getLeagueRefreshSpy).toHaveBeenCalledTimes(1))
    expect(screen.getByTestId('version')).toHaveTextContent('0')
    expect(leagueSection()?.textContent).toContain('Updating…')
    expect(pending).toHaveLength(1)

    await firePoll()
    await waitFor(() => expect(screen.getByTestId('version')).toHaveTextContent('1'))
    expect(getLeagueRefreshSpy).toHaveBeenCalledTimes(2)
    expect(leagueSection()?.textContent).toContain('Updated just now')
    expect(leagueSection()?.textContent).not.toContain('Updating…')
    expect(pending).toHaveLength(0)
  })

  it('does not bump the data version when a refresh was never running', async () => {
    const { leagueSection } = renderAtPath('/leagues/L_NFL/history', {
      drafts: [NFL],
      refresh: refreshStatus({ state: 'FRESH', leagueSleeperId: 'L_NFL', lastSuccessAt: new Date().toISOString() }),
      page: <VersionProbe />,
    })
    await waitFor(() => expect(leagueSection()?.textContent).toContain('Updated just now'))

    expect(screen.getByTestId('version')).toHaveTextContent('0')
    expect(getLeagueRefreshSpy).not.toHaveBeenCalled()
  })

  it('renders the could-not-reach-Sleeper line with the age of the data on FAILED', async () => {
    const threeHoursAgo = new Date(Date.now() - 3 * 3600_000).toISOString()
    const { leagueSection } = renderAtPath('/leagues/L_NFL/history', {
      drafts: [NFL],
      refresh: refreshStatus({
        state: 'FAILED',
        leagueSleeperId: 'L_NFL',
        lastSuccessAt: threeHoursAgo,
        lastFailureAt: new Date().toISOString(),
      }),
    })

    await waitFor(() =>
      expect(leagueSection()?.textContent).toContain("Couldn't reach Sleeper — data from 3 h ago"),
    )
  })

  it('shows nothing, and does not retry, when the POST fails', async () => {
    const { leagueSection } = renderAtPath('/leagues/L_NFL/history', { drafts: [NFL] })
    // Before the league resolves, so the rail's one POST hits the failing stub.
    refreshLeagueSpy.mockRejectedValue(new Error('network'))

    await waitFor(() => expect(refreshLeagueSpy).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await flush()

    expect(leagueSection()?.querySelector('.rail-league-refresh')).toBeNull()
    expect(getLeagueRefreshSpy).not.toHaveBeenCalled()
    expect(refreshLeagueSpy).toHaveBeenCalledTimes(1)
  })
})
