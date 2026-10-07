import type { Sport } from './draft'
import type { ExpectedWinsSwing } from './expectedWins'
import { apiError, apiFetch, json } from './http'

// --- specs/008-season-superlatives: Season superlatives ---

export type SuperlativeKind =
  | 'HIGHEST_WEEK' | 'LOWEST_WEEK' | 'BIGGEST_BLOWOUT' | 'CLOSEST_GAME'
  | 'CLOSE_WINS' | 'CLOSE_LOSSES' | 'LUCKIEST' | 'UNLUCKIEST' | 'MOST_BENCH_POINTS'
  | 'WAIVER_WIRE_WARRIOR' | 'JABARI_SMITH_JR' | 'JOEL_EMBIID' | 'UNETHICAL'

export type SuperlativeHolder = {
  rosterId: number
  managerId: number | null
  teamName: string
  /** Sleeper username, shown under the team name; null for an unowned roster. */
  username: string | null
  avatarId: string | null
}

/** JABARI_SMITH_JR only (added 2026-09-28, research R16): `[]` for every other kind. `team` is nullable -- a free agent has none. */
export type SuperlativePlayerHolder = {
  playerId: string
  playerName: string
  position: string | null
  team: string | null
  adds: number
  distinctTeams: number
}

/**
 * One row of a superlative's full standings (specs/010-superlatives-full-standings).
 * Mirrors SeasonSuperlativesService.Standing. Payload order is the ranking order; never
 * re-sort. `rank` is null exactly when `hasValue` is false, and `missingReason` is
 * non-null exactly when `hasValue` is false. Ties share a rank (1, 1, 3).
 */
export type SuperlativeStanding = {
  rank: number | null
  team: SuperlativeHolder
  value: number | null
  /** Per-row context line ("week 7", "vs X · week 3"); may be null. */
  note: string | null
  hasValue: boolean
  missingReason: string | null
}

/** JABARI_SMITH_JR only (spec 010): the top players by adds, `[]` for every other kind. Mirrors SeasonSuperlativesService.PlayerStanding. `position` and `team` are nullable. */
export type SuperlativePlayerStanding = {
  rank: number
  playerId: string
  playerName: string
  position: string | null
  team: string | null
  adds: number
  distinctTeams: number
}

/** Present only when fewer than the season's scored weeks were usable for a pairing-based kind. */
export type SuperlativeCoverage = {
  weeksCovered: number
  weeksExcluded: number
  reasons: string[]
}

type SwingWeek = ExpectedWinsSwing

/**
 * One row of a superlative's supporting detail, discriminated by `type`
 * (contracts/superlatives-api.md's detail table). Only `WEEK_SCORE` and `GAME`
 * are produced today (US1); the rest are here so later phases (US2-US6) need
 * no shape change, only a service that fills them in.
 */
export type SuperlativeDetail =
  | { type: 'WEEK_SCORE'; week: number; rosterId: number; points: number }
  | {
      type: 'GAME'
      week: number
      rosterId: number
      opponentRosterId: number
      opponentTeamName: string
      points: number
      opponentPoints: number
      margin: number
    }
  | {
      type: 'LUCK'
      rosterId: number
      actualWins: number
      expectedWins: number
      winsAboveExpected: number
      swingWeeks: SwingWeek[]
      fromWeek: number
      throughWeek: number
      /** A one-line sentence built from winsAboveExpected, e.g. "2.40 more wins than their scores earned". */
      reading: string
    }
  | {
      type: 'BENCH_TOTAL'
      rosterId: number
      pointsLeft: number
      weeksCounted: number
      fromWeek: number
      throughWeek: number
      biggestWeek: { week: number; pointsLeft: number } | null
    }
  | {
      type: 'PICKUP'
      /** Added T040 (specs/008-season-superlatives): every other per-holder detail type carries this; without it a tied holder's pickups can't be told apart from another holder's. */
      rosterId: number
      playerId: string
      playerName: string
      position: string
      addedWeek: number
      addType: 'WAIVER' | 'FREE_AGENT'
      startedWeeks: number[]
      points: number
    }
  | {
      type: 'ABSENCE'
      /** Added T049, same reasoning as PICKUP's own T040 fix: every other per-holder detail type carries rosterId, and without it a tied holder's absences can't be told apart from another holder's. */
      rosterId: number
      playerId: string
      playerName: string
      position: string
      gamesMissed: number
      /** Count of distinct weeks touched, not a list of them -- equals gamesMissed in football (one game per week); can be fewer in basketball, when more than one missed game falls in the same week (research R10). Fixed from an earlier `number[]` guess (T049). */
      weeksAffected: number
      pointsPerGame: number
      estimatedPointsLost: number
      estimated: boolean
    }
  | {
      type: 'CONDUCT'
      playerId: string
      playerName: string
      rosterId: number
      source: 'SUSPENDED' | 'COMMISSIONER'
      weeks: number[]
      reason: string | null
    }
  | {
      type: 'ADD'
      /** Which tied player (from `Superlative.playerHolders`) this add belongs to -- added 2026-09-28, US7. */
      playerId: string
      week: number
      rosterId: number
      /** The adding team's own name/avatar, carried on the row -- these teams aren't holders here, so the page has nothing else to look them up in (same reasoning as GAME's opponentTeamName). */
      teamName: string
      avatarId: string | null
      addType: 'WAIVER' | 'FREE_AGENT'
      /** null when not a bid (a free-agent add, or a waiver claim with no FAAB); 0 is a real bid, never confused with null. */
      faabBid: number | null
    }

export type Superlative = {
  kind: SuperlativeKind
  available: boolean
  reason?: string | null
  early: boolean
  value: number | null
  unit: 'POINTS' | 'WINS' | 'GAMES' | 'ADDS' | null
  holders: SuperlativeHolder[]
  emptyReason?: string | null
  detail: SuperlativeDetail[]
  coverage: SuperlativeCoverage | null
  /** JABARI_SMITH_JR only (added 2026-09-28); `[]` for every other kind. `holders` and `playerHolders` are both `[]` iff `emptyReason` is non-null or `available` is false. */
  playerHolders: SuperlativePlayerHolder[]
  /** Spec 010: every roster ranked, in payload order (never re-sort). `[]` when unavailable/empty and for JABARI_SMITH_JR. */
  standings: SuperlativeStanding[]
  /** Spec 010: JABARI_SMITH_JR only; `[]` for every other kind. */
  playerStandings: SuperlativePlayerStanding[]
}

export type SuperlativesResponse = {
  available: boolean
  reason?: string | null
  season: number
  requestedSeason?: number | null
  sport: Sport
  throughWeek: number | null
  weeksScored: number
  regularSeasonEnd: number | null
  early: boolean
  earlyThresholdWeeks: number
  closeGameMargin: number
  suspensionWeeksObserved: number[]
  commissionerListAvailable: boolean
  superlatives: Superlative[]
  /**
   * The resolved league-season's own Sleeper id -- NOT necessarily the id in
   * the URL. The cards above resolve through `LeagueSeasonResolver`, which
   * can walk back to an older PLAYED season (`requestedSeason` says when it
   * did); the conduct list is addressed by exact league row instead (per
   * contracts/superlatives-api.md), so before a new season's first scored
   * week, saving against the URL's own id silently edited a season the cards
   * weren't even showing (code-review fix, 2026-09-23). Always fetch/save/
   * delete the conduct list against THIS field, never the URL param.
   */
  leagueSleeperId: string
}

export const fetchSuperlatives = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/superlatives`).then(json<SuperlativesResponse>)

// --- specs/008-season-superlatives T059: the commissioner's conduct list (UNETHICAL) ---

/** One entry on the commissioner's list. `addedBy` is null when the configured app owner added it, who has no manager row in the league (data-model.md). */
export type ConductEntry = {
  id: number
  playerId: string
  playerName: string
  reason: string
  appliesFromWeek: number
  addedBy: string | null
  createdAt: string
}

/**
 * Per league-SEASON, not per league chain (spec amendment 9): a new season's
 * league row starts with an empty list, and last season's entries stay
 * attached to last season. `canEdit` is `LeagueMembership.canCommission`,
 * the same gate the write endpoints enforce with, so display and
 * enforcement can't disagree.
 */
export type ConductList = {
  canEdit: boolean
  commissionerKnown: boolean
  entries: ConductEntry[]
}

export const fetchConductList = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/conduct-list`).then(json<ConductList>)

/**
 * Surfaces the server's `{message}` on a 400 (bad reason/week/player) or a
 * 403 (not the commissioner) -- distinct from the generic `json()` helper
 * above, which reads `body.error`, not `body.message`.
 */
async function conductListResult<T>(res: Response): Promise<T> {
  if (!res.ok) {
    throw await apiError(res)
  }
  // DELETE returns 204 with no body.
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

/** Upserts on (league, player). 200 with the saved entry; throws with the server's message on 400/403. */
export const saveConductEntry = (
  sleeperLeagueId: string,
  entry: { playerId: string; reason: string; appliesFromWeek: number },
) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/conduct-list`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(entry),
  }).then((res) => conductListResult<ConductEntry>(res))

/** 204 on success; throws with the server's message on 403, or a 404 when entryId belongs to a different league. */
export const deleteConductEntry = (sleeperLeagueId: string, entryId: number) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/conduct-list/${entryId}`, {
    method: 'DELETE',
  }).then((res) => conductListResult<void>(res))
