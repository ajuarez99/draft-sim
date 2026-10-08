import type { Sport } from './draft'
import { apiFetch, json } from './http'

// --- specs/014-home-player-spotlight: Player spotlight on the league home ---
// Mirrors specs/014-home-player-spotlight/contracts/player-spotlight-api.md field for field.

export type SpotlightOwnership = {
  rostered: boolean
  /** Present only when rostered; may be absent for a roster with no name. */
  teamName?: string | null
  /** The owning manager's Sleeper avatar id; present only when rostered, may be null. */
  avatarId?: string | null
  isMe?: boolean
}

/** `opponent`/`isAway` are null when unknown (never guessed). */
export type SpotlightPerformance = {
  playerId: string
  name: string
  position: string | null
  team: string | null
  opponent: string | null
  isAway: boolean | null
  points: number
  ownership: SpotlightOwnership
}

/** `points`/`opponent`/`isAway` are present only when `outcome` is `PLAYED`. */
export type SpotlightTrendingEntry = {
  rank: number
  playerId: string
  name: string
  position: string | null
  team: string | null
  addCount: number
  ownership: SpotlightOwnership
  outcome: 'PLAYED' | 'DID_NOT_PLAY' | 'NO_GAME' | 'NO_PERIOD'
  points?: number
  opponent?: string | null
  isAway?: boolean | null
}

/** What the sections are scored against; discriminated on `kind`. */
export type SpotlightPeriod =
  | { kind: 'NIGHT'; date: string; gamesCount: number }
  | { kind: 'WEEK'; week: number; weekFinal: boolean }

export type SpotlightPerformanceSection = {
  entries: SpotlightPerformance[]
  unavailable: 'NO_PERIOD' | 'NO_ROOKIE_PLAYED' | 'NO_ROSTERED_PLAYED' | 'SECTION_FAILED' | null
}

export type SpotlightTrendingSection = {
  entries: SpotlightTrendingEntry[]
  lookbackHours: number
  /** ISO instant; null = never fetched. */
  fetchedAt: string | null
  stale: boolean
  omittedUnknownPlayers: number
  unavailable: 'NEVER_FETCHED' | 'SECTION_FAILED' | null
}

/** `applies: false` carries only these four fields. */
export type PlayerSpotlightInapplicable = {
  applies: false
  reason: string
  season: number
  sport: Sport
}

export type PlayerSpotlightApplicable = {
  applies: true
  reason?: null
  season: number
  sport: Sport
  playersPlayMultiplePerPeriod: boolean
  period: SpotlightPeriod | null
  periodUnavailable: 'NO_GAMES_YET' | 'NO_COMPLETE_NIGHT_YET' | 'NO_WEEK_SCORED' | null
  /** Date (YYYY-MM-DD) of a later night that has rows but is not yet complete. */
  laterNightInProgress: string | null
  /** YYYY-MM-DD; null when unknown, never guessed. */
  seasonStartDate: string | null
  /** Absent (not null, not []) when the sport has no per-night list; football uses the weekly report's topPerformers. */
  topOfNight?: SpotlightPerformanceSection
  trending: SpotlightTrendingSection
  rookieWatch: SpotlightPerformanceSection
}

export type PlayerSpotlight = PlayerSpotlightInapplicable | PlayerSpotlightApplicable

export const getPlayerSpotlight = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/player-spotlight`).then(json<PlayerSpotlight>)
