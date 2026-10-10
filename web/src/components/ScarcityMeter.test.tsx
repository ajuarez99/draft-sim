import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import ScarcityMeter from './ScarcityMeter'
import type { ScarcityResult } from '../scarcity'

function result(over: Partial<ScarcityResult> = {}): ScarcityResult {
  return {
    sport: 'nfl',
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

  // Spec 024 FR-018: a position with an empty pool is "no data", not a measurement, so it
  // gets no chip. Spec 025 reworded it: eligibility, not first-listed position.
  const nba = () =>
    result({
      sport: 'nba',
      rows: [
        { position: 'PG', poolSize: 17, leftNow: 15, expectedAtNext: null, running: false },
        { position: 'SG', poolSize: 0, leftNow: 0, expectedAtNext: null, running: false },
        { position: 'SF', poolSize: 0, leftNow: 0, expectedAtNext: null, running: false },
        { position: 'PF', poolSize: 8, leftNow: 8, expectedAtNext: null, running: false },
        { position: 'C', poolSize: 11, leftNow: 9, expectedAtNext: null, running: false },
      ],
    })

  it('does not draw a chip for a position with no starter-pool players, and names them once', () => {
    render(<ScarcityMeter scarcity={nba()} failed={false} />)
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
    expect(screen.queryByText('0 / 0')).toBeNull()
    expect(screen.getByText('SG, SF: no starter-pool players are eligible here')).toBeInTheDocument()
  })

  it('NBA: says "eligible" in each chip, with the full sentence in the title', () => {
    render(<ScarcityMeter scarcity={nba()} failed={false} />)
    const chip = screen.getByText('15 / 17').closest('li')!
    expect(chip).toHaveTextContent(/PG\s*15 \/ 17\s*eligible/)
    expect(chip.getAttribute('title')).toBe(
      'PG: 15 of the 17 starter-pool players eligible at PG are still on the board (a player can count at more than one position)',
    )
  })

  it('NBA: tells the reader a player can count at more than one position', () => {
    render(<ScarcityMeter scarcity={nba()} failed={false} />)
    const note = screen.getByText(/Starter pool: the board's top 120/)
    expect(note).toHaveTextContent('a player can count at more than one position')
  })

  // SC-005: football players have one position, so the eligibility wording would be noise
  // there. Found in live verification (T024): football chips read "RB 1 / 37 eligible".
  it('football: no "eligible" wording and no multi-position note', () => {
    render(<ScarcityMeter scarcity={result()} failed={false} />)
    const chip = screen.getByText('9 / 34').closest('li')!
    expect(chip).not.toHaveTextContent(/eligible/)
    expect(chip.getAttribute('title')).toBeNull()
    expect(screen.queryByText(/more than one position/)).toBeNull()
  })

  it('NBA: still prints the run sentence when every chip in the running family is hidden (review N8)', () => {
    const r = nba()
    // SG/SF hidden (poolSize 0); mark the hidden SG running and PG not.
    r.rows[1] = { ...r.rows[1], running: true }
    r.run = { position: 'G', count: 4, window: 6 }
    render(<ScarcityMeter scarcity={r} failed={false} />)
    expect(screen.getAllByText('4 of the last 6 were guards')).toHaveLength(1)
  })

  it('words an NBA run by family ("guards") once, even though both guard chips are running', () => {
    const r = nba()
    r.rows[0] = { ...r.rows[0], running: true }
    r.rows[1] = { ...r.rows[1], poolSize: 4, leftNow: 4, running: true }
    r.run = { position: 'G', count: 4, window: 6 }
    render(<ScarcityMeter scarcity={r} failed={false} />)
    expect(screen.getAllByText('4 of the last 6 were guards')).toHaveLength(1)
    // ...and on the first running chip (PG), where the eye lands first.
    expect(screen.getByText('4 of the last 6 were guards').closest('li')).toHaveTextContent(/^PG/)
  })

  it('adds no note when nothing is hidden', () => {
    render(<ScarcityMeter scarcity={result()} failed={false} />)
    expect(screen.queryByText(/are eligible here/)).toBeNull()
  })
})
