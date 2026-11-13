import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { draftSummary, renderAtPath, resetShell } from '../testShellHelpers'

/*
 * Phone navigation (claude/site-redesign.md Phase 2; plan review PR-01, PR-19, PR-26). At or below
 * 860px the top bar is wordmark / search / menu / account, a league's tabs are a fixed bottom bar
 * with "More", and the global links (and Sign out) live in one sheet. jsdom has no layout, so
 * matchMedia is stubbed where a test needs a phone, and everything here is behaviour: what exists,
 * what it opens, where focus goes. That the bar is pinned, 56px and clear of the pick card is CSS and
 * a browser check.
 */

afterEach(() => {
  resetShell()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

const NFL = draftSummary({ sleeperLeagueId: 'L1', sleeperDraftId: 'D1', leagueName: 'Ball Knowers' })
const NBA = draftSummary({ id: 2, leagueId: 2, sleeperLeagueId: 'LN', sleeperDraftId: 'DN', leagueName: 'Hoops', sport: 'nba' })

function stubPhone() {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('860px'),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}

const labels = (root: HTMLElement) => [...root.querySelectorAll('a, button')].map((e) => (e.textContent ?? '').trim())

describe('the sub-tab strip on a phone', () => {
  it('scrolls the current page into view when the strip overflows', async () => {
    const scrollIntoView = vi.fn()
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(1249)
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(347)
    Element.prototype.scrollIntoView = scrollIntoView

    renderAtPath('/leagues/L1/power', { drafts: [NFL] })

    await waitFor(() => expect(scrollIntoView).toHaveBeenCalled())
    const target = scrollIntoView.mock.contexts[0] as HTMLElement
    expect(target.textContent).toBe('Power rankings')
    expect(scrollIntoView).toHaveBeenCalledWith({ inline: 'nearest', block: 'nearest' })
  })

  it('marks the end edge for fading only while the strip overflows', async () => {
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(1249)
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(347)
    Element.prototype.scrollIntoView = vi.fn()
    const { container } = renderAtPath('/leagues/L1/power', { drafts: [NFL] })

    await waitFor(() => expect(container.querySelector('.league-subtabs')).not.toBeNull())
    await waitFor(() => expect(container.querySelector('.league-subtabs')?.getAttribute('data-overflow-end')).toBe('true'))
    expect(container.querySelector('.league-subtabs')?.hasAttribute('data-overflow-start')).toBe(false)
  })

  it('has no fade marker when the strip fits (desktop / jsdom)', async () => {
    const { container } = renderAtPath('/leagues/L1/power', { drafts: [NFL] })
    await waitFor(() => expect(container.querySelector('.league-subtabs')).not.toBeNull())
    expect(container.querySelector('.league-subtabs')?.hasAttribute('data-overflow-end')).toBe(false)
    expect(container.querySelector('.league-subtabs')?.hasAttribute('data-overflow-start')).toBe(false)
  })

  it('keeps Mock it outside the scrolling strip, so it cannot be scrolled off', async () => {
    stubPhone()
    const { container, leagueSection } = renderAtPath('/drafts/D1/board', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    const mock = within(container as HTMLElement).getByRole('button', { name: 'Mock it' })
    expect(mock.closest('.league-subtabs')).toBeNull()
    expect(mock.closest('.league-subtabs-wrap')).not.toBeNull()
  })
})

describe('the bottom tab bar', () => {
  beforeEach(stubPhone)

  it('shows Home, Matchups, Season, Draft and More inside a league, and the header renders no second tab strip', async () => {
    const { leagueSection, container } = renderAtPath('/leagues/L1/power', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())

    const bar = screen.getByRole('navigation', { name: 'League' })
    expect(bar.classList.contains('bottom-bar')).toBe(true)
    expect(labels(bar)).toEqual(['Home', 'Matchups', 'Season', 'Draft', 'More'])
    expect(within(bar).getByRole('link', { name: 'Season' }).getAttribute('aria-current')).toBe('page')
    expect(container.querySelectorAll('nav[aria-label="League"]')).toHaveLength(1)
  })

  // The old FAB sat inside .app-shell for the same reason: scroll position must not hide it.
  it('is outside the scrolling pane and replaces the jump-to button', async () => {
    const { leagueSection, container } = renderAtPath('/leagues/L1/history', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())

    const bar = container.querySelector('.bottom-bar')
    expect(bar).not.toBeNull()
    expect(bar!.closest('.app-main')).toBeNull()
    expect(bar!.parentElement?.className).toContain('app-shell')
    expect(container.querySelector('.jumpto-fab')).toBeNull()
  })

  it('is absent outside a league', async () => {
    const { topBar, container } = renderAtPath('/managers', { drafts: [NFL] })
    await waitFor(() => expect(topBar()).not.toBeNull())
    expect(container.querySelector('.bottom-bar')).toBeNull()
  })

  it('opens the sheet from More with the remaining league tabs above the global links (football: History)', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/L1/power', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())

    await user.click(screen.getByRole('button', { name: 'More' }))

    const sheet = screen.getByRole('dialog', { name: 'Menu' })
    expect(labels(sheet)).toEqual(['History', 'Leagues', 'Mock drafts', 'Managers', 'Time Machine', 'Sign out'])
  })

  it('lists Players and History behind More for a basketball league', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/LN/power', { drafts: [NBA] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())

    await user.click(screen.getByRole('button', { name: 'More' }))

    expect(labels(screen.getByRole('dialog', { name: 'Menu' })).slice(0, 2)).toEqual(['Players', 'History'])
  })

  it('moves focus into the sheet, closes it on Escape and returns focus to More', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/L1/power', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())

    const more = screen.getByRole('button', { name: 'More' })
    await user.click(more)
    const sheet = screen.getByRole('dialog', { name: 'Menu' })
    expect(sheet.contains(document.activeElement)).toBe(true)

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: 'Menu' })).toBeNull()
    expect(document.activeElement).toBe(more)
  })

  it('lights More when the current tab is one of the tabs behind it', async () => {
    const { leagueSection } = renderAtPath('/leagues/L1/history', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(screen.getByRole('button', { name: 'More' }).classList.contains('on')).toBe(true)
  })
})

describe('the menu button outside a league (plan review PR-01)', () => {
  beforeEach(stubPhone)

  it('reaches Leagues, Mock drafts, Managers, Time Machine and Sign out from the top bar', async () => {
    const user = userEvent.setup()
    const { topBar } = renderAtPath('/managers', { drafts: [NFL] })
    await waitFor(() => expect(topBar()).not.toBeNull())
    // On a phone the top bar carries no global links of its own.
    expect(topBar()!.querySelector('nav[aria-label="Main"]')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Menu' }))

    expect(labels(screen.getByRole('dialog', { name: 'Menu' }))).toEqual([
      'Leagues',
      'Mock drafts',
      'Managers',
      'Time Machine',
      'Sign out',
    ])
  })

  it('shows wordmark, search, menu and account in the bar', async () => {
    const { topBar } = renderAtPath('/', { drafts: [NFL] })
    await waitFor(() => expect(topBar()).not.toBeNull())
    const bar = topBar()!
    expect(within(bar).getByRole('link', { name: /Ball Knowers/ })).toBeTruthy()
    expect(within(bar).getByRole('button', { name: 'Jump to a league, page or manager' })).toBeTruthy()
    expect(within(bar).getByRole('button', { name: 'Menu' })).toBeTruthy()
    expect(within(bar).getByRole('button', { name: /^account:/i })).toBeTruthy()
  })

  it('signs out from the sheet', async () => {
    const user = userEvent.setup()
    renderAtPath('/', { drafts: [NFL] })

    await user.click(await screen.findByRole('button', { name: 'Menu' }))
    await user.click(within(screen.getByRole('dialog', { name: 'Menu' })).getByRole('button', { name: 'Sign out' }))

    // Signed out: the shell renders no chrome at all.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Menu' })).toBeNull())
  })

  function Probe() {
    const loc = useLocation()
    return <p data-testid="probe">{`${loc.pathname} ${JSON.stringify(loc.state)}`}</p>
  }

  it('Mock drafts in the sheet opens the new-mock modal request on Home, like the desktop link', async () => {
    const user = userEvent.setup()
    renderAtPath('/managers', { drafts: [NFL], page: <Probe /> })

    await user.click(await screen.findByRole('button', { name: 'Menu' }))
    await user.click(within(screen.getByRole('dialog', { name: 'Menu' })).getByRole('button', { name: 'Mock drafts' }))

    const probe = screen.getByTestId('probe').textContent ?? ''
    expect(probe.startsWith('/ ')).toBe(true)
    expect(JSON.parse(probe.slice(2))).toEqual({ openMock: true, mockSeed: null })
    expect(screen.queryByRole('dialog', { name: 'Menu' })).toBeNull()
  })

  it('closes the sheet on a navigation', async () => {
    const user = userEvent.setup()
    renderAtPath('/managers', { drafts: [NFL] })
    await user.click(await screen.findByRole('button', { name: 'Menu' }))
    await user.click(within(screen.getByRole('dialog', { name: 'Menu' })).getByRole('link', { name: 'Time Machine' }))
    // MemoryRouter navigated; the sheet is a menu, not a mode.
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Menu' })).toBeNull())
  })
})

describe('Sign out from the account menu', () => {
  it('is not a standalone control next to the nav, and is reachable from the avatar popover', async () => {
    stubPhone()
    const user = userEvent.setup()
    renderAtPath('/', { drafts: [NFL] })

    const account = await screen.findByRole('button', { name: /^account:/i })
    expect(screen.queryByRole('button', { name: /sign out/i })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: /sign out/i })).toBeNull()

    await user.click(account)

    const menu = screen.getByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /sign out/i })).toBeTruthy()
    // Still not a plain nav button.
    expect(screen.queryByRole('button', { name: /sign out/i })).toBeNull()
  })

  it('moves focus in, closes on Escape, returns focus to the avatar, and signs out', async () => {
    const user = userEvent.setup()
    renderAtPath('/', { drafts: [NFL] })

    const account = await screen.findByRole('button', { name: /^account:/i })
    await user.click(account)
    expect(screen.getByRole('menu').contains(document.activeElement)).toBe(true)

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu')).toBeNull()
    expect(document.activeElement).toBe(account)

    await user.click(account)
    await user.click(screen.getByRole('menuitem', { name: /sign out/i }))
    await waitFor(() => expect(screen.queryByRole('navigation', { name: 'Main' })).toBeNull())
  })
})
