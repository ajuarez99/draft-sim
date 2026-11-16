import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TimeMachineShell from './TimeMachineShell'
import { clearUser, setUser, useUser } from '../user'
import { trackPageView } from '../analytics'
import { LEAGUE_WORDS } from '../leagueWords'

vi.mock('../analytics', () => ({ trackPageView: vi.fn(), trackAction: vi.fn() }))

// Spec 073 T012/T015: the Time Machine site's shell.

function Where() {
  const l = useLocation()
  const u = useUser()
  return <p data-testid="where">{`${l.pathname} ${u ? 'in' : 'out'}`}</p>
}

function Boom(): never {
  throw new TypeError('kaboom')
}

function renderShell(child = <p>game page</p>, at = '/game/7') {
  return render(
    <MemoryRouter initialEntries={[at]}>
      <TimeMachineShell>{child}</TimeMachineShell>
      <Where />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  // React and the boundary both log a caught render error; wanted in a browser, noise here.
  vi.spyOn(console, 'error').mockImplementation(() => {})
  vi.stubEnv('VITE_SITE', 'timemachine')
  vi.mocked(trackPageView).mockReset()
  setUser({ sleeperUserId: '1', username: 'popsharky', displayName: 'Pop', avatar: null })
})

afterEach(() => {
  // Unmount BEFORE signing out: signed out the shell renders its children bare, with no boundary, so a
  // child that throws would be re-rendered unguarded by the sign-out itself.
  cleanup()
  clearUser()
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

describe('TimeMachineShell', () => {
  it('renders .app-shell > header.topbar + main.app-main#main (the layout and the draft room height depend on them)', () => {
    const { container } = renderShell()
    const shell = container.querySelector('.app-shell')!
    expect(shell).not.toBeNull()
    expect([...shell.children].map((c) => c.tagName + (c.id ? `#${c.id}` : ''))).toEqual(['A', 'HEADER', 'MAIN#main'])
    expect(shell.querySelector(':scope > header.topbar')).not.toBeNull()
    expect(shell.querySelector(':scope > main.app-main#main')).not.toBeNull()
    expect(screen.getByText('game page').closest('main')).toBe(shell.querySelector('main'))
  })

  it('has a wordmark home link and an account menu, and no league navigation, search or menu button', () => {
    const { container } = renderShell()
    const bar = container.querySelector('header.topbar')!
    expect(bar.querySelector('a.topbar-wordmark')).toHaveAttribute('href', '/')
    expect(bar.querySelector('nav')).toBeNull()
    expect(screen.queryByRole('button', { name: /jump to/i })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Menu' })).toBeNull()
    expect(screen.getByRole('button', { name: /^Account:/ })).toBeInTheDocument()
    for (const el of container.querySelectorAll('a, button')) expect(el.textContent ?? '').not.toMatch(LEAGUE_WORDS)
    expect(container.querySelector('.league-header, .context-strip, .bottom-bar')).toBeNull()
  })

  it('Ctrl+K opens nothing', async () => {
    const user = userEvent.setup()
    renderShell()
    await user.keyboard('{Control>}k{/Control}')
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('signs out to "/" and shows only its children afterwards', async () => {
    const user = userEvent.setup()
    const { container } = renderShell(<p>game page</p>, '/game/7')
    await user.click(screen.getByRole('button', { name: /^Account:/ }))
    await user.click(screen.getByRole('menuitem', { name: 'Sign out' }))
    expect(screen.getByTestId('where').textContent).toBe('/ out')
    expect(container.querySelector('.app-shell')).toBeNull()
    expect(screen.getByText('game page')).toBeInTheDocument()
  })

  it('sends a page view on each path / signed-in change', () => {
    renderShell(<p>x</p>, '/game/7')
    expect(trackPageView).toHaveBeenCalledWith('/game/7', true)
  })

  it('signed out renders only its children, but still counts the visit', () => {
    clearUser()
    const { container } = renderShell(<p>front door</p>, '/')
    expect(container.querySelector('.app-shell')).toBeNull()
    expect(screen.getByText('front door')).toBeInTheDocument()
    expect(trackPageView).toHaveBeenCalledWith('/', false)
  })

  it('keeps the bar when a page throws, and the way home names Time Machine', () => {
    const { container } = renderShell(<Boom />)
    expect(container.querySelector('header.topbar')).not.toBeNull()
    expect(screen.getByText('This page hit an error')).toBeInTheDocument()
    const home = screen.getByRole('link', { name: 'back to Time Machine' })
    expect(home).toHaveAttribute('href', '/')
    expect(container.querySelector('main')!.textContent).not.toMatch(LEAGUE_WORDS)
  })
})
