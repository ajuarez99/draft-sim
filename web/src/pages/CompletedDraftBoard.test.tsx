import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RealPick } from '../api'
import { mkPlayer, mkRealPick, mkSeats } from '../testLiveRoom'
import CompletedDraftBoard from './CompletedDraftBoard'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ draftId: 'd1' }),
}))

const getRealDraftBoard = vi.fn()
const getSeats = vi.fn()
vi.mock('../api', () => ({
  getRealDraftBoard: (...a: unknown[]) => getRealDraftBoard(...a),
  getSeats: (...a: unknown[]) => getSeats(...a),
}))
vi.mock('../components/PlayerCard', () => ({ default: () => null }))

const TEAMS = 4

/** Pick numbers 1..4 with the given draft-time ADPs; today's `player.adp` is set to something unrelated on purpose. */
function picks(adps: (number | null | undefined)[]): RealPick[] {
  return adps.map((adp, i) => {
    const pick = mkRealPick(i + 1, TEAMS, mkPlayer('WR', `Player ${i + 1}`, 500))
    if (adp !== undefined) pick.adpAtDraft = adp
    return pick
  })
}

function load(adps: (number | null | undefined)[]) {
  getSeats.mockResolvedValue(mkSeats(TEAMS))
  getRealDraftBoard.mockResolvedValue({ draftId: 'd1', teams: TEAMS, rounds: 1, status: 'complete', picks: picks(adps) })
}

beforeEach(() => {
  getRealDraftBoard.mockReset()
  getSeats.mockReset()
})

describe('CompletedDraftBoard steals & reaches', () => {
  it('hides the toggle when no pick has a draft-time ADP', async () => {
    load([undefined, null, null, undefined])
    render(<CompletedDraftBoard />)
    await screen.findByText('Draft board')
    expect(screen.queryByRole('button', { name: /Steals & reaches/ })).toBeNull()
  })

  it('tints from draft-time ADP: taken after it is a steal (+), before it is a reach (minus)', async () => {
    // pick 1 taken at ADP 21 (20 early: reach), pick 2 at ADP 1 (1 late: steal),
    // pick 3 has no ADP, pick 4 at ADP 4 (even).
    load([21, 1, null, 4])
    const { container } = render(<CompletedDraftBoard />)
    const toggle = await screen.findByRole('button', { name: /Steals & reaches/ })
    expect(container.querySelector('.value-steal, .value-reach')).toBeNull()

    fireEvent.click(toggle)
    await waitFor(() => expect(container.querySelector('.board.value-view')).not.toBeNull())

    const cellOf = (n: number) =>
      [...container.querySelectorAll('.board .cell')].find((c) => c.querySelector('.pickno')?.textContent === String(n)) as HTMLElement
    expect(cellOf(1).className).toContain('value-reach')
    expect(cellOf(1).textContent).toContain('−20')
    expect(cellOf(2).className).toContain('value-steal')
    expect(cellOf(2).textContent).toContain('+1')
    // No ADP at draft time: untinted, and says so.
    expect(cellOf(3).className).not.toMatch(/value-(steal|reach)/)
    expect(cellOf(3).textContent).toContain('no ADP')
    expect(cellOf(3).querySelector('[title="no ADP at draft time"]')).not.toBeNull()
    // Even: untinted.
    expect(cellOf(4).className).not.toMatch(/value-(steal|reach)/)
  })

  it('ORDERING: a pick 20 after its draft-time ADP is never tinted as a reach', async () => {
    // 4 teams, 1 round only has picks 1..4, so use ADP below the pick instead:
    // pick 4 with ADP -16 is 20 picks after it. today's player.adp (500) must not matter.
    load([null, null, null, -16])
    const { container } = render(<CompletedDraftBoard />)
    fireEvent.click(await screen.findByRole('button', { name: /Steals & reaches/ }))
    await waitFor(() => expect(container.querySelector('.board.value-view')).not.toBeNull())
    const cell = [...container.querySelectorAll('.board .cell')].find(
      (c) => c.querySelector('.pickno')?.textContent === '4',
    ) as HTMLElement
    expect(cell.className).toContain('value-steal')
    expect(cell.className).not.toContain('value-reach')
    expect(cell.textContent).toContain('+20')
  })
})
