import { describe, expect, it } from 'vitest'
import { countdown, dayLabel, landingStats } from './historicalDaily'

describe('dayLabel', () => {
  it('prints the calendar day it was given, whatever the time zone', () => {
    expect(dayLabel('2026-11-14')).toBe('Nov 14')
    expect(dayLabel('2026-01-01')).toBe('Jan 1')
  })
})

describe('countdown', () => {
  const next = '2026-11-15T05:00:00Z'
  const at = (iso: string) => new Date(iso).getTime()
  it('prints hours and minutes, rounded down', () => {
    expect(countdown(next, at('2026-11-14T23:47:30Z'))).toBe('5h 12m')
  })
  it('drops the hours under an hour', () => {
    expect(countdown(next, at('2026-11-15T04:48:00Z'))).toBe('12m')
  })
  it('says so in the last minute, and is null once the moment has passed', () => {
    expect(countdown(next, at('2026-11-15T04:59:30Z'))).toBe('under a minute')
    expect(countdown(next, at('2026-11-15T05:00:00Z'))).toBeNull()
    expect(countdown(next, at('2026-11-15T06:00:00Z'))).toBeNull()
  })
})

describe('landingStats', () => {
  const g = (status: 'DRAFTING' | 'COMPLETE' | 'ABANDONED', draftScore?: number | null) => ({ status, draftScore })
  it('takes the best and the mean over finished games that have a score', () => {
    expect(landingStats([g('COMPLETE', 79), g('COMPLETE', 64), g('COMPLETE', 91)])).toEqual({
      finished: 3,
      scored: 3,
      best: 91,
      average: 78,
    })
  })
  it('leaves unfinished and abandoned games out, and unscored finished games out of the score figures', () => {
    // An old result has no score: it is a finished game, but the average covers 2 games, not 3.
    expect(landingStats([g('COMPLETE', 80), g('COMPLETE', null), g('COMPLETE', 60), g('DRAFTING'), g('ABANDONED', 99)])).toEqual({
      finished: 3,
      scored: 2,
      best: 80,
      average: 70,
    })
  })
  it('has no best or average with nothing scored', () => {
    expect(landingStats([g('COMPLETE'), g('DRAFTING')])).toEqual({ finished: 1, scored: 0, best: null, average: null })
    expect(landingStats([])).toEqual({ finished: 0, scored: 0, best: null, average: null })
  })
})
