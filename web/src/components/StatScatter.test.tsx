import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import StatScatter, { niceTicks } from './StatScatter'

/** specs/032 T023. */
const pts = [
  { id: '1', name: 'Ann', x: 20, y: 55.5 },
  { id: '2', name: 'Bob', x: 30, y: 60 },
  { id: '3', name: null, x: 10, y: 50 },
]
const fmt = (v: number) => v.toFixed(1)

const mount = (over: Partial<Parameters<typeof StatScatter>[0]> = {}) =>
  render(
    <StatScatter
      title="USG against TS% · 2025–26"
      xLabel="USG (percentage points)"
      yLabel="TS% (percentage points)"
      points={pts}
      excluded={0}
      formatX={fmt}
      formatY={fmt}
      {...over}
    />,
  )

describe('StatScatter', () => {
  it('draws one dot per point, each with a name and both exact values in its label', () => {
    mount()
    const dots = screen.getAllByTestId('scatter-dot')
    expect(dots).toHaveLength(3)
    expect(dots[0]).toHaveAttribute('aria-label', 'Ann: USG (percentage points) 20.0, TS% (percentage points) 55.5')
    expect(dots[2]).toHaveAttribute('aria-label', expect.stringContaining('Unknown player'))
  })

  it('labels both axes with the stat name and unit', () => {
    const { container } = mount()
    const labels = [...container.querySelectorAll('.sc-axis-label')].map((t) => t.textContent)
    expect(labels).toEqual(['USG (percentage points)', 'TS% (percentage points)'])
  })

  it('gives every dot a 24 px tap target (a 12 unit radius, with 1 unit = 1 px)', () => {
    const { container } = mount()
    const hits = container.querySelectorAll('.sc-hit')
    expect(hits).toHaveLength(3)
    for (const h of hits) expect(Number(h.getAttribute('r')) * 2).toBeGreaterThanOrEqual(24)
    // the viewBox is the pixel size, so the SVG scales with its container
    const svg = container.querySelector('svg')!
    expect(svg.getAttribute('viewBox')).toBe(`0 0 ${svg.getAttribute('width')} ${svg.getAttribute('height')}`)
  })

  it('shows the name and both values on hover, on tap, and on focus; a second tap closes it', () => {
    mount()
    const [ann, bob] = screen.getAllByTestId('scatter-dot')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    fireEvent.pointerEnter(ann)
    expect(screen.getByRole('status')).toHaveTextContent('Ann')
    expect(screen.getByRole('status')).toHaveTextContent('USG (percentage points): 20.0')
    expect(screen.getByRole('status')).toHaveTextContent('TS% (percentage points): 55.5')
    fireEvent.pointerLeave(ann)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    fireEvent.click(bob)
    expect(screen.getByRole('status')).toHaveTextContent('Bob')
    fireEvent.click(bob)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    fireEvent.focus(ann)
    expect(screen.getByRole('status')).toHaveTextContent('Ann')
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('keeps the tooltip inside the container, even for a dot at the far left or right', () => {
    mount({ points: [{ id: 'a', name: 'Edge', x: 0, y: 0 }, { id: 'b', name: 'Other', x: 100, y: 100 }] })
    const w = 480
    for (const dot of screen.getAllByTestId('scatter-dot')) {
      fireEvent.click(dot)
      const tip = screen.getByRole('status')
      const left = parseFloat(tip.style.left)
      const width = parseFloat(tip.style.width)
      expect(left - width / 2).toBeGreaterThanOrEqual(0)
      expect(left + width / 2).toBeLessThanOrEqual(w)
      fireEvent.click(dot)
    }
  })

  it('states how many players are not plotted, and does not claim any when none are left out', () => {
    const { rerender } = mount({ excluded: 4 })
    expect(screen.getByText(/3 players plotted\. 4 players are not plotted because one of the two values is missing; none is drawn as 0\./)).toBeInTheDocument()
    rerender(
      <StatScatter title="t" xLabel="x" yLabel="y" points={pts} excluded={1} formatX={fmt} formatY={fmt} />,
    )
    expect(screen.getByText(/1 player is not plotted/)).toBeInTheDocument()
    rerender(<StatScatter title="t" xLabel="x" yLabel="y" points={pts} excluded={0} formatX={fmt} formatY={fmt} />)
    expect(screen.queryByText(/not plotted/)).not.toBeInTheDocument()
  })

  it('has a table of the same points', () => {
    mount()
    const table = screen.getByRole('table', { hidden: true })
    const rows = within(table).getAllByRole('row', { hidden: true })
    expect(rows).toHaveLength(4)
    expect(rows[1]).toHaveTextContent('Ann20.055.5')
  })

  it('copes with no points and with every value equal', () => {
    const { unmount } = mount({ points: [] })
    expect(screen.queryAllByTestId('scatter-dot')).toHaveLength(0)
    expect(screen.getByText(/0 players plotted/)).toBeInTheDocument()
    unmount()
    mount({ points: [{ id: 'a', name: 'A', x: 5, y: 5 }, { id: 'b', name: 'B', x: 5, y: 5 }] })
    const c = screen.getAllByTestId('scatter-dot')[0].querySelector('.sc-pt')!
    expect(Number.isFinite(Number(c.getAttribute('cx')))).toBe(true)
    expect(Number.isFinite(Number(c.getAttribute('cy')))).toBe(true)
  })
})

describe('niceTicks', () => {
  it('returns round values inside the range', () => {
    expect(niceTicks(0, 100, 5)).toEqual([0, 50, 100])
    const t = niceTicks(3.2, 47, 5)
    expect(t[0]).toBeGreaterThanOrEqual(3.2)
    expect(t.at(-1)).toBeLessThanOrEqual(47)
    expect(t).toEqual([20, 40])
  })
  it('returns the single value for an empty range', () => {
    expect(niceTicks(2, 2)).toEqual([2])
  })
})
