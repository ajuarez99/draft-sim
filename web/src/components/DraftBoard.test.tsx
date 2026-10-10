import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { PredictedPick, Seat } from '../api'
import { mkPlayer, mkSeat } from '../testLiveRoom'
import { pickNoAt } from '../snake'
import DraftBoard from './DraftBoard'

const TEAMS = 12

function pickAt(pickNo: number, teams = TEAMS, reversalRound = 0): PredictedPick {
  const round = Math.ceil(pickNo / teams)
  let slot = 1
  for (let s = 1; s <= teams; s++) if (pickNoAt(round, s, teams, reversalRound) === pickNo) slot = s
  return {
    pickNo,
    round,
    slot,
    manager: `Mgr${slot}`,
    avatarId: null,
    player: mkPlayer('WR', `Player ${pickNo}`),
    probability: 0.5,
    isModal: true,
    alternatives: [],
  }
}

const seats = (n: number, skip: number[] = []): Seat[] =>
  Array.from({ length: n }, (_, i) => i + 1)
    .filter((s) => !skip.includes(s))
    .map((s) => mkSeat(s))

type Over = Partial<Parameters<typeof DraftBoard>[0]>
const board = (over: Over = {}) => (
  <DraftBoard
    board={[]}
    teams={TEAMS}
    rounds={3}
    userPicks={{}}
    seats={seats(TEAMS)}
    sport="nfl"
    reversalRound={0}
    density="full"
    {...over}
  />
)

const cell = (c: HTMLElement, pickNo: number) => c.querySelector(`.board .cell[data-pickno="${pickNo}"]`) as HTMLElement

describe('DraftBoard (spec 024 US2)', () => {
  it('labels a cell with round.pick: pick 15 of a 12-team board is 2.03', () => {
    const { container } = render(board({ board: [pickAt(15)] }))
    expect(cell(container, 15).textContent).toContain('2.03')
  })

  it('draws a right arrow for round 1 and a left arrow for round 2 on a plain snake', () => {
    const { container } = render(board())
    const dirs = [...container.querySelectorAll('.board .rnd')].map((e) => e.getAttribute('data-forward'))
    expect(dirs).toEqual(['true', 'false', 'true'])
    expect(container.querySelectorAll('.rnd-arrow')[0].textContent).toBe('→')
    expect(container.querySelectorAll('.rnd-arrow')[1].textContent).toBe('←')
  })

  it('with reversalRound 3, round 3 runs the same direction as round 2', () => {
    const { container } = render(board({ reversalRound: 3 }))
    const dirs = [...container.querySelectorAll('.board .rnd')].map((e) => e.getAttribute('data-forward'))
    expect(dirs).toEqual(['true', 'false', 'false'])
    // and the cells follow: round 3's first pick (25) is in the last column, not the first.
    const cells = [...container.querySelectorAll('.board .cell')]
    expect(cells[2 * TEAMS + TEAMS - 1].getAttribute('data-pickno')).toBe('25')
  })

  it('a slot with no Seat on a real draft shows Claim, which calls onClaim(slot)', () => {
    const onClaim = vi.fn()
    render(board({ seats: seats(TEAMS, [5]), onClaim }))
    fireEvent.click(screen.getByRole('button', { name: /Unclaimed · Claim/ }))
    expect(onClaim).toHaveBeenCalledWith(5)
    expect(screen.getAllByText(/Claim/)).toHaveLength(1)
  })

  // Found in live verification: after Claim set ?slot=5 the room treated the seat as
  // known (survival appeared) but the vacant header still read "Unclaimed · Claim /
  // assumed" -- the unclaimed branch ignored mySlotAssumed.
  it('a claimed vacancy (mySlot set, not assumed) reads as yours, not "assumed"', () => {
    const { container } = render(board({ seats: seats(TEAMS, [5]), onClaim: () => {}, mySlot: 5, mySlotAssumed: false }))
    const head = container.querySelectorAll('.col-head')[4]
    expect(head.textContent).toContain('Your seat')
    expect(head.textContent).not.toMatch(/assumed|Claim/)
    expect(head.classList.contains('mine')).toBe(true)
  })

  it('an assumed vacancy still reads "assumed" and is not mine', () => {
    const { container } = render(board({ seats: seats(TEAMS, [5]), onClaim: () => {}, mySlot: 5, mySlotAssumed: true }))
    const head = container.querySelectorAll('.col-head')[4]
    expect(head.textContent).toContain('assumed')
    expect(head.classList.contains('mine')).toBe(false)
  })

  it('a mock (no onClaim) shows no Claim, and BOT seats are plain headers', () => {
    render(board({ seats: seats(TEAMS), hideProvenanceDots: true }))
    expect(screen.queryByText(/Claim/)).toBeNull()
  })

  it('a seat that exists never shows Claim, even when onClaim is passed', () => {
    render(board({ seats: seats(TEAMS), onClaim: () => {} }))
    expect(screen.queryByText(/Claim/)).toBeNull()
  })

  it('marks the column of a confirmed seat, without "assumed"', () => {
    const { container } = render(board({ mySlot: 3 }))
    expect(container.querySelectorAll('.col-head.mine')).toHaveLength(1)
    expect(container.querySelector('.col-head.assumed')).toBeNull()
    expect(screen.queryByText('assumed')).toBeNull()
    expect(screen.getByText('you')).toBeInTheDocument()
  })

  it('an assumed seat is labelled and styled differently, and is not "mine"', () => {
    const { container } = render(board({ mySlot: 1, mySlotAssumed: true }))
    expect(container.querySelector('.col-head.mine')).toBeNull()
    expect(container.querySelectorAll('.col-head.assumed')).toHaveLength(1)
    expect(screen.getByText('assumed')).toBeInTheDocument()
    expect(screen.queryByText('you')).toBeNull()
  })

  it('with no mySlot, myPicks alone marks nothing (A6: no per-cell outlines)', () => {
    const { container } = render(board({ board: [pickAt(1), pickAt(24)], myPicks: [1, 24, 25] }))
    expect(container.querySelector('.mine')).toBeNull()
    expect(container.querySelector('.assumed')).toBeNull()
  })

  it('even a confirmed seat does not outline its cells', () => {
    const { container } = render(board({ board: [pickAt(1)], mySlot: 1, myPicks: [1, 24] }))
    expect(container.querySelectorAll('.cell.mine')).toHaveLength(0)
  })

  it('onTheClockPickNo marks exactly one cell, and none when undefined', () => {
    const { container, rerender } = render(board({ board: [pickAt(1)], onTheClockPickNo: 2 }))
    expect(container.querySelectorAll('.cell.on-the-clock')).toHaveLength(1)
    expect(cell(container, 2).className).toContain('on-the-clock')
    expect(screen.getAllByText('On the clock')).toHaveLength(1)
    rerender(board({ board: [pickAt(1)] }))
    expect(container.querySelector('.on-the-clock')).toBeNull()
  })

  it('live room: cells past revealedThrough are empty, the rest landed', () => {
    const { container } = render(board({ room: 'live', board: [pickAt(1), pickAt(2), pickAt(3)], revealedThrough: 2 }))
    expect(cell(container, 1).getAttribute('data-kind')).toBe('landed')
    expect(cell(container, 2).getAttribute('data-kind')).toBe('landed')
    expect(cell(container, 3).getAttribute('data-kind')).toBe('empty')
  })

  it('projection room: your chosen pick differs from a projected one', () => {
    const mine = mkPlayer('RB', 'Mine')
    const { container } = render(board({ board: [pickAt(1), pickAt(2)], userPicks: { 2: mine } }))
    expect(cell(container, 1).getAttribute('data-kind')).toBe('projected')
    expect(cell(container, 2).getAttribute('data-kind')).toBe('chosen')
  })

  it('mock/finished room: every filled cell is a real pick', () => {
    const { container } = render(board({ room: 'real', board: [pickAt(1)] }))
    expect(cell(container, 1).getAttribute('data-kind')).toBe('real')
  })

  it('compact density draws a filled cell as one line: no face, no meta line', () => {
    const { container } = render(board({ board: [pickAt(1)], density: 'compact' }))
    const c = cell(container, 1)
    expect(container.querySelector('.board.compact')).not.toBeNull()
    expect(c.querySelector('.meta')).toBeNull()
    expect(c.querySelector('img')).toBeNull()
    expect(c.querySelector('.pos')).not.toBeNull()
    expect(c.querySelector('.name')).not.toBeNull()
    expect(c.textContent).toContain('1.01')
  })

  it('full density keeps the face/meta line', () => {
    const { container } = render(board({ board: [pickAt(1)], density: 'full' }))
    expect(cell(container, 1).querySelector('.meta')).not.toBeNull()
  })
})
