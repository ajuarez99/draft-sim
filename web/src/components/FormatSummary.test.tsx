import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import FormatSummary, { formatTimer } from './FormatSummary'

const text = (p: Parameters<typeof FormatSummary>[0]) => render(<FormatSummary {...p} />).container.textContent

describe('FormatSummary', () => {
  it('states teams, rounds, timer and type', () => {
    expect(text({ teams: 4, rounds: 14, pickTimerSeconds: 120, draftType: 'snake' })).toBe('4 teams · 14 rounds · 2 min · snake')
  })
  it('omits a null or undefined timer and type', () => {
    expect(text({ teams: 4, rounds: 14, pickTimerSeconds: null, draftType: null })).toBe('4 teams · 14 rounds')
    expect(text({ teams: 12, rounds: 15 })).toBe('12 teams · 15 rounds')
  })
  it('reads 90 as 1 min 30 s and 28800 as 8 h', () => {
    expect(formatTimer(90)).toBe('1 min 30 s')
    expect(formatTimer(28800)).toBe('8 h')
    expect(formatTimer(45)).toBe('45 s')
  })
  it('adds the reversal note for a mock with a reversal round', () => {
    expect(text({ teams: 4, rounds: 14, draftType: 'snake', reversalRound: 3 })).toContain('order reverses from round 3')
    expect(text({ teams: 4, rounds: 14, draftType: 'snake', reversalRound: 0 })).not.toContain('reverses')
  })
  it('a non-snake type carries an honest note, since the board draws snake (review R7)', () => {
    expect(text({ teams: 12, rounds: 15, draftType: 'linear' })).toBe('12 teams · 15 rounds · linear (board shows snake order)')
    expect(text({ teams: 12, rounds: 15, draftType: 'Snake' })).toBe('12 teams · 15 rounds · Snake')
  })
  it('renders nothing before the size is known', () => {
    expect(text({ teams: 0, rounds: 0 })).toBe('')
  })
})
