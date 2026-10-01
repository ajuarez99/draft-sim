import { render, screen, within } from '@testing-library/react'
import { beforeAll, describe, expect, it } from 'vitest'
import type { AvailabilityRow } from '../api'
import { mkPlayer } from '../testLiveRoom'
import AvailabilityPanel from './AvailabilityPanel'

const row = (name: string, adp: number, survival: number, position = 'RB'): AvailabilityRow => ({
  player: mkPlayer(position, name, adp),
  survivalByPick: { '20': survival, '33': survival },
})

beforeAll(() => {
  // jsdom has no ResizeObserver; the panel uses one to publish its height.
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
})

const base = { myPicks: [20, 33], teams: 12, started: true, sport: 'nfl' as const }

describe('AvailabilityPanel', () => {
  it('groups rows under tier headings, with a face per player', () => {
    const availability = [row('Alpha', 10, 0.5), row('Bravo', 12, 0.5), row('Charlie', 40, 0.5)]
    const { container } = render(<AvailabilityPanel {...base} availability={availability} />)
    expect(screen.getByText('Tier 1')).toBeInTheDocument()
    expect(screen.getByText('Tier 2')).toBeInTheDocument()
    // One face per player: an image or an initials fallback, both named for the player.
    expect(container.querySelectorAll('.pface')).toHaveLength(3)
  })

  it('keeps the verdict thresholds for known survival values', () => {
    const availability = [row('Low', 10, 0.2), row('Mid', 11, 0.5), row('High', 12, 0.8)]
    render(<AvailabilityPanel {...base} availability={availability} />)
    const verdictOf = (name: string) => {
      const tr = screen.getByText(name).closest('tr') as HTMLElement
      return within(tr).getByText(/Act now|Coin flip|Safe/).textContent
    }
    expect(verdictOf('Low')).toBe('Act now')
    expect(verdictOf('Mid')).toBe('Coin flip')
    expect(verdictOf('High')).toBe('Safe')
  })

  it('shows the reason and no survival or verdict when there is no availability', () => {
    render(
      <AvailabilityPanel
        {...base}
        players={[mkPlayer('QB', 'Solo', 5)]}
        noAvailabilityReason="Availability needs a simulation, which mock drafts don't run."
      />,
    )
    expect(screen.getByText("Availability needs a simulation, which mock drafts don't run.")).toBeInTheDocument()
    expect(screen.getByText('Solo')).toBeInTheDocument()
    expect(screen.queryByText('Verdict')).toBeNull()
    expect(screen.queryByText(/Act now|Coin flip|Safe/)).toBeNull()
  })

  it('holds survival back when a reason is given even if availability is present', () => {
    render(
      <AvailabilityPanel
        {...base}
        availability={[row('Held', 10, 0.1)]}
        noAvailabilityReason="Availability appears once your seat is known."
      />,
    )
    expect(screen.getByText('Availability appears once your seat is known.')).toBeInTheDocument()
    expect(screen.getByText('Held')).toBeInTheDocument()
    expect(screen.queryByText('Act now')).toBeNull()
  })

  it('calls out a position run using the given sport', () => {
    const wr = (n: number) => ({ position: n < 4 ? 'WR' : 'RB' })
    const recent = Array.from({ length: 6 }, (_, i) => wr(i))
    // 4 WR + 2 RB in the last 6 picks
    render(<AvailabilityPanel {...base} availability={[row('A', 10, 0.5)]} recentPicks={[...recent.slice(0, 4), { position: 'RB' }, { position: 'RB' }]} />)
    expect(screen.getByText(/WR run:/)).toBeInTheDocument()
    expect(screen.getByText(/4 of the last 6 picks/)).toBeInTheDocument()
  })

  it('does not read a football run into a basketball room', () => {
    const recent = Array.from({ length: 6 }, () => ({ position: 'WR' }))
    render(<AvailabilityPanel {...base} sport="nba" availability={[row('A', 10, 0.5, 'PG')]} recentPicks={recent} />)
    expect(screen.queryByText(/run:/)).toBeNull()
  })
})
