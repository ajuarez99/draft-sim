import { describe, expect, it } from 'vitest'
import { LEAGUE_WORDS } from './leagueWords'
import * as copy from './redraftCopy'

// Spec 074 T021: the Redraft copy module's own guards. Every export is rendered once with sample arguments, so
// a new string cannot slip past the banned-words check: an export with no sample here fails the test by name.
const SAMPLE_ARGS: Record<string, unknown[]> = {
  sportName: ['NBA'], sportWord: ['NBA'], sportWordCap: ['NFL'], plural: [2, 'a', 'b'], footSeasons: ['NBA'],
  seasonOver: ['NFL'], seasonH1: ['2016-17', 'NBA'], seasonTagline: ['2016-17', 'NBA'], draftResume: ['2016-17'],
  seasonFacts: [12, 13], benchLabel: [4], goingInNote: ["ranked by last season's points"], missedSeasonNote: ['2016-17'],
  spoilerH2: ['2016-17'], topHeading: [12], rankedGoingIn: [4, 380], tieText: [3], tieAria: [3], moverGoingInOf: [4, 380], rookieNote: ['NFL'],
  yesterdayLink: ['2016-17'], yearPillSuffix: ['NBA'], otherSeasons: ['NBA'], weeksPlayed: [19],
  bustsSub: [12], stealsSub: [12], moverFinishedOf: [131, 412], moverDelta: [2, 131], bustsShort: [3, 12],
  bustsEmpty: [12], stealsShort: [1, 12], stealsEmpty: [12], unrankedLine: [2, 12], dreamTotal: ['3,000.0'],
  excludedHeading: ['1976-77'], countedHeading: ['2007'], howRanksWork: [412],
  prevSeason: ['2015-16'], nextSeason: ['2017-18'], allSeasons: ['NFL'], draftAgain: ['2007'], seasonLoading: ['2007'],
  sportIndexH1: ['NBA'], sportIndexTagline: ['1970-71', '2024-25'], decadeSeasons: [10], namingRule: ['NBA'],
  eraNote: ['NBA'], seasonsEmpty: ['NFL'], yesterdayRow: ['NBA', 'Mon'], yesterdayNone: ['NFL'], sportTile: ['NBA'],
  sportTileRange: ['1970-71', '2024-25'], sportTileCount: [56], claimLine: [2], claimLeftBehind: [1],
  sharedKicker: ['2016-17', 'NBA', 'Chosen season'], sharedDailyTail: ['Mon'], sharedFinish: [3, 12],
  sharedPlayToday: ['NFL'], sharedHowPlayed: ['2007'], seasonTitle: ['2016-17', 'NBA'],
  seasonDescription: ['2016-17', 'NBA'], sportIndexTitle: ['NBA', '1970-71', '2024-25'],
  sportIndexDescription: ['NFL', '2000', '2025', 26],
}

function allStrings(): string[] {
  const out: string[] = []
  for (const [name, v] of Object.entries(copy)) {
    if (typeof v === 'string') out.push(v)
    else if (typeof v === 'function') {
      const args = SAMPLE_ARGS[name]
      if (!args) throw new Error(`redraftCopy.test: add sample arguments for the new export "${name}"`)
      const r = (v as (...a: unknown[]) => unknown)(...args)
      if (typeof r === 'string') out.push(r)
    } else if (Array.isArray(v)) out.push(...v.flatMap((s: { title: string; body: string }) => [s.title, s.body]))
    else if (v && typeof v === 'object') out.push(...Object.values(v as Record<string, string>))
  }
  return out
}

describe('redraftCopy', () => {
  const strings = allStrings()

  it('collects a non-trivial number of strings (the guard is not reading nothing)', () => {
    expect(strings.length).toBeGreaterThan(120)
  })

  it('has no league wording', () => {
    expect(strings.filter((s) => LEAGUE_WORDS.test(s))).toEqual([])
  })

  it('never says "Time Machine" (spec 074 FR-003)', () => {
    expect(strings.filter((s) => /time\s*machine/i.test(s))).toEqual([])
  })

  it('never says "hindsight": the game uses the word for a different thing (FR-006a)', () => {
    expect(strings.filter((s) => /hindsight/i.test(s))).toEqual([])
  })

  it('states no search volume, ranking or traffic gain (FR-022)', () => {
    expect(strings.filter((s) => /\b(google|search volume|searches|traffic|#1 ranked|ranks #1)\b/i.test(s))).toEqual([])
  })

  it('carries the amended sentences verbatim (tasks.md T021)', () => {
    expect(copy.dreamSub).toBe(
      'Built by taking the top scorer each lineup spot allows, ignoring draft order. It may not be the single best possible roster, and nobody could have drafted all of it.',
    )
    expect(copy.missedSeasonNote('2016-17')).toBe(
      'Only players who appeared in 2016-17 are on the board, so a player who missed the whole season is not listed.',
    )
    expect(copy.weeksPlayed(19)).toBe('19 weeks played')
    expect(copy.weeksPlayed(1)).toBe('1 week played')
    expect(copy.sharedReduced).toBe("The picks and lineups show tomorrow, once today's game is over.")
    expect(copy.analyticsLine).toBe(
      'We count page visits and a few actions, like signing in or sharing a result, ourselves. The counts hold no username, address or id of yours, and we set no cookies. We keep a random id in this browser so your games stay yours.',
    )
  })

  it('says the guest rule once, in one wording, wherever it appears (review-fixes E)', () => {
    expect(copy.stateLineGuest).toBe(
      'Free, no sign-up. The game of the day is free every day. Guests also get one season draft of their choice; an abandoned one counts.',
    )
    expect(copy.stateLineStatic).toBe(copy.stateLineGuest)
    expect(copy.notCountedNote).toBe(
      "Seasons you pick don't count toward achievements, your daily streak, or your best and average draft score.",
    )
    expect(copy.stateLineSignedIn).toBe(copy.notCountedNote)
    expect(copy.limitBody).toBe(
      'Guests get one season of their choice, and an abandoned one counts. Sign in with your Sleeper username to draft any season.',
    )
    expect(copy.limitNote).toBe('No password. Games from this browser move into your history when you sign in.')
    expect(copy.keepResultBody).toBe('Sign in with your Sleeper username to save this result beyond this browser.')
    expect(copy.keepBody).toBe(
      'Sign in with your Sleeper username to keep your history, play any season, and earn achievements from the game of the day.',
    )
  })

  it('the rookie sentence is true per sport: basketball places every known pick, football only early rounds (HistoricalBoardBuilder)', () => {
    expect(copy.rookieNote('NBA')).toContain('known NBA draft pick')
    expect(copy.rookieNote('NFL')).toMatch(/early in the NFL draft/)
    expect(copy.rookieNote('NFL')).toMatch(/Later-round and undrafted rookies/)
    expect(copy.rookieNote('NBA')).not.toBe(copy.rookieNote('NFL'))
    // V-08d: no reason is given for a missing rank anywhere
    expect(copy.rookieNote('NBA')).not.toMatch(/season before|no stats/)
    expect(copy.rookieNote('NFL')).not.toMatch(/season before|no stats/)
  })

  it('the data note about finishing is self-contained and the missing-game copy is true for a missing id too', () => {
    expect(copy.howRanksWork(412)).toBe("Finished is a player's rank in season points among all 412 players on our board.")
    expect(copy.howRanksWork(412)).not.toMatch(/those points/)
    expect(copy.sharedNoneHeading).toBe('No finished game here')
    expect(copy.sharedNoneBody).toBe('There is no finished game at this address.')
  })

  it('"going in" has a denominator and the unranked line gives no wrong reason', () => {
    expect(copy.rankedGoingIn(4, 380)).toBe('Going in 4th of 380 ranked')
    expect(copy.rankedGoingIn(null, 380)).toBe('No pre-season rank')
    expect(copy.moverGoingInOf(4, 380)).toBe('4th of 380 ranked')
    expect(copy.unrankedLine(2, 12)).not.toMatch(/no stats the season before/)
    expect(copy.unrankedLine(2, 12)).toContain('with no pre-season rank')
  })

  it('the tie marker keeps "T5" visible and says the words in hidden text (an aria-label on a span is ignored)', () => {
    expect(copy.tieText(5)).toBe('T5')
    expect(copy.tieAria(5).trim()).toBe('tied for 5th')
  })

  it('movers read the right way round: a bust falls, a steal rises', () => {
    expect(copy.moverDelta(2, 150)).toBe('Fell 148 spots')
    expect(copy.moverDelta(200, 3)).toBe('Rose 197 spots')
    expect(copy.moverDelta(5, 6)).toBe('Fell 1 spot')
  })

  it('head tags carry the exact review-fixes E wording, and a season description names no player (the outcome is a spoiler)', () => {
    expect(copy.seasonTitle('2016-17', 'NBA')).toBe('2016-17 Fantasy Basketball Redraft | Ball Knowers')
    expect(copy.seasonTitle('2007', 'NFL')).toBe('2007 Fantasy Football Redraft | Ball Knowers')
    expect(copy.seasonDescription('2007', 'NFL')).toBe(
      'Redraft the 2007 NFL season using only what players did the year before, then see who finished on top. Free, no sign-up for your first season.',
    )
    expect(copy.seasonDescription('2016-17', 'NBA')).not.toBe(copy.seasonDescription('2007', 'NFL'))
    expect(copy.sportIndexTitle('NBA', '1970-71', '2024-25')).toBe('NBA Redraft: Past Seasons 1970-71 to 2024-25 | Ball Knowers')
    expect(copy.sportIndexDescription('NFL', '2000', '2025', 26)).toBe(
      'Redraft any of 26 past NFL seasons, 2000 to 2025, using only what players did the year before. Free, no sign-up for your first season.',
    )
    expect(copy.sportIndexDescription('NFL', '2025', '2025', 1)).toBe(
      'Redraft the only past NFL season, 2025, using only what players did the year before. Free, no sign-up for your first season.',
    )
    expect(copy.dailyTitle).toBe('Daily Redraft: A Past NBA or NFL Season | Ball Knowers')
    expect(copy.dailyDescription).toBe(
      'Every day, everyone gets the same past NBA season and the same past NFL season to redraft. New games at midnight Eastern. Free every day, no sign-up.',
    )
  })
})
