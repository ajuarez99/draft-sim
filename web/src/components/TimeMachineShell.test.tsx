import { useState } from 'react'
import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
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

  // PINNED RULE REWRITTEN (T041, spec 074): signing out used to drop the bar and show only the children (the front door).
  // The bar and main stay: the account menu becomes a "Sign in" link to the home page's panel.
  it('signs out to "/" and keeps the bar, with a Sign in link where the account menu was', async () => {
    const user = userEvent.setup()
    const { container } = renderShell(<p>game page</p>, '/game/7')
    await user.click(screen.getByRole('button', { name: /^Account:/ }))
    await user.click(screen.getByRole('menuitem', { name: 'Sign out' }))
    expect(screen.getByTestId('where').textContent).toBe('/ out')
    expect(container.querySelector('.app-shell > header.topbar')).not.toBeNull()
    expect(container.querySelector('main.app-main#main')).not.toBeNull()
    expect(screen.queryByRole('button', { name: /^Account:/ })).toBeNull()
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/#sign-in')
    expect(screen.getByText('game page')).toBeInTheDocument()
  })

  it('sends a page view on each path / signed-in change', () => {
    renderShell(<p>x</p>, '/game/7')
    expect(trackPageView).toHaveBeenCalledWith('/game/7', true)
  })

  // C6: a client-side move to another page starts at the top of it and puts focus on its h1.
  it('moving to another page focuses that page\'s h1 and scrolls main to the top; a first render and a hash link do not', async () => {
    const view = render(
      <MemoryRouter initialEntries={['/nba/2016-17']}>
        <TimeMachineShell>
          <Routes>
            <Route path="/nba/:season" element={<><h1>A season</h1><Link to="/nba/2017-18">next</Link><Link to="/nba/2017-18#x">hashed</Link></>} />
          </Routes>
        </TimeMachineShell>
      </MemoryRouter>,
    )
    const main = view.container.querySelector('main')!
    expect(screen.getByRole('heading', { level: 1 })).not.toHaveFocus() // the first render is a page load already
    main.scrollTop = 500
    await userEvent.click(screen.getByRole('link', { name: 'next' }))
    expect(screen.getByRole('heading', { level: 1 })).toHaveFocus()
    expect(main.scrollTop).toBe(0)
  })

  // V-02: a season page shows a loading h1, then replaces the whole tree with the loaded page's own h1; the focused node is
  // removed by the swap and focus would fall to <body>.
  function Season({ finish }: { finish: { fn: (() => void) | null } }) {
    const [loaded, setLoaded] = useState(false)
    finish.fn = () => setLoaded(true)
    return loaded ? (
      <div>
        <h1>Loaded season</h1>
        <Link to="/nba/2017-18">next</Link>
      </div>
    ) : (
      <header>
        <h1>Loading season</h1>
      </header>
    )
  }
  function renderSeasons(finish: { fn: (() => void) | null }) {
    return render(
      <MemoryRouter initialEntries={['/nba/2016-17']}>
        <TimeMachineShell>
          <Routes>
            <Route path="/nba/:season" element={<SeasonKeyed finish={finish} />} />
          </Routes>
        </TimeMachineShell>
      </MemoryRouter>,
    )
  }
  function SeasonKeyed({ finish }: { finish: { fn: (() => void) | null } }) {
    const l = useLocation()
    return <Season key={l.pathname} finish={finish} />
  }

  it('after a client-side move into a page that loads its data, focus ends on the LOADED h1, not on <body>', async () => {
    const finish = { fn: null as (() => void) | null }
    renderSeasons(finish)
    await act(async () => finish.fn?.())
    await userEvent.click(screen.getByRole('link', { name: 'next' }))
    expect(screen.getByRole('heading', { level: 1, name: 'Loading season' })).toHaveFocus()
    await act(async () => finish.fn?.())
    expect(screen.getByRole('heading', { level: 1, name: 'Loaded season' })).toHaveFocus()
    expect(document.activeElement).not.toBe(document.body)
  })

  it('does not steal focus from a visitor who has already moved it, nor on the first page load', async () => {
    const finish = { fn: null as (() => void) | null }
    renderSeasons(finish)
    // first page load: the loading h1 is not focused, and the swap does not focus the new one
    expect(screen.getByRole('heading', { level: 1 })).not.toHaveFocus()
    await act(async () => finish.fn?.())
    expect(screen.getByRole('heading', { level: 1, name: 'Loaded season' })).not.toHaveFocus()
    await userEvent.click(screen.getByRole('link', { name: 'next' }))
    // the visitor moves focus to the account menu before the data lands
    const menu = screen.getByRole('link', { name: 'Ball Knowers Redraft, home' })
    menu.focus()
    await act(async () => finish.fn?.())
    expect(menu).toHaveFocus()
  })

  it('a link that carries a hash leaves focus and scroll to the page', async () => {
    const view = render(
      <MemoryRouter initialEntries={['/a']}>
        <TimeMachineShell>
          <Routes>
            <Route path="/a" element={<><h1>A</h1><Link to="/b#sign-in">go</Link></>} />
            <Route path="/b" element={<h1>B</h1>} />
          </Routes>
        </TimeMachineShell>
      </MemoryRouter>,
    )
    await act(async () => {})
    await userEvent.click(screen.getByRole('link', { name: 'go' }))
    expect(screen.getByRole('heading', { level: 1, name: 'B' })).not.toHaveFocus()
    expect(view.container.querySelector('main')).not.toHaveFocus()
  })

  // PINNED RULE REWRITTEN (T041): "signed out renders only its children". A guest gets the bar, the main and the page, a
  // "Sign in" link instead of the account menu, and the visit is counted (analytics records it under the route, not Landing).
  it('signed out renders the bar and the page, with a Sign in link, and still counts the visit', () => {
    clearUser()
    const { container } = renderShell(<p>front door</p>, '/')
    expect(container.querySelector('.app-shell > header.topbar')).not.toBeNull()
    expect(screen.getByText('front door').closest('main')).toBe(container.querySelector('main#main'))
    const signIn = screen.getByRole('link', { name: 'Sign in' })
    expect(signIn).toHaveClass('topbar-signin')
    expect(signIn).toHaveAttribute('href', '/#sign-in')
    expect(screen.queryByRole('button', { name: /^Account:/ })).toBeNull()
    expect(trackPageView).toHaveBeenCalledWith('/', false)
  })

  it('a guest page that throws keeps the bar too', () => {
    clearUser()
    const { container } = renderShell(<Boom />)
    expect(container.querySelector('header.topbar')).not.toBeNull()
    expect(screen.getByText('This page hit an error')).toBeInTheDocument()
  })

  it('keeps the bar when a page throws, and the way home names Redraft', () => {
    const { container } = renderShell(<Boom />)
    expect(container.querySelector('header.topbar')).not.toBeNull()
    expect(screen.getByText('This page hit an error')).toBeInTheDocument()
    const home = screen.getByRole('link', { name: 'back to Redraft' })
    expect(home).toHaveAttribute('href', '/')
    expect(container.querySelector('main')!.textContent).not.toMatch(LEAGUE_WORDS)
  })
})
