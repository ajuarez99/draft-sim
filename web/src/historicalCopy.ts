import type { HistoricalGameState, HistoricalPlayerCard, HistoricalPrior, HistoricalSport, MockSeat, PlayerRef } from './api'

/*
 * Spec 053: small mapping and wording helpers for the historical draft game, kept out of the
 * components so they can be tested as plain functions. Nothing here knows about ADP: a card's
 * `rank` is last season's points rank (the API's rankingLabel says so on screen), and the
 * PlayerRef.adp field the shared draft-room types require is filled from it only so existing
 * components can sort -- it is never rendered by anything this feature composes.
 */

/**
 * The sport of a game, list row or season row. A 053 backend (still live while the web deploys first) sends no
 * `sport`, and everything it has is basketball. This is a reading of OLD payloads only: requests always carry an
 * explicit sport (api.ts), so it never stands in for a missing choice.
 */
export function sportOf(x: { sport?: HistoricalSport | null }): HistoricalSport {
  return x.sport ?? 'NBA'
}

/** Football's stat keys in display order (spec 056 N3): pinned against the backend's 13 in a test. */
export const NFL_STAT_ORDER = [
  'pass_yd', 'pass_td', 'pass_int', 'rush_yd', 'rush_td', 'rec', 'rec_yd', 'rec_td',
  'fum_lost', 'pass_2pt', 'rush_2pt', 'rec_2pt', 'st_td',
]

// Basketball keys first, then football's (spec 056). The two sets share no key, so one table serves both.
const STAT_ORDER = ['pts', 'reb', 'ast', 'stl', 'blk', 'to', 'tpm', ...NFL_STAT_ORDER]
const SHORT: Record<string, string> = {
  pts: 'pts', reb: 'reb', ast: 'ast', stl: 'stl', blk: 'blk', to: 'tov', tpm: '3pm',
  pass_yd: 'pass yd', pass_td: 'pass TD', pass_int: 'INT', rush_yd: 'rush yd', rush_td: 'rush TD',
  rec: 'rec', rec_yd: 'rec yd', rec_td: 'rec TD', fum_lost: 'fum lost',
  pass_2pt: '2pt', rush_2pt: '2pt', rec_2pt: '2pt', st_td: 'ret TD',
}
const LONG: Record<string, string> = {
  pts: 'points',
  reb: 'rebounds',
  ast: 'assists',
  stl: 'steals',
  blk: 'blocks',
  to: 'turnovers',
  tpm: '3-pointers',
  dd: 'double-doubles',
  td: 'triple-doubles',
  bonus_pt_40p: '40-point bonus',
  bonus_pt_50p: '50-point bonus',
  bonus_reb_20p: '20-rebound bonus',
  bonus_ast_15p: '15-assist bonus',
  tf: 'technical fouls',
  ff: 'flagrant fouls',
  pass_yd: 'passing yards',
  pass_td: 'passing touchdowns',
  pass_int: 'interceptions thrown',
  rush_yd: 'rushing yards',
  rush_td: 'rushing touchdowns',
  rec: 'receptions',
  rec_yd: 'receiving yards',
  rec_td: 'receiving touchdowns',
  fum_lost: 'fumbles lost',
  pass_2pt: 'passing 2-point conversions',
  rush_2pt: 'rushing 2-point conversions',
  rec_2pt: 'receiving 2-point conversions',
  st_td: 'return touchdowns',
}

export const statShort = (k: string) => SHORT[k] ?? k
export const statLong = (k: string) => LONG[k] ?? k

function orderIndex(k: string): number {
  const i = STAT_ORDER.indexOf(k)
  return i < 0 ? STAT_ORDER.length : i
}

/**
 * "29.3 pts 8.1 reb 4.2 ast", only the stats the card carries (the API already dropped uncounted ones).
 * Football's wire format keeps zero keys (a WR's pass_yd: 0), so for NFL those are hidden here.
 */
export function perGameText(perGame: Record<string, number>, sport: HistoricalSport = 'NBA'): string {
  return Object.keys(perGame)
    .filter((k) => sport !== 'NFL' || perGame[k] !== 0)
    .sort((a, b) => orderIndex(a) - orderIndex(b))
    .map((k) => `${perGame[k]} ${statShort(k)}`)
    .join(' ')
}

/** Football season totals: "3 fum lost · 2 ret TD". The three 2-point keys read as one "2pt" total. */
export function totalsText(totals: Record<string, number> | null | undefined): string {
  if (!totals) return ''
  const parts: [number, string, number][] = []
  let twoPt = 0
  for (const k of Object.keys(totals)) {
    if (!totals[k]) continue
    if (k.endsWith('_2pt')) twoPt += totals[k]
    else parts.push([orderIndex(k), `${totals[k]} ${statShort(k)}`, 0])
  }
  if (twoPt > 0) parts.push([orderIndex('pass_2pt'), `${twoPt} 2pt`, 0])
  return parts
    .sort((a, b) => a[0] - b[0])
    .map((p) => p[1])
    .join(' · ')
}

/**
 * "1994-95 · 79 G · 29.3 pts 8.1 reb 4.2 ast", or the rookie line. A football rookie with a known draft pick reads
 * "rookie · NFL pick #5"; anyone else without a line says so ("no NBA/NFL stats last season").
 */
export function priorLine(prior: HistoricalPrior | null, sport: HistoricalSport = 'NBA', rookiePick: number | null = null): string {
  if (!prior) {
    if (sport === 'NFL' && rookiePick != null) return `rookie · NFL pick #${rookiePick}`
    return `no ${sport} stats last season`
  }
  return [prior.seasonLabel, `${prior.games} G`, perGameText(prior.perGame, sport), sport === 'NFL' ? totalsText(prior.totals) : '']
    .filter((x) => x !== '')
    .join(' · ')
}

/** "assists 94.6% of box scores". One decimal, as the spec's own example. */
export function coverageText(stat: string, share: number | undefined): string {
  if (share == null) return statLong(stat)
  return `${statLong(stat)} ${(share * 100).toFixed(1)}% of box scores`
}

/**
 * The card as the shared rooms' PlayerRef. `sleeperId` is NOT a Sleeper id (the historical id, prefixed so it can
 * never be mistaken for one); rooms showing these players pass noPhoto/hidePhotos so no headshot is requested.
 */
export function cardToPlayerRef(c: HistoricalPlayerCard, sport: HistoricalSport = 'NBA'): PlayerRef {
  // Only a fallback for a card with no position: football's own no-position default (WR), never basketball's SF.
  const fallback = sport === 'NFL' ? 'WR' : 'SF'
  const positions = (c.positions.length > 0 ? c.positions : [fallback]) as PlayerRef['positions']
  return {
    id: c.id,
    sleeperId: `h${c.id}`,
    name: c.name,
    position: positions![0],
    positions,
    team: c.team,
    adp: c.rank,
    positionalRank: c.positionalRank,
  }
}

/** Seats for the shared turn indicator and board: you, then numbered computer teams. */
export function historicalSeats(g: Pick<HistoricalGameState, 'teams' | 'userSlot'>): MockSeat[] {
  return Array.from({ length: g.teams }, (_, i) => {
    const slot = i + 1
    return {
      slot,
      type: slot === g.userSlot ? 'USER' : 'BOT',
      managerId: null,
      manager: teamName(slot, g.userSlot),
      avatarId: null,
    } as MockSeat
  })
}

export const teamName = (slot: number, userSlot: number) => (slot === userSlot ? 'You' : `Team ${slot}`)

export function ordinal(n: number): string {
  const v = n % 100
  if (v >= 11 && v <= 13) return `${n}th`
  return `${n}${({ 1: 'st', 2: 'nd', 3: 'rd' } as Record<number, string>)[n % 10] ?? 'th'}`
}

export const pts1 = (n: number) => n.toLocaleString('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
