import { describe, expect, it } from 'vitest'
import { notCountedReason } from './historicalCopy'

// Spec 073 CR-04: the backend freezes "by the league" into stored seasons; the Time Machine says the NBA.
describe('notCountedReason', () => {
  it('rewrites the frozen backend phrase', () => {
    expect(notCountedReason('not tracked by the league that season', 'stl')).toBe('not tracked by the NBA that season')
    expect(notCountedReason('Steals: not tracked by the league that season.', 'stl')).toBe('Steals: not tracked by the NBA that season.')
  })
  it('passes every other reason through unchanged', () => {
    for (const r of ['steals were not fully recorded', 'not recorded', '', 'tracked by the league']) {
      expect(notCountedReason(r, 'stl')).toBe(r)
    }
  })
  it('the triple-doubles line says triple-doubles, the double-doubles line keeps its own words', () => {
    const why = "double-doubles need steals and blocks, which weren't fully recorded"
    expect(notCountedReason(why, 'td')).toBe("triple-doubles need steals and blocks, which weren't fully recorded")
    expect(notCountedReason(why, 'dd')).toBe(why)
    expect(notCountedReason('double-doubles need pts, reb, ast, stl and blk all counted; stl is not', 'td')).toMatch(/^triple-doubles need/)
  })
})
