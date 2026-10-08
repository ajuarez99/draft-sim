import type { Sport } from './draft'
import { apiFetch, json } from './http'

export type TrendRole = 'RISER' | 'FALLER' | 'STEADY'
export type PlayerTrendsReason = 'NOT_CONFIGURED' | 'NOT_BASKETBALL' | 'NO_GAMES'
export type StreamingReason = 'SEASON_COMPLETE' | 'NOT_DRAFTED' | 'ROSTERS_NOT_LOADED'
export type OneGameCredit = { code: 'ONE_GAME_CREDITED'; share: number; seasonMeasured: number }
export type TrendRow = {
  sleeperPlayerId: string
  name: string
  positions: string[]
  team: string | null
  games: number
  lastGameDate: string
  recentMin: number | null
  seasonMin: number | null
  minDelta: number | null
  role: TrendRole | null
  recentUsg: number | null
  seasonUsg: number | null
  ptsPerMin: number | null
  seasonPts: number | null
  formPts: number | null
  missedTeamGames: number
  rostered: boolean | null
  rosteredBy: string | null
  gamesThisWeek: number | null
  gamesNextWeek: number | null
}
export type PlayerTrends = {
  sport: Sport
  season: number
  available: boolean
  reason: PlayerTrendsReason | null
  rolesSeason: number | null
  rolesFallback: boolean
  streamingSeason: number | null
  streamingFallback: boolean
  windows: { recentGames: number; formGames: number; formMinGames: number; minSeasonGames: number; recencyDays: number }
  roleThresholdMinutes: number | null
  currentWeek: number | null
  risersTotal: number
  fallersTotal: number
  /** Per ownership group; 0 when ownership isn't known (streamingReason is set). */
  risersFreeAgentTotal: number
  risersRosteredTotal: number
  fallersFreeAgentTotal: number
  fallersRosteredTotal: number
  excludedStale: number
  excludedNoTeam: number
  /** The data season's last game date (ISO), the day "stale" is measured from. */
  staleReferenceDate: string | null
  risers: TrendRow[]
  fallers: TrendRow[]
  streamingReason: StreamingReason | null
  rostersFetchedAt: string | null
  streaming: TrendRow[]
  oneGameCredit: OneGameCredit | null
}
export const getPlayerTrends = (id: string) =>
  apiFetch(`/api/leagues/${id}/player-trends`).then(json<PlayerTrends>)
