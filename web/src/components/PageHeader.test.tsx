import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import PageHeader from './PageHeader'
import { InsideLeagueContext } from '../appSlots'

/*
 * Phase 3 of claude/site-redesign.md: the title is body-face sentence case (no `.cond`), the eyebrow is
 * not rendered inside a league (the league header says league and season), and every `sub` renders
 * exactly as before. Rendered with no router on purpose -- 25 pages' tests do the same, which is why
 * "inside a league" arrives as a context from the shell rather than from a router hook.
 */

describe('PageHeader', () => {
  it('renders the title as an h1 without the display-face class, as written by the caller', () => {
    render(<PageHeader title="Power rankings" />)
    const h1 = screen.getByRole('heading', { level: 1, name: 'Power rankings' })
    expect(h1.classList.contains('page-title')).toBe(true)
    expect(h1.classList.contains('cond')).toBe(false)
  })

  it('renders the eyebrow outside a league, as it always did', () => {
    render(<PageHeader eyebrow="Across your leagues" title="Scouting report" />)
    expect(screen.getByText('Across your leagues')).toBeInTheDocument()
  })

  it('does not render the eyebrow inside a league: the league header already says which', () => {
    render(
      <InsideLeagueContext.Provider value>
        <PageHeader eyebrow="League · 2025" title="Luck" />
      </InsideLeagueContext.Provider>,
    )
    expect(screen.queryByText('League · 2025')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Luck' })).toBeInTheDocument()
  })

  it('renders the sub exactly as before, inside a league or not', () => {
    const sub = 'Who the schedule has helped, and who it has hurt, with a sentence long enough to have been dropped by any length rule.'
    const { unmount } = render(<PageHeader title="Luck" sub={sub} />)
    expect(screen.getByText(sub)).toBeInTheDocument()
    unmount()
    render(
      <InsideLeagueContext.Provider value>
        <PageHeader title="Luck" sub={<>Takeaway: <strong>one team</strong> was lucky.</>} />
      </InsideLeagueContext.Provider>,
    )
    expect(screen.getByText('one team').tagName).toBe('STRONG')
  })

  it('still renders actions', () => {
    render(<PageHeader title="Home" actions={<button type="button">Start a mock draft</button>} />)
    expect(screen.getByRole('button', { name: 'Start a mock draft' })).toBeInTheDocument()
  })
})
