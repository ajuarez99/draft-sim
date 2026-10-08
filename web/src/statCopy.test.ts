import { describe, expect, it } from 'vitest'
import {
  ALL_STAT_REASONS,
  SCORING_KEY_LABELS,
  fallbackNote,
  ownershipAsOf,
  rankMoveSentence,
  reasonSentence,
  reasonShort,
  scoringKeyLabel,
  seasonLabel,
} from './statCopy'

describe('statCopy', () => {
  it('has a non-empty sentence for every reason code', () => {
    for (const code of ALL_STAT_REASONS) {
      expect(reasonSentence(code, { date: '2026-03-04' }).trim().length, code).toBeGreaterThan(0)
    }
  })

  it('names the date for a stale player, and still reads without one', () => {
    expect(reasonSentence('NOT_QUALIFIED_STALE', { date: '2026-03-04' })).toContain('Mar 4, 2026')
    expect(reasonSentence('NOT_QUALIFIED_STALE')).not.toContain('undefined')
  })

  it('labels every NBA scoring key the league can use', () => {
    for (const k of ['pts', 'reb', 'ast', 'stl', 'blk', 'to', 'tpm', 'dd', 'td', 'ff', 'tf',
      'bonus_pt_40p', 'bonus_pt_50p', 'bonus_reb_20p', 'bonus_ast_15p']) {
      expect(SCORING_KEY_LABELS[k], k).toBeTruthy()
    }
    expect(scoringKeyLabel('mystery_key')).toBe('mystery_key')
  })

  it('numbers seasons by start year', () => {
    expect(seasonLabel(2025)).toBe('2025–26')
    expect(seasonLabel(1999)).toBe('1999–00')
  })

  it('writes the fallback note', () => {
    expect(fallbackNote('2026–27', '2025–26')).toBe(
      '2026–27 has no games yet; showing 2025–26. Trends may show a different season.',
    )
  })

  it('words an ownership reading by what it is', () => {
    expect(ownershipAsOf({ kind: 'WEEK', fetchedAt: null, week: 18 }, 2025)).toBe(
      'End of 2025–26 regular season (week 18)',
    )
    expect(ownershipAsOf({ kind: 'CURRENT', fetchedAt: '2026-10-08T12:00:00Z', week: null }, 2026)).toMatch(/^As of Oct \d+, 2026$/)
    expect(ownershipAsOf(null, 2026)).toBeNull()
  })

  it('has a short label for every reason, and "no att." for NO_ATTEMPTS', () => {
    for (const code of ALL_STAT_REASONS) expect(reasonShort(code).trim().length, code).toBeGreaterThan(0)
    expect(reasonShort('NO_ATTEMPTS')).toBe('no att.')
  })

  it('states the rank move against real points per game', () => {
    const base = { pointsRank: 152, leagueRank: 40, groupSize: 299 }
    expect(rankMoveSentence({ ...base, rankMove: 112 })).toBe(
      'By real points per game he’s #152 of 299; in this league’s scoring he’s #40 — 112 places higher.',
    )
    expect(rankMoveSentence({ pointsRank: 40, leagueRank: 117, groupSize: 299, rankMove: -77 })).toBe(
      'By real points per game he’s #40 of 299; in this league’s scoring he’s #117 — 77 places lower.',
    )
    expect(rankMoveSentence({ pointsRank: 40, leagueRank: 40, groupSize: 299, rankMove: 0 })).toBe(
      'By real points per game he’s #40 of 299; in this league’s scoring he’s #40 — the same in both.',
    )
  })
})
