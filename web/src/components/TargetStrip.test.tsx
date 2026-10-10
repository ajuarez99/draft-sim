import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { mkPlayer } from '../testLiveRoom'
import type { MarkedTarget } from '../targets'
import TargetStrip, { EMPTY_COPY, LIVE_COPY, MOCK_NOTE, UNAVAILABLE_COPY } from './TargetStrip'

const mk = (name: string, over: Partial<MarkedTarget> = {}): MarkedTarget => {
  const player = mkPlayer('RB', name)
  return { sleeperId: player.sleeperId, name, player, taken: false, ...over }
}

const props = (over: Partial<React.ComponentProps<typeof TargetStrip>> = {}) => ({
  items: [] as MarkedTarget[],
  status: 'ready' as const,
  error: false,
  sport: 'nfl' as const,
  room: 'projection' as const,
  onMove: vi.fn(),
  onRemove: vi.fn(),
  onRetry: vi.fn(),
  ...over,
})

describe('TargetStrip', () => {
  it('shows the how-to line when empty', () => {
    render(<TargetStrip {...props()} />)
    expect(screen.getByText(EMPTY_COPY)).toBeInTheDocument()
  })

  it('collapses chips that do not fit into +N, on one row', () => {
    const items = ['Ann Alpha', 'Bob Bravo', 'Cy Charlie', 'Di Delta', 'Ed Echo'].map((n) => mk(n))
    const { container } = render(<TargetStrip {...props({ items, maxVisible: 2 })} />)
    expect(container.querySelectorAll('.target-chip')).toHaveLength(2)
    expect(screen.getByRole('button', { name: /3 more targets/ })).toHaveTextContent('+3')
    // One list, one row: the chips never sit in a second container.
    expect(container.querySelectorAll('.target-chips')).toHaveLength(1)
  })

  it('labels a taken chip "taken" and shows no percentage on it', () => {
    const items = [mk('Gone Guy', { taken: true }), mk('Still Here')]
    const { container } = render(<TargetStrip {...props({ items, survivalOf: () => 0.5 })} />)
    const chips = container.querySelectorAll('.target-chip')
    expect(within(chips[0] as HTMLElement).getByText('taken')).toBeInTheDocument()
    expect(chips[0].querySelector('.target-pct')).toBeNull()
    expect(chips[1].querySelector('.target-pct')!.textContent).toBe('50%')
  })

  it('a missing target shows its name and no percentage', () => {
    const items: MarkedTarget[] = [{ sleeperId: 'm1', name: 'Off Board', player: null, taken: false }]
    const { container } = render(<TargetStrip {...props({ items, survivalOf: () => 0.9 })} />)
    expect(screen.getByText('Off Board')).toBeInTheDocument()
    expect(container.querySelector('.target-pct')).toBeNull()
  })

  it('shows no percentage where survivalOf has none (never a 0)', () => {
    const { container } = render(<TargetStrip {...props({ items: [mk('Unknown Seat')], survivalOf: () => undefined })} />)
    expect(container.querySelector('.target-pct')).toBeNull()
    expect(container.textContent).not.toContain('0%')
  })

  it('reorders with the arrow buttons and removes, from the popover', () => {
    const [a, b] = [mk('Ann Alpha'), mk('Bob Bravo')]
    const p = props({ items: [a, b] })
    render(<TargetStrip {...p} />)
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const dialog = screen.getByRole('dialog', { name: 'Edit targets' })
    expect(within(dialog).getByRole('button', { name: 'Move Ann Alpha up' })).toBeDisabled()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Move Ann Alpha down' }))
    expect(p.onMove).toHaveBeenCalledWith(a.sleeperId, 1)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Move Bob Bravo up' }))
    expect(p.onMove).toHaveBeenCalledWith(b.sleeperId, -1)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Remove Bob Bravo' }))
    expect(p.onRemove).toHaveBeenCalledWith(b.sleeperId)
  })

  it('Escape closes the popover', () => {
    render(<TargetStrip {...props({ items: [mk('Ann Alpha')] })} />)
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('a save error says so, offers a retry, and keeps the edited order on screen', () => {
    const items = [mk('Second Edit'), mk('First Edit')]
    const p = props({ items, error: true })
    const { container } = render(<TargetStrip {...p} />)
    fireEvent.click(screen.getByRole('button', { name: /Couldn.t save targets — retry/ }))
    expect(p.onRetry).toHaveBeenCalled()
    const names = [...container.querySelectorAll('.target-chip')].map((c) => c.getAttribute('title'))
    expect(names).toEqual(['Second Edit', 'First Edit'])
  })

  it('the live room says picks are made on Sleeper; the mock says there is no survival number', () => {
    const { rerender } = render(<TargetStrip {...props({ room: 'live' })} />)
    expect(screen.getByText(LIVE_COPY)).toBeInTheDocument()
    rerender(<TargetStrip {...props({ room: 'mock', items: [mk('Ann Alpha')] })} />)
    expect(screen.getByText(MOCK_NOTE)).toBeInTheDocument()
    expect(screen.queryByText(LIVE_COPY)).toBeNull()
  })

  it('re-attaches the width observer to the new strip after a failed load is retried', () => {
    const observed: Element[] = []
    const original = globalThis.ResizeObserver
    globalThis.ResizeObserver = class {
      observe(el: Element) {
        observed.push(el)
      }
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
    try {
      const { rerender, container } = render(<TargetStrip {...props({ status: 'loading' })} />)
      rerender(<TargetStrip {...props({ status: 'loadFailed' })} />)
      rerender(<TargetStrip {...props({ status: 'ready' })} />)
      const wrap = container.querySelector('.target-strip-wrap')!
      expect(observed[observed.length - 1]).toBe(wrap)
      expect(wrap.isConnected).toBe(true)
    } finally {
      globalThis.ResizeObserver = original
    }
  })

  it('says so on an older server, and offers a retry when the first load failed', () => {
    const { rerender } = render(<TargetStrip {...props({ status: 'unavailable' })} />)
    expect(screen.getByText(UNAVAILABLE_COPY)).toBeInTheDocument()
    const p = props({ status: 'loadFailed' })
    rerender(<TargetStrip {...p} />)
    fireEvent.click(screen.getByRole('button', { name: /Couldn.t load targets — retry/ }))
    expect(p.onRetry).toHaveBeenCalled()
  })
})
