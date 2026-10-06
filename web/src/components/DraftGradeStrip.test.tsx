import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { TeamGrade } from '../api'
import { mkPickGrade } from '../testLiveRoom'
import DraftGradeStrip from './DraftGradeStrip'

const team = (slot: number, over: Partial<TeamGrade> = {}): TeamGrade => ({
  slot,
  manager: `Mgr${slot}`,
  avatarId: null,
  draftValue: 12.5,
  rank: 1,
  grade: 'A',
  bestPickNo: 1,
  worstPickNo: 2,
  ...over,
})

describe('DraftGradeStrip', () => {
  const picks = [mkPickGrade(1, { playerName: 'Best Guy' }), mkPickGrade(2, { playerName: 'Worst Guy' })]

  it('renders one item per slot with signed value, grade, axis label and best/worst names', () => {
    const { container } = render(<DraftGradeStrip teams={[team(2, { draftValue: -4, grade: 'D' }), team(1)]} picks={picks} />)
    expect(container.querySelectorAll('.dgs-item')).toHaveLength(2)
    // Ordered by slot.
    expect(container.querySelector('.dgs-item .dgs-name')?.textContent).toBe('Mgr1')
    expect(screen.getByText('+12.5')).toBeTruthy()
    expect(screen.getByText('−4.0')).toBeTruthy()
    expect(screen.getAllByText('vs. the average team in this draft')).toHaveLength(2)
    expect(container.querySelector('.grade-chip')?.textContent).toBe('A')
    expect(screen.getAllByText('Best Guy').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Worst Guy').length).toBeGreaterThan(0)
  })

  it('a null draft value shows an em dash and no grade, never 0', () => {
    const { container } = render(
      <DraftGradeStrip teams={[team(1, { draftValue: null, grade: null, rank: null, bestPickNo: null, worstPickNo: null })]} picks={picks} />,
    )
    expect(container.querySelector('.dgs-none')?.textContent).toBe('—')
    expect(container.querySelector('.grade-chip')).toBeNull()
    expect(container.textContent).not.toContain('0.0')
  })

  it('uses its own class, not the roster strip', () => {
    const { container } = render(<DraftGradeStrip teams={[team(1)]} picks={picks} />)
    expect(container.querySelector('.draft-grade-strip')).not.toBeNull()
    expect(container.querySelector('.team-strip')).toBeNull()
  })

  it('renders nothing with no teams', () => {
    const { container } = render(<DraftGradeStrip teams={[]} picks={[]} />)
    expect(container.firstChild).toBeNull()
  })
})
