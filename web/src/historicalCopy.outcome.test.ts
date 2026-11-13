import { describe, expect, it } from 'vitest'
import { isOldFormat, outcomeHeadline, scheduleNote, summaryStatus } from './historicalCopy'
import type { HistoricalFormat } from './api'

const fmt: HistoricalFormat = {
  version: 2, seasonWeeks: 25, configuredRegularSeasonWeeks: 19, regularSeasonWeeks: 19,
  playoffWeeks: [20, 21, 22], unusedWeeks: 3, shortened: false,
}

describe('isOldFormat', () => {
  it('is true only when format is missing, whatever else the result carries', () => {
    expect(isOldFormat({})).toBe(true)
    expect(isOldFormat({ format: null })).toBe(true)
    expect(isOldFormat({ format: fmt })).toBe(false)
  })
})

describe('outcomeHeadline', () => {
  it('words each outcome', () => {
    expect(outcomeHeadline('CHAMPION', 1, 12)).toBe('Champion')
    expect(outcomeHeadline('LOST_FINAL', 2, 12)).toBe('You lost the final')
    expect(outcomeHeadline('LOST_SEMIFINAL', 3, 12)).toBe('You lost in the semifinal')
    expect(outcomeHeadline('LOST_ROUND_ONE', 5, 12)).toBe('You lost in round one')
    expect(outcomeHeadline('MISSED_PLAYOFFS', 8, 12)).toBe('You missed the playoffs (8th of 12)')
    expect(outcomeHeadline('NO_PLAYOFFS', 3, 12)).toBe('You finished 3rd of 12')
  })
  it('falls back to the plain place for a missing or unknown outcome', () => {
    expect(outcomeHeadline(null, 2, 12)).toBe('You finished 2nd of 12')
    expect(outcomeHeadline(undefined, 11, 12)).toBe('You finished 11th of 12')
  })
})

describe('summaryStatus', () => {
  const base = { status: 'COMPLETE' as const, finish: 3 }
  it('says which kind of number a finish is', () => {
    expect(summaryStatus({ ...base, formatVersion: undefined, outcome: undefined })).toBe('Finished 3rd (old format)')
    expect(summaryStatus({ ...base, formatVersion: 2, outcome: 'NO_PLAYOFFS' })).toBe('Finished 3rd (no playoffs)')
    expect(summaryStatus({ ...base, formatVersion: 2, outcome: 'LOST_SEMIFINAL' })).toBe('Lost in the semifinal')
    expect(summaryStatus({ ...base, formatVersion: 2, outcome: 'LOST_ROUND_ONE' })).toBe('Lost in round one')
    expect(summaryStatus({ ...base, formatVersion: 2, outcome: 'MISSED_PLAYOFFS', finish: 8 })).toBe('Missed the playoffs (8th)')
  })
  it('covers the unfinished states', () => {
    expect(summaryStatus({ status: 'DRAFTING', finish: null })).toBe('Drafting')
    expect(summaryStatus({ status: 'ABANDONED', finish: null })).toBe('Abandoned')
    expect(summaryStatus({ status: 'COMPLETE', finish: null })).toBe('Complete')
  })
})

describe('scheduleNote', () => {
  it('computes the lopsided-schedule sentence from the weeks and teams', () => {
    expect(scheduleNote(19, 12)).toBe('19 weeks over an 11-week cycle: you play eight teams twice and three once.')
    expect(scheduleNote(14, 12)).toBe('14 weeks over an 11-week cycle: you play three teams twice and eight once.')
    expect(scheduleNote(25, 12)).toBe('25 weeks over an 11-week cycle: you play three teams three times and eight twice.')
  })
  it('says nothing when the season is a whole number of cycles, and handles a short one', () => {
    expect(scheduleNote(22, 12)).toBeNull()
    expect(scheduleNote(11, 12)).toBeNull()
    expect(scheduleNote(4, 6)).toMatch(/shorter than one full round/)
  })
})
