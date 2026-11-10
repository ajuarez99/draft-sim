import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'
import { draftSummary, renderAtPath, resetShell } from '../testShellHelpers'

/*
 * The top bar on a desktop (claude/site-redesign.md Phase 2). The phone shapes are in PhoneNav.test.tsx.
 */

afterEach(resetShell)

function StateProbe() {
  const loc = useLocation()
  return <p data-testid="probe">{`${loc.pathname} ${JSON.stringify(loc.state)}`}</p>
}

const LEAGUE = draftSummary({ sleeperLeagueId: 'L1', sleeperDraftId: 'D1', leagueName: 'Ball Knowers' })

describe('the global links', () => {
  it('are Leagues, Mock drafts, Managers and Time Machine, in that order, with text labels only', async () => {
    const { topBar } = renderAtPath('/', { drafts: [LEAGUE] })
    await waitFor(() => expect(topBar()).not.toBeNull())
    const nav = within(topBar()!).getByRole('navigation', { name: 'Main' })
    expect([...nav.querySelectorAll('a, button')].map((e) => e.textContent)).toEqual(['Leagues', 'Mock drafts', 'Managers', 'Time Machine'])
  })

  it('mark the current one with aria-current, and keep Managers current on its own sub-route', async () => {
    const { topBar } = renderAtPath('/managers/12/history', { drafts: [LEAGUE] })
    await waitFor(() => expect(topBar()).not.toBeNull())
    const bar = within(topBar()!)
    expect(bar.getByRole('link', { name: 'Managers' }).getAttribute('aria-current')).toBe('page')
    expect(bar.getByRole('link', { name: 'Leagues' }).hasAttribute('aria-current')).toBe(false)
    expect(bar.getByRole('link', { name: 'Time Machine' }).hasAttribute('aria-current')).toBe(false)
  })

  it('lights Leagues only on the picker', async () => {
    const { topBar } = renderAtPath('/', { drafts: [LEAGUE] })
    await waitFor(() => expect(topBar()).not.toBeNull())
    expect(within(topBar()!).getByRole('link', { name: 'Leagues' }).getAttribute('aria-current')).toBe('page')
  })

  // Plan review PR-17: Rail passed the league in route state so the Scouting report can list its
  // managers first; a plain link would drop it with no test failing.
  it('Managers carries the league in router state, so the Scouting report can list its managers first', async () => {
    const user = userEvent.setup()
    const { leagueSection, topBar } = renderAtPath('/leagues/L1/history', { drafts: [LEAGUE], page: <StateProbe /> })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await user.click(within(topBar()!).getByRole('link', { name: 'Managers' }))
    expect(screen.getByTestId('probe').textContent).toBe('/managers {"railLeagueId":"L1"}')
  })

  it('Managers carries no state when the shell is showing no league', async () => {
    const user = userEvent.setup()
    const { topBar } = renderAtPath('/', { drafts: [LEAGUE], page: <StateProbe /> })
    await waitFor(() => expect(topBar()).not.toBeNull())
    await user.click(within(topBar()!).getByRole('link', { name: 'Managers' }))
    expect(screen.getByTestId('probe').textContent).toBe('/managers null')
  })
})

describe('Mock drafts', () => {
  it('is a button that asks Home to open the new-mock modal, not a link', async () => {
    const user = userEvent.setup()
    const { topBar } = renderAtPath('/managers', { drafts: [LEAGUE] })
    await waitFor(() => expect(topBar()).not.toBeNull())
    const mock = within(topBar()!).getByRole('button', { name: 'Mock drafts' })
    expect(mock.tagName).toBe('BUTTON')
    await user.click(mock)
    // On Home, the picker reads location.state.openMock; the bar marks Leagues current there.
    await waitFor(() => expect(within(topBar()!).getByRole('link', { name: 'Leagues' }).getAttribute('aria-current')).toBe('page'))
  })
})

describe('the account menu', () => {
  it('holds the name and Sign out, moves focus in, closes on Escape and returns focus to the avatar', async () => {
    const user = userEvent.setup()
    const { topBar } = renderAtPath('/', { drafts: [LEAGUE] })
    await waitFor(() => expect(topBar()).not.toBeNull())

    const account = within(topBar()!).getByRole('button', { name: /^account:/i })
    expect(screen.queryByRole('menu')).toBeNull()
    await user.click(account)

    const menu = screen.getByRole('menu')
    expect(menu.textContent).toContain('Tester')
    expect(within(menu).getByRole('menuitem', { name: 'Sign out' })).toBeTruthy()
    expect(menu.contains(document.activeElement)).toBe(true)
    expect(account.getAttribute('aria-expanded')).toBe('true')

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu')).toBeNull()
    expect(document.activeElement).toBe(account)
  })

  it('closes on a press outside it', async () => {
    const user = userEvent.setup()
    const { topBar } = renderAtPath('/', { drafts: [LEAGUE] })
    await waitFor(() => expect(topBar()).not.toBeNull())
    await user.click(within(topBar()!).getByRole('button', { name: /^account:/i }))
    expect(screen.getByRole('menu')).toBeTruthy()
    await user.click(document.body)
    expect(screen.queryByRole('menu')).toBeNull()
  })
})
