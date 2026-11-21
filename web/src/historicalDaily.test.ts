import { describe, expect, it } from 'vitest'
import { countdown, dailyName, dayLabel, landingStats } from './historicalDaily'

describe('dailyName (spec 078)', () => {
  it('puts the running number between the prefix and the date', () => {
    expect(dailyName('Daily', '2026-11-21', 8)).toBe('Daily #8 · Nov 21')
    expect(dailyName('game of the day', '2026-11-21', 8, ', ')).toBe('game of the day #8, Nov 21')
    expect(dailyName('', '2026-11-20', 7)).toBe('#7 · Nov 20')
  })
  it('reads as before when there is no number', () => {
    expect(dailyName('Daily', '2026-11-14', null)).toBe('Daily · Nov 14')
    expect(dailyName('Daily', '2026-11-14', undefined)).toBe('Daily · Nov 14')
    expect(dailyName('', '2026-11-14', null)).toBe('Nov 14')
  })
})

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
  const g = (status: 'DRAFTING' | 'COMPLETE' | 'ABANDONED', draftScore?: number | null, chosenSeason = false) => ({
    status,
    draftScore,
    chosenSeason,
  })
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
  it('ignores chosen-season games entirely: not finished, not in the best, not in the average (spec 074 FR-011)', () => {
    // A 100 on a season the player picked must not become the "best draft score", and must not move the mean.
    const games = [g('COMPLETE', 70), g('COMPLETE', 100, true), g('COMPLETE', 90, true), g('COMPLETE', 50)]
    expect(landingStats(games)).toEqual({ finished: 2, scored: 2, best: 70, average: 60 })
    expect(landingStats([g('COMPLETE', 88, true)])).toEqual({ finished: 0, scored: 0, best: null, average: null })
  })
})
