import { act, fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import RankBoard, { type RankBoardMember } from './RankBoard'

/**
 * What can honestly be asserted about this board in jsdom, and no more.
 *
 * jsdom has no layout engine -- `getBoundingClientRect()` returns all zeros --
 * so nothing here can prove where a dragged chip LANDS. That arithmetic lives
 * in dragGesture.ts and is unit-tested there over fabricated geometry.
 *
 * What these tests do cover is the decision that has to happen before any
 * geometry matters: which gestures start a drag at all. That is the whole of
 * the scroll-versus-drag fix (US2), and it is pure event handling, so jsdom
 * can see it.
 */

const MEMBERS: RankBoardMember[] = [
  { rosterId: 1, managerId: 11, manager: 'Alice', teamName: 'Team A', isMe: true },
  { rosterId: 2, managerId: 12, manager: 'Bob', teamName: 'Team B' },
  { rosterId: 3, managerId: 13, manager: 'Cleo', teamName: 'Team C' },
  { rosterId: 4, managerId: 14, manager: 'Dev', teamName: 'Team D' },
]

const FULL_ORDER = [1, 2, 3, 4]

function renderBoard(props: Partial<React.ComponentProps<typeof RankBoard>> = {}) {
  const onSubmit = vi.fn()
  const utils = render(
    <RankBoard members={MEMBERS} ariaLabel="Test ballot" initialOrder={FULL_ORDER} onSubmit={onSubmit} {...props} />,
  )
  const board = utils.container.querySelector('.rankboard') as HTMLElement
  return { ...utils, onSubmit, board }
}

/** True once a drag is actually under way -- the ghost only renders after the
 *  threshold is crossed and `draggingChipId` is set. */
function isDragging(container: HTMLElement): boolean {
  return container.querySelector('.rankboard-ghost') !== null
}

/** Press, then move far enough to clear DRAG_THRESHOLD_PX (6). */
function pressAndMove(target: Element, board: HTMLElement, pointerType: string) {
  fireEvent.pointerDown(target, { pointerId: 1, button: 0, pointerType, clientX: 50, clientY: 50 })
  fireEvent.pointerMove(board, { pointerId: 1, button: 0, pointerType, clientX: 50, clientY: 90 })
}

describe('RankBoard gesture gating', () => {
  it('starts a drag when a touch rests on the row body', () => {
    // The fix for "I pressed the team and the page just scrolled". The grip is
    // only discoverable once you know it is there; a hold is what people
    // actually reach for.
    vi.useFakeTimers()
    try {
      const { container } = renderBoard()
      const chip = screen.getByRole('button', { name: /Alice, Team A, rank 1 of 4/ })

      fireEvent.pointerDown(chip, { pointerId: 1, button: 0, pointerType: 'touch', clientX: 50, clientY: 50 })
      expect(isDragging(container)).toBe(false) // nothing yet -- this could still be a scroll

      act(() => {
        vi.advanceTimersByTime(400)
      })
      expect(isDragging(container)).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  it('abandons a pending hold if the finger travels first', () => {
    // A swipe that happens to begin on a chip is still a swipe.
    vi.useFakeTimers()
    try {
      const { container, board } = renderBoard()
      const chip = screen.getByRole('button', { name: /Alice, Team A, rank 1 of 4/ })

      fireEvent.pointerDown(chip, { pointerId: 1, button: 0, pointerType: 'touch', clientX: 50, clientY: 50 })
      fireEvent.pointerMove(board, { pointerId: 1, pointerType: 'touch', clientX: 50, clientY: 90 })
      act(() => {
        vi.advanceTimersByTime(400)
      })

      expect(isDragging(container)).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not start a drag when a touch swipes straight off the row body', () => {
    // The scroll case. Before the handle existed this picked the team up,
    // which is why the board could not be read on a phone without disturbing
    // it -- every row is a 44px full-width target and they tile the list.
    const { container, board } = renderBoard()
    const chip = screen.getByRole('button', { name: /Alice, Team A, rank 1 of 4/ })

    pressAndMove(chip, board, 'touch')

    expect(isDragging(container)).toBe(false)
  })

  it('starts a drag when a touch begins on the grip handle', () => {
    const { container, board } = renderBoard()
    const handle = container.querySelectorAll('.rankboard-handle')[0]
    expect(handle).toBeTruthy()

    pressAndMove(handle, board, 'touch')

    expect(isDragging(container)).toBe(true)
  })

  it('starts a drag when a mouse begins anywhere on the row body', () => {
    // FR-021: the handle is a touch affordance and must cost the pointer path
    // nothing -- no delay, no smaller grab area.
    const { container, board } = renderBoard()
    const chip = screen.getByRole('button', { name: /Alice, Team A, rank 1 of 4/ })

    pressAndMove(chip, board, 'mouse')

    expect(isDragging(container)).toBe(true)
  })

  it('ignores a non-primary button even on the handle', () => {
    const { container, board } = renderBoard()
    const handle = container.querySelectorAll('.rankboard-handle')[0]

    fireEvent.pointerDown(handle, { pointerId: 1, button: 2, pointerType: 'mouse', clientX: 50, clientY: 50 })
    fireEvent.pointerMove(board, { pointerId: 1, button: 2, pointerType: 'mouse', clientX: 50, clientY: 90 })

    expect(isDragging(container)).toBe(false)
  })

  it('gives every placed chip a handle, and unplaced tray chips none', () => {
    const { container } = renderBoard({ initialOrder: undefined })
    // Nothing placed: four chips in the tray, no grips -- the tray is a
    // wrapped flex row, not a scroller, so there is no gesture to disambiguate.
    expect(container.querySelectorAll('.rankboard-handle')).toHaveLength(0)
    expect(container.querySelector('.rankboard-tray')).toBeTruthy()
  })
})

describe('RankBoard tap route', () => {
  const names = (c: HTMLElement) =>
    [...c.querySelectorAll('.rankboard-slot')].map((s) => s.querySelector('.rankboard-chip-name')?.textContent ?? '-')

  it('moves a tapped team to the tapped rank, shifting the rest by one', () => {
    // Not a swap: Alice goes to rank 3 and Bob/Cleo each move up one, which
    // is what `move()` in rankOrder.ts does and what FR-005 requires.
    const { container } = renderBoard()
    fireEvent.click(screen.getByRole('button', { name: /Alice, Team A, rank 1 of 4/ }))
    fireEvent.click(screen.getByRole('button', { name: /Cleo, Team C, rank 3 of 4/ }))
    expect(names(container)).toEqual(['Bob', 'Cleo', 'Alice', 'Dev'])
  })

  it('clears the selection without moving anything when the same chip is tapped twice', () => {
    const { container } = renderBoard()
    const alice = screen.getByRole('button', { name: /Alice, Team A, rank 1 of 4/ })
    fireEvent.click(alice)
    expect(alice).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(alice)
    expect(alice).toHaveAttribute('aria-pressed', 'false')
    expect(names(container)).toEqual(['Alice', 'Bob', 'Cleo', 'Dev'])
  })

  it('shows nudge buttons only on the selected chip, and moves one rank per press', () => {
    const { container } = renderBoard()
    expect(container.querySelectorAll('.rankboard-nudge')).toHaveLength(0)

    fireEvent.click(screen.getByRole('button', { name: /Cleo, Team C, rank 3 of 4/ }))
    expect(container.querySelectorAll('.rankboard-nudge')).toHaveLength(1)

    fireEvent.click(screen.getByRole('button', { name: 'Move Cleo up one rank' }))
    expect(names(container)).toEqual(['Alice', 'Cleo', 'Bob', 'Dev'])

    fireEvent.click(screen.getByRole('button', { name: 'Move Cleo down one rank' }))
    expect(names(container)).toEqual(['Alice', 'Bob', 'Cleo', 'Dev'])
  })

  it('disables the nudge that would run off the end of the board', () => {
    renderBoard()
    fireEvent.click(screen.getByRole('button', { name: /Alice, Team A, rank 1 of 4/ }))
    expect(screen.getByRole('button', { name: 'Move Alice up one rank' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Move Alice down one rank' })).toBeEnabled()
  })

  it('keeps ArrowUp/ArrowDown working on a focused chip', () => {
    // FR-017: the keyboard path is a floor. The buttons call the same
    // moveChipBy, so this is the one rule reached two ways, not two rules.
    const { container } = renderBoard()
    fireEvent.keyDown(screen.getByRole('button', { name: /Dev, Team D, rank 4 of 4/ }), { key: 'ArrowUp' })
    expect(names(container)).toEqual(['Alice', 'Bob', 'Dev', 'Cleo'])
  })

  it('announces a completed move to assistive technology', () => {
    const { container } = renderBoard()
    fireEvent.keyDown(screen.getByRole('button', { name: /Dev, Team D, rank 4 of 4/ }), { key: 'ArrowUp' })
    expect(container.querySelector('.rankboard-sr-only')?.textContent).toBe('Placed Dev 3rd of 4.')
  })
})

describe('RankBoard submission gate', () => {
  it('disables submit until every rank is filled', () => {
    const { rerender } = renderBoard({ initialOrder: undefined, submitLabel: 'Submit ballot' })
    expect(screen.getByRole('button', { name: 'Submit ballot' })).toBeDisabled()
    expect(screen.getByText(/4 of 4 still unranked/)).toBeTruthy()

    rerender(
      <RankBoard
        key="complete"
        members={MEMBERS}
        ariaLabel="Test ballot"
        initialOrder={FULL_ORDER}
        onSubmit={vi.fn()}
        submitLabel="Submit ballot"
      />,
    )
    expect(screen.getByRole('button', { name: 'Submit ballot' })).toBeEnabled()
  })

  it('submits the complete ordering, rank 1 first', () => {
    const { onSubmit } = renderBoard({ submitLabel: 'Submit ballot' })
    fireEvent.click(screen.getByRole('button', { name: 'Submit ballot' }))
    expect(onSubmit).toHaveBeenCalledWith([1, 2, 3, 4])
  })
})
