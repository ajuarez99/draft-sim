import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import SuperlativeStandings from './SuperlativeStandings'
import { standingFigure } from '../pages/Superlatives'
import type {
  Superlative,
  SuperlativeHolder,
  SuperlativePlayerContribution,
  SuperlativePlayerStanding,
  SuperlativeStanding,
} from '../api'

vi.mock('react-router-dom', () => ({
  Link: ({ to, children, className }: { to: string; children: React.ReactNode; className?: string }) => (
    <a href={to} className={className}>
      {children}
    </a>
  ),
}))

const t1: SuperlativeHolder = { rosterId: 1, managerId: 11, teamName: 'Alpha', username: 'alpha_u', avatarId: null }
const t2: SuperlativeHolder = { rosterId: 2, managerId: 12, teamName: 'Bravo', username: 'bravo_u', avatarId: null }

function contrib(rank: number, name: string, team: SuperlativeHolder, value: number, note: string | null = null): SuperlativePlayerContribution {
  return { rank, playerId: `id-${name}`, playerName: name, position: 'C', team, value, note }
}

function standing(rank: number, team: SuperlativeHolder, value: number): SuperlativeStanding {
  return { rank, team, value, note: null, hasValue: true, missingReason: null }
}

function award(kind: Superlative['kind'], over: Partial<Superlative> = {}): Superlative {
  return {
    kind, available: true, early: false, value: 10, unit: 'POINTS', holders: [t1], detail: [], coverage: null,
    playerHolders: [], standings: [standing(1, t1, 30), standing(2, t2, 20)], playerStandings: [],
    playerContributions: [], trendingCoverage: null, ...over,
  }
}

function mount(s: Superlative) {
  return render(
    <SuperlativeStandings
      s={s}
      title="The Award"
      hue={200}
      leagueId="L1"
      sport="nba"
      figure={(v) => `${v} fig`}
    />,
  )
}

const rows = [
  contrib(1, 'Zed', t2, 20, 'second note'),
  contrib(2, 'Amy', t1, 15),
  contrib(3, 'Bob', t1, 5),
]

function names(): string[] {
  return screen.getAllByRole('listitem').map((li) => within(li).getByRole('link').textContent ?? '')
}

describe('SuperlativeStandings players view (spec 040 US3)', () => {
  it.each(['JOEL_EMBIID', 'WAIVER_WIRE_WARRIOR'] as const)('%s opens on Players', (kind) => {
    mount(award(kind, { playerContributions: rows }))
    expect(screen.getByRole('button', { name: 'Players' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Teams' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('link', { name: 'Zed' })).toBeInTheDocument()
  })

  it('UNETHICAL opens on Teams', () => {
    mount(award('UNETHICAL', { playerContributions: rows }))
    expect(screen.getByRole('button', { name: 'Teams' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('link', { name: 'Zed' })).not.toBeInTheDocument()
    expect(screen.getByText('Alpha')).toBeInTheDocument()
  })

  it('the toggle switches between the two views', async () => {
    const user = userEvent.setup()
    mount(award('UNETHICAL', { playerContributions: rows }))
    await user.click(screen.getByRole('button', { name: 'Players' }))
    expect(screen.getByRole('button', { name: 'Players' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('link', { name: 'Zed' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Teams' }))
    expect(screen.queryByRole('link', { name: 'Zed' })).not.toBeInTheDocument()
  })

  it('keeps payload order, never re-sorting, and shows rank, team, figure and note', () => {
    // Deliberately not value-sorted by name or rank alphabetical: Zed, Amy, Bob is the payload order.
    mount(award('JOEL_EMBIID', { playerContributions: rows }))
    expect(names()).toEqual(['Zed', 'Amy', 'Bob'])
    const first = screen.getAllByRole('listitem')[0]
    expect(within(first).getByText('1')).toBeInTheDocument()
    expect(within(first).getByText('Bravo')).toBeInTheDocument()
    expect(within(first).getByText('20 fig')).toBeInTheDocument()
    expect(within(first).getByText('second note')).toBeInTheDocument()
    expect(within(first).getByText(/\(C\)/)).toBeInTheDocument()
  })

  it('has no toggle when the kind has no contributions, and still shows the team standings', () => {
    mount(award('HIGHEST_WEEK'))
    expect(screen.queryByRole('button', { name: 'Players' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Teams' })).not.toBeInTheDocument()
    expect(screen.getByText('Alpha')).toBeInTheDocument()
  })

  it('has no toggle for a team award whose contributions are empty (e.g. an Embiid with nothing yet)', () => {
    mount(award('JOEL_EMBIID', { playerContributions: [] }))
    expect(screen.queryByRole('button', { name: 'Players' })).not.toBeInTheDocument()
    expect(screen.getByText('Alpha')).toBeInTheDocument()
  })

  it('a click inside the panel does not reach an ancestor click handler', async () => {
    const user = userEvent.setup()
    const outer = vi.fn()
    render(
      <div onClick={outer}>
        <SuperlativeStandings s={award('UNETHICAL', { playerContributions: rows })} title="T" hue={1} leagueId="L1" sport="nba" figure={(v) => String(v)} />
      </div>,
    )
    await user.click(screen.getByRole('button', { name: 'Players' }))
    expect(outer).not.toHaveBeenCalled()
  })
})

describe('Jabari fold label (spec 040 F6)', () => {
  function ps(rank: number, id: string, adds: number): SuperlativePlayerStanding {
    return { rank, playerId: id, playerName: id, position: 'WR', team: null, adds, distinctTeams: 2 }
  }

  it('says "N more players" without a per-group add count when two rank groups are collapsed', () => {
    // 10 players tied at rank 1 fill the visible cap; ranks 11 and 12 (different add counts) fold away.
    const top = Array.from({ length: 10 }, (_, i) => ps(1, `top${i}`, 5))
    mount(award('JABARI_SMITH_JR', {
      holders: [], standings: [],
      playerStandings: [...top, ps(11, 'mid', 4), ps(12, 'low', 3)],
    }))
    const summary = screen.getByText(/more players/)
    expect(summary.textContent).toContain('2 more players')
    expect(summary.textContent).not.toMatch(/adds each/)
  })

  it('names the count only for a tied first group that is itself collapsed', () => {
    const wall = Array.from({ length: 11 }, (_, i) => ps(1, `t${i}`, 2))
    mount(award('JABARI_SMITH_JR', { holders: [], standings: [], playerStandings: wall }))
    expect(screen.getByText(/11 players tied with 2 adds each/)).toBeInTheDocument()
  })

  it('shows a negative player figure with a single minus sign (code-review M1)', () => {
    mount(award('WAIVER_WIRE_WARRIOR', { playerContributions: [contrib(1, 'Amy', t1, 12), contrib(2, 'Tyrod', t1, -0.76)] }))
    expect(standingFigure('WAIVER_WIRE_WARRIOR', 'POINTS', -0.76)).toBe('-0.76 points')
    expect(standingFigure('JOEL_EMBIID', 'POINTS', -4)).toBe('-4.00 estimated points lost')
  })

  // ---- spec 051 F6: Trending Tony's Players view figure is a rank, not a count ----------------------

  const mountTony = (s: Superlative, kind: 'tony' | 'other' = 'tony') =>
    render(
      <SuperlativeStandings
        s={s} title="The Trending Tony" hue={200} leagueId="L1" sport="nba"
        figure={(v) => standingFigure(s.kind, s.unit, v)}
        contributionFigure={kind === 'tony' ? (v) => `#${v} trending` : undefined}
      />,
    )

  it('TRENDING_TONY: Teams reads "4 trending pickups", Players reads "#2 trending", never "2 trending pickups"', async () => {
    mountTony(award('TRENDING_TONY', {
      unit: 'PICKUPS', standings: [standing(1, t1, 4), standing(2, t2, 1)], playerContributions: [contrib(1, 'Zed', t1, 2)],
    }))
    expect(screen.getByText('4 trending pickups')).toBeInTheDocument()
    expect(screen.getByText('1 trending pickup')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Players' }))
    expect(screen.getByText('#2 trending')).toBeInTheDocument()
    expect(screen.queryByText('2 trending pickups')).not.toBeInTheDocument()
  })

  it('contributions of another award still use figure (no contributionFigure passed)', () => {
    mountTony(award('JOEL_EMBIID', { playerContributions: [contrib(1, 'Zed', t1, 2)] }), 'other')
    expect(screen.getByText('2.00 estimated points lost')).toBeInTheDocument()
  })
})
