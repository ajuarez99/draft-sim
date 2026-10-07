import type { Provenance, Sport } from './draft'
import { apiFetch, json } from './http'
import type { StandingRow } from './leagueHistory'

/**
 * specs/006-deeper-history-both-sports US3 (FR-008, research R5). A ranked
 * figure that always carries the population it was ranked against and the one
 * league chain it was ranked within -- a bare "#2" is not a rank, and the repo
 * has a standing rule about exactly this
 * (feedback_label_the_axis_spell_out_the_number.md: one encoding per mark,
 * the exact value beside it).
 *
 * One entry per league chain a manager plays in for this sport: a manager in
 * two football chains gets two `winRate` ranks, never one blended across
 * chains that have never played each other.
 */
export type Rank = {
  figure: 'winRate' | 'pointsPerSeason' | 'averageEfficiency'
  position: number
  population: number
  leagueName: string
  sleeperLeagueId: string
}

/**
 * A figure this app genuinely cannot answer, named rather than printed as a
 * zero (FR-011). `unavailable` is present on every CareerProfile even when
 * empty, so "no answer" is distinguishable from an older server that never
 * asked the question -- render the reason, never a bare 0.
 */
export type Unavailable = {
  figure: 'playoffAppearances' | 'tradesPerSeason'
  reason: string
}

/**
 * specs/006-deeper-history-both-sports US6 (data-model.md FaabTendency,
 * research R8). Every field is a FRACTION of a season's own
 * `settings_json.waiver_budget`, aggregated across seasons only AFTER each
 * bid is normalised -- never a raw dollar figure, which this database's
 * budgets (100 -> 10000, a 50x spread across one manager's own career) make
 * meaningless to sum first and divide once.
 */
export type FaabTendency = {
  /** Mean bid size, win or lose -- "what this manager usually bids". */
  typicalBidPct: number
  /** The single biggest bid attempted, win or lose. */
  largestBidPct: number
  /** Total of WON bids only, per season counted -- money actually spent. */
  spentPerSeasonPct: number
  /** FAAB bids attempted (won or lost) per season counted. */
  claimsPerSeason: number
  /** Won bids / bids attempted, within FAAB seasons only. */
  bidSuccessRate: number
}

/**
 * One league-season this manager shared no FAAB bidding with, and why --
 * `waiver_type` 0 is waiver PRIORITY, with no bidding at all. NFL 2025 alone
 * holds 321 such WAIVER rows with zero bids, and that is the correct format
 * for that season, never something to render as a gap.
 */
export type FaabExcludedSeason = {
  season: number
  leagueName: string
  reason: string
}

/**
 * specs/006-deeper-history-both-sports US6. One manager, one sport, their
 * whole career's waiver behaviour -- mirrors
 * TransactionAnalysisService.WaiverTendency field-for-field.
 */
export type WaiverTendency = {
  /** WAIVER + FREE_AGENT rows / seasonsCounted -- no status filter, a failed claim is still a move attempted. */
  movesPerSeason: number
  /**
   * The SAME divisor the rest of this manager's CareerProfile already uses
   * (T073) -- not a second count of "seasons with transactions ingested".
   * One source for the number and its label.
   */
  seasonsCounted: number
  /** Null when no season this manager played ran FAAB at all. */
  faab: FaabTendency | null
  faabExcludedSeasons: FaabExcludedSeason[]
}

/**
 * A careers[].seasons[] row: standingRow()'s own shape plus `counted`, the one
 * fact StandingRow cannot carry on its own (counted is about roster_week_points,
 * not roster_season). An uncounted season -- NBA 2026, ingested and unplayed --
 * is still listed here; it just contributes to none of the totals beside it.
 */
export type CareerSeason = StandingRow & { counted: boolean }

/**
 * One manager, one sport, their whole recorded career (never spans sports --
 * FR-002). Mirrors ManagerCareerService.CareerProfile field-for-field.
 *
 * Every average below is divided by `seasonsCounted`, and that count rides on
 * the SAME object as the average it explains (SC-008) -- a career figure with
 * one or two played seasons behind it that doesn't say so reads as a season
 * record wearing a career's name.
 */
export type CareerProfile = {
  sport: Sport
  /** The divisor behind every average below, and the seasons-covered label beside it. */
  seasonsCounted: number
  /** Every roster-season, including uncounted ones -- see CareerSeason. */
  seasons: CareerSeason[]
  wins: number
  losses: number
  ties: number
  /** Null, never 0, when no games have been played. */
  winRate: number | null
  pointsFor: number
  pointsAgainst: number
  /** Null when there are no counted seasons to divide by. */
  pointsPerSeason: number | null
  /**
   * Weeks-weighted mean of RosterManagementService's own per-week optimal
   * lineup -- never roster_season.points_possible, which gives a different,
   * more flattering number for the same manager-season (contracts/
   * manager-profile-api.md). Null, never 1.0, when there is no potential to
   * divide by.
   */
  averageEfficiency: number | null
  /** Weeks actually behind averageEfficiency. */
  weeksCounted: number
  /**
   * A COUNT of weeks dropped for want of a per-player breakdown -- not the
   * week-number list RosterManagementTeam.weeksExcluded carries; a career
   * spans several leagues' own week numbers, which cannot be merged into one
   * list. An excluded week must stay visible rather than silently vanish from
   * the average (FR-006).
   */
  weeksExcluded: number
  /** Sum of per-season ExpectedWinsService figures. Null only when not one counted season produced a computable figure. */
  winsAboveExpected: number | null
  /** Counted seasons with finalPlacement == 1 AND the season complete. */
  titles: number
  unavailable: Unavailable[]
  ranks: Rank[]
  /**
   * specs/006-deeper-history-both-sports US6 (T073). Always present -- never
   * null -- even when this manager has never placed a FAAB bid: `waivers.faab`
   * is the field that goes null then, not `waivers` itself. `tradesPerSeason`
   * stays in `unavailable` above regardless; waivers answering does not make
   * trades answerable too (T068).
   */
  waivers: WaiverTendency
}

export type ManagerHistory = {
  managerId: number
  manager: string | null
  avatarId: string | null
  /**
   * One entry per sport this manager has actually drafted in, newest concept
   * first: a manager is not a football manager, they are a manager, and
   * profiles are fitted per (manager, sport). This was a single object fitted
   * from Sport.NFL unconditionally, which put a person's football reach bias on
   * a page an NBA league's standings row links to -- and ten of twelve managers
   * here are the same Sleeper id in both leagues
   * (claude/merge-review-multi-sport.md S1).
   *
   * Empty when the manager has no drafts in any sport. Sports with nothing to
   * say are omitted rather than sent as zeroes.
   */
  draftHistory: {
    sport: Sport
    reachBias: number | null
    /** Room-relative reach (audit 11); null with no scoreable picks. */
    relativeReachBias: number | null
    /** Its standard error; null with fewer than 2 scoreable picks. */
    relativeReachStdErr: number | null
    positionalTilt: Record<string, number> | null
    draftsObserved: number
    /** 0 means reachBias is the league mean, not a measurement. See managerBehaviour.ts. */
    picksScored: number
    provenance: Provenance
  }[]
  /**
   * specs/006-deeper-history-both-sports T040/US3, and since T076 the ONLY
   * season list on this type -- the flat `seasons` it shipped beside for one
   * release is gone (step 3 of contracts/manager-profile-api.md's Migration).
   * One entry per sport the manager has a roster-season in, each holding that
   * sport's own rows; never a top-level total spanning sports, and no second
   * flat copy that a caller could total across sports without noticing.
   */
  careers: CareerProfile[]
}

export const getManagerHistory = (managerId: number) =>
  apiFetch(`/api/managers/${managerId}/history`).then(json<ManagerHistory>)
