import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { PredictedPick } from '../api'
import { mkDraftGrades, mkPickGrade, mkPlayer } from '../testLiveRoom'
import PlayerCard from './PlayerCard'

const pick = (pickNo: number): PredictedPick => ({
  pickNo,
  round: 1,
  slot: pickNo,
  manager: 'M',
  avatarId: null,
  player: mkPlayer('WR', 'Some Guy'),
  probability: 1,
  isModal: true,
  alternatives: [],
})

describe('PlayerCard "How he played out"', () => {
  it('lists each labelled line with correct ordinals', () => {
    const grades = mkDraftGrades({ picks: [mkPickGrade(3, { positionDrafted: 1, positionFinish: 22, position: 'PG', production: 563.14, weeksPlayed: 2 })] })
    render(<PlayerCard pick={pick(3)} teams={12} grades={grades} onClose={() => {}} />)
    expect(screen.getByText('How he played out')).toBeTruthy()
    expect(screen.getByText(/563\.1 season points, counting each week's average game/)).toBeTruthy()
    expect(screen.getByText('2 of 3')).toBeTruthy()
    expect(screen.getByText(/vs\. what a player taken at pick 3 scored in this draft \(fitted\)/)).toBeTruthy()
    expect(screen.getByText('+20.0 points')).toBeTruthy()
    expect(screen.getByText("drafted 1st, finished 22nd among this draft's picks")).toBeTruthy()
  })

  it('football keeps the position wording and names the comparison group', () => {
    const grades = mkDraftGrades({
      productionBasis: 'WEEKLY_GAME',
      picks: [mkPickGrade(3, { positionDrafted: 2, positionFinish: 5, position: 'WR' })],
    })
    render(<PlayerCard pick={pick(3)} teams={12} grades={grades} onClose={() => {}} />)
    expect(screen.getByText('2nd WR drafted, finished 5th among drafted WRs')).toBeTruthy()
    expect(screen.getByText(/vs\. what a WR taken at pick 3 scored in this draft \(fitted\)/)).toBeTruthy()
  })

  it('basketball baseline says "player", not the display eligibility', () => {
    const grades = mkDraftGrades({ picks: [mkPickGrade(3, { position: 'PG/SG' })] })
    render(<PlayerCard pick={pick(3)} teams={12} grades={grades} onClose={() => {}} />)
    expect(screen.getByText(/vs\. what a player taken at pick 3 scored in this draft \(fitted\)/)).toBeTruthy()
  })

  it('prints unknown, never 0, for null values', () => {
    const grades = mkDraftGrades({
      picks: [mkPickGrade(3, { position: null, slotBaseline: null, valueOverSlot: null, positionDrafted: null, positionFinish: null })],
    })
    const { container } = render(<PlayerCard pick={pick(3)} teams={12} grades={grades} onClose={() => {}} />)
    const dds = [...container.querySelectorAll('.played-out dd')].map((d) => d.textContent)
    expect(dds.slice(2, 5)).toEqual(['unknown', 'unknown', 'unknown'])
    expect(dds[5]).toBe('unknown (unknown weeks started)')
  })

  it('shows nothing without grades, when unavailable, or when the pick has no grade row', () => {
    for (const grades of [undefined, null, mkDraftGrades({ available: false, reason: 'NO_SCORED_WEEKS' }), mkDraftGrades({ picks: [mkPickGrade(9)] })]) {
      const { container, unmount } = render(<PlayerCard pick={pick(3)} teams={12} grades={grades} onClose={() => {}} />)
      expect(container.querySelector('.played-out')).toBeNull()
      unmount()
    }
  })
})

describe('PlayerCard no-baseline wording', () => {
  it('names the minimum when position and minimum are known', () => {
    const grades = mkDraftGrades({ productionBasis: 'WEEKLY_GAME', minPicksPerPosition: 8, picks: [mkPickGrade(3, { position: 'PG', slotBaseline: null, valueOverSlot: null })] })
    render(<PlayerCard pick={pick(3)} teams={12} grades={grades} onClose={() => {}} />)
    expect(screen.getByText('no baseline: fewer than 8 PGs drafted')).toBeTruthy()
  })
})

describe('PlayerCard counted-for-you lines', () => {
  const mine = { countedForYou: 480.04, creditedForYou: 560, weeksStartedForYou: 18, weeksUnknownForYou: 0 }

  it('NBA shows counted, the credited line and its scale note', () => {
    const grades = mkDraftGrades({ picks: [mkPickGrade(3, mine)] })
    render(<PlayerCard pick={pick(3)} teams={12} grades={grades} onClose={() => {}} />)
    expect(screen.getByText('Counted for M')).toBeTruthy()
    expect(screen.getByText('480.0 (18 weeks started)')).toBeTruthy()
    expect(screen.getByText('Credited by Sleeper')).toBeTruthy()
    expect(screen.getByText('560.0')).toBeTruthy()
    expect(screen.getByText('a different scale: Sleeper counts one game a week')).toBeTruthy()
    expect(screen.queryByText(/weeks unknown/)).toBeNull()
  })

  it('NFL has no credited line', () => {
    const grades = mkDraftGrades({ productionBasis: 'WEEKLY_GAME', picks: [mkPickGrade(3, mine)] })
    render(<PlayerCard pick={pick(3)} teams={12} grades={grades} onClose={() => {}} />)
    expect(screen.getByText('Counted for M')).toBeTruthy()
    expect(screen.queryByText('Credited by Sleeper')).toBeNull()
    expect(screen.queryByText(/different scale/)).toBeNull()
  })

  it('null prints unknown, never 0, and the drafting-team fallback is used with no manager', () => {
    const grades = mkDraftGrades({ picks: [mkPickGrade(3, {})] })
    const p = { ...pick(3), manager: '' }
    render(<PlayerCard pick={p} teams={12} grades={grades} onClose={() => {}} />)
    expect(screen.getByText('Counted for the drafting team')).toBeTruthy()
    expect(screen.getByText('unknown (unknown weeks started)')).toBeTruthy()
    expect(screen.getByText('unknown')).toBeTruthy()
  })

  it('shows the unknown-weeks line only when greater than zero', () => {
    const grades = mkDraftGrades({ picks: [mkPickGrade(3, { ...mine, weeksUnknownForYou: 2 })] })
    render(<PlayerCard pick={pick(3)} teams={12} grades={grades} onClose={() => {}} />)
    expect(screen.getByText('2 weeks unknown')).toBeTruthy()
  })

  it('pluralises one week', () => {
    const grades = mkDraftGrades({ picks: [mkPickGrade(3, { ...mine, weeksStartedForYou: 1, weeksUnknownForYou: 1 })] })
    render(<PlayerCard pick={pick(3)} teams={12} grades={grades} onClose={() => {}} />)
    expect(screen.getByText('480.0 (1 week started)')).toBeTruthy()
    expect(screen.getByText('1 week unknown')).toBeTruthy()
  })
})
