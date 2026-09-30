import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { laneOverflow } from '../railLane'
import { draftSummary, renderAtPath, resetRail } from '../testRailHelpers'

/*
 * Phone navigation (claude/audit-2026-09-28/12): the league-pages lane says it
 * continues, keeps the current page on screen, has an always-visible "All
 * pages" way out, and Sign out sits behind the avatar instead of next to nav.
 * jsdom has no layout, so widths and matchMedia are stubbed where a test needs
 * a phone.
 */

afterEach(() => {
  resetRail()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

const LEAGUE = draftSummary({ sleeperLeagueId: 'L1', sleeperDraftId: 'D1', leagueName: 'Ball Knowers' })

function stubPhone() {
  vi.stubGlobal(
    'matchMedia',
    (query: string) => ({
      matches: query.includes('700px'),
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }),
  )
}

describe('laneOverflow (the edge-fade helper)', () => {
  it('shows no fade when everything fits', () => {
    expect(laneOverflow(0, 347, 347)).toEqual({ start: false, end: false })
  })
  it('fades only the end at the start of a long lane', () => {
    expect(laneOverflow(0, 347, 1249)).toEqual({ start: false, end: true })
  })
  it('fades both edges mid-lane', () => {
    expect(laneOverflow(400, 347, 1249)).toEqual({ start: true, end: true })
  })
  it('fades only the start at the end of the lane, tolerating a fractional scrollLeft', () => {
    expect(laneOverflow(901.6, 347, 1249)).toEqual({ start: true, end: false })
  })
})

describe('the league pages lane', () => {
  it('scrolls the current page into view when the lane overflows', async () => {
    const scrollIntoView = vi.fn()
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(1249)
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(347)
    Element.prototype.scrollIntoView = scrollIntoView

    renderAtPath('/leagues/L1/power', { drafts: [LEAGUE] })

    await waitFor(() => expect(scrollIntoView).toHaveBeenCalled())
    const target = scrollIntoView.mock.contexts[0] as HTMLElement
    expect(target.getAttribute('aria-label')).toBe('Power rankings')
    expect(scrollIntoView).toHaveBeenCalledWith({ inline: 'nearest', block: 'nearest' })
  })

  it('marks the end edge for fading only while the lane overflows', async () => {
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(1249)
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(347)
    Element.prototype.scrollIntoView = vi.fn()
    const { container, unmount } = renderAtPath('/leagues/L1/power', { drafts: [LEAGUE] })

    await waitFor(() => expect(container.querySelector('.app-rail-pages')).not.toBeNull())
    await waitFor(() =>
      expect(container.querySelector('.app-rail-pages')?.getAttribute('data-overflow-end')).toBe('true'),
    )
    expect(container.querySelector('.app-rail-pages')?.hasAttribute('data-overflow-start')).toBe(false)
    unmount()
  })

  it('has no fade marker when the lane fits (desktop / jsdom)', async () => {
    const { container } = renderAtPath('/leagues/L1/power', { drafts: [LEAGUE] })
    await waitFor(() => expect(container.querySelector('.app-rail-pages')).not.toBeNull())
    expect(container.querySelector('.app-rail-pages')?.hasAttribute('data-overflow-end')).toBe(false)
    expect(container.querySelector('.app-rail-pages')?.hasAttribute('data-overflow-start')).toBe(false)
  })

  it('opens Jump to from the trailing "All pages" chip', async () => {
    const user = userEvent.setup()
    renderAtPath('/leagues/L1/power', { drafts: [LEAGUE] })

    await user.click(await screen.findByRole('button', { name: 'All pages' }))

    await waitFor(() => expect(screen.getByRole('dialog', { name: 'Jump to' })).toBeTruthy())
  })
})

describe('Sign out on a phone', () => {
  beforeEach(stubPhone)

  it('is not a standalone control next to the nav, and is reachable from the avatar popover', async () => {
    const user = userEvent.setup()
    renderAtPath('/', { drafts: [LEAGUE] })

    const account = await screen.findByRole('button', { name: /^account:/i })
    expect(screen.queryByRole('button', { name: /sign out/i })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: /sign out/i })).toBeNull()

    await user.click(account)

    const menu = screen.getByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /sign out/i })).toBeTruthy()
    // Still not a plain nav button.
    expect(screen.queryByRole('button', { name: /sign out/i })).toBeNull()
  })

  it('signs out from the popover', async () => {
    const user = userEvent.setup()
    renderAtPath('/', { drafts: [LEAGUE] })

    await user.click(await screen.findByRole('button', { name: /^account:/i }))
    await user.click(screen.getByRole('menuitem', { name: /sign out/i }))

    // Signed out: the shell renders no rail at all.
    await waitFor(() => expect(screen.queryByRole('navigation', { name: 'Main' })).toBeNull())
  })
})
