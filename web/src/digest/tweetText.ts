import type { DigestEntry, DigestLine, SportDigest } from '../api'

/**
 * Spec 043: turns the digest's structured entries into post text. Pure, so the wording (the part
 * Allan will want to tweak) and the 280 limit are testable without a browser.
 */

/** X's post limit, in its weighted length (below). */
export const TWEET_LIMIT = 280

/** Entries a list card shows before the 280 limit trims it further. Arbitrary: a taste call. */
export const CARD_ENTRIES = 5

/**
 * X's weighted length, simplified from twitter-text v3: the code points in these ranges weigh 1 and
 * every other weighs 2. The text is NFC-normalized first (a decomposed "c + U+0301" must count as one
 * "ć"), and an emoji sequence (ZWJ, variation selector, flag, skin tone) counts as ONE grapheme of
 * weight 2. Assumed, not checked against X: the real check is pasting a card into X's composer.
 */
const WEIGHT_ONE_RANGES: ReadonlyArray<readonly [number, number]> = [
  [0, 4351],
  [8192, 8205],
  [8208, 8223],
  [8242, 8247],
]

const weightOfCodePoint = (cp: number): number =>
  WEIGHT_ONE_RANGES.some(([lo, hi]) => cp >= lo && cp <= hi) ? 1 : 2

const EMOJI_GRAPHEME = /\p{Extended_Pictographic}|\p{Regional_Indicator}|‍|️/u

function graphemes(text: string): string[] {
  if (typeof Intl !== 'undefined' && typeof Intl.Segmenter === 'function') {
    return Array.from(new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text), (s) => s.segment)
  }
  return Array.from(text) // no segmenter: per code point, which over-counts emoji sequences (safe side)
}

export function weightedLength(text: string): number {
  let total = 0
  for (const g of graphemes(text.normalize('NFC'))) {
    if (EMOJI_GRAPHEME.test(g)) {
      total += 2
    } else {
      for (const ch of g) total += weightOfCodePoint(ch.codePointAt(0)!)
    }
  }
  return total
}

// ---- Stat lines ---------------------------------------------------------------------------------

const NBA_LABELS: ReadonlyArray<readonly [string, string]> = [
  ['pts', 'PTS'],
  ['reb', 'REB'],
  ['ast', 'AST'],
  ['stl', 'STL'],
  ['blk', 'BLK'],
  ['tpm', '3PM'],
]

const val = (line: DigestLine, key: string): number => line[key] ?? 0

/**
 * A stat line as post text. Basketball: `34 PTS/12 REB/8 AST/3 STL`. Football merges every touchdown
 * into one `N TD` after the yardage: `300 PASS YD, 2 TD`, `112 REC YD, 9 REC, 1 TD`. `sep` is the
 * joiner (`/` for a compact line, `, ` for a sentence).
 */
export function formatLine(sport: 'nba' | 'nfl', line: DigestLine | null, sep?: string): string {
  if (!line) return ''
  if (sport === 'nba') {
    return NBA_LABELS.filter(([k]) => val(line, k) !== 0)
      .map(([k, label]) => `${val(line, k)} ${label}`)
      .join(sep ?? '/')
  }
  const parts: string[] = []
  const push = (n: number, label: string) => {
    if (n !== 0) parts.push(`${n} ${label}`)
  }
  push(val(line, 'pass_yd'), 'PASS YD')
  push(val(line, 'rush_yd'), 'RUSH YD')
  push(val(line, 'rec_yd'), 'REC YD')
  push(val(line, 'rec'), 'REC')
  push(val(line, 'pass_td') + val(line, 'rush_td') + val(line, 'rec_td'), 'TD')
  push(val(line, 'pass_int'), 'INT')
  return parts.join(sep ?? ', ')
}

/** Points as post text: at most two decimals, no trailing zeros (`62.5`, `12.34`, `0`). */
export const formatPoints = (p: number): string => String(Math.round(p * 100) / 100)

const fp = (p: number) => `${formatPoints(p)} FP`

// ---- Fitting ------------------------------------------------------------------------------------

export type PostCard = {
  text: string
  weightedLength: number
  /** Lines kept after trimming to the limit. */
  shown: number
  /** Lines the card could have had. */
  total: number
}

/**
 * Drops trailing lines until the header plus the lines fit `limit`. `total` defaults to `lines.length`.
 * Null when not even the first line fits: a header with no lines is nothing worth posting (L5).
 */
export function fitToLimit(
  header: string,
  lines: string[],
  limit: number = TWEET_LIMIT,
  total: number = lines.length,
): PostCard | null {
  let kept = lines.length
  const build = (n: number) => [header, ...lines.slice(0, n)].join('\n')
  while (kept > 0 && weightedLength(build(kept)) > limit) kept--
  if (kept === 0 && lines.length > 0) return null
  const text = build(kept)
  return { text, weightedLength: weightedLength(text), shown: kept, total }
}

// ---- Cards --------------------------------------------------------------------------------------

const withLine = (sport: 'nba' | 'nfl', e: DigestEntry): string => {
  const l = formatLine(sport, e.line)
  return l ? ` (${l})` : ''
}

export type TrendingPostCard = PostCard & {
  /** Trending players whose team had no game: left out of the text, counted here. */
  noGame: number
}

/**
 * Trending adds and how they did. Only PLAYED and DID_NOT_PLAY entries are posted, in Sleeper's order
 * with their true rank numbers; a NO_GAME entry is left out and counted (F8). Returns null when there
 * is nothing to say about performance (no card, or the night is not ready: outcomes are NO_PERIOD).
 */
export function trendingCard(sd: SportDigest, when = 'last night'): TrendingPostCard | null {
  const t = sd.trending
  if (!t || t.status !== 'OK') return null
  const noGame = t.entries.filter((i) => i.outcome === 'NO_GAME').length
  const items = t.entries.filter((i) => i.outcome === 'PLAYED' || i.outcome === 'DID_NOT_PLAY')
  if (items.length === 0) return null
  const lines = items.map((i) => {
    const adds = `(+${i.addCount.toLocaleString('en-US')})`
    return i.outcome === 'PLAYED' && i.entry.points != null
      ? `${i.rank}. ${i.entry.name} ${adds} — ${fp(i.entry.points)}${withLine(sd.sport, i.entry)}`
      : `${i.rank}. ${i.entry.name} ${adds} — DNP`
  })
  const card = fitToLimit(`📈 Most added on Sleeper, and how they did ${when}:`, lines.slice(0, CARD_ENTRIES), TWEET_LIMIT, items.length)
  return card ? { ...card, noGame } : null
}

export function topPerformersCard(sd: SportDigest, when = 'last night'): PostCard | null {
  if (sd.topPerformers.length === 0) return null
  const lines = sd.topPerformers.map(
    (e, i) => `${i + 1}. ${e.name} — ${fp(e.points ?? 0)}${withLine(sd.sport, e)}`,
  )
  return fitToLimit(`🔥 Top fantasy scores ${when}:`, lines.slice(0, CARD_ENTRIES), TWEET_LIMIT, lines.length)
}

/** ✅ at or above his own expectation, ❌ below it, nothing without a baseline, DNP when he did not play. */
export function keyPlayersCard(sd: SportDigest, when = 'last night'): PostCard | null {
  if (sd.keyPlayers.length === 0) return null
  const lines = sd.keyPlayers.map((k) => {
    if (!k.played || k.entry.points == null) return `${k.entry.name} — DNP`
    const mark = k.verdict === 'ABOVE' ? ' ✅' : k.verdict === 'BELOW' ? ' ❌' : ''
    return `${k.entry.name} — ${fp(k.entry.points)}${mark}`
  })
  // The spec's header has no time words; one is added only when the card covers a chosen date.
  const header = when === 'last night' ? '⭐ How the stars did:' : `⭐ How the stars did ${when}:`
  return fitToLimit(header, lines, TWEET_LIMIT, lines.length)
}

/** The night's best single line on its own. Null with no top performer. */
export function spotlightCard(sd: SportDigest, when = 'last night'): PostCard | null {
  const best = sd.topPerformers[0]
  if (!best || best.points == null) return null
  const long = formatLine(sd.sport, best.line, ', ')
  const text = `${best.name} ${when}: ${long ? `${long} — ` : ''}${formatPoints(best.points)} fantasy points.`
  return { text, weightedLength: weightedLength(text), shown: 1, total: 1 }
}

const SUFFIXES = /^(jr|sr|ii|iii|iv|v)\.?$/i

/** "Odell Beckham Jr." -> "Beckham"; a one-word name is kept. */
export function lastName(name: string): string {
  const parts = name.trim().split(/\s+/)
  let i = parts.length - 1
  if (i > 0 && SUFFIXES.test(parts[i])) i--
  return parts[i]
}

/**
 * Football only: `🏈 Sunday's leaders (Wk 7): QB Name 31.2 · RB Name 28.4 · WR Name 27 · TE Name 19.6`.
 * The leaders are one night's rows, so the header names that night (B1): a bare "Week 7 leaders" would
 * claim the whole week. Over 280, names shrink to last names; still over, trailing positions are dropped.
 */
export function positionLeadersCard(sd: SportDigest): PostCard | null {
  const leaders = sd.positionLeaders
  if (!leaders || leaders.length === 0) return null
  const header = leadersHeader(sd)
  const build = (n: number, short: boolean) =>
    `${header} ${leaders
      .slice(0, n)
      .map((e) => `${e.position} ${short ? lastName(e.name) : e.name} ${formatPoints(e.points ?? 0)}`)
      .join(' · ')}`
  for (const short of [false, true]) {
    for (let n = leaders.length; n >= 1; n--) {
      if (!short && n < leaders.length) break // full names are all-or-nothing; shrink names before dropping
      const text = build(n, short)
      if (weightedLength(text) <= TWEET_LIMIT) return { text, weightedLength: weightedLength(text), shown: n, total: leaders.length }
    }
  }
  return null // not even one position fits: nothing worth posting (L5)
}

/** "Sunday's leaders (Wk 7):", "Monday night leaders (Wk 7):"; the day comes from the covered date. */
export function leadersHeader(sd: SportDigest): string {
  const wk = sd.week != null ? ` (Wk ${sd.week})` : ''
  if (!sd.date) return `🏈 Leaders${wk}:`
  const day = new Date(`${sd.date}T12:00:00`).toLocaleDateString('en-US', { weekday: 'long' })
  const weekend = day === 'Saturday' || day === 'Sunday'
  return `🏈 ${weekend ? `${day}'s` : `${day} night`} leaders${wk}:`
}
