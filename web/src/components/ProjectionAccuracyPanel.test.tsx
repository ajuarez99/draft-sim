import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { AccuracyCell, AccuracySeries, ProjectionAccuracy } from '../api'

vi.mock('../api', () => ({ getProjectionAccuracy: vi.fn() }))
vi.mock('../pages/LeagueHome', () => ({ formatIsoDate: (d: string) => d }))

import { ProjectionAccuracyView } from './ProjectionAccuracyPanel'

const cell = (key: string, n: number, mae: number | null, lean: number | null): AccuracyCell => ({ key, n, mae, lean })

function series(kind: 'GAME' | 'SEASON_AVERAGE', label: string, over: Partial<AccuracySeries> = {}): AccuracySeries {
  return {
    kind,
    label,
    overall: cell('ALL', 612, 7.26, -2.14),
    byPosition: [cell('PG', 320, 6.8, 1.04), cell('C', 12, null, null)],
    byBand: [cell('0–20', 400, 5.1, 3.2), cell('50+', 3, null, null)],
    didNotPlay: 17,
    pending: 640,
    ...over,
  }
}

function measured(over: Partial<ProjectionAccuracy> = {}): ProjectionAccuracy {
  return {
    sport: 'nba', season: 2026, unit: 'GAME', status: 'MEASURED', minSamples: 500, minCell: 50,
    series: [series('GAME', 'Game projection'), series('SEASON_AVERAGE', 'Season average (what the rankings use)')],
    snapshotsFrom: '2026-10-20', scoredThrough: '2026-10-28', withoutSeasonAverage: 0, voided: 2,
    excludedCategories: ['bonus_pt_40p'], caveats: [
      'Morning-of projection: late scratches and inactives are not reflected.',
      'Not graded (no projection carries them): bonus_pt_40p.',
    ], source: 'rotowire',
    ...over,
  }
}

describe('ProjectionAccuracyPanel', () => {
  it('shows counts and no figure while it is too early', () => {
    const early = measured({
      status: 'TOO_EARLY',
      series: [series('GAME', 'Game projection', {
        overall: cell('ALL', 212, null, null),
        byPosition: [cell('PG', 120, null, null)],
        byBand: [cell('0–20', 90, null, null)],
      })],
    })
    render(<ProjectionAccuracyView a={early} />)
    expect(screen.getByText(/Too early to measure/)).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('value', '212')
    expect(document.body.textContent).not.toMatch(/±/)
    expect(document.body.textContent).not.toMatch(/runs \d/)
    // The counts are there.
    const row = screen.getByRole('row', { name: /Overall/ })
    expect(within(row).getByText('212')).toBeInTheDocument()
  })

  it('says the lean in words, high or low, and never colours it', () => {
    render(<ProjectionAccuracyView a={measured()} />)
    // Opens on the season-average series (what the rankings use): overall lean -2.14.
    expect(screen.getByRole('row', { name: /Overall/ })).toHaveTextContent('runs 2.1 low')
    expect(screen.getByRole('row', { name: /^PG/ })).toHaveTextContent('runs 1.0 high')
    const html = document.body.innerHTML
    expect(html).not.toMatch(/green|red|good|bad|positive|negative/i)
    for (const td of document.querySelectorAll('td')) {
      expect(td.getAttribute('class') ?? '').not.toMatch(/best|worst|up|down/)
      expect(td.hasAttribute('style')).toBe(false)
    }
  })

  it('shows "too few (n)" for a row below the cell minimum, and keeps its count', () => {
    render(<ProjectionAccuracyView a={measured()} />)
    const row = screen.getByRole('row', { name: /^C/ })
    expect(row).toHaveTextContent('too few (12)')
    expect(row).not.toHaveTextContent('±')
  })

  it('offers both NBA series as tabs and switches between them', async () => {
    render(<ProjectionAccuracyView a={measured({
      series: [
        series('GAME', 'Game projection', { overall: cell('ALL', 612, 5.0, 1.0) }),
        series('SEASON_AVERAGE', 'Season average (what the rankings use)'),
      ],
    })} />)
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((t) => t.textContent)).toEqual(['Game projection', 'Season average (what the rankings use)'])
    expect(tabs[1]).toHaveAttribute('aria-selected', 'true')
    await userEvent.click(tabs[0])
    expect(screen.getByRole('row', { name: /Overall/ })).toHaveTextContent('runs 1.0 high')
  })

  it('football has no tabs and counts player-weeks', () => {
    render(<ProjectionAccuracyView a={measured({
      sport: 'nfl', unit: 'PLAYER_WEEK',
      series: [series('GAME', 'Weekly projection')],
    })} />)
    expect(screen.queryAllByRole('tab')).toHaveLength(0)
    expect(screen.getByText('Player-weeks')).toBeInTheDocument()
    expect(screen.getByText(/a week;/)).toBeInTheDocument()
  })

  it('renders the excluded-categories caveat in words, plus the DNP and pending footer', () => {
    render(<ProjectionAccuracyView a={measured()} />)
    expect(screen.getByText('Not graded (no projection carries them): 40+ point bonus.')).toBeInTheDocument()
    expect(screen.getByText(/Morning-of projection/)).toBeInTheDocument()
    expect(screen.getByText(/Covers games from/)).toHaveTextContent('graded through')
    expect(screen.getByText(/Did not play:/)).toHaveTextContent('Did not play: 17')
    expect(screen.getByText(/Did not play:/)).toHaveTextContent('Not yet played: 640')
    expect(screen.getByText(/Did not play:/)).toHaveTextContent('Postponed or canceled (not graded): 2')
  })

  it('says nothing is captured yet for NO_SNAPSHOTS instead of drawing an empty table', () => {
    render(<ProjectionAccuracyView a={measured({ status: 'NO_SNAPSHOTS', series: [], snapshotsFrom: null, scoredThrough: null, caveats: [], excludedCategories: [] })} />)
    expect(screen.getByText(/No pre-game projections have been captured/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).toBeNull()
  })
})
