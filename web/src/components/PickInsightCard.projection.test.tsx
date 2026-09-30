import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { buildFactInsight, type LikelyNext, type ModelShare, type PickInsight } from '../pickInsight'
import { mkPlayer, mkRealPick, mkSeats, NFL_TEMPLATE } from '../testLiveRoom'
import PickInsightCard from './PickInsightCard'

const TEAMS = 12

function insight(over: Partial<PickInsight> = {}): PickInsight {
  const pick = mkRealPick(16, TEAMS, mkPlayer('WR', 'Taken Guy', 30), 3)
  return { ...buildFactInsight(pick, [pick], mkSeats(TEAMS).seats, 'nfl', NFL_TEMPLATE), ...over }
}

function show(over: Partial<PickInsight>) {
  return render(<PickInsightCard insight={insight(over)} teams={TEAMS} autoFocus={false} onClose={() => {}} />)
}

const ready = (probability: number, wideOpen = false): LikelyNext => ({
  state: 'ready',
  top: { player: mkPlayer('RB', 'Top Back'), probability },
  rest: [
    { player: mkPlayer('WR', 'Second Wr'), probability: 0.2 },
    { player: mkPlayer('TE', 'Third Te'), probability: 0.004 },
  ],
  wideOpen,
})

describe('PickInsightCard row 4 (model share)', () => {
  it('exact: "Model had this at 12%"', () => {
    const ms: ModelShare = { share: 0.123, bound: 'exact', asOfPick: 15 }
    show({ modelShare: ms })
    expect(screen.getByText(/Model had this at 12%/)).toBeTruthy()
    expect(screen.queryByText('Surprise')).toBeNull()
    expect(screen.queryByText(/as of pick/)).toBeNull()
  })

  it('under: "Model had this under 3%", with Surprise and a staleness label', () => {
    show({ modelShare: { share: 0.03, bound: 'under', asOfPick: 12 }, surprise: true })
    expect(screen.getByText(/Model had this under 3%/)).toBeTruthy()
    expect(screen.getByText('Surprise')).toBeTruthy()
    expect(screen.getByText(/as of pick 12/)).toBeTruthy()
  })

  it('a share under 1% reads "<1%"', () => {
    show({ modelShare: { share: 0.004, bound: 'under', asOfPick: 15 }, surprise: true })
    expect(screen.getByText(/Model had this under <1%/)).toBeTruthy()
  })

  it('no model share, no chip', () => {
    show({ modelShare: null })
    expect(screen.queryByText(/Model had this/)).toBeNull()
  })
})

describe('PickInsightCard row 6 (likely next)', () => {
  it('ready: heading at R.PP, provenance, top with whole-number share, runners-up', () => {
    const { container } = show({ nextPickNo: 28, likelyNext: ready(0.4) })
    expect(screen.getByText('Likely next @ 3.04')).toBeTruthy()
    expect(container.querySelector('.pick-card-likely')?.textContent).toContain('fitted from 2 drafts')
    expect(screen.getByText('Top Back')).toBeTruthy()
    expect(screen.getByText('40%')).toBeTruthy()
    expect(screen.getByText(/Second Wr 20% · Third Te <1%/)).toBeTruthy()
    expect(screen.queryByText('Wide open')).toBeNull()
  })

  it('ready and wide open', () => {
    show({ nextPickNo: 28, likelyNext: ready(0.18, true) })
    expect(screen.getByText('Wide open')).toBeTruthy()
  })

  it('updating', () => {
    show({ nextPickNo: 28, likelyNext: { state: 'updating' } })
    expect(screen.getByText('Updating…')).toBeTruthy()
  })

  it('busy', () => {
    show({ nextPickNo: 28, likelyNext: { state: 'busy' } })
    expect(screen.getByText('projection server busy, will retry next pick')).toBeTruthy()
  })

  it('none shows its reason', () => {
    show({ nextPickNo: 28, likelyNext: { state: 'none', reason: 'projection not back yet' } })
    expect(screen.getByText('projection not back yet')).toBeTruthy()
  })

  it('no picks left says so', () => {
    show({ nextPickNo: null, likelyNext: { state: 'none', reason: 'no picks left' } })
    expect(screen.getByText('no picks left')).toBeTruthy()
    expect(screen.getByText('Likely next')).toBeTruthy()
  })

  it('the fact-only default draws no row at all', () => {
    const { container } = show({})
    expect(container.querySelector('.pick-card-likely')).toBeNull()
  })
})
