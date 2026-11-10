import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { draftSummary, renderAtPath, resetShell } from '../testShellHelpers'

/*
 * The league switcher in the league header: moving between leagues without going Home. The pure
 * landing rule (a page you are on may not exist in the league you are switching to) is tested on
 * switchTarget itself in switchTarget.test.ts; this is the menu that uses it.
 */

afterEach(resetShell)

const NFL_A = draftSummary({ sleeperLeagueId: 'LA', sleeperDraftId: 'DA', leagueName: 'Alpha League' })
const NFL_B = draftSummary({
  id: 2,
  leagueId: 2,
  sleeperLeagueId: 'LB',
  sleeperDraftId: 'DB',
  leagueName: 'Beta League',
})
const NBA_C = draftSummary({
  id: 3,
  leagueId: 3,
  sleeperLeagueId: 'LC',
  sleeperDraftId: 'DC',
  leagueName: 'Hoops League',
  sport: 'nba',
})

describe('the switcher in the league header', () => {
  it('is absent when there is nothing to switch to', async () => {
    const { leagueSection } = renderAtPath('/leagues/LA/history', { drafts: [NFL_A] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(screen.queryByTitle('Switch league')).toBeNull()
  })

  it('lists the other leagues, not the one you are in', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/LA/history', { drafts: [NFL_A, NFL_B, NBA_C] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await waitFor(() => expect(screen.queryByTitle('Switch league')).not.toBeNull())
    await user.click(screen.getByTitle('Switch league'))

    const items = screen.getAllByRole('menuitem').map((i) => i.textContent ?? '')
    expect(items.some((t) => t.includes('Beta League'))).toBe(true)
    expect(items.some((t) => t.includes('Hoops League'))).toBe(true)
    expect(items.some((t) => t.includes('Alpha League'))).toBe(false)
  })

  it('offers the same page in the league you switch to', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/LA/power', { drafts: [NFL_A, NFL_B] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await waitFor(() => expect(screen.queryByTitle('Switch league')).not.toBeNull())
    await user.click(screen.getByTitle('Switch league'))

    const beta = screen.getAllByRole('menuitem').find((i) => (i.textContent ?? '').includes('Beta'))
    expect(beta?.getAttribute('href')).toBe('/leagues/LB/power')
  })

  it('lands an NFL league on its League home when you are on an NBA league’s Schedule grid', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/LC/schedule', { drafts: [NFL_A, NBA_C] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await waitFor(() => expect(screen.queryByTitle('Switch league')).not.toBeNull())
    await user.click(screen.getByTitle('Switch league'))

    const alpha = screen.getAllByRole('menuitem').find((i) => (i.textContent ?? '').includes('Alpha'))
    expect(alpha?.getAttribute('href')).toBe('/leagues/LA')
  })

  it('moves focus in on open, closes on Escape and returns focus to the trigger', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/LA/history', { drafts: [NFL_A, NFL_B] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await waitFor(() => expect(screen.queryByTitle('Switch league')).not.toBeNull())

    const trigger = screen.getByTitle('Switch league')
    await user.click(trigger)
    expect(screen.getByRole('menu').contains(document.activeElement)).toBe(true)

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu')).toBeNull()
    expect(document.activeElement).toBe(trigger)
  })
})

/*
 * The old rail collapsed in a draft room and its season links vanished there; the flyout was the
 * only route to them. The compact top bar has the same job now: a draft room is exactly where
 * comparing seasons is wanted (plan review PR-02).
 */
describe('seasons and leagues from a draft room (the compact top bar)', () => {
  const S26 = draftSummary({
    sleeperLeagueId: 'L26',
    sleeperDraftId: 'D26',
    season: 2026,
    previousLeagueId: 'L25',
    leagueName: 'Alpha League',
  })
  const S25 = draftSummary({
    id: 9,
    leagueId: 9,
    sleeperLeagueId: 'L25',
    sleeperDraftId: 'D25',
    season: 2025,
    leagueName: 'Alpha League',
  })

  it('carries the season dropdown, the switcher and the tabs in the top bar itself', async () => {
    const other = draftSummary({ id: 5, leagueId: 5, sleeperLeagueId: 'LB', sleeperDraftId: 'DB', leagueName: 'Beta League' })
    const { leagueSection, topBar } = renderAtPath('/drafts/D26/board', { drafts: [S26, S25, other] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    const bar = topBar()!
    expect(bar.classList.contains('compact')).toBe(true)
    expect(bar.contains(leagueSection())).toBe(true)
    expect(bar.querySelector('nav[aria-label="League"]')).not.toBeNull()
    expect(bar.querySelector('button[aria-label^="Season:"]')).not.toBeNull()
    await waitFor(() => expect(bar.querySelector('button[title="Switch league"]')).not.toBeNull())
    // The global links fold into the menu button there, so no Main nav competes for the width.
    expect(bar.querySelector('nav[aria-label="Main"]')).toBeNull()
    expect(screen.getByRole('button', { name: 'Menu' })).toBeTruthy()
  })

  it('links a finished season to its board', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/drafts/D26/board', { drafts: [S26, S25] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await user.click(await screen.findByRole('button', { name: /^Season: / }))

    const older = screen.getAllByRole('menuitem').find((i) => (i.textContent ?? '').includes('2025'))
    expect(older?.getAttribute('href')).toBe('/drafts/D25/board')
  })

  it('keeps the full header and the global links on a content page', async () => {
    const { leagueSection, topBar, container } = renderAtPath('/leagues/L26/history', { drafts: [S26, S25] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(topBar()!.classList.contains('compact')).toBe(false)
    expect(container.querySelector('.league-header:not(.compact)')).not.toBeNull()
    expect(topBar()!.querySelector('nav[aria-label="Main"]')).not.toBeNull()
  })
})
