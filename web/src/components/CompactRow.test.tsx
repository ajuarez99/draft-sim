import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import CompactRow from './CompactRow'
import type { FeedPick } from './PickFeed'
import type { PlayerRef } from '../api'
import type { SlotStatus } from '../teamNeeds'

let nextId = 1
function player(position: string, name: string): PlayerRef {
  const id = nextId++
  return { id, sleeperId: String(id), name, position: position as PlayerRef['position'], team: 'SEA', adp: id, positionalRank: id }
}
const picks = (n: number): FeedPick[] =>
  Array.from({ length: n }, (_, i) => ({ pickNo: i + 1, player: player('WR', `Player ${i + 1}`), manager: `Mgr ${i + 1}` }))

const needs: SlotStatus[] = [
  { slot: 'QB', player: player('QB', 'Quinn') },
  { slot: 'RB', player: null },
]

describe('CompactRow', () => {
  it('shows only the latest pick until the ticker is expanded', () => {
    render(<CompactRow feedPicks={picks(8)} teams={12} sport="nfl" />)
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
    expect(screen.getByText('Player 8')).toBeInTheDocument()
    const toggle = screen.getByRole('button', { name: /Last 5/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(screen.getAllByRole('listitem')).toHaveLength(5)
    fireEvent.click(screen.getByRole('button', { name: /Latest/ }))
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
  })

  it('offers no expand toggle for a single pick', () => {
    render(<CompactRow feedPicks={picks(1)} teams={12} sport="nfl" />)
    expect(screen.queryByRole('button', { name: /Last/ })).toBeNull()
  })

  it('renders only the parts it is given, with no empty boxes', () => {
    const { container, rerender } = render(<CompactRow teams={12} sport="nfl" />)
    expect(container).toBeEmptyDOMElement()

    rerender(<CompactRow teams={12} sport="nfl" needs={needs} />)
    expect(screen.getByText('1 of 2 starters')).toBeInTheDocument()
    expect(container.querySelector('.compact-ticker')).toBeNull()
    expect(container.querySelector('.compact-scarcity')).toBeNull()
    expect(container.querySelector('.compact-read')).toBeNull()

    rerender(<CompactRow teams={12} sport="nfl" needs={[]} feedPicks={[]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('opens the room read as a popover from its toggle', () => {
    const { container } = render(<CompactRow teams={12} sport="nfl" roomRead={<p>seat reads</p>} />)
    expect(screen.queryByText('seat reads')).toBeNull()
    const toggle = screen.getByRole('button', { name: 'Room read' })
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(container.querySelector('.compact-popover')).toHaveTextContent('seat reads')
    fireEvent.click(toggle)
    expect(screen.queryByText('seat reads')).toBeNull()
  })

  it('shows the scarcity part when given one', () => {
    render(<CompactRow teams={12} sport="nfl" scarcity={<div>chips</div>} />)
    expect(screen.getByText('chips')).toBeInTheDocument()
  })
})
