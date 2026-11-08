import { describe, expect, it } from 'vitest'
import { reportScoreNote, scoreSourceNotes, weekRuns } from './scoreSource'

describe('weekRuns', () => {
  it('collapses consecutive weeks into a range', () => {
    expect(weekRuns([1, 2])).toBe('1–2')
    expect(weekRuns([3, 1, 2, 5])).toBe('1–3, 5')
    expect(weekRuns([4])).toBe('4')
    expect(weekRuns([2, 2, 3])).toBe('2–3')
  })
})

describe('scoreSourceNotes', () => {
  it('names commissioner weeks and official weeks in words, and nothing for the feed', () => {
    expect(
      scoreSourceNotes([
        { week: 1, source: 'COMMISSIONER' },
        { week: 2, source: 'COMMISSIONER' },
        { week: 3, source: 'OFFICIAL' },
        { week: 4, source: 'FEED' },
      ]),
    ).toEqual([
      'Weeks 1–2 use scores the commissioner entered from the Sleeper app.',
      "Week 3 uses Sleeper's official totals.",
    ])
  })

  it('is empty when every week is the feed', () => {
    expect(scoreSourceNotes([{ week: 1, source: 'FEED' }])).toEqual([])
    expect(scoreSourceNotes([])).toEqual([])
  })

  it('uses the singular for one week', () => {
    expect(scoreSourceNotes([{ week: 1, source: 'COMMISSIONER' }])).toEqual([
      'Week 1 uses scores the commissioner entered from the Sleeper app.',
    ])
  })
})

describe('reportScoreNote', () => {
  it('notes commissioner and official weeks only', () => {
    expect(reportScoreNote('COMMISSIONER')).toMatch(/entered by the commissioner/)
    expect(reportScoreNote('OFFICIAL')).toMatch(/official totals/)
    expect(reportScoreNote('FEED')).toBeNull()
    expect(reportScoreNote(null)).toBeNull()
    expect(reportScoreNote(undefined)).toBeNull()
  })
})
