import { describe, expect, it } from 'vitest'
import type { DigestEntry, DigestKeyEntry, DigestTrendingItem, SportDigest } from '../api'
import {
  fitToLimit,
  formatLine,
  keyPlayersCard,
  lastName,
  positionLeadersCard,
  spotlightCard,
  topPerformersCard,
  trendingCard,
  TWEET_LIMIT,
  weightedLength,
} from './tweetText'

function entry(over: Partial<DigestEntry> = {}): DigestEntry {
  return {
    playerId: '1',
    name: 'Nikola Jokić',
    position: 'C',
    team: 'DEN',
    opponent: 'GSW',
    isAway: false,
    points: 62.5,
    line: { pts: 34, reb: 12, ast: 8, stl: 3 },
    ...over,
  }
}

function digest(over: Partial<SportDigest> = {}): SportDigest {
  return {
    sport: 'nba',
    season: 2026,
    pointsBasis: 'Sleeper default points',
    status: 'READY',
    statsFetchedAt: '2026-10-21T11:00:00Z',
    date: '2026-10-20',
    week: 1,
    gamesCount: 2,
    lastSlateDate: '2026-10-20',
    inProgressDate: null,
    nextGameDate: null,
    trending: null,
    topPerformers: [],
    keyPlayers: [],
    positionLeaders: null,
    ...over,
  }
}

function item(rank: number, outcome: DigestTrendingItem['outcome'], e: Partial<DigestEntry> = {}, addCount = 4210): DigestTrendingItem {
  return { rank, addCount, outcome, entry: entry({ name: `Player ${rank}`, ...e }) }
}

function trendingDigest(items: DigestTrendingItem[]): SportDigest {
  return digest({
    trending: { status: 'OK', lookbackHours: 24, fetchedAt: '2026-10-20T23:48:00Z', stale: false, omittedUnknownPlayers: 0, entries: items },
  })
}

describe('weightedLength', () => {
  it('counts a Latin name with a diacritic as one per letter, composed or decomposed', () => {
    expect(weightedLength('Nikola Jokić')).toBe(12)
    expect(weightedLength('Nikola Jokić')).toBe(12) // c + combining acute
  })

  it('counts an emoji as 2, and a ZWJ family sequence as one grapheme of 2', () => {
    expect(weightedLength('🔥')).toBe(2)
    expect(weightedLength('👨‍👩‍👧‍👦')).toBe(2)
    expect(weightedLength('⭐️')).toBe(2) // star + variation selector
  })

  it('weighs the general-punctuation dashes and the middle dot as 1', () => {
    expect(weightedLength('—')).toBe(1)
    expect(weightedLength('·')).toBe(1)
  })

  it('weighs CJK at 2', () => {
    expect(weightedLength('日本')).toBe(4)
  })
})

describe('formatLine', () => {
  it('writes basketball compactly in a fixed order and skips zero stats', () => {
    expect(formatLine('nba', { pts: 34, reb: 12, ast: 8, stl: 3 })).toBe('34 PTS/12 REB/8 AST/3 STL')
    expect(formatLine('nba', { ast: 8, pts: 34, blk: 0, tpm: 5 })).toBe('34 PTS/8 AST/5 3PM')
  })

  it('merges football touchdowns into one TD after the yardage', () => {
    expect(formatLine('nfl', { pass_yd: 300, pass_td: 2 })).toBe('300 PASS YD, 2 TD')
    expect(formatLine('nfl', { rec_yd: 112, rec: 9, rec_td: 1 })).toBe('112 REC YD, 9 REC, 1 TD')
    expect(formatLine('nfl', { rush_yd: 80, rush_td: 1, rec_yd: 30, rec: 5 })).toBe('80 RUSH YD, 30 REC YD, 5 REC, 1 TD')
  })

  it('uses the given separator and is empty without a line', () => {
    expect(formatLine('nba', { pts: 1, reb: 2 }, ', ')).toBe('1 PTS, 2 REB')
    expect(formatLine('nba', null)).toBe('')
  })
})

describe('fitToLimit', () => {
  it('never returns more than the limit and reports shown of total', () => {
    const lines = Array.from({ length: 12 }, (_, i) => `${i + 1}. A fairly long player name here — 40.5 FP (30 PTS/10 REB/5 AST)`)
    const card = fitToLimit('Header', lines)!
    expect(card.weightedLength).toBeLessThanOrEqual(TWEET_LIMIT)
    expect(weightedLength(card.text)).toBe(card.weightedLength)
    expect(card.shown).toBeLessThan(12)
    expect(card.shown).toBeGreaterThan(0)
    expect(card.total).toBe(12)
    expect(card.text.split('\n')).toHaveLength(card.shown + 1)
  })

  it('never offers a header-only card when not even the first line fits (L5)', () => {
    expect(fitToLimit('h', ['x'.repeat(400)])).toBeNull()
    expect(fitToLimit('h', [])).toMatchObject({ text: 'h', shown: 0, total: 0 })
  })

  it('keeps everything that fits', () => {
    const card = fitToLimit('H', ['a', 'b'])
    expect(card).toMatchObject({ text: 'H\na\nb', shown: 2, total: 2 })
  })
})

describe('trendingCard', () => {
  it('leaves NO_GAME out of the text, keeps true ranks, and counts them', () => {
    const card = trendingCard(
      trendingDigest([item(1, 'PLAYED'), item(2, 'NO_GAME', { points: null, line: null }), item(3, 'DID_NOT_PLAY', { points: null, line: null })]),
    )!
    expect(card.noGame).toBe(1)
    expect(card.text).toContain('📈 Most added on Sleeper, and how they did last night:')
    expect(card.text).toContain('1. Player 1 (+4,210) — 62.5 FP (34 PTS/12 REB/8 AST/3 STL)')
    expect(card.text).not.toContain('Player 2')
    expect(card.text).toContain('3. Player 3 (+4,210) — DNP')
    expect(card.total).toBe(2)
  })

  it('renders a played zero as 0 FP, never DNP', () => {
    const card = trendingCard(trendingDigest([item(1, 'PLAYED', { points: 0, line: null })]))!
    expect(card.text).toContain('1. Player 1 (+4,210) — 0 FP')
    expect(card.text).not.toContain('DNP')
  })

  it('has nothing to post when every entry had no game or the night is not ready', () => {
    expect(trendingCard(trendingDigest([item(1, 'NO_GAME', { points: null })]))).toBeNull()
    expect(trendingCard(trendingDigest([item(1, 'NO_PERIOD', { points: null })]))).toBeNull()
    expect(trendingCard(digest({ trending: null }))).toBeNull()
  })

  it('stays within 280', () => {
    const many = Array.from({ length: 10 }, (_, i) => item(i + 1, 'PLAYED', { name: 'Giannis Antetokounmpo-Longname' }))
    expect(trendingCard(trendingDigest(many))!.weightedLength).toBeLessThanOrEqual(TWEET_LIMIT)
  })
})

describe('topPerformersCard', () => {
  it('numbers the first five and stays within 280', () => {
    const top = Array.from({ length: 10 }, (_, i) => entry({ name: `Player ${i + 1}`, points: 60 - i }))
    const card = topPerformersCard(digest({ topPerformers: top }))!
    expect(card.text.startsWith('🔥 Top fantasy scores last night:')).toBe(true)
    expect(card.text).toContain('1. Player 1 — 60 FP (34 PTS/12 REB/8 AST/3 STL)')
    expect(card.text).not.toContain('Player 6')
    expect(card.weightedLength).toBeLessThanOrEqual(TWEET_LIMIT)
    expect(card.total).toBe(10)
  })

  it('is null with no entries', () => {
    expect(topPerformersCard(digest())).toBeNull()
  })
})

describe('keyPlayersCard', () => {
  const key = (verdict: DigestKeyEntry['verdict'], name: string, points: number | null): DigestKeyEntry => ({
    rank: 1,
    entry: entry({ name, points }),
    played: points != null,
    expectation: 50,
    expectationBasis: verdict === 'NO_BASELINE' ? 'NONE' : 'THIS_SEASON',
    verdict,
  })

  it('marks above with a check, below with a cross, no baseline with nothing, and DNP as DNP', () => {
    const card = keyPlayersCard(
      digest({ keyPlayers: [key('ABOVE', 'Ann', 62.5), key('BELOW', 'Bo', 14.5), key('NO_BASELINE', 'Cy', 20), key('DNP', 'Di', null)] }),
    )!
    const lines = card.text.split('\n')
    expect(lines[0]).toBe('⭐ How the stars did:')
    expect(lines[1]).toBe('Ann — 62.5 FP ✅')
    expect(lines[2]).toBe('Bo — 14.5 FP ❌')
    expect(lines[3]).toBe('Cy — 20 FP')
    expect(lines[4]).toBe('Di — DNP')
  })

  it('stays within 280 with fifteen stars', () => {
    const many = Array.from({ length: 15 }, (_, i) => key('ABOVE', `Longish Player Name ${i}`, 55.5))
    const card = keyPlayersCard(digest({ keyPlayers: many }))!
    expect(card.weightedLength).toBeLessThanOrEqual(TWEET_LIMIT)
    expect(card.shown).toBeLessThan(15)
    expect(card.total).toBe(15)
  })
})

describe('spotlightCard', () => {
  it('writes the best line as a sentence', () => {
    const card = spotlightCard(digest({ topPerformers: [entry()] }))!
    expect(card.text).toBe('Nikola Jokić last night: 34 PTS, 12 REB, 8 AST, 3 STL — 62.5 fantasy points.')
  })

  it('is null with no top performer', () => {
    expect(spotlightCard(digest())).toBeNull()
  })
})

describe('positionLeadersCard', () => {
  const leaders = (names: string[]): DigestEntry[] =>
    ['QB', 'RB', 'WR', 'TE'].slice(0, names.length).map((pos, i) => entry({ name: names[i], position: pos, points: 31.2 - i }))

  it('names the night, not the week, and skips nothing it was given', () => {
    const card = positionLeadersCard(
      digest({ sport: 'nfl', date: '2026-10-18', week: 7, positionLeaders: leaders(['Josh Allen', 'Saquon Barkley', 'Ja’Marr Chase', 'Trey McBride']) }),
    )!
    expect(card.text).toBe("🏈 Sunday's leaders (Wk 7): QB Josh Allen 31.2 · RB Saquon Barkley 30.2 · WR Ja’Marr Chase 29.2 · TE Trey McBride 28.2")
    expect(card.weightedLength).toBeLessThanOrEqual(TWEET_LIMIT)
  })

  it('says "night" for a Monday game and never claims the whole week (B1)', () => {
    const l = leaders(['Josh Allen'])
    const mon = positionLeadersCard(digest({ sport: 'nfl', date: '2026-10-19', week: 6, positionLeaders: l }))!
    expect(mon.text.startsWith('🏈 Monday night leaders (Wk 6): QB Josh Allen')).toBe(true)
    expect(mon.text).not.toMatch(/Week 6 leaders/)
    const noDate = positionLeadersCard(digest({ sport: 'nfl', date: null, week: null, positionLeaders: l }))!
    expect(noDate.text.startsWith('🏈 Leaders: ')).toBe(true)
  })

  it('is null rather than a header-only card when not even one position fits', () => {
    const huge = 'X'.repeat(300)
    expect(positionLeadersCard(digest({ sport: 'nfl', week: 7, positionLeaders: leaders([huge]) }))).toBeNull()
  })

  it('shortens to last names when full names would not fit', () => {
    const long = 'Alexander Maximilian Montgomery-Throckmorton-Featherstonehaugh Jr.'
    const card = positionLeadersCard(digest({ sport: 'nfl', week: 7, positionLeaders: leaders([long, long, long, long]) }))!
    expect(card.weightedLength).toBeLessThanOrEqual(TWEET_LIMIT)
    expect(card.text).toContain('Montgomery-Throckmorton-Featherstonehaugh')
    expect(card.text).not.toContain('Alexander')
  })

  it('is null for basketball', () => {
    expect(positionLeadersCard(digest({ positionLeaders: null }))).toBeNull()
  })

  it('drops a suffix when finding the last name', () => {
    expect(lastName('Odell Beckham Jr.')).toBe('Beckham')
    expect(lastName('Prince')).toBe('Prince')
  })
})
