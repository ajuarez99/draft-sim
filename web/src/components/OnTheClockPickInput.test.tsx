import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import OnTheClockPickInput from './OnTheClockPickInput'
import type { PlayerRef } from '../api'

function player(overrides: Partial<PlayerRef>): PlayerRef {
  return {
    id: 1,
    sleeperId: 's1',
    name: 'Test Player',
    position: 'RB',
    team: 'FA',
    adp: 1,
    positionalRank: 1,
    ...overrides,
  }
}

const available: PlayerRef[] = [
  player({ id: 1, sleeperId: 's1', name: 'Alpha Back', position: 'RB', adp: 5 }),
  player({ id: 2, sleeperId: 's2', name: 'Beta Wideout', position: 'WR', adp: 1 }),
  player({ id: 3, sleeperId: 's3', name: 'Gamma Passer', position: 'QB', adp: 10 }),
]

describe('OnTheClockPickInput', () => {
  it('lists every available player sorted by ADP, ALL selected by default', () => {
    render(
      <OnTheClockPickInput
        pickNo={1}
        round={1}
        available={available}
        rosterPositions={[]}
        draftedPlayers={[]}
        onPick={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    const rows = screen.getAllByRole('row').slice(1) // drop the header row
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent('Beta Wideout') // adp 1, lowest first
    expect(rows[1]).toHaveTextContent('Alpha Back')
    expect(rows[2]).toHaveTextContent('Gamma Passer')
  })

  it('filters to the selected position', async () => {
    const user = userEvent.setup()
    render(
      <OnTheClockPickInput
        pickNo={1}
        round={1}
        available={available}
        rosterPositions={[]}
        draftedPlayers={[]}
        onPick={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'WR' }))

    expect(screen.getByText('Beta Wideout')).toBeInTheDocument()
    expect(screen.queryByText('Alpha Back')).not.toBeInTheDocument()
    expect(screen.queryByText('Gamma Passer')).not.toBeInTheDocument()
  })

  it('calls onPick with the chosen player when a row is clicked', async () => {
    const onPick = vi.fn()
    const user = userEvent.setup()
    render(
      <OnTheClockPickInput
        pickNo={4}
        round={1}
        available={available}
        rosterPositions={[]}
        draftedPlayers={[]}
        onPick={onPick}
        onClose={vi.fn()}
      />,
    )
    await user.click(screen.getByText('Beta Wideout'))
    expect(onPick).toHaveBeenCalledWith(available[1])
  })

  it('closes on Escape', () => {
    const onClose = vi.fn()
    render(
      <OnTheClockPickInput
        pickNo={1}
        round={1}
        available={available}
        rosterPositions={[]}
        draftedPlayers={[]}
        onPick={vi.fn()}
        onClose={onClose}
      />,
    )
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })

  it('shows a "fills a need" tag for an open starting slot', () => {
    render(
      <OnTheClockPickInput
        pickNo={1}
        round={1}
        available={[player({ id: 1, sleeperId: 's1', name: 'Alpha Back', position: 'RB' })]}
        rosterPositions={['RB', 'WR']}
        draftedPlayers={[]}
        onPick={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    expect(screen.getByText('Fills RB')).toBeInTheDocument()
  })
})
