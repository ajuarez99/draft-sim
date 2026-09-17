import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { draftSummary, getDraftsSpy, getManagersSpy, renderAtPath, resetRail } from '../testRailHelpers'

/*
 * The palette as the shell actually wires it: the shortcut, the visible entry
 * point, and the promise that opening it costs no network on a warm cache.
 */

afterEach(resetRail)

const LEAGUE = draftSummary({ sleeperLeagueId: 'L1', sleeperDraftId: 'D1', leagueName: 'Ball Knowers' })

async function openPalette(user: ReturnType<typeof userEvent.setup>) {
  await user.keyboard('{Control>}k{/Control}')
  await waitFor(() => expect(screen.getByRole('dialog', { name: 'Jump to' })).toBeTruthy())
}

describe('the jump-to shortcut', () => {
  it('opens the palette on Ctrl+K from any route', async () => {
    const user = userEvent.setup()
    renderAtPath('/leagues/L1/analysis', { drafts: [LEAGUE] })

    expect(screen.queryByRole('dialog', { name: 'Jump to' })).toBeNull()
    await openPalette(user)
  })

  it('opens from the rail control too, for anyone who never learns the shortcut', async () => {
    const user = userEvent.setup()
    renderAtPath('/', { drafts: [LEAGUE] })

    await user.click(screen.getByTitle('Jump to (Ctrl+K)'))

    await waitFor(() => expect(screen.getByRole('dialog', { name: 'Jump to' })).toBeTruthy())
  })

  // A bare `k` must not open it: draft rooms have their own player search, and
  // every page with a text input would fight an unmodified key.
  it('ignores an unmodified k', async () => {
    const user = userEvent.setup()
    renderAtPath('/', { drafts: [LEAGUE] })

    await user.keyboard('k')

    expect(screen.queryByRole('dialog', { name: 'Jump to' })).toBeNull()
  })

  it('closes again on Escape', async () => {
    const user = userEvent.setup()
    renderAtPath('/', { drafts: [LEAGUE] })

    await openPalette(user)
    await user.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Jump to' })).toBeNull())
  })
})

describe('the phone return affordance', () => {
  /*
   * At phone width the rail is a horizontal bar that scrolls away with the
   * page -- deliberately, because pinning it measured 252px of an 812px
   * viewport. That leaves the return trip: from the bottom of a long standings
   * table there is no navigation on screen. This button is the whole fix.
   *
   * jsdom applies no media queries, so what is asserted here is the behaviour
   * (it exists, it opens the palette). That it is hidden above 860px and
   * occupies no permanent band is a CSS claim, verified in a real browser.
   */
  it('opens the palette from the fixed control', async () => {
    const user = userEvent.setup()
    const { container } = renderAtPath('/leagues/L1/history', { drafts: [LEAGUE] })

    const fab = container.querySelector<HTMLButtonElement>('.jumpto-fab')
    expect(fab).not.toBeNull()

    await user.click(fab!)

    await waitFor(() => expect(screen.getByRole('dialog', { name: 'Jump to' })).toBeTruthy())
  })

  it('is outside the scrolling pane, so scroll position cannot hide it', () => {
    const { container } = renderAtPath('/leagues/L1/history', { drafts: [LEAGUE] })

    const fab = container.querySelector('.jumpto-fab')
    // A child of .app-main would scroll with the content it is meant to rescue
    // you from.
    expect(fab?.closest('.app-main')).toBeNull()
    expect(fab?.parentElement?.className).toContain('app-shell')
  })
})

describe('what the palette offers', () => {
  it('lists the league pages built from the same table the rail renders', async () => {
    const user = userEvent.setup()
    renderAtPath('/', { drafts: [LEAGUE] })

    await openPalette(user)

    await waitFor(() => expect(screen.getAllByRole('option').length).toBeGreaterThan(0))
    const labels = screen.getAllByRole('option').map((o) => o.textContent ?? '')
    expect(labels.some((l) => l.includes('History'))).toBe(true)
    expect(labels.some((l) => l.includes('Power rankings'))).toBe(true)
  })

  it('offers no Analysis for a basketball league', async () => {
    const user = userEvent.setup()
    renderAtPath('/', {
      drafts: [draftSummary({ sport: 'nba', leagueName: 'Hoops', sleeperLeagueId: 'LN', sleeperDraftId: 'DN' })],
    })

    await openPalette(user)

    await waitFor(() => expect(screen.getAllByRole('option').length).toBeGreaterThan(0))
    const labels = screen.getAllByRole('option').map((o) => o.textContent ?? '')
    expect(labels.some((l) => l.includes('Analysis'))).toBe(false)
  })
})

describe('what opening it costs', () => {
  /*
   * NFR-001. The rail has already fetched the draft list by the time anyone
   * opens the palette, and the palette reads that same cache rather than
   * fetching an identical copy. If this ever regresses, every Ctrl+K on a
   * league page becomes a round trip.
   */
  it('does not refetch the draft list the rail already holds', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/L1/history', { drafts: [LEAGUE] })

    // Warm: the rail has resolved its league, so the cache is populated.
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    const callsBefore = getDraftsSpy.mock.calls.length
    expect(callsBefore).toBe(1)

    await openPalette(user)
    await waitFor(() => expect(screen.getAllByRole('option').length).toBeGreaterThan(0))

    expect(getDraftsSpy.mock.calls.length).toBe(callsBefore)
  })

  it('builds the index once, not once per open', async () => {
    const user = userEvent.setup()
    renderAtPath('/', { drafts: [LEAGUE] })

    await openPalette(user)
    await waitFor(() => expect(getManagersSpy.mock.calls.length).toBeGreaterThan(0))
    const after = getManagersSpy.mock.calls.length

    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Jump to' })).toBeNull())
    await openPalette(user)

    expect(getManagersSpy.mock.calls.length).toBe(after)
  })

  // The index costs two manager fetches, so it must not be built for people
  // who never open the palette.
  it('fetches nothing for the index until the palette is opened', async () => {
    const { leagueSection } = renderAtPath('/leagues/L1/history', { drafts: [LEAGUE] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())

    expect(getManagersSpy.mock.calls.length).toBe(0)
  })

  // Both sports, because ten of the twelve Ball Knowers managers are the same
  // Sleeper user in each and a single-sport call would list only the football
  // half of the room.
  it('asks for managers in both sports', async () => {
    const user = userEvent.setup()
    renderAtPath('/', { drafts: [LEAGUE] })

    await openPalette(user)

    await waitFor(() => expect(getManagersSpy.mock.calls.length).toBe(2))
    expect(getManagersSpy.mock.calls.map((c) => c[0]).sort()).toEqual(['nba', 'nfl'])
  })
})
