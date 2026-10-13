import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import RankedBars, { type Bar } from './RankedBars'
import { formatResult } from '../statFormula'

/** specs/032 T015. */

const draw = (bars: Bar[]) =>
  render(
    <MemoryRouter>
      <RankedBars title="Test chart" bars={bars} sport="nba" leagueId="L25" format={formatResult} />
    </MemoryRouter>,
  )
const styleOf = (el: Element) => (el as HTMLElement).style
const barOf = (li: HTMLElement) => li.querySelector('.sc-bar') as HTMLElement

describe('RankedBars', () => {
  it('all positive: bars grow right from the left edge in proportion, with the exact value beside each', () => {
    draw([{ id: 'a', name: 'Ann', value: 8 }, { id: 'b', name: 'Bob', value: 4 }])
    const [a, b] = screen.getAllByRole('listitem')
    expect(styleOf(barOf(a)).left).toBe('0%')
    expect(styleOf(barOf(a)).width).toBe('100%')
    expect(styleOf(barOf(b)).width).toBe('50%')
    expect(within(a).getByText('8.00')).toBeInTheDocument()
    expect(within(b).getByText('4.00')).toBeInTheDocument()
    expect(document.querySelector('.sc-bar-zero')).toBeNull()
  })

  it('all negative: the leader is the bar closest to zero, the baseline is at the right, and the sign shows', () => {
    draw([{ id: 'a', name: 'Near', value: -1 }, { id: 'b', name: 'Far', value: -4 }])
    const [near, far] = screen.getAllByRole('listitem')
    expect(styleOf(barOf(near)).width).toBe('25%')
    expect(styleOf(barOf(near)).left).toBe('75%')
    expect(styleOf(barOf(far)).width).toBe('100%')
    expect(styleOf(barOf(far)).left).toBe('0%')
    expect(within(near).getByText('−1.00')).toBeInTheDocument()
    expect(barOf(near)).toHaveClass('neg')
  })

  it('mixed sign: the baseline sits at its proportional position and is drawn', () => {
    draw([{ id: 'a', name: 'Up', value: 6 }, { id: 'b', name: 'Down', value: -2 }])
    const [up, down] = screen.getAllByRole('listitem')
    // span 8, baseline at 2/8 = 25%
    expect(styleOf(barOf(up)).left).toBe('25%')
    expect(styleOf(barOf(up)).width).toBe('75%')
    expect(styleOf(barOf(down)).left).toBe('0%')
    expect(styleOf(barOf(down)).width).toBe('25%')
    expect(up.querySelector('.sc-bar-zero')).toBeInTheDocument()
    expect(within(down).getByText('−2.00')).toBeInTheDocument()
  })

  it('a null name reads "Unknown player", and the name links to the player page', () => {
    draw([{ id: 'x', name: null, value: 3 }, { id: 'y', name: 'Yan', value: 1 }])
    expect(screen.getByRole('link', { name: 'Unknown player' })).toHaveAttribute('href', '/leagues/L25/players/x')
    expect(screen.getByRole('link', { name: 'Yan' })).toHaveAttribute('href', '/leagues/L25/players/y')
  })

  it('each row carries the exact formatted value in its text, and all-zero values draw no bar width', () => {
    draw([{ id: 'a', name: 'Ann', value: 1234.5 }, { id: 'b', name: 'Bob', value: 0.12345 }])
    const [a, b] = screen.getAllByRole('listitem')
    expect(a).toHaveTextContent('1,235')
    expect(b).toHaveTextContent('0.123')
    expect(a).toHaveAttribute('title', 'Ann: 1,235')
  })

  it('all zero: no bar width and no NaN', () => {
    draw([{ id: 'a', name: 'Ann', value: 0 }])
    expect(styleOf(barOf(screen.getByRole('listitem'))).width).toBe('0%')
    expect(document.body.textContent).not.toMatch(/NaN|Infinity/)
  })
})
