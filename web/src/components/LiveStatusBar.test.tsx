import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import LiveStatusBar from './LiveStatusBar'
import type { LiveState, Seat } from '../api'

// The live room's `drafting` branch cannot be reached in a browser without a
// real in-progress Sleeper draft, and there hasn't been one since 2026-09-02.
// These cover the mapping into OnTheClock instead -- which is the whole of
// what this component does now that it composes rather than draws its own
// identity block.

const seats: Seat[] = [
  {
    slot: 3,
    managerId: 77,
    manager: 'allieOOP',
    provenance: 'FITTED',
    reachBias: 4.8,
    unpredictability: 1,
    positionalTilt: {},
    note: null,
    draftsObserved: 2,
    picksScored: 30,
  },
]

const live = (over: Partial<LiveState> = {}): LiveState => ({
  draftId: 'd1',
  status: 'drafting',
  tracking: true,
  picksMade: 27,
  lastPickNo: 27,
  totalPicks: 210,
  teams: 14,
  rounds: 15,
  seatsMapped: 14,
  onTheClockSlot: 3,
  // Nothing in this component reads them; the field is required on LiveState
  // because the backend always sends the array (empty before the first pick).
  recentPicks: [],
  serverTime: '2026-09-07T12:00:00Z',
  ...over,
})

const base = {
  draftId: 'd1',
  connected: true,
  secondsSinceContact: 2,
  seats,
  onSeatClick: () => {},
}

describe('LiveStatusBar', () => {
  it('names the seat on the clock and the pick it is waiting on', () => {
    render(<LiveStatusBar {...base} live={live()} />)
    expect(screen.getByText('On the clock')).toBeInTheDocument()
    expect(screen.getByText('allieOOP')).toBeInTheDocument()
    // picksMade 27 -> the room is waiting on pick 28, which is 2.14 at 14 teams.
    expect(screen.getByText('— 2.14')).toBeInTheDocument()
  })

  it('counts down to your next pick', () => {
    render(<LiveStatusBar {...base} live={live()} mySlot={1} nextOwnPick={36} />)
    expect(screen.getByText('8')).toBeInTheDocument()
    expect(screen.getByText('picks until you')).toBeInTheDocument()
  })

  it("marks the strip as yours when it's your seat on the clock", () => {
    const { container } = render(<LiveStatusBar {...base} live={live()} mySlot={3} />)
    expect(container.querySelector('.on-clock.mine')).not.toBeNull()
    expect(screen.getByText('Your pick')).toBeInTheDocument()
  })

  it('opens the seat popover from the identity block', async () => {
    const onSeatClick = vi.fn()
    render(<LiveStatusBar {...base} live={live()} onSeatClick={onSeatClick} />)
    await userEvent.click(screen.getByRole('button', { name: /allieOOP/ }))
    expect(onSeatClick).toHaveBeenCalledWith(3)
  })

  // Four reasons nobody is on the clock, and they are not interchangeable --
  // collapsing "not started" and "we can't reach the backend" into one word is
  // exactly the failure this table exists to prevent.
  it.each([
    ['complete', live({ status: 'complete', onTheClockSlot: null }), true, 'Draft complete'],
    ['pre_draft', live({ status: 'pre_draft', onTheClockSlot: null }), true, 'Draft has not started'],
    ['drafting with no seat map', live({ onTheClockSlot: null }), true, 'Waiting for the draft order'],
    ['no state, connected', null, true, 'Connecting to the draft'],
    ['no state, disconnected', null, false, 'Not connected'],
  ])('says why nobody is on the clock: %s', (_name, state, connected, expected) => {
    render(<LiveStatusBar {...base} live={state} connected={connected} />)
    expect(screen.getByText(expected)).toBeInTheDocument()
  })

  it('does not claim a draft size before a state frame arrives', () => {
    render(<LiveStatusBar {...base} live={null} />)
    expect(screen.queryByText(/0 picks/)).toBeNull()
  })

  it('flags a stale stream', () => {
    const { container } = render(<LiveStatusBar {...base} live={live()} secondsSinceContact={600} />)
    expect(container.querySelector('.live-fresh.stale')).not.toBeNull()
    expect(screen.getByText(/Stale/)).toBeInTheDocument()
  })

  it('reports no contact rather than a zero age', () => {
    render(<LiveStatusBar {...base} live={live()} secondsSinceContact={null} />)
    expect(screen.getByText('No contact')).toBeInTheDocument()
  })
})
