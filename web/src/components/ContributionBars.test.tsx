import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import ContributionBars, { MAX_NAMED, namedTerms, type BreakdownRow } from './ContributionBars'
import { formatResult } from '../statFormula'

/** specs/032 T031 (US5). */

const mk = (id: string, name: string, vals: number[]): BreakdownRow => ({
  id, name, parts: vals.map((value, i) => ({ id: `t${i}`, text: `T${i}`, value })), total: vals.reduce((a, b) => a + b, 0),
})
const draw = (rows: BreakdownRow[]) =>
  render(
    <MemoryRouter>
      <ContributionBars title="Breakdown test" rows={rows} sport="nba" leagueId="L25" format={formatResult} />
    </MemoryRouter>,
  )
const px = (el: Element, k: 'left' | 'width') => parseFloat((el as HTMLElement).style[k])

describe('ContributionBars', () => {
  it('stacks positives right of the zero line and negatives left of it, and prints the total', () => {
    draw([mk('a', 'Ann', [6, -2]), mk('b', 'Bob', [4, 1])])
    const [a] = within(screen.getByRole('list', { name: 'Breakdown test' })).getAllByRole('listitem')
    const [pos, neg] = within(a).getAllByTestId('part')
    const zero = px(a.querySelector('.sc-bar-zero') as Element, 'left')
    expect(px(pos, 'left')).toBeCloseTo(zero, 6)
    expect(px(neg, 'left') + px(neg, 'width')).toBeCloseTo(zero, 6)
    expect(within(a).getByText('4.00')).toBeInTheDocument()
    // the widths add back up to the parts: |6| + |-2| is the full span (6 + 2) of the chart
    expect(px(pos, 'width') + px(neg, 'width')).toBeCloseTo(100, 6)
  })

  it('every part is named with its exact value in the row label and its tooltip', () => {
    draw([mk('a', 'Ann', [6, -2.5])])
    const li = within(screen.getByRole('list', { name: 'Breakdown test' })).getByRole('listitem')
    expect(li).toHaveAttribute('aria-label', 'Ann: 3.50 = T0 6.00; T1 −2.50')
    expect(screen.getAllByTestId('part')[1]).toHaveAttribute('title', 'Ann · T1: −2.50')
  })

  it('uses at most 6 colours: 5 named terms and the rest folded into "other", exact values kept', () => {
    const rows = [mk('a', 'Ann', [9, 8, 7, 6, 5, 0.5, 0.25])]
    draw(rows)
    expect(namedTerms(rows)).toHaveLength(MAX_NAMED)
    expect(namedTerms(rows)).toEqual([0, 1, 2, 3, 4])
    const colours = new Set(screen.getAllByTestId('part').map((p) => (p as HTMLElement).style.background))
    expect(colours.size).toBe(6)
    expect(screen.getByText('Other: T5, T6')).toBeInTheDocument()
    expect(screen.getAllByTestId('part')[6]).toHaveAttribute('title', 'Ann · T6: 0.250')
  })

  it('with 5 or fewer terms there is no "other"', () => {
    draw([mk('a', 'Ann', [1, 2, 3])])
    expect(screen.queryByText(/^Other:/)).not.toBeInTheDocument()
  })
})
