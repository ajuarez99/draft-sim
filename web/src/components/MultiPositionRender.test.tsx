import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { PlayerRef, PredictedPick } from '../api'
import { mkPlayer, mkSeat } from '../testLiveRoom'
import DraftBoard from './DraftBoard'
import PickFeed from './PickFeed'
import PlayerCard from './PlayerCard'

const multi = (name: string, positions: string[]): PlayerRef => {
  const sorted = [...positions].sort()
  return { ...mkPlayer(sorted[0], name, 10), positions: positions as PlayerRef['positions'] }
}

const predicted = (player: PlayerRef, pickNo = 1): PredictedPick => ({
  pickNo,
  round: 1,
  slot: pickNo,
  manager: `Mgr${pickNo}`,
  avatarId: null,
  player,
  probability: 0.5,
  isModal: true,
  alternatives: [],
})

const renderBoard = (players: PlayerRef[], density: 'full' | 'compact') =>
  render(
    <DraftBoard
      board={players.map((p, i) => predicted(p, i + 1))}
      teams={12}
      rounds={1}
      userPicks={{}}
      seats={Array.from({ length: 12 }, (_, i) => mkSeat(i + 1))}
      sport="nba"
      reversalRound={0}
      density={density}
    />,
  )
const cellOf = (c: HTMLElement, n: number) => c.querySelector(`.board .cell[data-pickno="${n}"]`) as HTMLElement

describe('multi-position pills, cells and accents (spec 025 US2)', () => {
  it('full board cell: label with no rank, `pos multi` pill with all three colour props, split cell tint', () => {
    const { container } = renderBoard([multi('Edwards', ['PG', 'SG']), multi('LeBron', ['PG', 'SF', 'PF'])], 'full')
    const pill = cellOf(container, 1).querySelector('.pos') as HTMLElement
    expect(pill.className).toBe('pos multi')
    expect(pill.textContent).toBe('PG/SG')
    expect(pill.style.getPropertyValue('--pos-a')).toBe('var(--pg)')
    expect(pill.style.getPropertyValue('--pos-b')).toBe('var(--sg)')
    const three = cellOf(container, 2).querySelector('.pos') as HTMLElement
    expect(three.textContent).toBe('PG/SF/PF')
    expect(three.style.getPropertyValue('--pos-c')).toBe('var(--pf)')
    // The cell is not tinted with the first position's colour.
    expect(cellOf(container, 1).className).toContain('pos-multi')
    expect(cellOf(container, 1).className).not.toContain('pos-PG')
  })

  it('compact board cell: family code, full label in the title', () => {
    const { container } = renderBoard(
      [multi('A', ['PG', 'SG']), multi('B', ['SF', 'PF']), multi('C', ['C', 'PF']), multi('D', ['PF', 'SF', 'SG'])],
      'compact',
    )
    const codes = [1, 2, 3, 4].map((n) => {
      const pill = cellOf(container, n).querySelector('.pos') as HTMLElement
      return [pill.textContent, pill.getAttribute('title')]
    })
    expect(codes).toEqual([
      ['G', 'PG/SG'],
      ['F', 'SF/PF'],
      ['F/C', 'PF/C'],
      ['G/F', 'SG/SF/PF'],
    ])
  })

  it('an NBA player with no position gets no football tint or colour (review N3)', () => {
    const { container } = renderBoard([mkPlayer('WR', 'Nobody', 50)], 'full')
    const cell = cellOf(container, 1)
    expect(cell.className).not.toContain('pos-')
    expect((cell.querySelector('.pos') as HTMLElement).className).toBe('pos')
  })

  it('a four-position player keeps all four colours (review N2)', () => {
    const { container } = renderBoard([multi('Swiss', ['PG', 'SG', 'SF', 'PF'])], 'full')
    const pill = cellOf(container, 1).querySelector('.pos') as HTMLElement
    expect(pill.style.getPropertyValue('--pos-d')).toBe('var(--pf)')
  })

  it('single-position cells are unchanged: solid class, no inline style, bare label', () => {
    const { container } = renderBoard([{ ...mkPlayer('C', 'Jokic', 3), positions: ['C'] }], 'compact')
    const cell = cellOf(container, 1)
    expect(cell.className).toContain('pos-C')
    const pill = cell.querySelector('.pos') as HTMLElement
    expect(pill.className).toBe('pos C')
    expect(pill.textContent).toBe('C')
    expect(pill.getAttribute('style')).toBeNull()
  })

  it('pick feed: a multi-position newest row sets no --lead-hue; a single one still does', () => {
    const feed = (p: PlayerRef) =>
      render(<PickFeed sport="nba" teams={12} picks={[{ pickNo: 1, player: p, manager: 'Sam' }]} />).container.querySelector('li') as HTMLElement
    expect(feed(multi('Edwards', ['PG', 'SG'])).style.getPropertyValue('--lead-hue')).toBe('')
    expect(feed({ ...mkPlayer('C', 'Jokic', 3), positions: ['C'] }).style.getPropertyValue('--lead-hue')).toBe('var(--c)')
  })

  it('player card: Edwards reads PG/SG, Jokic reads C, with no rank numbers', () => {
    render(<PlayerCard pick={predicted(multi('Edwards', ['PG', 'SG']))} teams={12} sport="nba" onClose={() => {}} />)
    expect(screen.getByText('PG/SG').className).toBe('pos multi')
  })
})
