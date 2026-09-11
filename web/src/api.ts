// Types mirror the Java records in engine/SimulationResult.java. They are
// hand-maintained; if you change a record over there, change it here.

import { currentUserId } from './user'

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
  provenance: Provenance
  reachBias: number
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
  status: string
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

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }))
    throw new Error(body.error ?? `HTTP ${res.status}`)
  }
  return res.json() as Promise<T>
}

// Blank (local default) means every call below stays a same-origin relative
// path, exactly as it worked before this existed -- Vite's dev proxy and a
// same-origin production deploy both need nothing set. Only a split-origin
// deploy (DEPLOY.md: Vercel frontend + Fly/Railway backend) sets this.
const API_BASE = (import.meta.env.VITE_API_BASE ?? '').replace(/\/$/, '')

// Also blank locally -- API_TOKEN is unset by default, so there is no header
// to send and every route stays open, matching the backend's own default. See
// DEPLOY.md's warning: this ships to every browser that loads the page, which
// is fine for a private tool and not fine for one you hand out a link to.
const API_TOKEN = import.meta.env.VITE_API_TOKEN

/** `/api/...` -> the full request URL, honoring VITE_API_BASE. */
export const apiUrl = (path: string) => `${API_BASE}${path}`

/**
 * Every call in this file goes through here instead of bare `fetch` so a
 * split-origin deploy and a bearer token are both a config change, not a
 * per-call edit. Does NOT cover `useLiveDraft.ts`'s EventSource -- the
 * browser's native EventSource can't set a request header at all, so
 * live-mode against a token-protected backend needs its own answer (a
 * query-string token the backend also accepts, most likely) before it can be
 * deployed. Not needed for same-origin or auth-off deploys.
 */
function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  if (API_TOKEN) headers.set('Authorization', `Bearer ${API_TOKEN}`)
  // Read synchronously off user.ts's module-level variable rather than a React
  // hook -- this function is plain, called from outside any component. Absent
  // when signed out, matching the backend's own no-header default.
  const userId = currentUserId()
  if (userId) headers.set('X-Sleeper-User', userId)
  return fetch(apiUrl(path), { ...init, headers })
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
  player: PlayerRef
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

// --- claude/user-identity-and-onboarding.md §4a/§5: Sleeper-username identity ---

/** Mirrors SleeperUserController.user's response shape. */
export type SleeperUser = {
  sleeperUserId: string
  username: string
  displayName: string
  avatar: string | null
}

/**
 * The lookup's two outcomes, kept inside one 200 rather than split across a
 * status code -- see SleeperUserController.user's comment.
 */
type SleeperUserLookup = ({ found: true } & SleeperUser) | { found: false }

/**
 * null (not a thrown error) when Sleeper genuinely has no such username.
 *
 * A 404 is deliberately NOT that case any more. This used to read
 * `if (res.status === 404) return null`, which conflated "Sleeper has no such
 * name" with "this backend has no such route" -- and the second is a routine
 * state, not an exotic one, because the frontend and backend deploy
 * independently (DEPLOY.md: Vercel + Fly). The result was that the sign-in
 * gate told a visitor with a perfectly valid username to check their spelling,
 * with no way forward: the caller's retry path never fired. A 404 now throws
 * like any other unexpected status, so SignIn reaches its `unreachable` state
 * and offers a retry instead.
 */
export async function getSleeperUser(usernameOrId: string): Promise<SleeperUser | null> {
  const res = await apiFetch(`/api/sleeper/user/${encodeURIComponent(usernameOrId)}`)
  const body = await json<SleeperUserLookup>(res)
  return body.found ? body : null
}

/** Mirrors SleeperUserController.leagues' per-row response shape. */
export type SleeperLeague = {
  sleeperLeagueId: string
  name: string
  sport: Sport
  season: number
  totalRosters: number
  draftId: string | null
  status: string | null
  previousLeagueId: string | null
  /** Already has a `league` row in this app's own DB -- true means "Set up", not "start fresh". */
  ingested: boolean
}

export const getSleeperUserLeagues = (sleeperUserId: string) =>
  apiFetch(`/api/sleeper/users/${sleeperUserId}/leagues`).then(json<SleeperLeague[]>)

/**
 * One `event: state` frame from GET /api/drafts/{id}/live-stream (SSE, native
 * EventSource, GET). Mirrors the backend's LiveState record field-for-field,
 * hand-maintained per AGENTS.md -- if the record changes, change this in the
 * same commit.
 *
 * `status` is Sleeper's own word ("pre_draft" | "drafting" | "complete") and is
 * genuinely nullable: the column is, and Sleeper has been observed returning a
 * draft object without one (same class as DraftSummary.status above).
 *
 * `onTheClockSlot` is typed nullable on the same reasoning: a pre_draft or
 * complete draft has nobody on the clock. If the Java record turns out to
 * declare a primitive `int` there, narrow this and drop the null branches in
 * LiveStatusBar -- do not leave the two disagreeing.
 */
export type LiveState = {
  draftId: string
  status: string | null
  tracking: boolean
  picksMade: number
  lastPickNo: number
  totalPicks: number
  teams: number
  rounds: number
  seatsMapped: number
  onTheClockSlot: number | null
  /**
   * The last dozen picks that actually landed, oldest first -- the same shape
   * `RealPick` already mirrors for GET /drafts/{id}/board, because the backend
   * builds both through one helper (LeagueController.PickNaming).
   *
   * These are facts, and they are the only names on the live page that do not
   * have to wait for a simulation: everything else there comes out of
   * SimulationResult.board, which lands a debounce plus a full Monte Carlo run
   * after the pick did. Merge them over the projection's landed prefix rather
   * than beside it -- where they overlap, these win.
   */
  recentPicks: RealPick[]
  serverTime: string
}

/**
 * POST /api/drafts/{id}/track's response. The tick now runs synchronously on
 * the calling thread, so this doubles as the status refresh.
 *
 * `observed: false` means `status` is the stale DB value because Sleeper was
 * unreachable on that tick -- it must be presented as stale, not as fact.
 * `seatsMapped` is the draft-night health number: 0 means every seat is a
 * league-average bot.
 *
 * (The current controller also echoes `draftId` back; it isn't in the frozen
 * contract and nothing reads it, so it isn't mirrored here.)
 */
export type TrackResponse = {
  status: string | null
  observed: boolean
  tracking: boolean
  alreadyTracking: boolean
  seatsMapped: number
  teams: number
}

export const trackDraft = (sleeperDraftId: string) =>
  apiFetch(`/api/drafts/${sleeperDraftId}/track`, { method: 'POST' }).then(json<TrackResponse>)

// Scoped to the one league being added -- unlike /api/ingest/all, this doesn't
// re-download the entire player pool or rebuild the global board/profiles.
export const ingestLeague = (sleeperLeagueId: string) =>
  apiFetch(`/api/ingest/league/${sleeperLeagueId}`, { method: 'POST' }).then(json<Record<string, unknown>>)

// The three other /api/ingest/* sub-routes, called individually rather than
// through /api/ingest/all/{id} -- claude/user-identity-and-onboarding.md §5d:
// `all` runs three sequential Sleeper crawls plus a rebuild and can exceed a
// 30-60s platform HTTP timeout on a first-ever ingest, and these sub-routes
// exist precisely so a caller can split it and show staged progress instead.
export const ingestPlayers = (sport: Sport) =>
  apiFetch(`/api/ingest/players?sport=${sport}`, { method: 'POST' }).then(json<Record<string, unknown>>)

export const ingestAdp = (sport: Sport) =>
  apiFetch(`/api/ingest/adp?sport=${sport}`, { method: 'POST' }).then(json<Record<string, unknown>>)

export const ingestBoard = (sport: Sport) =>
  apiFetch(`/api/ingest/board?sport=${sport}`, { method: 'POST' }).then(json<Record<string, unknown>>)

// Walks a league's `previous_league_id` chain and stores each season's
// standings. Backs the "Load past seasons" button on the history page --
// which used to be a `POST /api/ingest/league-history/{id}` printed on screen
// for the reader to run in a terminal.
export const ingestLeagueHistory = (sleeperLeagueId: string) =>
  apiFetch(`/api/ingest/league-history/${sleeperLeagueId}`, { method: 'POST' }).then(json<Record<string, unknown>>)

export type ManualTendencies = {
  reachBias: number | null
  unpredictability: number | null
  note: string | null
}

// Mirrors ManagerController.describe()'s response shape (api/ManagerController.java:63-82).
// Only field the tendencies UI actually needs is `stated`, but keep the type honest/complete
// per this file's own convention of mirroring the backend record shape exactly.
export type ManagerSummary = {
  managerId: number
  manager: string
  provenance: Provenance
  effectiveReachBias: number
  // The unshrunk average of this manager's own scoreable picks -- "what they
  // normally pick," independent of anything stated about them. Null with no
  // scoreable picks. Compare against stated.reachBias; do not confuse with
  // effectiveReachBias, which is already blended with any stated value.
  empiricalReachBias: number | null
  unpredictability: number
  positionalTilt: Record<string, number>
  note: string | null
  draftsObserved: number
  picksScored: number
  stated: ManualTendencies
}

// All three manager endpoints take `sport` and default it to nfl backend-side
// (ManagerController.java), and all three used to be called from here without
// one -- so /managers listed only football managers, and a basketball seat's
// popover read and then WROTE that manager's *football* stated tendencies.
// Ten of the twelve Ball Knowers managers are the same Sleeper user id in both
// leagues (multi-sport-and-rebrand.md), so that was not a rare edge: manual
// tendencies are stored per (manager, sport), and editing a note on an NBA
// seat would land on the NFL row. Required parameter, no default -- the
// default is what hid this.
export const getManagers = (sport: Sport) =>
  apiFetch(`/api/managers?sport=${sport}`).then(json<ManagerSummary[]>)

export const setTendencies = (managerId: number, sport: Sport, body: ManualTendencies) =>
  apiFetch(`/api/managers/${managerId}/tendencies?sport=${sport}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then(json<unknown>)

export const clearTendencies = (managerId: number, sport: Sport) =>
  apiFetch(`/api/managers/${managerId}/tendencies?sport=${sport}`, { method: 'DELETE' }).then(
    json<unknown>,
  )

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

// Mirrors engine/SeatSpec.java's 3-state shape.
export type SeatType = 'USER' | 'MANAGER' | 'BOT'

// Mirrors mock/MockSessionState.java field-for-field.
export type MockSeat = { slot: number; type: SeatType; managerId: number | null; manager: string }

export type MockPick = {
  pickNo: number
  round: number
  draftSlot: number
  seatType: SeatType
  // LIVE means this pick was copied in from a real Sleeper draft at fork
  // time (createMockSessionFromDraft) -- already-decided and non-editable,
  // same as BOT, just decided by a real person in the real draft rather than
  // by this session's engine or its own USER seat.
  source: 'USER' | 'BOT' | 'LIVE'
  // Null only if the board changed (a re-ingest) since this pick was made and
  // the player dropped off it -- MockDraftView filters these out of the board
  // array entirely rather than rendering a broken cell.
  player: PlayerRef | null
}

export type MockSessionState = {
  id: number
  status: 'IN_PROGRESS' | 'COMPLETE'
  teams: number
  rounds: number
  rosterPositions: string[]
  userSlot: number
  // This session's own snake-order pick numbers -- computed once on the
  // backend (DraftSlot.picksForSlot) so the frontend doesn't need its own
  // copy of the snake-order formula.
  myPicks: number[]
  seats: MockSeat[]
  picks: MockPick[]
  available: PlayerRef[]
  currentPickNo: number
  onTheClockSlot: number | null
  isUsersTurn: boolean
  // The real draft this session was forked from (createMockSessionFromDraft),
  // or null for an ordinary from-scratch mock started at /mock/new.
  sourceDraftId: number | null
  // The first pick this session hadn't yet decided at fork time. Null when
  // sourceDraftId is null.
  forkedAtPickNo: number | null
}

// Mirrors store/MockDraftRepository.SessionSummary. Backs the picker screen's
// "Mock drafts" list.
export type MockSessionSummary = {
  id: number
  status: 'IN_PROGRESS' | 'COMPLETE'
  teams: number
  rounds: number
  userSlot: number
  currentPickNo: number
  createdAt: string
}

export const getMockSessions = () => apiFetch('/api/mocks').then(json<MockSessionSummary[]>)

// managerSeats seats a real manager's fitted/stated profile at a slot instead
// of an unmodelled bot -- keyed by slot number, same shape MockDraftController
// .CreateRequest expects. Any slot besides userSlot left out of it is still a
// plain bot.
export const createMockSession = (teams: number, userSlot: number, managerSeats?: Record<number, number>) =>
  apiFetch('/api/mocks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ teams, userSlot, managerSeats: managerSeats ?? {} }),
  }).then(json<MockSessionState>)

// Forks a real, drafting-status Sleeper draft into a new mock session seeded
// with its picks so far -- the live-draft-to-mock bridge. mySlot is optional;
// omitted, the backend falls back to the same owner auto-detection
// getSeats()'s mySlot already uses.
export const createMockSessionFromDraft = (sleeperDraftId: string, mySlot?: number) =>
  apiFetch(
    `/api/mocks/from-draft/${sleeperDraftId}${mySlot != null ? `?mySlot=${mySlot}` : ''}`,
    { method: 'POST' },
  ).then(json<MockSessionState>)

export const getMockSession = (id: number) => apiFetch(`/api/mocks/${id}`).then(json<MockSessionState>)

export const submitMockPick = (id: number, sleeperPlayerId: string) =>
  apiFetch(`/api/mocks/${id}/pick`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sleeperPlayerId }),
  }).then(json<MockSessionState>)

/**
 * The backend streams Server-Sent Events, but the request is a POST and the
 * browser's built-in EventSource only does GET. So we read the response body as
 * a stream and parse the SSE framing ourselves.
 *
 * SSE framing is simple: events are separated by a blank line, and within an
 * event each line is "field: value". We only care about "event:" and "data:".
 */
// --- claude/league-suite.md Phase A: league history + power rankings ---

// Mirrors LeagueHistoryController.standingRow()'s shape. season/sleeperLeagueId
// are only populated by getManagerHistory (a per-league standings list already
// knows both without repeating them on every row).
export type StandingRow = {
  rosterId: number
  managerId: number | null
  manager: string | null
  wins: number | null
  losses: number | null
  ties: number | null
  pointsFor: number | null
  pointsAgainst: number | null
  champion: boolean
  season: number | null
  sleeperLeagueId: string | null
}

export type SeasonHistory = {
  season: number
  leagueId: number
  sleeperLeagueId: string
  name: string | null
  standings: StandingRow[]
}

export type LeagueHistory = {
  sleeperLeagueId: string
  seasons: SeasonHistory[]
}

export const getLeagueHistory = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/history`).then(json<LeagueHistory>)

export type ManagerHistory = {
  managerId: number
  manager: string | null
  seasons: StandingRow[]
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
    positionalTilt: Record<string, number> | null
    draftsObserved: number
    /** 0 means reachBias is the league mean, not a measurement. See managerBehaviour.ts. */
    picksScored: number
    provenance: Provenance
  }[]
}

export const getManagerHistory = (managerId: number) =>
  apiFetch(`/api/managers/${managerId}/history`).then(json<ManagerHistory>)

/**
 * The one place the mode list is written down.
 *
 * It used to be written down four times -- the Record types below plus three
 * `as PowerRankingKind[]` array literals in PowerRankings.tsx (the mode
 * selector, the per-team transpose's mode list, and the per-team legend). The
 * Records fail to compile when a mode is added, which is what you want; the
 * casts silently do not, which is how adding MEMBER would have shipped a mode
 * selector with four segments and a comparison view still drawing three lines
 * under copy reading "across all three modes"
 * (claude/plan-review-power-rankings-ballots.md finding 9d).
 *
 * Order is display order, and MEMBER is last on purpose -- it is never the
 * default mode (finding 20).
 *
 * COMPUTED_MARKET_VALUE stopped being its own mode once it was demoted to
 * week 0 of COMPUTED_REALIZED (the one-time preseason baseline that weeks 1+,
 * built purely from games played, walk forward from) -- see the backend's
 * PowerRankingService#computeWeek0IfMissing.
 */
export const ALL_POWER_RANKING_KINDS = ['COMPUTED_REALIZED', 'COMMISSIONER', 'MEMBER'] as const

export type PowerRankingKind = (typeof ALL_POWER_RANKING_KINDS)[number]

// Mirrors LeagueHistoryController.snapshotRow()'s shape. score is null for
// COMMISSIONER (an ordering, not a measurement -- claude/league-suite.md's
// "only rank is shared across every mode" argument).
export type PowerRankingEntry = {
  season: number
  week: number
  kind: PowerRankingKind
  rosterId: number
  managerId: number | null
  manager: string | null
  rank: number
  score: number | null
  note: string | null

  // MEMBER only, null on every stored mode. The spread is a first-class output
  // rather than something the client derives, and it cannot be parsed back out
  // of `note` -- the note is prose for humans and the spread bar is a drawing
  // (claude/power-rankings-ballots.md, finding 7).
  //
  // ballotCount is per-ROSTER, not per-week: a roster ranked 1st by the only
  // manager who bothered would otherwise average 1.00 and win the week outright
  // over a roster averaging 1.4 across seven ballots (finding 8).
  bestRank?: number | null
  worstRank?: number | null
  stdev?: number | null
  ballotCount?: number | null

  // MEMBER only: this roster's own owner's rank of themselves, minus the room's
  // average of them. Negative = ranks himself higher than the room does. Kept
  // per-entry rather than as its own endpoint because it is exactly a property
  // of this entry -- and it is the only place one member's individual vote
  // surfaces at all, which is why it is a single derived integer rather than
  // the ballot it came from.
  selfRankBias?: number | null

  // TODO(power-rankings-reskin): not populated by the backend yet. The
  // power-rankings-reskin.md mockups surface a "makes playoffs NN%" stat as a
  // headline number (hero card, your-team strip, ladder column) that needs a
  // rest-of-season Monte Carlo simulation against the real schedule -- a
  // backend feature this presentation-only reskin explicitly does not add
  // (see that doc's own "stop and report" rule). The UI is wired to this
  // field so it lights up the moment a backend PR populates it; until then it
  // is always undefined and every call site renders its own "--" fallback.
  makesPlayoffsPct?: number | null
}

export type NflState = {
  week: number
  season: string
  seasonStartDate: string
  started: boolean
}

export type PowerRankings = {
  sleeperLeagueId: string
  nflState: NflState
  entries: PowerRankingEntry[]
}

export const getPowerRankings = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/power`).then(json<PowerRankings>)

export const computePowerRankings = (sleeperLeagueId: string, season: number, week: number) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/power/compute?season=${season}&week=${week}`, {
    method: 'POST',
  }).then(json<{ week0: number; realized: number; realizedSkipped?: string }>)

export const saveCommissionerRanking = (
  sleeperLeagueId: string,
  season: number,
  week: number,
  rosterIds: number[],
) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/power/commissioner`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ season, week, rosterIds }),
  }).then(json<{ saved: number }>)

export async function streamSimulation(
  req: SimRequest,
  onProgress: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<SimulationResult> {
  const res = await apiFetch('/api/sims/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
    signal,
  })
  if (!res.ok || !res.body) {
    const body = await res.json().catch(() => ({ error: res.statusText }))
    throw new Error(body.error ?? `HTTP ${res.status}`)
  }

  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  let result: SimulationResult | null = null

  // The reader has to be released on EVERY exit path, not just the clean one.
  // Before this, an abort (or an `event: error` throw) left the loop's reader
  // holding the body open: the fetch was cancelled but nothing told the reader,
  // so navigating away mid-run left a backend simulation still burning CPU
  // against a result nobody would read. That matters in live mode, where an SSE
  // stream is already held open alongside this one.
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += value

      let split: number
      while ((split = buffer.indexOf('\n\n')) !== -1) {
        const raw = buffer.slice(0, split)
        buffer = buffer.slice(split + 2)

        let name = 'message'
        const dataLines: string[] = []
        for (const line of raw.split('\n')) {
          if (line.startsWith('event:')) name = line.slice(6).trim()
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
        }
        if (dataLines.length === 0) continue
        const payload = JSON.parse(dataLines.join('\n'))

        if (name === 'progress') onProgress(payload.fraction)
        else if (name === 'result') result = payload as SimulationResult
        else if (name === 'error') throw new Error(payload.message)
      }
    }
  } finally {
    // Already-closed readers reject here; that is not an error worth surfacing.
    reader.cancel().catch(() => {})
  }

  if (!result) throw new Error('stream ended without a result')
  return result
}

// --- claude/power-rankings-ballots.md: member ballots (power-ranking mode 2) ---

export type BallotMember = {
  rosterId: number
  managerId: number | null
  manager: string | null
  /** Sleeper's metadata.team_name, already resolved -- the backend applies the
   *  team_name -> display_name -> null chain and treats the literal "TBD" as
   *  absent (design doc finding 19), so this is renderable as-is. */
  teamName: string | null
  isMe: boolean
}

/**
 * Mirrors LeagueHistoryController.ballot(). `canSubmit` folds together every
 * reason a ballot might be refused -- signed out, not a member of this league,
 * a non-NFL league (mode 2 is NFL-only by construction while nflState is), and
 * a week that is not the current one -- so the client never has to re-derive
 * the rule and disagree with the server about it.
 */
export type BallotState = {
  season: number
  week: number
  canSubmit: boolean
  canCommission: boolean
  /** False when no league_member row carries is_commissioner -- i.e. this
   *  league predates the flag and needs a re-ingest. The page says so rather
   *  than silently showing nobody an editor. */
  commissionerKnown: boolean
  memberCount: number
  ballotCount: number
  members: BallotMember[]
  mine: { rosterIds: number[]; submittedAt: string } | null
}

export const getBallot = (sleeperLeagueId: string, week?: number) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/ballot${week == null ? '' : `?week=${week}`}`).then(json<BallotState>)

/** rosterIds[0] is 1st. The season is read from league.season server-side and
 *  nothing here is trusted for it, so it is deliberately not a parameter. */
export const submitBallot = (sleeperLeagueId: string, week: number, rosterIds: number[]) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/ballot`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ week, rosterIds }),
  }).then(json<{ saved: number }>)
