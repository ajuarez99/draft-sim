import type { Sport } from './draft'
import { apiFetch, json } from './http'

// --- specs/004-ffwrapped-feature-parity US2: the Roster management page ---

/**
 * One team's season. Mirrors RosterManagementService.TeamRow.
 *
 * `efficiency` is nullable on purpose and must not be coerced to a number at
 * the edge: null means there was no potential to divide by, and rendering that
 * as 100% would be the most flattering possible wrong answer. `weeksExcluded`
 * lists weeks dropped for want of a per-player breakdown -- they are excluded
 * from the totals, so the page has to say so rather than let a short season
 * read as a full one.
 */
export type RosterManagementTeam = {
  rosterId: number
  managerId: number | null
  teamName: string
  /** Sleeper username, shown under the team name; null for an unowned roster. */
  username: string | null
  avatarId: string | null
  totalPoints: number
  potentialPoints: number
  efficiency: number | null
  weeksCounted: number
  weeksExcluded: number[]
  /** RosterManagementService.TeamRow.grade: letter for the efficiency rank; null with no efficiency or no grade cutoffs. */
  grade?: string | null
}

/** `available: false` carries a reason; it is not an empty table. */
export type RosterManagement = {
  available: boolean
  reason?: string | null
  season: number
  /** Set when the season asked for had not been played and an earlier one is
   *  shown instead. The page says so; a silently different year would be worse
   *  than the refusal it replaces. */
  requestedSeason?: number | null
  sport: Sport
  weeksScored: number
  /** RosterManagementController.body: weeksScored < SeasonWindow.EARLY_THRESHOLD_WEEKS. */
  gradesEarly?: boolean
  /** RosterManagementController: SeasonWindow.EARLY_THRESHOLD_WEEKS. */
  earlyThresholdWeeks?: number
  teams: RosterManagementTeam[]
}

export const getRosterManagement = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/roster-management`).then(json<RosterManagement>)
