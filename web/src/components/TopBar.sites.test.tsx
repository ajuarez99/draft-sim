import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { draftSummary, renderAtPath, resetShell } from '../testShellHelpers'
import { buildSearchIndex, searchDestinations } from '../searchIndex'

// Spec 073 T016/T018: the Time Machine link exists only in the combined build; the league build offers none,
// on the desktop bar, the phone menu, and the jump-to search.

afterEach(() => {
  resetShell()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

const LEAGUE = draftSummary({ sleeperLeagueId: 'L1', sleeperDraftId: 'D1', leagueName: 'Ball Knowers' })

function stubPhone() {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('860px'),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}

const labels = (root: HTMLElement) => [...root.querySelectorAll('a, button')].map((e) => (e.textContent ?? '').trim())

describe('the desktop top bar', () => {
  it('combined: Leagues, Mock drafts, Managers, Redraft', async () => {
    const { topBar } = renderAtPath('/', { drafts: [LEAGUE] })
    await waitFor(() => expect(topBar()).not.toBeNull())
    const nav = within(topBar()!).getByRole('navigation', { name: 'Main' })
    expect(labels(nav)).toEqual(['Leagues', 'Mock drafts', 'Managers', 'Redraft'])
  })

  it('leagues: Leagues, Mock drafts, Managers and no Time Machine', async () => {
    vi.stubEnv('VITE_SITE', 'leagues')
    const { topBar } = renderAtPath('/', { drafts: [LEAGUE] })
    await waitFor(() => expect(topBar()).not.toBeNull())
    const nav = within(topBar()!).getByRole('navigation', { name: 'Main' })
    expect(labels(nav)).toEqual(['Leagues', 'Mock drafts', 'Managers'])
    expect(screen.queryByText('Redraft')).toBeNull()
  })
})

describe('the phone menu', () => {
  it('combined: the sheet lists Redraft', async () => {
    stubPhone()
    const user = userEvent.setup()
    const { topBar } = renderAtPath('/managers', { drafts: [LEAGUE] })
    await waitFor(() => expect(topBar()).not.toBeNull())
    await user.click(screen.getByRole('button', { name: 'Menu' }))
    expect(labels(screen.getByRole('dialog', { name: 'Menu' }))).toEqual(['Leagues', 'Mock drafts', 'Managers', 'Redraft', 'Sign out'])
  })

  it('leagues: the sheet has no Time Machine', async () => {
    vi.stubEnv('VITE_SITE', 'leagues')
    stubPhone()
    const user = userEvent.setup()
    const { topBar } = renderAtPath('/managers', { drafts: [LEAGUE] })
    await waitFor(() => expect(topBar()).not.toBeNull())
    await user.click(screen.getByRole('button', { name: 'Menu' }))
    expect(labels(screen.getByRole('dialog', { name: 'Menu' }))).toEqual(['Leagues', 'Mock drafts', 'Managers', 'Sign out'])
  })
})

describe('jump-to search', () => {
  it('has no Time Machine entry for any query, in any build', () => {
    const index = buildSearchIndex([LEAGUE], [])
    for (const q of ['time machine', 'history', 'time', 'machine', 'game']) {
      for (const hit of searchDestinations(index, q)) {
        expect(`${hit.label} ${hit.href}`.toLowerCase(), q).not.toContain('time machine')
        expect(hit.href, q).not.toMatch(/^\/history(\/|$)/)
      }
    }
  })
})
