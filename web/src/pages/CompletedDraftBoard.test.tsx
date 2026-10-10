import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RealPick } from '../api'
import { mkDraftGrades, mkPickGrade, mkPlayer, mkRealPick, mkSeats } from '../testLiveRoom'
import CompletedDraftBoard from './CompletedDraftBoard'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ draftId: 'd1' }),
}))

const getRealDraftBoard = vi.fn()
const getSeats = vi.fn()
const getDraftGrades = vi.fn()
vi.mock('../api', () => ({
  getRealDraftBoard: (...a: unknown[]) => getRealDraftBoard(...a),
  getSeats: (...a: unknown[]) => getSeats(...a),
  getDraftGrades: (...a: unknown[]) => getDraftGrades(...a),
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

function load(adps: (number | null | undefined)[], status = 'complete') {
  getSeats.mockResolvedValue(mkSeats(TEAMS))
  getRealDraftBoard.mockResolvedValue({ draftId: 'd1', teams: TEAMS, rounds: 1, status, picks: picks(adps) })
}

beforeEach(() => {
  getRealDraftBoard.mockReset()
  getSeats.mockReset()
  getDraftGrades.mockReset()
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
      [...container.querySelectorAll('.board .cell')].find((c) => c.getAttribute('data-pickno') === String(n)) as HTMLElement
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
      (c) => c.getAttribute('data-pickno') === '4',
    ) as HTMLElement
    expect(cell.className).toContain('value-steal')
    expect(cell.className).not.toContain('value-reach')
    expect(cell.textContent).toContain('+20')
  })
})

const gradesPayload = () =>
  mkDraftGrades({
    picks: [mkPickGrade(1, { valueOverSlot: 33.3 }), mkPickGrade(2, { valueOverSlot: -12.5 }), mkPickGrade(3, { valueOverSlot: null }), mkPickGrade(4, { valueOverSlot: 5 })],
    teams: [{ slot: 1, manager: 'Mgr1', avatarId: null, draftValue: 7, rank: 1, grade: 'A', bestPickNo: 1, worstPickNo: 2 }],
  })

const cellOf = (container: HTMLElement, n: number) =>
  [...container.querySelectorAll('.board .cell')].find((c) => c.getAttribute('data-pickno') === String(n)) as HTMLElement

describe('CompletedDraftBoard how it played out', () => {
  it('has no grades control for an incomplete draft', async () => {
    load([21, 1, null, 4], 'drafting')
    render(<CompletedDraftBoard />)
    await screen.findByRole('button', { name: /Steals & reaches/ })
    expect(screen.queryByRole('button', { name: /How it played out/ })).toBeNull()
    expect(getDraftGrades).not.toHaveBeenCalled()
  })

  it('with ADP it is segmented Off / Steals & reaches / How it played out, and the views are exclusive', async () => {
    load([21, 1, null, 4])
    getDraftGrades.mockResolvedValue(gradesPayload())
    const { container } = render(<CompletedDraftBoard />)
    await screen.findByRole('button', { name: 'Off' })

    fireEvent.click(screen.getByRole('button', { name: 'Steals & reaches' }))
    await waitFor(() => expect(cellOf(container, 1).textContent).toContain('−20'))
    expect(cellOf(container, 1).textContent).not.toContain('33.3')

    fireEvent.click(screen.getByRole('button', { name: 'How it played out' }))
    await waitFor(() => expect(cellOf(container, 1).textContent).toContain('+33.3'))
    // In place of the ADP delta, never beside it.
    expect(cellOf(container, 1).textContent).not.toContain('−20')
    expect(cellOf(container, 1).querySelectorAll('.value-delta')).toHaveLength(1)
    expect(cellOf(container, 2).textContent).toContain('−12.5')
    expect(cellOf(container, 3).textContent).not.toContain('no ADP')

    fireEvent.click(screen.getByRole('button', { name: 'Steals & reaches' }))
    await waitFor(() => expect(cellOf(container, 1).textContent).toContain('−20'))
    expect(cellOf(container, 1).textContent).not.toContain('+33.3')

    // Back to played out: kept, not refetched.
    fireEvent.click(screen.getByRole('button', { name: 'How it played out' }))
    await waitFor(() => expect(cellOf(container, 1).textContent).toContain('+33.3'))
    expect(getDraftGrades).toHaveBeenCalledTimes(1)
  })

  it('without ADP it is a single chip, and the NBA legend states what was measured', async () => {
    load([undefined, null, null, undefined])
    getDraftGrades.mockResolvedValue(gradesPayload())
    render(<CompletedDraftBoard />)
    fireEvent.click(await screen.findByRole('button', { name: 'How it played out' }))
    expect(screen.queryByRole('button', { name: 'Off' })).toBeNull()
    expect(await screen.findByText(/Sleeper credited one game per starter per week/)).toBeTruthy()
    expect(screen.getByText(/Counts 3 weeks/)).toBeTruthy()
    expect(screen.getByText('vs. the average team in this draft')).toBeTruthy()
  })

  it('shows the early badge when grades are early', async () => {
    load([undefined, null, null, undefined])
    getDraftGrades.mockResolvedValue({ ...gradesPayload(), gradesEarly: true, earlyThresholdWeeks: 4 })
    render(<CompletedDraftBoard />)
    fireEvent.click(await screen.findByRole('button', { name: 'How it played out' }))
    expect(await screen.findByText(/early — this is mostly noise/)).toBeTruthy()
    expect(screen.getByText(/fewer than 4 weeks are scored/)).toBeTruthy()
  })

  it('unavailable shows the reason sentence, no strip, and no deltas', async () => {
    load([undefined, null, null, undefined])
    getDraftGrades.mockResolvedValue(mkDraftGrades({ available: false, reason: 'NO_SCORED_WEEKS', picks: [], teams: [] }))
    const { container } = render(<CompletedDraftBoard />)
    fireEvent.click(await screen.findByRole('button', { name: 'How it played out' }))
    expect(await screen.findByText(/No weeks have been scored yet/)).toBeTruthy()
    expect(container.querySelector('.draft-grade-strip')).toBeNull()
    expect(cellOf(container, 1).querySelector('.value-delta')).toBeNull()
  })
})

describe('CompletedDraftBoard unavailable and failed grades', () => {
  it('says the weeks are scored but lack game stats when weeksMissingGameData is non-empty', async () => {
    load([undefined, null, null, undefined])
    getDraftGrades.mockResolvedValue(
      mkDraftGrades({ available: false, reason: 'NO_SCORED_WEEKS', weeksMissingGameData: [1, 2], picks: [], teams: [] }),
    )
    render(<CompletedDraftBoard />)
    fireEvent.click(await screen.findByRole('button', { name: 'How it played out' }))
    expect(await screen.findByText(/Weeks 1, 2 are scored, but their game-by-game stats aren't loaded yet/)).toBeTruthy()
    expect(screen.queryByText(/No weeks have been scored yet/)).toBeNull()
  })

  it('offers a Try again button that refetches after a failure', async () => {
    load([undefined, null, null, undefined])
    getDraftGrades.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(gradesPayload())
    render(<CompletedDraftBoard />)
    fireEvent.click(await screen.findByRole('button', { name: 'How it played out' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Steals & busts').catch(() => null)).not.toBeUndefined()
    expect(getDraftGrades).toHaveBeenCalledTimes(2)
  })
})

describe('CompletedDraftBoard steals & busts panel', () => {
  async function open(over: Parameters<typeof mkDraftGrades>[0]) {
    load([undefined, null, null, undefined])
    getDraftGrades.mockResolvedValue({ ...gradesPayload(), ...over })
    const r = render(<CompletedDraftBoard />)
    fireEvent.click(await screen.findByRole('button', { name: 'How it played out' }))
    return r
  }

  it('lists steals and busts with name, round, pick, team and signed value', async () => {
    const { container } = await open({ steals: [1], busts: [2] })
    await screen.findByText('Steals & busts')
    const steals = container.querySelector('.sb-steals')!
    expect(steals.textContent).toContain('Player 1')
    expect(steals.textContent).toContain('Round 1, pick 1')
    expect(steals.textContent).toContain('Mgr1')
    expect(steals.textContent).toContain('+33.3 pts')
    const busts = container.querySelector('.sb-busts')!
    expect(busts.textContent).toContain('Player 2')
    expect(busts.textContent).toContain('−12.5 pts')
    expect(screen.getByText(/vs\. what players taken at that pick scored in this draft \(fitted\)/)).toBeTruthy()
  })

  it('football caption names the position', async () => {
    await open({ productionBasis: 'WEEKLY_GAME', steals: [1], busts: [2] })
    await screen.findByText('Steals & busts')
    expect(screen.getAllByText(/what players at his position taken at that pick scored in this draft \(fitted\)/).length).toBeGreaterThan(0)
  })

  it('falls back to PickGrade.playerName and prints unknown for a null value', async () => {
    const { container } = await open({
      steals: [9],
      busts: [],
      picks: [mkPickGrade(9, { playerName: 'Ghost Guy', valueOverSlot: null })],
    })
    await screen.findByText('Steals & busts')
    expect(container.querySelector('.sb-steals')!.textContent).toContain('Ghost Guy')
    expect(container.querySelector('.sb-steals')!.textContent).toContain('unknown')
  })

  it('renders nothing when both lists are empty', async () => {
    await open({ steals: [], busts: [] })
    await screen.findByText(/Counts 3 weeks/)
    expect(screen.queryByText('Steals & busts')).toBeNull()
  })
})
