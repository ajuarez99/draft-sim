import type { Sport } from './draft'
import { apiFetch, json } from './http'

/*
 * specs/017-nba-schedule-grid. Mirrors contracts/api.md C1 and C2 field for
 * field (the Java records ScheduleGridService.Result and NextMatchupService.Result).
 */
export type ScheduleWeek = { week: number; firstDate: string | null; lastDate: string | null }
export type ScheduleTeam = { team: string; games: number[]; seasonTotal: number }
export type PlayoffWindow = { startWeek: number | null; endWeek: number | null; reason: string | null }
export type LeagueSchedule = {
  sport: Sport
  season: number
  available: boolean
  reason: string | null
  fetchedAt: string | null
  currentWeek: number | null
  lastLeagueWeek: number | null // added after review (F2)
  seasonOver: boolean // added after review (F2)
  weeks: ScheduleWeek[]
  playoff: PlayoffWindow
  teams: ScheduleTeam[]
  /** exhibition: games with a non-franchise side (an All-Star team), not counted either. */
  excluded: { postponed: number; canceled: number; exhibition: number }
}
export const getLeagueSchedule = (id: string) =>
  apiFetch(`/api/leagues/${id}/schedule`).then(json<LeagueSchedule>)

export type MatchupSide = {
  rosterId: number
  teamName: string | null
  username: string | null
  avatarId: string | null
}
export type NextMatchup = {
  sport: Sport
  season: number
  week: number | null
  available: boolean
  reason: string | null
  me: MatchupSide | null
  opponent: MatchupSide | null
}
export const getNextMatchup = (id: string) =>
  apiFetch(`/api/leagues/${id}/next-matchup`).then(json<NextMatchup>)
