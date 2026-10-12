import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import PreseasonStrengthBlock from './PreseasonStrengthBlock'
import type { SeasonStrength } from '../api'
import { favorite, projRoster, strength } from '../testSeasonStart'

function renderBlock(s: SeasonStrength) {
  return render(
    <MemoryRouter>
      <PreseasonStrengthBlock strength={s} leagueId="L1" />
    </MemoryRouter>,
  )
}

/** FR-007a: a projected ranking is not a forecast of winning anything. */
const FORBIDDEN = /odds|likely|% to win|chance|probability/i

describe('PreseasonStrengthBlock', () => {
  it('renders the top three with exact values and the unit beside each', () => {
    renderBlock(strength({ me: null }))
    const rows = screen.getAllByRole('listitem')
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByText('312.4')).toBeInTheDocument()
    expect(within(rows[1]).getByText('301.1')).toBeInTheDocument()
    expect(within(rows[2]).getByText('290.0')).toBeInTheDocument()
    for (const row of rows) expect(within(row).getByText('pts/wk')).toBeInTheDocument()
    // "Projected" is said once, in the caption the list is described by.
    expect(screen.getByText('Projected points per week')).toBeInTheDocument()
  })

  it('adds your row, with your rank, when you are outside the top three', () => {
    renderBlock(strength())
    const rows = screen.getAllByRole('listitem')
    expect(rows).toHaveLength(4)
    expect(within(rows[3]).getByText('Sharky Bytes')).toBeInTheDocument()
    expect(within(rows[3]).getByText('250.5')).toBeInTheDocument()
    expect(screen.getByText('You rank 7 of 12 on projected strength.')).toBeInTheDocument()
  })

  it('does not repeat you when you are already in the top three', () => {
    const s = strength({ me: null, top: [projRoster(1, 1, 312.4, { isMe: true }), projRoster(2, 2, 301.1), projRoster(3, 3, 290)] })
    renderBlock(s)
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
    expect(screen.getAllByText('you')).toHaveLength(1)
    expect(screen.queryByText(/You rank/)).toBeNull()
  })

  it('links to the Analysis page', () => {
    renderBlock(strength())
    expect(screen.getByRole('link', { name: 'Team strength' })).toHaveAttribute('href', '/leagues/L1/analysis')
  })

  it('shows only the reason when projections are unavailable, with no numbers', () => {
    renderBlock(
      strength({
        available: false,
        reason: 'raw',
        reasonCode: 'PROJECTIONS_NOT_LOADED',
        top: [],
        me: null,
        favorite: null,
      }),
    )
    expect(screen.getByRole('status')).toHaveTextContent(/Basketball projections haven't loaded yet/)
    expect(screen.queryByRole('listitem')).toBeNull()
    expect(document.body.textContent).not.toMatch(/\d\d\d\.\d/)
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('names the source, its age, the unmeasured accuracy and the categories it does not project', () => {
    renderBlock(strength())
    expect(screen.getByText(/Source: sleeper · updated today · accuracy for this league not measured/)).toBeInTheDocument()
    expect(screen.getByText('Not projected: 50+ point bonus.')).toBeInTheDocument()
    expect(screen.getByText(/One average game per starter/)).toBeInTheDocument()
  })

  describe('favorite headline (US3)', () => {
    it('names the leader and shows both totals and the margin in pts/week', () => {
      renderBlock(strength())
      expect(screen.getByText('Projected strongest roster: Dunk Tank, by 11.3 pts/week (3.8%)')).toBeInTheDocument()
      expect(screen.getByText(/312\.4 pts\/week \(projected\)\. Next-best roster: 301\.1 pts\/week \(projected\)\./)).toBeInTheDocument()
    })

    it('calls a tie a dead heat and names every leader', () => {
      const leaders = [
        { rosterId: 1, teamName: 'Dunk Tank', username: 'a', avatarId: null, total: 312.4 },
        { rosterId: 2, teamName: 'Brick City', username: 'b', avatarId: null, total: 311.0 },
        { rosterId: 3, teamName: null, username: 'carol', avatarId: null, total: 310.2 },
      ]
      renderBlock(strength({ favorite: favorite({ leaders, tie: true, marginPoints: 9, marginPct: 3.1 }) }))
      expect(screen.getByText('Projected dead heat: Dunk Tank, Brick City and carol, within 3%')).toBeInTheDocument()
      expect(screen.queryByText(/strongest roster/)).toBeNull()
    })

    it('shows no headline without a favorite', () => {
      renderBlock(strength({ favorite: null }))
      expect(screen.queryByText(/Projected strongest|Projected dead heat/)).toBeNull()
    })

    it('says a narrow gap is within what the projections cannot resolve', () => {
      renderBlock(strength({ favorite: favorite({ marginPct: 4.0, minGapPct: 3 }) }))
      expect(screen.getByText("A gap this size is within what these projections can't resolve.")).toBeInTheDocument()
    })

    it('stays quiet about that for a wide gap', () => {
      renderBlock(strength({ favorite: favorite({ marginPct: 9.0, minGapPct: 3 }) }))
      expect(screen.queryByText(/within what these projections/)).toBeNull()
    })

    it('uses no odds language anywhere in the block, leader or tie', () => {
      const leaders = [
        { rosterId: 1, teamName: 'Dunk Tank', username: 'a', avatarId: null, total: 312.4 },
        { rosterId: 2, teamName: 'Brick City', username: 'b', avatarId: null, total: 311.0 },
      ]
      for (const s of [strength(), strength({ favorite: favorite({ leaders, tie: true, marginPct: 0.4 }) })]) {
        const { container, unmount } = renderBlock(s)
        expect(container.textContent ?? '').not.toMatch(FORBIDDEN)
        unmount()
      }
    })
  })

  it('labels every row, including non-leaders and you, with the team name, falling back to username then Roster N', () => {
    const s = strength({
      top: [projRoster(1, 1, 312.4), projRoster(2, 2, 301.1), projRoster(4, 3, 290, { manager: null })],
      me: projRoster(9, 7, 250.5, { isMe: true, manager: 'popsharky' }),
      teamLabels: [
        { rosterId: 1, teamName: 'Dunk Tank', username: 'user1' },
        { rosterId: 2, teamName: 'Brick City', username: 'user2' },
        { rosterId: 9, teamName: 'Sharky Bytes', username: 'popsharky' },
      ],
    })
    renderBlock(s)
    const rows = screen.getAllByRole('listitem')
    expect(within(rows[0]).getByText('Dunk Tank')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Brick City')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Roster 4')).toBeInTheDocument()
    expect(within(rows[3]).getByText('Sharky Bytes')).toBeInTheDocument()
  })

  it('labels the headline totals with the strength unit, per week only on the margin (football)', () => {
    renderBlock(
      strength({
        unit: 'REST_OF_SEASON',
        basisNote: null,
        unprojectedCategories: [],
        favorite: favorite({ leaderTotal: 1156.7, nextTotal: 1114.2, marginPoints: 4.1, marginPct: 3.8 }),
      }),
    )
    expect(screen.getByText('Projected strongest roster: Dunk Tank, by 4.1 pts/week (3.8%)')).toBeInTheDocument()
    expect(screen.getByText(/1156\.7 pts \(projected\)\. Next-best roster: 1114\.2 pts \(projected\)\./)).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/1156\.7 [^.]*pts\/week/)
  })

  it('says "projected" in the caption and the headline, so every number is marked', () => {
    const { container } = renderBlock(strength())
    expect(container.querySelector('.lh-str-caption')?.textContent).toMatch(/[Pp]rojected/)
    expect(container.querySelector('ol')?.getAttribute('aria-describedby')).toBe('lh-strength-cap')
    for (const p of container.querySelectorAll('.lh-headline, .lh-headline + p')) {
      expect(p.textContent).toMatch(/[Pp]rojected/)
    }
  })

  it('tells a football (rest-of-season) block apart: no per-week caveats', () => {
    renderBlock(strength({ unit: 'REST_OF_SEASON', basisNote: null, unprojectedCategories: [] }))
    expect(screen.getByText('Projected points, rest of the regular season')).toBeInTheDocument()
    expect(screen.getAllByText('pts').length).toBeGreaterThan(0)
    expect(screen.queryByText(/One average game/)).toBeNull()
    expect(screen.queryByText(/Injuries and missed games/)).toBeNull()
  })
})
