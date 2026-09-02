import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import TurnIndicator from './TurnIndicator'
import type { MockSeat } from '../api'

const seats: MockSeat[] = [
  { slot: 1, type: 'USER', managerId: null, manager: 'You' },
  { slot: 2, type: 'BOT', managerId: null, manager: 'Bot 2' },
]

describe('TurnIndicator', () => {
  it('shows the completion state when the draft is done', () => {
    render(
      <TurnIndicator currentPickNo={121} round={15} onTheClockSlot={null} isUsersTurn={false} seats={seats} complete />,
    )
    expect(screen.getByText('Mock draft complete')).toBeInTheDocument()
  })

  it("says 'Your pick' when it's the user's turn", () => {
    render(
      <TurnIndicator currentPickNo={1} round={1} onTheClockSlot={1} isUsersTurn={true} seats={seats} complete={false} />,
    )
    expect(screen.getByText('Your pick')).toBeInTheDocument()
    expect(screen.getByText('Pick 1')).toBeInTheDocument()
  })

  it('names the manager on the clock when it is not the user', () => {
    render(
      <TurnIndicator currentPickNo={2} round={1} onTheClockSlot={2} isUsersTurn={false} seats={seats} complete={false} />,
    )
    expect(screen.getByText('Bot 2 is on the clock')).toBeInTheDocument()
  })

  it('falls back gracefully when the on-the-clock seat is unknown', () => {
    render(
      <TurnIndicator currentPickNo={3} round={1} onTheClockSlot={99} isUsersTurn={false} seats={seats} complete={false} />,
    )
    expect(screen.getByText('Someone is on the clock')).toBeInTheDocument()
  })
})
