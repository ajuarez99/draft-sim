import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CrossSiteRedirect from './CrossSiteRedirect'
import { goTo } from '../goTo'

vi.mock('../goTo', () => ({ goTo: vi.fn() }))

// Spec 073 CR-02: the same-origin guard compares strings, so the configured origin has to be normalised
// (makeConfig stores URL.origin). A differently-cased value used to defeat it.
afterEach(() => {
  vi.unstubAllEnvs()
  vi.mocked(goTo).mockReset()
})

describe('CrossSiteRedirect same-origin guard', () => {
  it('fires for a mixed-case origin that is really this one: not-found, no redirect', () => {
    vi.stubEnv('VITE_SITE', 'timemachine')
    vi.stubEnv('VITE_LEAGUES_ORIGIN', window.location.origin.replace('localhost', 'LOCALHOST'))
    render(<MemoryRouter initialEntries={['/leagues/9']}><CrossSiteRedirect target="leagues" /></MemoryRouter>)
    expect(goTo).not.toHaveBeenCalled()
    expect(screen.queryByText(/Taking you to/)).toBeNull()
  })

  it('a different origin still redirects', () => {
    vi.stubEnv('VITE_SITE', 'timemachine')
    vi.stubEnv('VITE_LEAGUES_ORIGIN', 'https://leagues.example.test')
    render(<MemoryRouter initialEntries={['/leagues/9']}><CrossSiteRedirect target="leagues" /></MemoryRouter>)
    expect(goTo).toHaveBeenCalledWith('https://leagues.example.test/leagues/9')
  })
})
