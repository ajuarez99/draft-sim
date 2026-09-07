import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import TurnIndicator from './TurnIndicator'
import type { MockSeat } from '../api'

const seats: MockSeat[] = [
  { slot: 1, type: 'USER', managerId: null, manager: 'You' },
  { slot: 2, type: 'BOT', managerId: null, manager: 'Bot 2' },
]

// TurnIndicator is now a thin adapter over OnTheClock, so these assert the
// mapping (who is on the clock, whose turn it is, the completion state) rather
// than the markup -- the strip's own layout is OnTheClock's business.
describe('TurnIndicator', () => {
  it('shows the completion state when the draft is done', () => {
    render(
      <TurnIndicator
        currentPickNo={121}
        onTheClockSlot={null}
        isUsersTurn={false}
        seats={seats}
        complete
        teams={8}
        rounds={15}
      />,
    )
    expect(screen.getByText('Mock draft complete')).toBeInTheDocument()
  })

  it("says 'Your pick' when it's the user's turn", () => {
    render(
      <TurnIndicator
        currentPickNo={1}
        onTheClockSlot={1}
        isUsersTurn
        seats={seats}
        complete={false}
        teams={8}
        rounds={15}
      />,
    )
    expect(screen.getByText('Your pick')).toBeInTheDocument()
    expect(screen.getByText('You')).toBeInTheDocument()
    // Your own turn shows overall progress, not a countdown to yourself.
    expect(screen.getByText('1/120')).toBeInTheDocument()
  })

  it('names the manager on the clock when it is not the user', () => {
    render(
      <TurnIndicator
        currentPickNo={2}
        onTheClockSlot={2}
        isUsersTurn={false}
        seats={seats}
        complete={false}
        teams={8}
        rounds={15}
      />,
    )
    expect(screen.getByText('On the clock')).toBeInTheDocument()
    expect(screen.getByText('Bot 2')).toBeInTheDocument()
  })

  it('counts down to your next pick while someone else is up', () => {
    render(
      <TurnIndicator
        currentPickNo={2}
        onTheClockSlot={2}
        isUsersTurn={false}
        seats={seats}
        complete={false}
        teams={8}
        rounds={15}
        nextOwnPick={16}
      />,
    )
    expect(screen.getByText('14')).toBeInTheDocument()
    expect(screen.getByText('picks until you')).toBeInTheDocument()
  })

  it('falls back gracefully when the on-the-clock seat is unknown', () => {
    render(
      <TurnIndicator
        currentPickNo={3}
        onTheClockSlot={99}
        isUsersTurn={false}
        seats={seats}
        complete={false}
        teams={8}
        rounds={15}
      />,
    )
    expect(screen.getByText('Unknown seat')).toBeInTheDocument()
  })
})
