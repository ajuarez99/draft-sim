import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { switchTarget } from './LeagueRailSection'
import { draftSummary, renderAtPath, resetRail } from '../testRailHelpers'
import type { LeagueContext } from '../destinations'

/*
 * Moving between leagues and seasons without going Home.
 *
 * The rule worth testing is the fallback: a page you are on may not exist in
 * the league you are switching to, and the switcher has to land you somewhere
 * real rather than on a page that league does not have.
 */

afterEach(resetRail)

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

function ctxOf(d: typeof NFL_A): LeagueContext {
  return { lineage: { current: d, seasons: [d] }, season: d }
}

describe('switchTarget', () => {
  it('keeps the page you were on when the other league has it', () => {
    expect(switchTarget('history', ctxOf(NFL_B))).toBe('/leagues/LB/history')
    expect(switchTarget('power', ctxOf(NFL_B))).toBe('/leagues/LB/power')
  })

  // The case that makes the fallback necessary. Analysis is football-only, and
  // the rule for that lives in the destination table -- this reads it rather
  // than restating it, which is why the two can't drift apart.
  it('falls back to History when the target league has no such page', () => {
    expect(switchTarget('analysis', ctxOf(NBA_C))).toBe('/leagues/LC/history')
  })

  it('keeps Analysis between two football leagues', () => {
    expect(switchTarget('analysis', ctxOf(NFL_B))).toBe('/leagues/LB/analysis')
  })

  it('sends a board to the target league’s own board', () => {
    expect(switchTarget('board', ctxOf(NFL_B))).toBe('/drafts/DB/board')
  })

  it('falls back to History from a page that is not a league page at all', () => {
    expect(switchTarget(null, ctxOf(NFL_B))).toBe('/leagues/LB/history')
  })
})

describe('the switcher in the rail', () => {
  it('is absent when there is nothing to switch to', async () => {
    const { leagueSection } = renderAtPath('/leagues/LA/history', { drafts: [NFL_A] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(screen.queryByTitle('Switch league or season')).toBeNull()
  })

  it('lists the other leagues, not the one you are in', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/LA/history', { drafts: [NFL_A, NFL_B, NBA_C] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await waitFor(() => expect(screen.queryByTitle('Switch league or season')).not.toBeNull())
    await user.click(screen.getByTitle('Switch league or season'))

    const items = screen.getAllByRole('menuitem').map((i) => i.textContent ?? '')
    expect(items.some((t) => t.includes('Beta League'))).toBe(true)
    expect(items.some((t) => t.includes('Hoops League'))).toBe(true)
    expect(items.some((t) => t.includes('Alpha League'))).toBe(false)
  })

  it('offers the same page in the league you switch to', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/LA/power', { drafts: [NFL_A, NFL_B] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await waitFor(() => expect(screen.queryByTitle('Switch league or season')).not.toBeNull())
    await user.click(screen.getByTitle('Switch league or season'))

    const beta = screen.getAllByRole('menuitem').find((i) => (i.textContent ?? '').includes('Beta'))
    expect(beta?.getAttribute('href')).toBe('/leagues/LB/power')
  })

  it('offers an NBA league History when you are on an NFL league’s Analysis', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/LA/analysis', { drafts: [NFL_A, NBA_C] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await waitFor(() => expect(screen.queryByTitle('Switch league or season')).not.toBeNull())
    await user.click(screen.getByTitle('Switch league or season'))

    const hoops = screen.getAllByRole('menuitem').find((i) => (i.textContent ?? '').includes('Hoops'))
    expect(hoops?.getAttribute('href')).toBe('/leagues/LC/history')
  })
})

describe('seasons while the rail is collapsed', () => {
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

  /*
   * The gap this closes: draft rooms collapse the rail by default (a 14-team
   * board has no width to spare), and the season links were gated on the rail
   * being expanded -- so the seasons vanished in exactly the room where you
   * most want to compare them.
   */
  it('reaches the other seasons from a collapsed draft room', async () => {
    const user = userEvent.setup()
    const { leagueSection, container } = renderAtPath('/drafts/D26/board', { drafts: [S26, S25] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(container.querySelector('nav.app-rail')?.className).toContain('collapsed')
    // The old inline row of years is gone at this width...
    expect(container.querySelector('.rail-league-seasons')).toBeNull()

    await user.click(screen.getByTitle('Switch league or season'))

    // ...and the flyout carries them instead.
    const items = screen.getAllByRole('menuitem').map((i) => i.textContent ?? '')
    expect(items.some((t) => t.includes('2025'))).toBe(true)
    expect(items.some((t) => t.includes('2026'))).toBe(true)
  })

  it('links a finished season to its board', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/drafts/D26/board', { drafts: [S26, S25] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await user.click(screen.getByTitle('Switch league or season'))

    const older = screen.getAllByRole('menuitem').find((i) => (i.textContent ?? '').includes('2025'))
    expect(older?.getAttribute('href')).toBe('/drafts/D25/board')
  })

  it('still shows the inline season row when the rail is expanded', async () => {
    const { leagueSection, container } = renderAtPath('/leagues/L26/history', { drafts: [S26, S25] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(container.querySelector('nav.app-rail')?.className).not.toContain('collapsed')
    expect(container.querySelector('.rail-league-seasons')).not.toBeNull()
  })
})
