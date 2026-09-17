import { waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { draftSummary, renderAtPath, resetRail } from '../testRailHelpers'

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
    expect(rowLabels()).toEqual(['Draft board', 'History', 'Power rankings', 'Analysis', 'Mock it'])
  })

  // The sport gate is one-way, and it now lives in one place rather than in
  // this component's JSX -- so the rail and the palette cannot disagree about
  // whether basketball has an Analysis page.
  it('does not offer Analysis to a basketball league', async () => {
    const { leagueSection, rowLabels } = renderAtPath('/leagues/L_NBA/history', { drafts: [NBA] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(rowLabels()).not.toContain('Analysis')
    expect(rowLabels()).toContain('Power rankings')
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
