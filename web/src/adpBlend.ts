import type { AdpPlayer } from './api'

/*
 * Spec 046 US1: the compare page's pure rules -- the gap, whether Yahoo's number can be trusted, the tint,
 * and the sort key. Pure so the sliders answer instantly and so the rules are tested without a DOM. The
 * trust-Yahoo blend (US2) extends this module; nothing here assumes it.
 *
 * Direction, stated once: gap = yahooAdp - dynasty. Negative means Yahoo's redraft leagues take the player
 * EARLIER than Sleeper's dynasty drafters (a veteran with win-now value: Durant, LeBron); positive means
 * dynasty takes him earlier (a young prospect: a rookie). An ADP is a pick number, so smaller is earlier.
 */

/**
 * The gap, in picks, at which a row is tinted. Hand-set and ARBITRARY (spec 046 FR-005): 20 picks is about two
 * rounds in a ten-team league, a gap a drafter would notice, not a fitted threshold. The page's slider moves it.
 */
export const GAP_THRESHOLD_DEFAULT = 20

/**
 * A Yahoo value from fewer than this share of Yahoo's leagues is not trusted (research R6 amended, F7). The
 * server sends the same figure as `sources[yahoo].minPercentDrafted`; this is the fallback when a payload
 * lacks it. Hand-set and ARBITRARY: roughly where the tail compression starts in the 2026-10-23 capture.
 */
export const YAHOO_MIN_PCT_DEFAULT = 0.5

/** Which source ranks the player EARLIER, by at least the threshold. */
export type Tint = 'yahoo' | 'sleeper'

/** The fields the rules read; an AdpPlayer satisfies it. */
export type AdpRow = Pick<AdpPlayer, 'name' | 'dynasty' | 'redraft' | 'yahooAdp' | 'yahooCost' | 'yahooPctDrafted' | 'team' | 'yearsExp'>

/**
 * Yahoo ADP minus dynasty ADP in picks, or null when either is missing. This is the RAW gap and says
 * nothing about whether Yahoo's number is trustworthy: pair it with {@link yahooReliable}.
 */
export function gap(p: Pick<AdpRow, 'dynasty' | 'yahooAdp'>): number | null {
  if (p.dynasty == null || p.yahooAdp == null) return null
  return p.yahooAdp - p.dynasty
}

/**
 * Whether Yahoo's ADP for this player rests on enough leagues to be compared. A value with no drafted-share
 * is not trusted either: absence of evidence is not reliability.
 */
export function yahooReliable(p: Pick<AdpRow, 'yahooAdp' | 'yahooPctDrafted'>, minPct: number): boolean {
  return p.yahooAdp != null && p.yahooPctDrafted != null && p.yahooPctDrafted >= minPct
}

/** The gap only when Yahoo's side is reliable; what the page tints, sorts and spells out. */
export function trustedGap(p: AdpRow, minPct: number): number | null {
  return yahooReliable(p, minPct) ? gap(p) : null
}

/**
 * Which side wins the row, or null (not tinted). Null whenever Yahoo's value is unreliable, however large the
 * raw gap: a deep-tail Yahoo ADP from a handful of leagues is exactly the number most likely to be noise.
 */
export function tintOf(p: AdpRow, threshold: number, minPct: number): Tint | null {
  const g = trustedGap(p, minPct)
  if (g == null) return null
  if (g <= -threshold) return 'yahoo'
  if (g >= threshold) return 'sleeper'
  return null
}

/** What the gap cell says, always as words (a tint alone would be colour-only meaning). */
export function gapText(p: AdpRow, minPct: number): string {
  if (p.yahooAdp == null) return 'not ranked by Yahoo'
  if (p.dynasty == null) return 'no dynasty ADP'
  if (!yahooReliable(p, minPct)) return 'not compared'
  const g = p.yahooAdp - p.dynasty
  const n = Math.abs(g)
  if (n < 0.05) return 'level'
  return `Yahoo ${n.toFixed(1)} picks ${g < 0 ? 'earlier' : 'later'}`
}

/** The columns the table can sort by. `gap` is signed, so either direction is available. */
export type SortCol = 'name' | 'team' | 'years' | 'dynasty' | 'redraft' | 'yahooAdp' | 'yahooCost' | 'gap'
export type SortDir = 'asc' | 'desc'

/** A row's value in one column; null sorts last in BOTH directions (a missing value isn't "best" or "worst"). */
export function sortKey(p: AdpRow, col: SortCol, minPct: number): number | string | null {
  switch (col) {
    case 'name': return p.name.toLowerCase()
    case 'team': return p.team
    case 'years': return p.yearsExp
    case 'dynasty': return p.dynasty
    case 'redraft': return p.redraft
    case 'yahooAdp': return p.yahooAdp
    case 'yahooCost': return p.yahooCost
    case 'gap': return trustedGap(p, minPct)
  }
}

function cmp(a: number | string, b: number | string): number {
  return typeof a === 'number' && typeof b === 'number' ? a - b : String(a).localeCompare(String(b))
}

/**
 * Sorts a copy. Nulls last in either direction; ties fall to dynasty ADP, then redraft ADP, then name, all
 * ascending whatever the direction, so the order of equal rows doesn't flip when the user reverses a sort.
 */
export function sortRows<T extends AdpRow>(rows: readonly T[], col: SortCol, dir: SortDir, minPct: number): T[] {
  const sign = dir === 'asc' ? 1 : -1
  return [...rows].sort((a, b) => {
    const ka = sortKey(a, col, minPct)
    const kb = sortKey(b, col, minPct)
    if (ka == null && kb != null) return 1
    if (ka != null && kb == null) return -1
    if (ka != null && kb != null) {
      const c = cmp(ka, kb)
      if (c !== 0) return sign * c
    }
    return tieBreak(a, b)
  })
}

/** dynasty, then redraft (nulls last), then name. */
function tieBreak(a: AdpRow, b: AdpRow): number {
  for (const key of ['dynasty', 'redraft'] as const) {
    const x = a[key]
    const y = b[key]
    if (x == null && y != null) return 1
    if (x != null && y == null) return -1
    if (x != null && y != null && x !== y) return x - y
  }
  return a.name.localeCompare(b.name)
}

/*
 * Spec 046 US2: the trust-Yahoo blend and Blend $. Both are DERIVED GUIDES, not measurements, and every knob
 * below is hand-set: say so wherever one is shown (FR-013).
 */

/** How far to move from dynasty ADP toward Yahoo's, 0..1. Hand-set and ARBITRARY; the page's slider moves it. */
export const TRUST_DEFAULT = 0.75

/**
 * The gap, in picks, at which Yahoo gets its full say. A smaller gap is mostly noise between two noisy
 * averages, so it moves the blend proportionally less. Hand-set and ARBITRARY; the page's slider moves it.
 */
export const FULL_TRUST_GAP_DEFAULT = 30

/** The blend weight below which the page does not bother showing a blended sub-line (it would read as no change). */
export const BLEND_SUBLINE_MIN_WEIGHT = 0.05

/**
 * How much of the way from dynasty to Yahoo this player's blend goes: `trust * min(|gap| / fullGap, 1)`.
 * Zero when Yahoo's number is missing or unreliable, or there is no dynasty ADP to blend from.
 */
export function blendWeight(p: AdpRow, trust: number, fullGap: number, minPct: number): number {
  if (p.dynasty == null || !yahooReliable(p, minPct) || p.yahooAdp == null) return 0
  const share = fullGap > 0 ? Math.min(Math.abs(p.yahooAdp - p.dynasty) / fullGap, 1) : 1
  return trust * share
}

/**
 * The blended ADP: `dyn + w * (yahoo - dyn)`. Dynasty-only whenever Yahoo can't be counted (unranked, or under
 * `minPct` of Yahoo leagues); null when there is no dynasty ADP at all, because a blend needs its anchor.
 */
export function blendValue(p: AdpRow, trust: number, fullGap: number, minPct: number): number | null {
  if (p.dynasty == null) return null
  const w = blendWeight(p, trust, fullGap, minPct)
  if (w === 0 || p.yahooAdp == null) return p.dynasty
  return p.dynasty + w * (p.yahooAdp - p.dynasty)
}

export type Blended<T> = { row: T; blend: number; weight: number; rank: number }

/**
 * The players that have a blend, best (smallest) first, ranked 1..n. Ties fall to dynasty, then redraft, then
 * name -- the same key every sort on the page uses. Players with no blend are not ranked (they have no dynasty ADP).
 *
 * Only the blended VALUE is monotonic in trust; the RANK is not (research R6 amended, F6): a Yahoo-favoured
 * player can slip behind another Yahoo-favoured player whose gap is bigger.
 */
export function blendOrder<T extends AdpRow>(rows: readonly T[], trust: number, fullGap: number, minPct: number): Blended<T>[] {
  const out: Omit<Blended<T>, 'rank'>[] = []
  for (const row of rows) {
    const blend = blendValue(row, trust, fullGap, minPct)
    if (blend != null) out.push({ row, blend, weight: blendWeight(row, trust, fullGap, minPct) })
  }
  out.sort((a, b) => (a.blend !== b.blend ? a.blend - b.blend : tieBreak(a.row, b.row)))
  return out.map((b, i) => ({ ...b, rank: i + 1 }))
}

/**
 * Yahoo's price curve scaled to this league's money: `result[i]` is the price of the i-th best player (rank
 * i + 1), whole dollars.
 *
 * The league buys N = teams * rounds players and has teams * budget to spend. Every spot costs at least $1, so
 * the curve is padded with $1 to N and the surplus over $1 is scaled so the whole room adds up:
 * `1 + k * (c_i - 1)` with `k = (teams * budget - N) / sum(c_i - 1)`. After rounding to whole dollars the sum is
 * within +/- 0.5 * N of `teams * budget` (an identity check, not a validation of the price shape).
 *
 * Null -- never a divide by zero -- for an empty curve, no budget/teams/rounds, a budget too small to give each
 * spot $1, or a curve with no surplus over $1.
 *
 * It is a scaled copy of Yahoo's curve and does NOT model how fewer teams and deeper rosters push money to the
 * top. In the one auction measured the top sold 25-50% above it.
 */
export function rescale(costCurve: readonly number[], teams: number | null, rounds: number | null, budget: number | null): number[] | null {
  if (!teams || !rounds || budget == null || teams <= 0 || rounds <= 0 || budget <= 0) return null
  if (costCurve.length === 0) return null
  const n = teams * rounds
  // The server floors at $1 and sorts descending; do both again so a payload that didn't can't break the sum.
  const costs = costCurve.map((c) => Math.max(1, c)).sort((a, b) => b - a).slice(0, n)
  while (costs.length < n) costs.push(1)
  const surplus = costs.reduce((s, c) => s + (c - 1), 0)
  const spare = teams * budget - n
  if (surplus <= 0 || spare < 0) return null
  const k = spare / surplus
  return costs.map((c) => Math.max(1, Math.round(1 + k * (c - 1))))
}

/**
 * Sorts a copy by blended rank (`rankOf` is computed once over the WHOLE player list, so filtering never
 * changes a player's rank or Blend $). Rows with no blend go last in either direction, tie-broken the usual way.
 */
export function sortByBlendRank<T extends AdpRow>(rows: readonly T[], rankOf: ReadonlyMap<T, Blended<T>>, dir: SortDir): T[] {
  const sign = dir === 'asc' ? 1 : -1
  return [...rows].sort((a, b) => {
    const ra = rankOf.get(a)
    const rb = rankOf.get(b)
    if (ra && rb) return sign * (ra.rank - rb.rank)
    if (ra) return -1
    if (rb) return 1
    return tieBreak(a, b)
  })
}
