import { describe, expect, it } from 'vitest'
import { fallbackLabel, oneGameNote, rolesWindowLabel, streamingReasonSentence } from './playerTrends'
import type { StreamingReason } from './api'

describe('streamingReasonSentence', () => {
  const reasons: StreamingReason[] = ['SEASON_COMPLETE', 'NOT_DRAFTED', 'ROSTERS_NOT_LOADED']
  it('never mentions ingest or an api path', () => {
    for (const r of reasons) {
      const s = streamingReasonSentence(r)
      expect(s.toLowerCase()).not.toContain('ingest')
      expect(s).not.toContain('/api/')
    }
  })
  it('promises no refresh for a finished season', () => {
    expect(streamingReasonSentence('SEASON_COMPLETE')).toContain('current season')
    expect(streamingReasonSentence('SEASON_COMPLETE').toLowerCase()).not.toContain('refresh')
  })
  it('says what is missing for the other two', () => {
    expect(streamingReasonSentence('NOT_DRAFTED')).toContain('hasn’t drafted')
    expect(streamingReasonSentence('ROSTERS_NOT_LOADED')).toContain('next refresh')
  })
})

describe('oneGameNote', () => {
  it('is null without a credit', () => expect(oneGameNote(null)).toBeNull())
  it('carries the share, the season and the measured provenance', () => {
    const n = oneGameNote({ code: 'ONE_GAME_CREDITED', share: 0.99, seasonMeasured: 2025 })!
    expect(n).toContain('99% of multi-game weeks in 2025')
    expect(n).toContain('+4% for 4 vs 2 scheduled games (same player, 2025, Ball Knowers)')
  })
})

describe('fallback labels', () => {
  it('names both seasons and the threshold', () => {
    expect(fallbackLabel(2026, 2025, 5)).toBe(
      '2025 season (2026 is too early: fewer than half the teams have played 5 games)',
    )
  })
  it('says end of the season for fallback roles only', () => {
    expect(rolesWindowLabel(true, 2025, 2026, 3)).toContain('end of the 2025 season')   // fallback or complete
    expect(rolesWindowLabel(false, 2026, 2026, 3)).not.toContain('end of')
  })
})
