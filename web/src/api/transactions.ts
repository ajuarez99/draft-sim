import type { Sport } from './draft'
import { apiFetch, json } from './http'

// --- specs/004-ffwrapped-feature-parity US6: transactions ---

/**
 * `postMovePositionalRank` is null when no week has been played since the move
 * — ungraded, not bad. `weeksCounted` rides beside every rank so a one-week
 * sample is not read as a season verdict.
 *
 * Rank is within the player's own position, among players rostered in THIS
 * league. ffwrapped ranks against a wider pool, so the numbers are close but
 * not identical; the direction is what `rankDirection` states.
 */
export type MovedPlayer = {
  playerId: string
  playerName: string
  position: string | null
  postMovePositionalRank: number | null
  weeksCounted: number
}

export type TransactionAdd = {
  week: number
  teamName: string
  type: string
  status: string | null
  added: MovedPlayer
  dropped: MovedPlayer | null
  faabBid: number | null
}

export type TradeSide = { teamName: string; received: MovedPlayer[] }
export type LeagueTrade = { week: number; sides: TradeSide[] }

export type ManagerTransactionCounts = {
  managerId: number | null
  teamName: string
  counts: Record<string, number>
  total: number
}

export type LeagueTransactions = {
  available: boolean
  reason?: string | null
  season: number
  sport: Sport
  byManager: ManagerTransactionCounts[]
  trades: LeagueTrade[]
  adds: TransactionAdd[]
  rankDirection: string
}

export const getLeagueTransactions = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/transactions`).then(json<LeagueTransactions>)
