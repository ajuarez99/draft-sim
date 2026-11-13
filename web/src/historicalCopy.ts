import type {
  HistoricalGameState,
  HistoricalGameSummary,
  HistoricalOutcome,
  HistoricalPlayerCard,
  HistoricalPrior,
  HistoricalResult,
  HistoricalSport,
  MockSeat,
  PlayerRef,
} from './api'

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
export const pts2 = (n: number) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/**
 * Two scores of one matchup, as text. Winners are decided on hundredths, so two scores that print the same at one
 * decimal while one side won would read as an unexplained tie: print that matchup at two decimals instead (CR-06).
 */
export function pointsPair(a: number, b: number): [string, string] {
  if (a !== b && pts1(a) === pts1(b)) return [pts2(a), pts2(b)]
  return [pts1(a), pts1(b)]
}

/**
 * Spec 063: the ONE test for "this result was stored before playoffs existed". A result with no `format` (an old
 * stored game, or a 062-shaped response from an old backend) renders exactly as it did, plus one line. Nothing
 * else in the web app asks this question another way.
 */
export function isOldFormat(result: Pick<HistoricalResult, 'format'>): boolean {
  return result.format == null
}

/**
 * The finish headline for a game played under the playoff format. NO_PLAYOFFS (and an outcome this build does not
 * know) reads as the plain standings place; MISSED_PLAYOFFS says where the team ended up.
 */
export function outcomeHeadline(outcome: HistoricalOutcome | null | undefined, place: number, teams: number): string {
  switch (outcome) {
    case 'CHAMPION':
      return 'Champion'
    case 'LOST_FINAL':
      return 'You lost the final'
    case 'LOST_SEMIFINAL':
      return 'You lost in the semifinal'
    case 'LOST_ROUND_ONE':
      return 'You lost in round one'
    case 'MISSED_PLAYOFFS':
      return `You missed the playoffs (${ordinal(place)} of ${teams})`
    default:
      return `You finished ${ordinal(place)} of ${teams}`
  }
}

/**
 * "Would the hindsight roster have won?" in one sentence. The answer leads; the record follows. Third person,
 * because it is a roster nobody drafted.
 */
export function hindsightSeasonVerdict(
  s: { wins: number; losses: number; ties: number; regularSeasonRank: number; finish: number; outcome: HistoricalOutcome },
  teams: number,
): string {
  const record = `${s.wins}-${s.losses}${s.ties > 0 ? `-${s.ties}` : ''}`
  const seed = `${record}, ${ordinal(s.regularSeasonRank)} after the regular season`
  switch (s.outcome) {
    case 'CHAMPION':
      return `Yes. It went ${seed}, and won the title.`
    case 'LOST_FINAL':
      return `No. It went ${seed}, and lost the final.`
    case 'LOST_SEMIFINAL':
      return `No. It went ${seed}, and lost in the semifinal.`
    case 'LOST_ROUND_ONE':
      return `No. It went ${seed}, and lost in round one.`
    case 'MISSED_PLAYOFFS':
      return `No. It went ${record} and missed the playoffs (${ordinal(s.finish)} of ${teams}).`
    default:
      return s.finish === 1
        ? `Yes. It went ${record} and finished 1st of ${teams}.`
        : `No. It went ${record} and finished ${ordinal(s.finish)} of ${teams}.`
  }
}

/**
 * The status words on the past-games list. An old game's number is a standings rank and a new game's is a playoff
 * finish, so the two are never printed as the same kind of thing (spec 063 FR-021).
 */
export function summaryStatus(g: Pick<HistoricalGameSummary, 'status' | 'finish' | 'formatVersion' | 'outcome'>): string {
  if (g.status === 'DRAFTING') return 'Drafting'
  if (g.status !== 'COMPLETE') return 'Abandoned'
  if (g.finish == null) return 'Complete'
  if (g.formatVersion == null) return `Finished ${ordinal(g.finish)} (old format)`
  switch (g.outcome) {
    case 'CHAMPION':
      return 'Champion'
    case 'LOST_FINAL':
      return 'Lost the final'
    case 'LOST_SEMIFINAL':
      return 'Lost in the semifinal'
    case 'LOST_ROUND_ONE':
      return 'Lost in round one'
    case 'MISSED_PLAYOFFS':
      return `Missed the playoffs (${ordinal(g.finish)})`
    case 'NO_PLAYOFFS':
      return `Finished ${ordinal(g.finish)} (no playoffs)`
    default:
      return `Finished ${ordinal(g.finish)}`
  }
}

const WORDS = ['no', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve']
const word = (n: number) => WORDS[n] ?? String(n)
const times = (n: number) => (n === 1 ? 'once' : n === 2 ? 'twice' : `${word(n)} times`)

/**
 * Spec 063 PR-15: a regular season that is not a whole number of round robins is lopsided, and the results say so.
 * Computed from the real week count and team count, never written as a literal. Null when every team plays every
 * other the same number of times (nothing to warn about). The circle-method schedule repeats the same weekly
 * cycle, so the first `rest` weeks of the cycle are the ones played one extra time.
 */
export function scheduleNote(regularSeasonWeeks: number, teams: number): string | null {
  if (teams < 2 || regularSeasonWeeks < 1) return null
  const opponents = teams - 1
  const cycle = teams % 2 === 0 ? teams - 1 : teams
  if (regularSeasonWeeks < opponents) {
    return `${regularSeasonWeeks} weeks is shorter than one full round of ${cycle}, so you play ${word(regularSeasonWeeks)} of the other ${word(opponents)} teams once and skip the rest.`
  }
  const base = Math.floor(regularSeasonWeeks / cycle)
  const extra = regularSeasonWeeks % cycle
  if (extra === 0) return null
  const fewer = opponents - extra
  return `${regularSeasonWeeks} weeks over ${/^(8|11|18)/.test(String(cycle)) ? 'an' : 'a'} ${cycle}-week cycle: you play ${word(extra)} ${extra === 1 ? 'team' : 'teams'} ${times(base + 1)} and ${word(fewer)} ${times(base)}.`
}
