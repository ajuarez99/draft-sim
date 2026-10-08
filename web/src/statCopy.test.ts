import { describe, expect, it } from 'vitest'
import {
  ADVANCED_DEFINITIONS,
  ADVANCED_KEYS,
  ADVANCED_LABELS,
  STYLE_KEYS,
  percentileMeaning,
  qualificationRule,
  rosteredGroupNote,
  ALL_STAT_REASONS,
  POOLING_NOTE,
  advancedDefinition,
  ordinal,
  pctGroupLabel,
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

  it('has a label and a definition for all 13 advanced keys', () => {
    expect(ADVANCED_KEYS).toHaveLength(13)
    for (const k of ADVANCED_KEYS) {
      expect(ADVANCED_LABELS[k].trim().length, k).toBeGreaterThan(0)
      expect(advancedDefinition(k).trim().length, k).toBeGreaterThan(20)
    }
    expect(ADVANCED_LABELS.ts).toBe('True shooting %')
  })

  it('adds the pooling note to team-relative stats only, and says turnovers are lower-is-better', () => {
    expect(advancedDefinition('usg')).toContain(POOLING_NOTE)
    expect(advancedDefinition('trbPct')).toContain('games he played')
    expect(advancedDefinition('ts')).not.toContain(POOLING_NOTE)
    expect(advancedDefinition('tovPct')).toContain('Lower is better')
  })

  it('names the comparison groups and writes ordinals', () => {
    expect(pctGroupLabel('NBA_POSITION', 'C')).toBe('vs Cs across the NBA')
    expect(pctGroupLabel('LEAGUE_ROSTERED', 'C')).toBe('vs players rostered in this league')
    expect([1, 2, 3, 4, 11, 12, 13, 21, 71].map(ordinal)).toEqual(
      ['1st', '2nd', '3rd', '4th', '11th', '12th', '13th', '21st', '71st'],
    )
  })

  it('never prints 100th or 0th from rounding', () => {
    expect([100, 99.69, 99.5, 0, 0.3, 0.9].map(ordinal)).toEqual(['99th', '99th', '99th', '1st', '1st', '1st'])
  })

  it('states the qualification rule from the numbers the API sent', () => {
    const t = qualificationRule({ minGamesShare: 0.5, minGames: 42, maxTeamGames: 83, minMinutesPerGame: 15, recencyDays: 14 })
    expect(t).toBe(
      'Ranked among players with at least 42 games (half of the most any team has played, 83) and 15+ minutes per game; last-5/last-10 also require a game in the last 14 days.',
    )
    expect(qualificationRule({ minGamesShare: 0.6, minGames: 49, maxTeamGames: 82, minMinutesPerGame: 20, recencyDays: 7 }))
      .toContain('at least 49 games (60% of the most any team has played, 82) and 20+ minutes')
  })

  it('words the rostered group with its as-of, and says when it is a fallback season', () => {
    const wk = { kind: 'WEEK' as const, fetchedAt: null, week: 18 }
    expect(rosteredGroupNote(wk, 2025, false)).toBe(
      'Players rostered in this league · End of 2025–26 regular season (week 18).',
    )
    expect(rosteredGroupNote(wk, 2025, true)).toContain('These are 2025–26 rosters, not today’s.')
    expect(rosteredGroupNote({ kind: 'CURRENT', fetchedAt: '2026-10-08T12:00:00Z', week: null }, 2026, false)).toContain('As of Oct 8, 2026')
    expect(rosteredGroupNote(null, 2025, false)).toContain('date not available')
  })

  it('describes a percentile as a position, with turnovers and style stats worded for what they are', () => {
    expect(percentileMeaning('tovPct')).toBe('higher percentile = fewer turnovers')
    expect(percentileMeaning('usg')).not.toMatch(/fewer|better/)
    expect([...STYLE_KEYS].sort()).toEqual(['ftr', 'minutesShare', 'tpar', 'usg'])
  })

  it('defines minutes share as a share of the game, not of the team’s player minutes', () => {
    expect(ADVANCED_LABELS.minutesShare).toBe('Share of game minutes played')
    expect(ADVANCED_DEFINITIONS.minutesShare).toContain('34 of 48')
    expect(ADVANCED_DEFINITIONS.minutesShare).not.toContain('total player minutes that he played')
  })

  it('gives GROUP_TOO_SMALL its own sentence when he has no position', () => {
    expect(reasonSentence('GROUP_TOO_SMALL')).toContain('Too few other players')
    expect(reasonSentence('GROUP_TOO_SMALL', { noPosition: true })).toContain('no position on file')
  })

  it('never prints 100th or 0th from rounding', () => {
    expect([100, 99.69, 99.5, 0, 0.3, 0.9].map(ordinal)).toEqual(['99th', '99th', '99th', '1st', '1st', '1st'])
  })

  it('states the qualification rule from the numbers the API sent', () => {
    const t = qualificationRule({ minGamesShare: 0.5, minGames: 42, maxTeamGames: 83, minMinutesPerGame: 15, recencyDays: 14 })
    expect(t).toBe(
      'Ranked among players with at least 42 games (half of the most any team has played, 83) and 15+ minutes per game; last-5/last-10 also require a game in the last 14 days.',
    )
    expect(qualificationRule({ minGamesShare: 0.6, minGames: 49, maxTeamGames: 82, minMinutesPerGame: 20, recencyDays: 7 }))
      .toContain('at least 49 games (60% of the most any team has played, 82) and 20+ minutes')
  })

  it('words the rostered group with its as-of, and says when it is a fallback season', () => {
    const wk = { kind: 'WEEK' as const, fetchedAt: null, week: 18 }
    expect(rosteredGroupNote(wk, 2025, false)).toBe('Players rostered in this league · End of 2025–26 regular season (week 18).')
    expect(rosteredGroupNote(wk, 2025, true)).toContain('These are 2025–26 rosters, not today’s.')
    expect(rosteredGroupNote({ kind: 'CURRENT', fetchedAt: '2026-10-08T12:00:00Z', week: null }, 2026, false)).toContain('As of Oct 8, 2026')
    expect(rosteredGroupNote(null, 2025, false)).toContain('date not available')
  })

  it('describes a percentile as a position, with turnovers and style stats worded for what they are', () => {
    expect(percentileMeaning('tovPct')).toBe('higher percentile = fewer turnovers')
    expect(percentileMeaning('usg')).not.toMatch(/fewer|better/)
    expect([...STYLE_KEYS].sort()).toEqual(['ftr', 'minutesShare', 'tpar', 'usg'])
  })

  it('defines minutes share as a share of the game, not of the team’s player minutes', () => {
    expect(ADVANCED_LABELS.minutesShare).toBe('Share of game minutes played')
    expect(ADVANCED_DEFINITIONS.minutesShare).toContain('34 of 48')
    expect(ADVANCED_DEFINITIONS.minutesShare).not.toContain('total player minutes that he played')
  })

  it('gives GROUP_TOO_SMALL its own sentence when he has no position', () => {
    expect(reasonSentence('GROUP_TOO_SMALL')).toContain('Too few other players')
    expect(reasonSentence('GROUP_TOO_SMALL', { noPosition: true })).toContain('no position on file')
  })
})
