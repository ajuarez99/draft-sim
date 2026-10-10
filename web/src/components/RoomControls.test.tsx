import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import RoomControls from './RoomControls'

describe('RoomControls', () => {
  it('renders one primary, last, with quiet children before it', () => {
    const onClick = vi.fn()
    const { container } = render(
      <RoomControls primary={{ label: 'Go', onClick, title: 'why' }}>
        <button className="chip">Quiet</button>
      </RoomControls>,
    )
    expect(container.querySelectorAll('.room-primary')).toHaveLength(1)
    expect(container.querySelector('.room-controls-group')!.lastElementChild!.textContent).toBe('Go')
    fireEvent.click(screen.getByRole('button', { name: 'Go' }))
    expect(onClick).toHaveBeenCalled()
  })
  it('a disabled primary stays rendered and keeps its reason as the title', () => {
    render(<RoomControls primary={{ label: 'Go', onClick: () => {}, disabled: true, title: 'Not yet: reason' }} />)
    const b = screen.getByRole('button', { name: 'Go' })
    expect(b).toBeDisabled()
    expect(b).toHaveAttribute('title', 'Not yet: reason')
  })
})
