import type { HistoricalGameState, HistoricalPlayerCard, HistoricalPrior, MockSeat, PlayerRef } from './api'

/*
 * Spec 053: small mapping and wording helpers for the historical draft game, kept out of the
 * components so they can be tested as plain functions. Nothing here knows about ADP: a card's
 * `rank` is last season's points rank (the API's rankingLabel says so on screen), and the
 * PlayerRef.adp field the shared draft-room types require is filled from it only so existing
 * components can sort -- it is never rendered by anything this feature composes.
 */

const STAT_ORDER = ['pts', 'reb', 'ast', 'stl', 'blk', 'to', 'tpm']
const SHORT: Record<string, string> = { pts: 'pts', reb: 'reb', ast: 'ast', stl: 'stl', blk: 'blk', to: 'tov', tpm: '3pm' }
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
}

export const statShort = (k: string) => SHORT[k] ?? k
export const statLong = (k: string) => LONG[k] ?? k

function orderIndex(k: string): number {
  const i = STAT_ORDER.indexOf(k)
  return i < 0 ? STAT_ORDER.length : i
}

/** "29.3 pts 8.1 reb 4.2 ast", only the stats the card carries (the API already dropped uncounted ones). */
export function perGameText(perGame: Record<string, number>): string {
  return Object.keys(perGame)
    .sort((a, b) => orderIndex(a) - orderIndex(b))
    .map((k) => `${perGame[k]} ${statShort(k)}`)
    .join(' ')
}

/** "1994-95 · 79 G · 29.3 pts 8.1 reb 4.2 ast", or the rookie line. */
export function priorLine(prior: HistoricalPrior | null): string {
  if (!prior) return 'no NBA stats last season'
  return [prior.seasonLabel, `${prior.games} G`, perGameText(prior.perGame)].filter((s) => s !== '').join(' · ')
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
export function cardToPlayerRef(c: HistoricalPlayerCard): PlayerRef {
  const positions = (c.positions.length > 0 ? c.positions : ['SF']) as PlayerRef['positions']
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
