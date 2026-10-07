import { apiFetch, json } from './http'

// Types mirror the Java records in engine/SimulationResult.java. They are
// hand-maintained; if you change a record over there, change it here.
export type PlayerRef = {
  id: number
  sleeperId: string
  name: string
  // Football: QB/RB/WR/TE/K/DEF. Basketball: PG/SG/SF/PF/C (verified live via
  // GET /api/board?sport=nba, multi-sport-and-rebrand.md Phase 6). Kept as one
  // flat union rather than a per-sport generic -- nothing here narrows which
  // sport's board it came from, so a caller that must not mix the two sports'
  // positions (a filter chip row, a slot-eligibility check, the pick-run
  // detector) needs its own sport-scoped list; see positions.ts.
  position: 'QB' | 'RB' | 'WR' | 'TE' | 'K' | 'DEF' | 'PG' | 'SG' | 'SF' | 'PF' | 'C'
  team: string | null
  adp: number
  // 999 is Sleeper's own "no rank" sentinel (BoardService's default, never
  // null) -- a "{position}{positionalRank}" label must special-case it rather
  // than rendering "RB999".
  positionalRank: number
}

export type Candidate = { player: PlayerRef; probability: number }

export type PredictedPick = {
  pickNo: number
  round: number
  slot: number
  manager: string
  avatarId: string | null
  player: PlayerRef
  /** Marginal: share of runs this player went at this pick. Not the board's probability. */
  probability: number
  /** False when the most-voted player at this pick was already assigned earlier. */
  isModal: boolean
  alternatives: Candidate[]
}

export type AvailabilityRow = {
  player: PlayerRef
  // pick number -> probability he is still there
  survivalByPick: Record<string, number>
}

export type Provenance = 'NEUTRAL' | 'STATED' | 'FITTED' | 'BLENDED'

// The backend's Sport enum is @JsonValue-serialized by its lowercase code, the
// same wire form the `?sport=` query params and the `league.sport` column use
// (multi-sport-and-rebrand.md Phase 2) -- not the Java enum name.
export type Sport = 'nfl' | 'nba'

export type Confidence = {
  draftsObserved: number
  scoreablePicks: number
  managersWithHistory: number
  /** Seats running on what you typed, with no history behind them. */
  managersStated: number
  /** Seats with neither history nor stated tendencies. */
  managersNeutral: number
  totalSeats: number
  boardSource: string
  caveats: string[]
}

export type SimulationResult = {
  iterations: number
  temperature: number
  teams: number
  rounds: number
  mySlot: number
  myPicks: number[]
  board: PredictedPick[]
  availability: AvailabilityRow[]
  bestAvailable: Record<string, Candidate[]>
  confidence: Confidence
  // NOT added. multi-sport-and-rebrand.md Phase 6 calls for a `sport` field
  // here (same change as SeatsResponse below), but engine/SimulationResult.java
  // has no such field -- verified 2026-09-08 via MonteCarloRunner.java:161, its
  // one construction site, and by reading the record itself. Adding one would
  // mean widening a record the Phase-1-through-3 football-parity baseline
  // hashes the raw shape of (see that doc's Acceptance Criteria §1) for a field
  // this task doesn't need: every call site below sources `sport` from
  // SeatsResponse instead (fetched independently and already in hand wherever
  // a SimulationResult is displayed), so leaving this record alone was the
  // lower-risk path. Revisit together if the backend ever adds it for its own
  // reasons.
}

export type Seat = {
  slot: number
  managerId: number
  manager: string
  avatarId: string | null
  provenance: Provenance
  reachBias: number
  /** Picks earlier (+) or later (-) than the OTHER managers in the same draft room. Null with no scoreable picks. Display only. */
  relativeReachBias: number | null
  /** Standard error of relativeReachBias; null with fewer than 2 scoreable picks. Inside one SE reads as "drafts like the room". */
  relativeReachStdErr: number | null
  unpredictability: number
  positionalTilt: Record<string, number>
  note: string | null
  draftsObserved: number
  picksScored: number
}

export type SeatsResponse = {
  draftId: string
  teams: number
  rounds: number
  /** Null for a draft whose status column is null -- never the string "null" (lessons #12). */
  status: string | null
  seats: Seat[]
  /** Auto-detected slot for the configured app owner, or null if unconfigured/not in this league. */
  mySlot: number | null
  // Sleeper's raw flat slot list (e.g. ["QB","RB","RB","WR","WR","TE","FLEX",
  // "FLEX","K","DEF","BN",...]), same shape as league.roster_positions --
  // read-only, league/draft-level, constant across runs. Legitimately [] when
  // a league's roster settings haven't synced; see teamNeeds.ts.
  rosterPositions: string[]
  // Added alongside LeagueController.seats()'s `sport` (multi-sport-and-
  // rebrand.md Phase 6) -- resolved backend-side from the league row, same
  // lowercase code as DraftSummary.sport. This is the one place a page that
  // only has a draftId (DraftView, LiveDraftView) can learn which sport it's
  // showing without a second fetch; see positions.ts.
  sport: Sport
  /**
   * The round from which snake parity flips; 0 means the draft never reverses.
   * `reversalRound` is what the engine actually uses -- the user's override if
   * they set one, otherwise `reversalRoundFromSleeper`. The two are shown side
   * by side rather than merged because this is the one assumption in the app
   * that was never verified against a real draft (multi-sport-and-rebrand.md,
   * "Assumed, not verified"): every draft this account can see is
   * `reversal_round: 0`, so nothing here has been executed against data.
   */
  reversalRound: number
  reversalRoundFromSleeper: number
  /**
   * True when the user has expressed an opinion at all -- not the same as
   * `reversalRound !== reversalRoundFromSleeper`. Pinning the value Sleeper
   * currently reports is a real choice, and it survives a re-ingest that moves
   * Sleeper's number; simply following Sleeper does not.
   */
  reversalRoundOverridden: boolean
  /** Whether this caller is the league's Sleeper commissioner: only they may change the reversal round. Mirrors LeagueController.seats(). */
  canCommission: boolean
}

export type SimRequest = {
  draftSleeperId: string
  mySlot: number
  iterations: number
  temperature: number
  startState?: Record<number, string>
  // Reproducibility escape hatch for diffing a refactor against a captured
  // baseline (multi-sport-and-rebrand.md). Omit/undefined = fresh, non-
  // reproducible seed, same as always. Nothing in the app sends this yet.
  seed?: number
}

export const getSeats = (draftId: string) =>
  apiFetch(`/api/drafts/${draftId}/seats`).then(json<SeatsResponse>)

// Mirrors LeagueController.realBoard's response shape. The picks that actually
// happened -- distinct from PredictedPick, which is always the engine's guess.
export type RealPick = {
  pickNo: number
  round: number
  slot: number
  manager: string
  avatarId: string | null
  player: PlayerRef
  /** LeagueController.PickNaming.row: draft_pick.adp_at_time, the board when the pick was made. Null when never captured. */
  adpAtDraft?: number | null
}

export type RealDraftBoard = {
  draftId: string
  teams: number
  rounds: number
  status: string | null
  picks: RealPick[]
}

export const getRealDraftBoard = (draftId: string) =>
  apiFetch(`/api/drafts/${draftId}/board`).then(json<RealDraftBoard>)

// Mirrors LeagueController.pool: the head of the board in board order, with ids.
export const getDraftPool = (draftId: string, limit: number) =>
  apiFetch(`/api/drafts/${draftId}/pool?limit=${limit}`).then(json<PlayerRef[]>)

// Mirrors DraftRepository.DraftSummary (store/DraftRepository.java). Backs the picker screen.
export type DraftSummary = {
  id: number
  sleeperDraftId: string
  leagueId: number
  leagueName: string
  season: number
  teams: number
  rounds: number
  // Nullable: draft.status is a nullable column and Sleeper has been observed
  // returning a draft object without one. This type said non-null, so
  // DraftPicker did `d.status.replace(...)` and a single null row threw a
  // TypeError mid-render -- with no error boundary above it, that white-screens
  // the whole picker. Same stale-hand-maintained-type class as lessons.md #6.
  status: string | null
  startTime: string | null
  sleeperLeagueId: string
  // Sleeper's back-pointer to the same league's previous season; null for the
  // earliest one ingested. The picker groups seasons into one card per league
  // with it -- see leagueLineages() in DraftPicker.
  previousLeagueId: string | null
  // allWithLeague() is a deliberately mixed, sport-tagged list
  // (multi-sport-and-rebrand.md Phase 2), not one list per sport. Not yet
  // rendered anywhere; the sport pill is Phase 6.
  sport: Sport
}

export const getDrafts = () => apiFetch('/api/drafts').then(json<DraftSummary[]>)

/**
 * Sets this draft's reversal round, or clears the override (`null`) to go back
 * to whatever Sleeper reported. Returns the same three fields SeatsResponse
 * carries, so the caller can re-render without refetching seats.
 */
export const setReversalRound = (draftId: string, reversalRound: number | null) =>
  apiFetch(`/api/drafts/${draftId}/reversal-round`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reversalRound }),
  }).then(
    json<{
      draftId: string
      reversalRound: number
      reversalRoundFromSleeper: number
      reversalRoundOverridden: boolean
    }>,
  )
