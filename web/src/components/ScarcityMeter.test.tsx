import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import ScarcityMeter from './ScarcityMeter'
import type { ScarcityResult } from '../scarcity'

function result(over: Partial<ScarcityResult> = {}): ScarcityResult {
  return {
    S: 120,
    teams: 12,
    startersPerTeam: 10,
    definition: "Starter pool: the board's top 120 (12 teams × 10 starters)",
    rows: [
      { position: 'QB', poolSize: 20, leftNow: 14, expectedAtNext: null, running: false },
      { position: 'RB', poolSize: 34, leftNow: 9, expectedAtNext: null, running: false },
      { position: 'WR', poolSize: 40, leftNow: 30, expectedAtNext: null, running: false },
      { position: 'TE', poolSize: 12, leftNow: 8, expectedAtNext: null, running: false },
    ],
    run: null,
    gatedByDepth: false,
    projectedFrom: 46,
    ...over,
  }
}

describe('ScarcityMeter', () => {
  it('shows one chip per position and the definition line', () => {
    render(<ScarcityMeter scarcity={result()} failed={false} />)
    expect(screen.getAllByRole('listitem')).toHaveLength(4)
    expect(screen.getByText('9 / 34')).toBeInTheDocument()
    expect(screen.getByText(/Starter pool: the board's top 120/)).toBeInTheDocument()
    expect(screen.queryByText(/projected count/)).toBeNull()
  })

  it('adds the expected count with one decimal at most and the pick label', () => {
    const r = result()
    r.rows[1] = { ...r.rows[1], expectedAtNext: 4.56 }
    r.rows[0] = { ...r.rows[0], expectedAtNext: 5 }
    render(<ScarcityMeter scarcity={r} failed={false} myNextPickLabel="3.04" />)
    expect(screen.getByText(/~4\.6 at 3\.04/)).toBeInTheDocument()
    expect(screen.getByText(/~5 at 3\.04/)).toBeInTheDocument()
  })

  it('marks a running position with the feed wording', () => {
    const r = result({ run: { position: 'RB', count: 4, window: 6 } })
    r.rows[1] = { ...r.rows[1], running: true }
    render(<ScarcityMeter scarcity={r} failed={false} />)
    expect(screen.getByText('4 of the last 6 were RB')).toBeInTheDocument()
  })

  it('says the projected count starts later when the depth gate is what closed it', () => {
    render(<ScarcityMeter scarcity={result({ gatedByDepth: true, projectedFrom: 46 })} failed={false} />)
    expect(screen.getByText('projected count from pick ~46')).toBeInTheDocument()
  })

  it('says "no board built yet" and nothing else when the fetch failed', () => {
    render(<ScarcityMeter scarcity={result()} failed={true} />)
    expect(screen.getByText('no board built yet')).toBeInTheDocument()
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    expect(screen.queryByText(/Starter pool/)).toBeNull()
  })

  it('says "no board built yet" for an empty pool or no result', () => {
    const empty = result({ rows: result().rows.map((r) => ({ ...r, poolSize: 0, leftNow: 0 })) })
    const { rerender } = render(<ScarcityMeter scarcity={empty} failed={false} />)
    expect(screen.getByText('no board built yet')).toBeInTheDocument()
    rerender(<ScarcityMeter scarcity={null} failed={false} />)
    expect(screen.getByText('no board built yet')).toBeInTheDocument()
  })
})
