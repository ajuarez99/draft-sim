import { fireEvent, render, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { HistoricalPlayerCard } from '../api'
import HistoricalPlayerList from './HistoricalPlayerList'

function card(id: number, name: string, position: string, rookie: boolean | null | undefined): HistoricalPlayerCard {
  return { id, name, positions: [position], team: null, age: null, rank: id, positionalRank: id, prior: null, rookiePick: null, rookie }
}
const BOARD = [
  card(1, 'Vet Back', 'RB', false),
  card(2, 'Rookie Back', 'RB', true),
  card(3, 'Vet Wideout', 'WR', false),
  card(4, 'Rookie Wideout', 'WR', true),
]
const NAMES = BOARD.map((c) => c.name)

const show = (players: HistoricalPlayerCard[]) =>
  render(<HistoricalPlayerList players={players} rankingLabel="Last season's points" sport="NFL" canPick busy={false} onPick={() => {}} />)
const chip = (c: HTMLElement, label: string) => within(c).getAllByRole('button').find((b) => b.textContent === label)
const listed = (c: HTMLElement) => NAMES.filter((n) => within(c).queryByText(n) != null)

describe('Time Machine player list: rookies', () => {
  it('Rookies keeps only rookies and narrows the position filter', () => {
    const { container } = show(BOARD)
    expect(listed(container)).toHaveLength(4)
    fireEvent.click(chip(container, 'Rookies')!)
    expect(listed(container)).toEqual(['Rookie Back', 'Rookie Wideout'])
    fireEvent.click(chip(container, 'RB')!)
    expect(listed(container)).toEqual(['Rookie Back'])
    fireEvent.click(chip(container, 'Rookies')!)
    expect(listed(container)).toEqual(['Vet Back', 'Rookie Back'])
  })

  it('marks rookies, and only rookies, with an R', () => {
    const { container } = show(BOARD)
    const tags = [...container.querySelectorAll('.rookie-tag')]
    expect(tags).toHaveLength(2)
    for (const t of tags) expect(t.closest('li')?.textContent).toContain('Rookie')
  })

  for (const [what, value] of [
    ['null (a season with nothing earlier to compare against)', null],
    ['absent (a game started before the flag existed)', undefined],
  ] as const) {
    it(`offers no Rookies chip and no R when every card's flag is ${what}`, () => {
      const { container } = show(BOARD.map((c) => ({ ...c, rookie: value })))
      expect(chip(container, 'Rookies')).toBeUndefined()
      expect(container.querySelector('.rookie-tag')).toBeNull()
      expect(listed(container)).toHaveLength(4)
    })
  }

  it('an unknown player on a board that knows some rookies is not listed as one', () => {
    // Football's first data season: draftees are known rookies, the undrafted are unknown.
    const { container } = show([card(1, 'Vet Back', 'RB', null), card(2, 'Rookie Back', 'RB', true)])
    fireEvent.click(chip(container, 'Rookies')!)
    expect(listed(container)).toEqual(['Rookie Back'])
  })

  it('says so when no rookie matches', () => {
    const { container } = show(BOARD)
    fireEvent.click(chip(container, 'Rookies')!)
    fireEvent.click(chip(container, 'QB')!)
    expect(within(container).getByText('No rookies match.')).toBeInTheDocument()
  })
})
