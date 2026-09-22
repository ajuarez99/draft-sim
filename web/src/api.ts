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
  avatarId: string | null
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
  avatarId: string | null
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
export type MockSeat = {
  slot: number
  type: SeatType
  managerId: number | null
  manager: string
  avatarId: string | null
}

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
  // The sport this session drafts in. The room passes it to the position
  // filters, name abbreviation and pick-run detector -- all of which were
  // already sport-keyed and were being handed a hardcoded 'nfl'.
  sport: Sport
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
  // The round from which snake parity flips; 0 is plain snake. DraftBoard needs
  // it to draw the same order myPicks was computed against -- the real 2026 NBA
  // draft reverses at round 3, and a plain-snake grid puts your own highlighted
  // picks in another seat's column.
  reversalRound: number
  // The Sleeper league this mock borrowed its settings from (V16), so the rail
  // can show league context inside a mock room. Null for a mock started with no
  // league in mind, and for every session created before V16 -- those stored
  // only the league's display name, which is not a key and is not backfilled.
  //
  // OPTIONAL, not merely nullable, and deliberately so: the frontend and
  // backend are separate Railway services that deploy independently, so "new
  // frontend, old backend" is a state every rollout passes through. It cost a
  // white page on 2026-09-14 (see withSportDefaults below). A missing field
  // must degrade one rail section, never the page.
  sourceSleeperLeagueId?: string | null
}

// Mirrors store/MockDraftRepository.SessionSummary. Backs the picker screen's
// "Mock drafts" list.
export type MockSessionSummary = {
  id: number
  sport: Sport
  status: 'IN_PROGRESS' | 'COMPLETE'
  teams: number
  rounds: number
  userSlot: number
  currentPickNo: number
  createdAt: string
  // The real league whose settings this mock borrowed via the home screen's
  // "use settings from" step (V12) -- null for a mock started with no league
  // in mind. A display label; the sport is its own field above.
  sourceLeagueName: string | null
  // The same league as a key rather than a label (V16). Optional for the same
  // split-deploy reason as MockSessionState's copy above.
  sourceSleeperLeagueId?: string | null
}

/**
 * Backfills `sport`/`reversalRound` when the backend answering is older than
 * this bundle.
 *
 * The frontend and the backend are separate Railway services that deploy
 * independently, so "new frontend, old backend" is a real state the app passes
 * through on every rollout -- not a hypothetical. It happened on 2026-09-14:
 * the frontend shipped, the backend didn't, and `m.sport.toUpperCase()` on a
 * row with no `sport` threw during render and took the entire home screen to a
 * white page. A missing field should degrade one badge, never the page.
 *
 * `nfl` is the honest default rather than a guess: every mock written before
 * V14's column existed was football, which is exactly why the column shipped
 * with `default 'nfl'`. `reversalRound` 0 is plain snake, likewise what those
 * sessions actually ran.
 *
 * Applied here, at the one boundary the data enters, rather than at each of the
 * ~6 places that read it -- the repo's own two-implementations-of-one-rule
 * lesson (claude/multi-sport-landmines.md).
 */
const withSportDefaults = <T extends { sport?: Sport; reversalRound?: number }>(row: T) => ({
  ...row,
  sport: row.sport ?? ('nfl' as Sport),
  reversalRound: row.reversalRound ?? 0,
})

export const getMockSessions = () =>
  apiFetch('/api/mocks')
    .then(json<MockSessionSummary[]>)
    .then((rows) => rows.map(withSportDefaults))

// managerSeats seats a real manager's fitted/stated profile at a slot instead
// of an unmodelled bot -- keyed by slot number, same shape MockDraftController
// .CreateRequest expects. Any slot besides userSlot left out of it is still a
// plain bot.
//
// sourceSleeperLeagueId replaces V12's sourceLeagueName: the backend clones
// that league's roster template, round count, scoring and reversal round off
// the id, and looks the display name up itself. Omit it for a mock started
// with no league in mind, which then runs on the sport's own defaults.
//
// `sport` is always sent explicitly. The backend does default a missing one to
// football, but only for a pre-multi-sport client -- letting this function
// omit it would reintroduce exactly the silent football default the two-step
// modal exists to remove.
export const createMockSession = (
  sport: Sport,
  teams: number,
  userSlot: number,
  managerSeats?: Record<number, number>,
  sourceSleeperLeagueId?: string,
) =>
  apiFetch('/api/mocks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      sport,
      teams,
      userSlot,
      managerSeats: managerSeats ?? {},
      sourceSleeperLeagueId: sourceSleeperLeagueId ?? null,
    }),
  })
    .then(json<MockSessionState>)
    .then(withSportDefaults)

// Forks a real, drafting-status Sleeper draft into a new mock session seeded
// with its picks so far -- the live-draft-to-mock bridge. mySlot is optional;
// omitted, the backend falls back to the same owner auto-detection
// getSeats()'s mySlot already uses.
export const createMockSessionFromDraft = (sleeperDraftId: string, mySlot?: number) =>
  apiFetch(
    `/api/mocks/from-draft/${sleeperDraftId}${mySlot != null ? `?mySlot=${mySlot}` : ''}`,
    { method: 'POST' },
  )
    .then(json<MockSessionState>)
    .then(withSportDefaults)

export const getMockSession = (id: number) =>
  apiFetch(`/api/mocks/${id}`).then(json<MockSessionState>).then(withSportDefaults)

export const submitMockPick = (id: number, sleeperPlayerId: string) =>
  apiFetch(`/api/mocks/${id}/pick`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sleeperPlayerId }),
  })
    .then(json<MockSessionState>)
    .then(withSportDefaults)

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
  /**
   * specs/006-deeper-history-both-sports T040. The internal id, present on
   * every row from standingRow() (not conditional the way season/sleeperLeagueId
   * are) -- what a rank/chain lookup joins on, distinct from sleeperLeagueId,
   * which is what a re-ingest or a deep link uses.
   */
  leagueId: number
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  wins: number | null
  losses: number | null
  ties: number | null
  pointsFor: number | null
  pointsAgainst: number | null
  champion: boolean
  season: number | null
  sleeperLeagueId: string | null

  /**
   * specs/006-deeper-history-both-sports US1. Null from getLeagueHistory's
   * per-league call (the page already knows all three -- it asked for this
   * one league); populated from getManagerHistory, whose rows span several
   * leagues and sports with nothing else to tell them apart. Before this,
   * `ManagerHistory.tsx` summed NBA and NFL rows into one header record --
   * see baseline.md's T002 for the six-row, two-sport, no-`sport`-field
   * defect this fixes.
   */
  sport: Sport | null
  leagueName: string | null
  /** league.status == 'complete'. A null/false value is NOT complete -- see LeagueRepository.LeagueRow#complete(). */
  complete: boolean | null

  /**
   * specs/002-league-history-record-book US2. Optional, not required: this same
   * type backs getManagerHistory, whose rows are a manager's seasons across
   * leagues and carry no rank -- making these required would break that call's
   * typecheck rather than describe it.
   *
   * finalRank is non-null iff rankStatus is 'RANKED'. Every other status is a
   * different REASON there is no rank, and the page renders the reason; see
   * RankStatus.
   */
  finalRank?: number | null
  finalRankWeek?: number | null
  rankStatus?: RankStatus
}

/**
 * Why a season's row does or doesn't show a final power rank.
 *
 * The three absent cases are deliberately distinct. A season still being played
 * has no final rank and that is not an error; a finished season that was never
 * computed is one button away from having one; a finished season with no stored
 * weekly scores has nothing to compute from. Collapsing them into a bare "--"
 * is what this enum exists to prevent.
 */
export type RankStatus = 'RANKED' | 'IN_PROGRESS' | 'NOT_COMPUTED' | 'UNAVAILABLE'

export type SeasonHistory = {
  season: number
  leagueId: number
  sleeperLeagueId: string
  name: string | null
  standings: StandingRow[]
}

/**
 * specs/002-league-history-record-book. Mirrors
 * LeagueHistoryController.weeklyScoreRow(). `points` is a number, not a
 * pre-formatted string -- the wire carries the stored numeric(8,2) and the page
 * decides how to show it.
 */
export type WeeklyScoreRecord = {
  season: number
  week: number
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  points: number
}

export type MarginSide = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  points: number
}

export type MarginRecord = {
  season: number
  week: number
  margin: number
  winner: MarginSide
  loser: MarginSide
}

/**
 * specs/006-deeper-history-both-sports T060/T064. All-time points scored,
 * summed across the whole chain per manager (or per unowned roster-season --
 * see RecordWho, which already renders that case for the other four lists).
 * `spanSeasons` is what lets the page state "over N seasons" beside a total,
 * per US5.4: with one or two played seasons in this database, an unlabeled
 * all-time figure reads as a season record wearing a career number's name.
 */
export type PointsLeaderRecord = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  points: number
  spanSeasons: number[]
}

/**
 * A run of consecutive weeks one roster won (or lost) every game, within one
 * season (research R7). `withinSeasonOnly` rides on every entry so the page
 * states the rule rather than leaving it for the reader to assume (US5.2).
 */
export type StreakRecord = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  length: number
  spanSeasons: number[]
  startWeek: number
  endWeek: number
  withinSeasonOnly: boolean
}

/**
 * Always present on a 200, even when every list is empty. An absent key would
 * make "this league has no records" indistinguishable from "this server predates
 * the record book", which is exactly the ambiguity the contract forbids.
 *
 * `marginsUnavailableReason` is non-null exactly when the margin lists are
 * empty. A panel that renders nothing and says nothing reads as broken.
 *
 * `pointsLeaders`/`winStreaks`/`lossStreaks` carry no reason field of their
 * own: `pointsLeaders` is empty under the same condition as `highestWeeks`/
 * `lowestWeeks` (no stored weekly scores), and the two streak lists are empty
 * under the same condition as the margin lists (no paired games) -- the page
 * already has a sentence for each cause and reuses it.
 */
export type LeagueRecords = {
  limit: number
  highestWeeks: WeeklyScoreRecord[]
  lowestWeeks: WeeklyScoreRecord[]
  closestMatchups: MarginRecord[]
  biggestBlowouts: MarginRecord[]
  marginsUnavailableReason: string | null
  pointsLeaders: PointsLeaderRecord[]
  winStreaks: StreakRecord[]
  lossStreaks: StreakRecord[]
}

export type LeagueHistory = {
  sleeperLeagueId: string
  seasons: SeasonHistory[]
  records: LeagueRecords
}

export const getLeagueHistory = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/history`).then(json<LeagueHistory>)

export type BackfillResult = {
  backfilled: { season: number; week: number | null; entries: number }[]
  skipped: { season: number; reason: string }[]
}

/**
 * Computes the missing end-of-season power rank for completed seasons.
 *
 * Backs the button in a NOT_COMPUTED rank cell. It exists so the page never
 * has to print `POST /api/leagues/{id}/power/backfill` for the reader to run
 * in a terminal -- the same convention ingestLeagueHistory above already
 * carries.
 */
export const backfillFinalRanks = (sleeperLeagueId: string, season?: number) =>
  apiFetch(
    `/api/leagues/${sleeperLeagueId}/power/backfill${season == null ? '' : `?season=${season}`}`,
    { method: 'POST' },
  ).then(json<BackfillResult>)

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

// --- specs/006-deeper-history-both-sports US4: manager-vs-manager comparison ---

/** One side of a `versus` response -- just enough to render an identity, not a full ManagerHistory. */
export type VersusManagerRef = {
  managerId: number
  manager: string | null
  avatarId: string | null
}

/**
 * One scored, paired game between the two managers this endpoint compares.
 * `winner` is decided by `starters_points`, never by `roster_season.wins` --
 * that column is a whole season, and this is one game (mirrors
 * LeagueRecordService.margin's own comment). Ties are their own outcome
 * (US4.5), never folded into a side's losses.
 */
export type VersusMeeting = {
  season: number
  week: number
  leagueName: string
  sleeperLeagueId: string
  aPoints: number
  bPoints: number
  winner: 'A' | 'B' | 'TIE'
}

/**
 * A season both managers shared that produced zero meetings between THEM
 * specifically, named with its own reason rather than left silently absent
 * (US4.4). Backend's `HeadToHeadService.exclusionReason` distinguishes three
 * different facts here: fixtures scheduled but not yet scored, a schedule
 * that never paired them this season, or a league with nothing loaded at all
 * -- only the first of those resolves itself by waiting.
 */
export type VersusSeasonExcluded = {
  season: number
  leagueName: string
  reason: string
}

/**
 * One comparison figure, one value per side. Null on a side with no counted
 * season to compute it from -- the same null rules `CareerProfile`'s own
 * fields already state, since every value here is read straight off it.
 */
export type VersusFigure<T> = { a: T | null; b: T | null }

/**
 * The side-by-side career comparison -- each side is one manager's own
 * `CareerProfile` for this sport, read by `ManagerComparisonController`
 * rather than re-derived, so this page and `/managers/:id/history` can never
 * print two different numbers for the same manager.
 */
export type VersusComparison = {
  titles: VersusFigure<number>
  record: VersusFigure<string>
  pointsFor: VersusFigure<number>
  /** `pointsFor / (wins + losses + ties)`, not `/ weeks` -- matches the record beside it. */
  pointsPerGame: VersusFigure<number>
  winsAboveExpected: VersusFigure<number>
  averageEfficiency: VersusFigure<number>
  seasonsCounted: VersusFigure<number>
}

/** One sport's worth of the two managers' shared history. Never combined across sports (US4.2). */
export type VersusSport = {
  sport: Sport
  aWins: number
  bWins: number
  ties: number
  meetings: VersusMeeting[]
  seasonsExcluded: VersusSeasonExcluded[]
  comparison: VersusComparison
}

/**
 * Mirrors `ManagerComparisonController.versus`'s response shape. `sports` is
 * empty with `sharedNothing: true` when the two managers have never owned a
 * roster in the same `league_id` at all -- never a bare `0-0`, which would be
 * indistinguishable from "they played and split everything evenly" (US4.3).
 */
export type ManagerComparison = {
  a: VersusManagerRef
  b: VersusManagerRef
  sports: VersusSport[]
  sharedNothing: boolean
}

export const getManagerComparison = (aId: number, bId: number) =>
  apiFetch(`/api/managers/${aId}/versus/${bId}`).then(json<ManagerComparison>)

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
  avatarId: string | null
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

  // Rest-of-season Monte Carlo, real as of claude/playoff-odds.md. Null for
  // any week with no stored odds snapshot -- weeks that predate the feature,
  // and leagues whose seeding this app refuses to model (divisions, a
  // non-default playoff_seed_type). Null means "no answer", never "0%".
  makesPlayoffsPct?: number | null
}

/** `GET /state/{sport}` for THIS league's sport, not always football.
 *
 *  `week` is legitimately 0: measured 2026-09-15, `/state/nba` answers
 *  `week: 0` for the whole offseason while `/state/nfl` answers 2. Compare it
 *  with `===` or a null check and never for truthiness -- football is never at
 *  week 0 during a season, so a `!week` written here fails for basketball
 *  alone, and silently. */
export type SportState = {
  week: number
  season: string
  seasonStartDate: string
  started: boolean
}

/** How the odds on these entries were produced, so the page can say so out loud. */
export type PlayoffOddsSummary = {
  week: number
  iterations: number
  model: string
  weeksOfScoring: number
}

export type PowerRankings = {
  sleeperLeagueId: string
  sportState: SportState
  entries: PowerRankingEntry[]
  // Null when this league has no odds at all; the page then says nothing about
  // a simulation rather than describing one that never ran.
  playoffOdds?: PlayoffOddsSummary | null
}

export const getPowerRankings = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/power`).then(json<PowerRankings>)

// --- claude/league-analysis.md: the League analysis page ---

/**
 * Mirrors LeagueAnalysisService's records. Both blocks carry `available` and a
 * `reason` rather than an empty list the page has to interpret: "too early to
 * say" and "something broke" look identical from a zero-length array, and only
 * one of them is worth showing the reader.
 */
export type AnalysisWindow = {
  fromWeek: number
  toWeek: number
  weeks: number
  scoredWeeks: number
}

export type AnalysisScoreEntry = {
  rank: number
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  /** 1-100, league mean at 50. Our scaling of `raw` -- see the service. */
  score: number
  /** What ffwrapped's published formula actually returns, unscaled. */
  raw: number
  avgWeekly: number
  high: number
  low: number
  winPct: number
  wins: number
  losses: number
  ties: number
}

export type AnalysisRankingScores = {
  available: boolean
  reason: string | null
  formula: string
  weeksScored: number
  weeksRequired: number
  entries: AnalysisScoreEntry[]
}

export type AnalysisLineupPlayer = {
  sleeperPlayerId: string
  name: string
  position: string
  team: string | null
  /** The slot filled -- "FLEX" where `position` is RB/WR/TE, "BN" on the bench. */
  slot: string
  points: number
  /** Sleeper's tag as of the last player ingest: "IR", "Out", "Questionable"... */
  injuryStatus: string | null
}

export type AnalysisRosterProjection = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  rank: number
  /** This is the signed-in reader's own roster, decided by Sleeper owner_id on the backend. */
  isMe: boolean
  total: number
  byPosition: Record<string, number>
  rankByPosition: Record<string, number>
  /** In the league's own slot order -- QB, RB, RB, WR, WR, TE, FLEX, FLEX, K, DEF. */
  starters: AnalysisLineupPlayer[]
  /** Everyone who did not start, best projection first. The zeroes at the bottom are the IRs. */
  bench: AnalysisLineupPlayer[]
  /**
   * The rest-of-season total taken apart again, one entry per remaining week.
   * `total` is the sum of these; the sum is where a bye week disappears, which
   * is why the list is carried separately rather than derived from it.
   */
  byWeek: AnalysisWeekTotal[]
  /** Rostered players with no projection in the window: IR, Out, PUP. */
  missing: number
}

export type AnalysisWeekTotal = {
  week: number
  points: number
  /** This roster's projected rank in THAT week, 1 = highest, ties shared. */
  rank: number
}

export type AnalysisProjections = {
  available: boolean
  reason: string | null
  positionGroups: string[]
  rosters: AnalysisRosterProjection[]
}

/**
 * One side of one game. Sleeper has no home team -- only a `matchupId`
 * grouping two rosters -- so the wire has sides, best projection first, and a
 * `sides` of length 1 is a bye rather than a missing opponent.
 */
export type AnalysisSide = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  isMe: boolean
  projected: number
  byPosition: Record<string, number>
  starters: AnalysisLineupPlayer[]
}

export type AnalysisMatchup = {
  matchupId: number
  sides: AnalysisSide[]
}

export type AnalysisMatchups = {
  available: boolean
  reason: string | null
  /** The next unplayed week, projected on its own rather than sliced out of the rest of the season. */
  week: number
  matchups: AnalysisMatchup[]
}

/** @param rank this roster's scoring rank in THAT week, 1 = highest. */
export type AnalysisWeekScore = {
  week: number
  points: number
  rank: number
}

export type AnalysisScoreRow = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  isMe: boolean
  weeks: AnalysisWeekScore[]
  total: number
  avg: number
  high: number
  low: number
}

/** What every roster actually scored, week by week -- settled data, unlike the projections. */
export type AnalysisScores = {
  available: boolean
  reason: string | null
  weeks: number[]
  rosters: AnalysisScoreRow[]
}

export type LeagueAnalysis = {
  season: number
  /** Which of Sleeper's three scoring totals this league is read under. */
  scoringKey: 'PPR' | 'HALF_PPR' | 'STANDARD'
  window: AnalysisWindow
  rankingScores: AnalysisRankingScores
  projections: AnalysisProjections
  matchups: AnalysisMatchups
  scores: AnalysisScores
}

/**
 * @param week which week the MATCHUP block is valued for; the next unplayed
 *   one when omitted. Nothing else in the response moves with it -- the
 *   rest-of-season projections and the scores grid are not statements about a
 *   chosen week.
 */
export const getLeagueAnalysis = (sleeperLeagueId: string, week?: number) =>
  apiFetch(
    `/api/leagues/${sleeperLeagueId}/analysis${week == null ? '' : `?week=${week}`}`,
  ).then(json<LeagueAnalysis>)

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
  avatarId: string | null
  /** Sleeper's metadata.team_name, already resolved -- the backend applies the
   *  team_name -> display_name -> null chain and treats the literal "TBD" as
   *  absent (design doc finding 19), so this is renderable as-is. */
  teamName: string | null
  isMe: boolean
}

/**
 * Mirrors LeagueHistoryController.ballot(). `canSubmit` folds together every
 * reason a ballot might be refused -- signed out, not a member of this league,
 * and a week that is not the current one -- so the client never has to
 * re-derive the rule and disagree with the server about it. Sport is no longer
 * one of those reasons (claude/nba-power-rankings.md); a league whose members
 * have never been ingested is, via `isMember`, which is what an NBA league
 * looks like before its first post-V9 ingest.
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
  avatarId: string | null
  totalPoints: number
  potentialPoints: number
  efficiency: number | null
  weeksCounted: number
  weeksExcluded: number[]
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
  teams: RosterManagementTeam[]
}

export const getRosterManagement = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/roster-management`).then(json<RosterManagement>)

// --- specs/004-ffwrapped-feature-parity US3: Expected wins ---

/**
 * `luckSource` is a discriminator, not a pair of optional blocks: `swingWeeks`
 * is non-empty only for SWING_WEEKS, so the page shows one explanation or the
 * other and never both.
 */
export type ExpectedWinsSwing = {
  week: number
  result: 'WON' | 'LOST'
  points: number
  weeklyRank: number
  opponent: string
}

export type ExpectedWinsTeam = {
  rosterId: number
  managerId: number | null
  teamName: string
  avatarId: string | null
  expectedWins: number
  actualWins: number
  winsAboveExpected: number
  /** Opponents' points per game minus the league's. Positive = harder schedule. */
  strengthOfSchedule: number
  luckSource: 'SWING_WEEKS' | 'CONSISTENT_OPPONENT_SCORING'
  swingWeeks: ExpectedWinsSwing[]
}

export type ExpectedWins = {
  available: boolean
  reason?: string | null
  season: number
  requestedSeason?: number | null
  sport: Sport
  weeksScored: number
  leagueAveragePpg: number
  teams: ExpectedWinsTeam[]
}

export const getExpectedWins = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/expected-wins`).then(json<ExpectedWins>)

// --- specs/004-ffwrapped-feature-parity US4: Season forecast ---

/**
 * Read from one stored simulation snapshot, never computed on load — which is
 * what keeps this view and the playoff-odds figure in the Record cell showing
 * the same number.
 *
 * `winRange` and `averageSeed` are nullable: a snapshot taken before the
 * distributions were stored has no range, and that is not a range of zero.
 */
export type ForecastTeam = {
  rosterId: number
  managerId: number | null
  teamName: string
  avatarId: string | null
  playoffOdds: number
  averageWins: number
  projectedPoints: number
  winRange: { p10: number | null; p90: number | null }
  averageSeed: number | null
  seedOnePct: number
  seedOdds: Record<string, number>
}

/** `reason` names which refusal applies; they need different words on screen. */
export type SeasonForecast = {
  available: boolean
  reason?: 'UNMODELLED_SEEDING' | 'NO_SCORED_WEEKS' | 'NOT_COMPUTED' | null
  season: number
  requestedSeason?: number | null
  week?: number
  iterations?: number
  model?: string | null
  teams: ForecastTeam[]
}

export const getSeasonForecast = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/forecast`).then(json<SeasonForecast>)

// --- specs/004-ffwrapped-feature-parity US5: Weekly report ---

export type WeeklySide = {
  rosterId: number
  teamName: string
  avatarId: string | null
  record: string
  points: number
}

export type WeeklyMatchup = { home: WeeklySide; away: WeeklySide }

export type WeeklyPerformer = {
  playerId: string
  playerName: string
  position: string
  teamName: string
  points: number
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
  week: number
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

export const getWeeklyReport = (sleeperLeagueId: string, week: number) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/weekly-report/${week}`).then(json<WeeklyReport>)

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
