import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import SpreadStrip, { jitterOf, type SpreadGroup } from './SpreadStrip'
import { formatResult } from '../statFormula'

/** specs/032 T031 (US5). */

const pts = (vals: number[], prefix = 'p') => vals.map((v, i) => ({ id: `${prefix}${i}`, name: `Player ${prefix}${i}`, value: v }))
const draw = (groups: SpreadGroup[], top: string[] = []) =>
  render(<SpreadStrip title="Spread test" groups={groups} top={new Set(top)} format={formatResult} />)

describe('SpreadStrip', () => {
  it('draws one dot per player and prints the quartiles and median under the group name', () => {
    draw([{ key: 'all', label: 'All players', points: pts([1, 2, 3, 4, 5]) }])
    expect(screen.getAllByTestId('spread-dot')).toHaveLength(5)
    const svg = screen.getByRole('group', { name: /Spread test/ })
    expect(svg).toHaveTextContent('All players · 5 players')
    expect(svg).toHaveTextContent('25th 2.00 · median 3.00 · 75th 4.00')
  })

  it('one strip per group on a shared axis, each with its own median', () => {
    draw([
      { key: 'PG', label: 'PG', points: pts([1, 2, 3, 4, 5], 'a') },
      { key: 'C', label: 'C', points: pts([10, 20, 30, 40, 50], 'b') },
    ])
    const groups = screen.getAllByTestId('spread-group')
    expect(groups).toHaveLength(2)
    expect(within(groups[0]).getByText(/median 3\.00/)).toBeInTheDocument()
    expect(within(groups[1]).getByText(/median 30\.00/)).toBeInTheDocument()
    // a table view lists the position beside each value
    expect(screen.getByText('Position')).toBeInTheDocument()
  })

  it('marks the top N in the label as well as the colour, and keeps the rest unlabelled', () => {
    draw([{ key: 'all', label: 'All players', points: pts([1, 2, 3, 4, 5]) }], ['p4', 'p3'])
    const dots = screen.getAllByTestId('spread-dot')
    expect(dots.filter((d) => d.getAttribute('class')?.includes(' top'))).toHaveLength(2)
    expect(dots[4].getAttribute('aria-label')).toBe('Player p4: 5.00, in the top 2')
    expect(dots[0].getAttribute('aria-label')).toBe('Player p0: 1.00')
    expect(screen.getByText('Top 2 in the ranking')).toBeInTheDocument()
  })

  it('shows the name and exact value on tap, and the same picture on two loads (seeded jitter)', () => {
    const g = [{ key: 'all', label: 'All players', points: pts([1.234, 2, 3, 4, 5]) }]
    const first = draw(g)
    fireEvent.click(screen.getAllByTestId('spread-dot')[0])
    expect(screen.getByRole('status')).toHaveTextContent('Player p01.23')
    fireEvent.click(screen.getAllByTestId('spread-dot')[0])
    const markup = first.container.querySelector('svg')?.innerHTML
    first.unmount()
    const second = draw(g)
    expect(second.container.querySelector('svg')?.innerHTML).toBe(markup)
  })

  it('jitterOf is stable and inside [0, 1)', () => {
    expect(jitterOf('4017')).toBe(jitterOf('4017'))
    for (const id of ['1', '4017', 'abc', '']) {
      expect(jitterOf(id)).toBeGreaterThanOrEqual(0)
      expect(jitterOf(id)).toBeLessThan(1)
    }
    expect(jitterOf('1')).not.toBe(jitterOf('2'))
  })

  it('C7: a strip with fewer than 5 players keeps its dots and count but prints no quartiles', () => {
    draw([
      { key: 'PG', label: 'PG', points: pts([1, 2, 3, 4, 5], 'a') },
      { key: 'C', label: 'C', points: pts([10], 'b') },
    ])
    const [pg, c] = screen.getAllByTestId('spread-group')
    expect(pg).toHaveTextContent('25th 2.00 · median 3.00 · 75th 4.00')
    expect(c).toHaveTextContent('C · 1 player')
    expect(c).toHaveTextContent('too few players for quartiles')
    expect(c.textContent).not.toMatch(/median|25th|75th/)
    expect(c.querySelectorAll('.sc-iqr, .sc-median, .sc-quart')).toHaveLength(0)
    expect(within(c).getAllByTestId('spread-dot')).toHaveLength(1)
  })

  it('C10: the dots are not tab stops; the table is the keyboard path to every value', () => {
    const { container } = draw([{ key: 'all', label: 'All players', points: pts([1, 2, 3, 4, 5, 6, 7, 8]) }])
    expect(container.querySelectorAll('svg [tabindex]')).toHaveLength(0)
    expect(screen.getByText('Show these values as a table').tagName).toBe('SUMMARY')
  })

  describe('C10: the tooltip stays inside a container narrower than the SVG minimum', () => {
    const real = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth')
    afterEach(() => {
      if (real) Object.defineProperty(HTMLElement.prototype, 'clientWidth', real)
    })
    it('is clamped to the container width, not the SVG width', () => {
      Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => 200 })
      draw([{ key: 'all', label: 'All players', points: pts([1, 2, 3, 4, 5]) }])
      fireEvent.click(screen.getAllByTestId('spread-dot')[4])
      const tip = screen.getByRole('status') as HTMLElement
      const left = parseFloat(tip.style.left)
      const width = parseFloat(tip.style.width)
      expect(width).toBeLessThanOrEqual(192)
      expect(left - width / 2).toBeGreaterThanOrEqual(0)
      expect(left + width / 2).toBeLessThanOrEqual(200)
    })
  })
})
