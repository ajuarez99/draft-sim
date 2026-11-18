import { NEUTRAL_SCORING, howToPlaySteps, ordinal } from './historicalCopy'
import type { HistoricalSport } from './api'

// Spec 074 T021: every word on the Redraft pages that design.md specifies, in one module. The React pages
// (Phase 5) and the static bodies in seasonPageHtml.ts (run in Node by vite.config.ts) both read it, which is
// what keeps the delivered HTML and the mounted page from drifting. PURE: no React, no browser global, and
// only type imports from api.ts, so Node can load it.
//
// Rules this module keeps (guarded by redraftCopy.test.ts):
//  - no league wording (leagueWords.ts), and no "Time Machine" (the old name, spec 074 FR-003);
//  - no string states a search volume, a ranking or a traffic gain (FR-022);
//  - nobody says "hindsight" on a season page (FR-006a): the game uses that word for a different thing;
//  - what "points" are comes from the server's `pointsNote` (data), never from a second copy here
//    (tasks.md T021e). The sentences that remain here were checked against SeasonReplay.scorePlayerWeeks (best
//    single game per week) and NflHistoricalSport (QB/RB/WR/TE, full PPR): see the build report.

export const brand = 'Ball Knowers Redraft'

export const sportName = (s: HistoricalSport) => s
export const sportWord = (s: HistoricalSport) => (s === 'NBA' ? 'basketball' : 'football')
export const sportWordCap = (s: HistoricalSport) => (s === 'NBA' ? 'Basketball' : 'Football')
export const plural = (n: number, one: string, many: string) => (n === 1 ? one : many)

// ---- Shared chrome ------------------------------------------------------------------------------------

export const homeAria = `${brand}, home`
export const signInLink = 'Sign in'
export const skipLink = 'Skip to content'

export const footNavLabel = 'Redraft pages'
export const footHome = 'Home'
export const footDaily = 'Game of the day'
export const footNba = 'NBA seasons'
export const footNfl = 'NFL seasons'
export const footNote = 'Free. No password.'
export const footSeasons = (s: HistoricalSport) => (s === 'NBA' ? footNba : footNfl)

// ---- Season page --------------------------------------------------------------------------------------

export const seasonOver = (s: HistoricalSport) => `Fantasy ${sportWord(s)} · Ball Knowers`
export const seasonH1 = (label: string, s: HistoricalSport) => `${label} ${s} Redraft`
export const seasonTagline = (label: string, s: HistoricalSport) =>
  `Draft a team from the real ${label} ${s} season, knowing only what players did the year before. Then see how your picks held up.`
export const draftButton = 'Draft this season'
export const draftStarting = 'Starting…'
export const draftResume = (label: string) => `Resume your ${label} draft`
export const seasonFacts = (teams: number, rounds: number) => `${teams} teams · ${rounds} rounds · best ball`
/** The guest rule, said once: the page's state line, the static line and the limit panel all say this (review-fixes E). */
export const stateLineGuest =
  'Free, no sign-up. The game of the day is free every day. Guests also get one season draft of their choice; an abandoned one counts.'
/** The results page's line for a season the player picked (HistoricalGameAward.notCounted): said as a rule, not a failure. */
export const notCountedNote =
  "Seasons you pick don't count toward achievements, your daily streak, or your best and average draft score."
export const stateLineSignedIn = notCountedNote
export const yesterdayError = "Yesterday's seasons didn't load."
export const stateLineStatic = stateLineGuest
export const theDraft = 'The draft'
export const benchLabel = (n: number) => `Bench ${n}`
export const goingInNote = (rankingLabel: string) =>
  `"Going in" is our own pre-season ranking (${rankingLabel}), not where people really drafted players that year.`
// Amended after analysis (T021b, FR-006b, PR-13): what the going-in rank leaves out. Checked against
// HistoricalBoardBuilder.buildRanked (2026-11-17): a first-season player is placed by his real draft pick only when
// he has no prior line at all. Basketball places every one whose pick is known; football only those drafted in the
// first rounds (historical-nfl.rookie-max-round, hand-set). Anyone else with no prior stats sits in the unranked tail,
// which has no going-in rank. So the sentence differs by sport, and names no round number (the config may change).
export const rookieNote = (s: HistoricalSport) =>
  s === 'NBA'
    ? 'First-year players with a known NBA draft pick are ranked by that pick, not by stats. Other first-year players have no pre-season rank.'
    : 'Rookies drafted early in the NFL draft are ranked by their pick, not by stats. Later-round and undrafted rookies have no pre-season rank.'
export const missedSeasonNote = (label: string) =>
  `Only players who appeared in ${label} are on the board, so a player who missed the whole season is not listed.`

export const spoilerH2 = (label: string) => `Spoilers: how ${label} played out`
export const spoilerHint = 'Top finishers, busts, steals and the dream roster. Draft first if you want the surprise.'

export const topHeading = (n: number) => `Top ${n} finishers`
export const topSub = "By season points in Redraft's scoring."
/** `ranked`: how many players have a pre-season rank (SeasonPage.rankedPlayers), so "4th" has a denominator (D3). */
export const rankedGoingIn = (goingIn: number | null, ranked: number) =>
  goingIn == null ? 'No pre-season rank' : `Going in ${ordinal(goingIn)} of ${ranked} ranked`
export const tieText = (rank: number) => `T${rank}`
/** Read by a screen reader after the visible "T5"; an aria-label on a plain span is ignored, so it is hidden text (C1). */
export const tieAria = (rank: number) => ` tied for ${ordinal(rank)}`
export const ptsUnit = ' pts'
export const weeksPlayed = (n: number) => `${n} ${plural(n, 'week', 'weeks')} played`

export const bustsHeading = 'Biggest busts'
export const bustsSub = (teams: number) =>
  `Ranked in our pre-season top ${teams * 5} (the first five rounds), then finished far lower. A low weeks-played count can mean an injury.`
export const stealsHeading = 'Biggest steals'
export const stealsSub = (teams: number) =>
  `Finished in the season's top ${teams * 5} after being ranked much lower going in.`
export const moverGoingIn = 'Going in'
export const moverGoingInOf = (rank: number, ranked: number) => `${ordinal(rank)} of ${ranked} ranked`
export const moverFinished = 'Finished'
export const moverFinishedOf = (finish: number, board: number) => `${ordinal(finish)} of ${board}`
export const moverDelta = (goingIn: number, finish: number) => {
  const n = Math.abs(finish - goingIn)
  const word = plural(n, 'spot', 'spots')
  return finish > goingIn ? `Fell ${n} ${word}` : `Rose ${n} ${word}`
}
export const bustsShort = (n: number, teams: number) =>
  `Only ${n} ${plural(n, 'player', 'players')} in our pre-season top ${teams * 5} finished lower than ranked.`
export const bustsEmpty = (teams: number) => `Nobody in our pre-season top ${teams * 5} finished lower than ranked.`
export const stealsShort = (n: number, teams: number) =>
  `Only ${n} ${plural(n, 'player', 'players')} in the season's top ${teams * 5} had been ranked lower going in.`
export const stealsEmpty = (teams: number) =>
  `Nobody in the season's top ${teams * 5} had been ranked lower going in.`
export const unrankedLine = (n: number, teams: number) =>
  `${n} more ${plural(n, 'player', 'players')} finished in the top ${teams * 5} with no pre-season rank, so ${plural(n, 'he is', 'they are')} not listed here.`

export const dreamHeading = 'The dream roster'
// Tasks.md T021a (PR-14): greedy is not proven optimal, and the page does not claim it.
export const dreamSub =
  'Built by taking the top scorer each lineup spot allows, ignoring draft order. It may not be the single best possible roster, and nobody could have drafted all of it.'
// Only the best-ball total line is kept (the plan's dreamRosterPoints is TeamScorer.season, a team total).
export const dreamTotal = (pts: string) => `Team total: ${pts} points, counting the best lineup each week.`
export const positionFold = 'Top 5 at each position'
export const positionFullWord: Record<string, string> = {
  PG: 'Point guards', SG: 'Shooting guards', SF: 'Small forwards', PF: 'Power forwards', C: 'Centers',
  QB: 'Quarterbacks', RB: 'Running backs', WR: 'Wide receivers', TE: 'Tight ends',
}

export const aboutHeading = "About this season's data"
export const excludedHeading = (label: string) => `Not counted in ${label}`
export const countedHeading = (label: string) => `Scoring in ${label}`
/** In "About this season's data", after the server's `pointsNote` and the going-in sentence (both in "The draft"): what "finished" means. */
export const howRanksWork = (board: number) =>
  `Finished is a player's rank in season points among all ${board} players on our board.`
export const footballPositionsOnly = 'Quarterbacks, running backs, wide receivers and tight ends only.'

export const prevSeason = (slug: string) => `Previous season: ${slug}`
export const nextSeason = (slug: string) => `Next season: ${slug}`
export const allSeasons = (s: HistoricalSport) => `All ${s} seasons`
/** The prev/next group's name: it holds neighbours, not the whole list (C9). */
export const otherSeasons = (s: HistoricalSport) => `Other ${s} seasons`
export const draftAgain = (label: string) => `Draft ${label}`
export const seasonLoading = (label: string) => `Loading the ${label} season…`
export const seasonError = "This season didn't load."
/** The line a static page shows beside the controls that need scripts (C11). */
export const needsScript = 'Drafting needs JavaScript turned on.'
/** The sign-in form's words: one source for the mounted form and the static home (D10). */
export const signInLabel = 'Your Sleeper username'
export const signInPlaceholder = 'Username or profile link'
export const signInContinue = 'Continue'
export const tryAgain = 'Try again'

// Guest limit panel (design.md §1)
export const limitHeading = "You've used your free season draft"
export const limitBody =
  'Guests get one season of their choice, and an abandoned one counts. Sign in with your Sleeper username to draft any season.'
export const limitNote = 'No password. Games from this browser move into your history when you sign in.'
export const playTodays = "Play today's game"

// ---- Sport index --------------------------------------------------------------------------------------

export const sportIndexH1 = (s: HistoricalSport) => `${s} Redraft: every past season`
export const sportIndexTagline = (first: string, last: string) =>
  `Pick a season from ${first} to ${last}. Each page shows who finished on top, who busted and who was a steal (folded, so you can draft first), and lets you draft it.`
export const newestSeason = 'Newest season'
export const decadeSeasons = (n: number) => `${n} ${plural(n, 'season', 'seasons')}`
export const namingRule = (s: HistoricalSport) =>
  s === 'NBA'
    ? 'Seasons are named by both years: 2016-17 is the season that started in fall 2016.'
    : 'Seasons are named by the year they started: 2007 is the season played in fall 2007.'
export const eraNote = (s: HistoricalSport) =>
  s === 'NBA'
    ? 'Older seasons count fewer stats, because the NBA was not tracking them all yet. Each season page says which.'
    : 'Each season page lists its data notes.'
export const seasonsLoading = 'Loading seasons…'
export const seasonsError = "The season list didn't load."
export const seasonsEmpty = (s: HistoricalSport) => `No ${s} seasons are available yet.`
/** Hidden text after a year pill, so a link list reads "2016-17 NBA Redraft", not a bare year (C10). */
export const yearPillSuffix = (s: HistoricalSport) => ` ${s} Redraft`

// ---- Daily --------------------------------------------------------------------------------------------

export const dailyOver = 'Ball Knowers'
export const dailyH1 = 'Daily Redraft: a past season every day'
export const dailyTagline =
  'Every day, everyone gets the same past NBA season and the same past NFL season to draft, from a random slot. New games at midnight Eastern.'
export const dailyCardStatic = "Today's season shows when the page loads"
/** The mounted page's loading line: the static "shows when the page loads" would be wrong once it has loaded (D6). */
export const dailyCardLoading = 'Loading…'
export const dailyGuestStreak = 'Sign in to keep a daily streak.'
export const yesterdayHeading = "Yesterday's seasons"
export const yesterdayRow = (s: HistoricalSport, day: string) => `${sportWordCap(s)} · ${day}`
/** Same words as the shared result's link, so a list of links each says which season (C10). */
export const yesterdayLink = (label: string) => `How ${label} played out`
export const yesterdayNone = (s: HistoricalSport) => `No ${sportWord(s)} game was dealt yesterday.`
export const yesterdayStatic = "Yesterday's seasons show when the page loads."
export const dailyRollover = 'A new game is ready. Loading it…'
export const dailyNone = 'No game of the day is on offer yet.'
export const dailyError = "Today's games didn't load."
export const dailyPlay = 'Play'

// ---- Home ---------------------------------------------------------------------------------------------

export const homeH1 = 'Redraft a past NBA or NFL season'
export const homeTagline =
  'Draft a team from a real past season using only what players did the year before, then watch the year play out.'
export const homeTitle = 'Ball Knowers Redraft: Draft a Past NBA or NFL Season'
export const homeDescription =
  'Redraft a real past NBA or NFL season: pick a team using only what players did the year before, then watch the year play out. Free, no sign-up.'
export const dailyHeading = 'Game of the day'
export const dailyAbout = 'About the daily game'
export const pickASeason = 'Pick a season'
export const sportTile = (s: HistoricalSport) => sportWordCap(s)
export const sportTileRange = (first: string, last: string) => `${first} to ${last}`
export const sportTileCount = (n: number) => `${n} ${plural(n, 'season', 'seasons')} to pick from`
export const howHeading = 'How to play'
/** One source with historicalCopy.howToPlaySteps (the older callers pass their own sport sentence). */
export const howSteps: { title: string; body: string }[] = howToPlaySteps(NEUTRAL_SCORING)
export const keepHeading = 'Keep your games'
export const keepBody =
  'Sign in with your Sleeper username to keep your history, play any season, and earn achievements from the game of the day.'
export const keepNote = 'No password. This site never asks Sleeper for anything private.'
// Tasks.md T021d (PR-27): the site keeps one random id in this browser. The older "no cookies" line stays true.
export const analyticsLine =
  'We count page visits and a few actions, like signing in or sharing a result, ourselves. The counts hold no username, address or id of yours, and we set no cookies. We keep a random id in this browser so your games stay yours.'
/** The results page of a game of the day that a sign-in left behind (the Sleeper id had already played that day): it earns nothing. */
export const awardLeftBehind =
  "This game of the day stayed in this browser when you signed in, because you had already played that day's game in that sport. It earns nothing here."
export const savedHere = 'Saved in this browser only. Sign in to keep them.'
export const chosenPill = 'Chosen season'
export const claimLine = (moved: number) =>
  `${moved} ${plural(moved, 'game', 'games')} from this browser ${plural(moved, 'is', 'are')} now in your history.`
export const claimLeftBehind = (n: number) =>
  `${n} ${plural(n, 'game', 'games')} of the day stayed behind, because you had already played that day's game in that sport.`
export const keepResultHeading = 'Keep this result'
export const keepResultBody = 'Sign in with your Sleeper username to save this result beyond this browser.'

// ---- Shared result (Phase 6 reads these) --------------------------------------------------------------

export const sharedKicker = (label: string, s: HistoricalSport, tail: string | null) =>
  `Shared result · ${label} ${s} Redraft${tail ? ` · ${tail}` : ''}`
export const sharedDailyTail = (day: string) => `Game of the day, ${day}`
export const sharedChosenTail = 'Chosen season'
/** The share text's own line: it goes to somebody outside the page, so it says what a chosen season is not. */
export const shareChosenLine = 'Chosen season, not the game of the day'
export const sharedFinish = (place: number, teams: number) => `This team finished ${ordinal(place)} of ${teams}`
export const sharedPlayToday = (s: HistoricalSport) => `Play today's ${sportWord(s)} game`
export const sharedHowPlayed = (label: string) => `How ${label} played out`
export const sharedReduced = "The picks and lineups show tomorrow, once today's game is over."
export const sharedNoneHeading = 'No finished game here'
// The public route answers the same for a game that does not exist and one that is not finished, so this says only what is true of both.
export const sharedNoneBody = 'There is no finished game at this address.'
export const sharedDrafterSuffix = ' · this team'

// ---- Not found ----------------------------------------------------------------------------------------

export const notFoundTitle = `Page not found | ${brand}`
export const notFoundH1 = 'Nothing at this address'
export const notFoundBody = "It may be a season we don't offer, or a link with a typo."
export const notFoundNavLabel = 'Where to go'
export const notFoundLinks = { home: 'Home', daily: "Today's game", nba: 'NBA seasons', nfl: 'NFL seasons' }

// ---- Head tags (per page kind) ------------------------------------------------------------------------

export const seasonTitle = (label: string, s: HistoricalSport) => `${label} Fantasy ${sportWordCap(s)} Redraft | Ball Knowers`
/**
 * No player names: the page folds the outcome as a spoiler, and a search snippet should not give it away (review-fixes E;
 * this replaces the contracts/pages.md wording that named the top finisher and the biggest steal). The label is in every
 * one, so descriptions stay unique.
 */
export const seasonDescription = (label: string, s: HistoricalSport) =>
  `Redraft the ${label} ${s} season using only what players did the year before, then see who finished on top. Free, no sign-up for your first season.`
export const sportIndexTitle = (s: HistoricalSport, first: string, last: string) =>
  `${s} Redraft: Past Seasons ${first} to ${last} | Ball Knowers`
export const sportIndexDescription = (s: HistoricalSport, first: string, last: string, count: number) =>
  count === 1
    ? `Redraft the only past ${s} season, ${first}, using only what players did the year before. Free, no sign-up for your first season.`
    : `Redraft any of ${count} past ${s} seasons, ${first} to ${last}, using only what players did the year before. Free, no sign-up for your first season.`
export const dailyTitle = 'Daily Redraft: A Past NBA or NFL Season | Ball Knowers'
export const dailyDescription =
  'Every day, everyone gets the same past NBA season and the same past NFL season to redraft. New games at midnight Eastern. Free every day, no sign-up.'
export const appShellTitle = brand
export const appShellDescription = homeDescription
export const imageAlt = 'Ball Knowers Redraft: draft a team from a real past NBA or NFL season and watch it play out.'
