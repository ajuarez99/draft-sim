import { describe, expect, it } from 'vitest'
import { notCountedReason } from './historicalCopy'

// Spec 073 CR-04: the backend freezes "by the league" into stored seasons; the Time Machine says the NBA.
describe('notCountedReason', () => {
  it('rewrites the frozen backend phrase', () => {
    expect(notCountedReason('not tracked by the league that season')).toBe('not tracked by the NBA that season')
    expect(notCountedReason('Steals: not tracked by the league that season.')).toBe('Steals: not tracked by the NBA that season.')
  })
  it('passes every other reason through unchanged', () => {
    for (const r of ['steals were not fully recorded', 'not recorded', '', 'tracked by the league']) {
      expect(notCountedReason(r)).toBe(r)
    }
  })
})
