import type { Sport } from './draft'
import { apiFetch, json } from './http'

// --- specs/004-ffwrapped-feature-parity US5: Weekly report ---

export type WeeklySide = {
  rosterId: number
  teamName: string
  /** Sleeper username, shown under the team name; null for an unowned roster. */
  username: string | null
  avatarId: string | null
  record: string
  points: number
  /** WeeklyReportService.Side.isMe: this roster's manager is the caller's manager (false when signed out). */
  isMe?: boolean
}

export type WeeklyMatchup = { home: WeeklySide; away: WeeklySide }

export type WeeklyPerformer = {
  playerId: string
  playerName: string
  position: string
  teamName: string
  points: number
  /** Spotlight amendment: the player's NFL team, that week's opponent and home/away, and the starting manager's avatar. All null when unknown, never guessed. */
  team: string | null
  opponent: string | null
  isAway: boolean | null
  avatarId: string | null
}

export type WeeklyAward = { kind: string; teamName: string; detail: string }

/**
 * An award that could not be computed, and why. Rendered rather than dropped:
 * a missing award is otherwise indistinguishable from nobody qualifying.
 */
export type WeeklyOmittedAward = { kind: string; reason: string }

/**
 * One player's one game: what they scored, which night, against whom.
 *
 * `opponent` and `isAway` are nullable because a payload without them still
 * describes a real game -- the page shows the night and says the opponent is
 * unknown, rather than guessing one.
 */
export type WeeklyNightPerformance = {
  playerId: string
  playerName: string
  position: string
  teamName: string
  points: number
  date: string
  opponent?: string | null
  isAway?: boolean | null
}

/** One player's whole fantasy week, across every game they played. */
export type WeeklyPlayerWeek = {
  playerId: string
  playerName: string
  position: string
  teamName: string
  totalPoints: number
  gamesPlayed: number
}

/**
 * A ranking that could not be filled, and why. A discriminator, not a sentence:
 * the words a reader sees live in the page.
 */
export type WeeklySectionUnavailable = { section: 'BEST_NIGHTS' | 'BEST_WEEK'; reason: string }

/**
 * Carries EITHER `topPerformers` OR the `bestNights`/`bestWeek` pair, never
 * both. Which one depends on whether the sport's players can play more than
 * once in a scoring period -- football cannot, so its best night and best week
 * are the same list and only `topPerformers` is sent.
 *
 * The inapplicable side is **absent**, not an empty array. An empty array would
 * mean "we looked and found none"; absence means "this does not apply here".
 */
export type WeeklyReport = {
  available: boolean
  reason?: string | null
  season: number
  requestedSeason?: number | null
  /** The week this report is about. For a request of week 0 ("latest"), the resolved week. */
  week: number
  /** The newest STORED week, 0 when none: the top of the week input (an in-progress week can be viewed on purpose). */
  latestScoredWeek: number
  /** The newest FINAL week, 0 when none: what week 0 ("latest") resolves to when any week is final. */
  latestFinalWeek: number
  /** Whether `week` is final. False means scores can still change; the page says so. */
  weekFinal: boolean
  sport: Sport
  playersPlayMultiplePerPeriod: boolean
  matchups: WeeklyMatchup[]
  topPerformers?: WeeklyPerformer[]
  bestNights?: WeeklyNightPerformance[]
  bestWeek?: WeeklyPlayerWeek[]
  /**
   * `ALL_GAMES_PLAYED` says the week totals count every game a player played,
   * including games this league's scoring never counted. The page's disclosure
   * is driven by this rather than by hardcoded prose.
   */
  basis?: 'ALL_GAMES_PLAYED' | null
  sectionsUnavailable?: WeeklySectionUnavailable[]
  awards: WeeklyAward[]
  awardsOmitted: WeeklyOmittedAward[]
}

/** `week` 0 asks for the latest scored week; the response's `week` says which that was. */
export const getWeeklyReport = (sleeperLeagueId: string, week: number) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/weekly-report/${week}`).then(json<WeeklyReport>)
