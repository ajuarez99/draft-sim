import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import ChangeDumbbell, { type ChangeRow } from './ChangeDumbbell'
import { formatResult } from '../statFormula'

/** specs/032 T031 (US5). */

const draw = (rows: ChangeRow[]) =>
  render(
    <MemoryRouter>
      <ChangeDumbbell title="Change test" rows={rows} nowLabel="Full season" thenLabel="Last 10" sport="nba" leagueId="L25" format={formatResult} />
    </MemoryRouter>,
  )

describe('ChangeDumbbell', () => {
  const rows: ChangeRow[] = [
    { id: 'a', name: 'Ann', now: 10, then: 12.5 },
    { id: 'b', name: 'Bob', now: 8, then: 6 },
    { id: 'c', name: 'Cy', now: 5, then: 5 },
  ]

  it('prints both exact values and the signed change in text, so direction is not colour alone', () => {
    draw(rows)
    const [a, b, c] = within(screen.getByRole('list', { name: 'Change test' })).getAllByRole('listitem')
    expect(within(a).getByText('12.50 → 10.00')).toBeInTheDocument()
    expect(within(a).getByText('−2.50')).toBeInTheDocument()
    expect(within(b).getByText('+2.00')).toBeInTheDocument()
    expect(within(c).getByText('0.00')).toBeInTheDocument()
  })

  it('the line takes the rise, fall or flat class, and both ends sit on one shared scale', () => {
    draw(rows)
    const [a, b, c] = within(screen.getByRole('list', { name: 'Change test' })).getAllByRole('listitem')
    expect(a.querySelector('.sc-db-line')).toHaveClass('fall')
    expect(b.querySelector('.sc-db-line')).toHaveClass('rise')
    expect(c.querySelector('.sc-db-line')).toHaveClass('flat')
    // lo 5, hi 12.5: Ann's "then" (12.5) is the right-most dot, Cy's both dots are the left-most
    expect((a.querySelector('.sc-db-dot.then') as HTMLElement).style.left).toBe('98%')
    expect((c.querySelector('.sc-db-dot.now') as HTMLElement).style.left).toBe('2%')
  })

  it('names which dot is which in the legend, in words', () => {
    draw(rows)
    expect(screen.getByText('Last 10 (hollow dot)')).toBeInTheDocument()
    expect(screen.getByText('Full season (filled dot)')).toBeInTheDocument()
    expect(screen.getByText('Higher (+)')).toBeInTheDocument()
    expect(screen.getByText('Lower (−)')).toBeInTheDocument()
  })

  it('C3: the rise and fall colours are not the better-or-worse tokens, and the legend says higher/lower', async () => {
    draw(rows)
    expect(screen.queryByText(/better|worse/i)).not.toBeInTheDocument()
    // vitest stubs stylesheet imports, so read the file itself (no node types in this tsconfig).
    // @ts-expect-error node:fs has no types here
    const { readFileSync } = (await import('node:fs')) as { readFileSync: (p: string, enc: string) => string }
    const css = readFileSync('src/styles.css', 'utf8')
    for (const sel of ['.sc-db-line.rise', '.sc-db-line.fall', '.sc-swatch-rise', '.sc-swatch-fall']) {
      const rule = css.split(String.fromCharCode(10)).find((l: string) => l.startsWith(sel + " {")) ?? ""
      expect(rule, sel).not.toBe('')
      expect(rule, sel).not.toMatch(/var\(--(up|down)\)/)
    }
  })
})
