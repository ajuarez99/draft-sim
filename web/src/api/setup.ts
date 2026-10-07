import type { RealPick, Sport } from './draft'
import { apiFetch, json } from './http'

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
  draftId: string
  status: string | null
  observed: boolean
  tracking: boolean
  alreadyTracking: boolean
  seatsMapped: number
  teams: number
}

export const trackDraft = (sleeperDraftId: string) =>
  apiFetch(`/api/drafts/${sleeperDraftId}/track`, { method: 'POST' }).then(json<TrackResponse>)

// The setup stages below call /api/setup/*, NOT /api/ingest/*. The ingest routes
// now need the server's admin token (claude/audit-2026-09-28/01), which a browser
// must never hold; the setup routes do the same work but are authorised by
// membership instead (MemberSetupController): the caller must be signed in and
// appear in the league, by our own data or by Sleeper's league-users list.
//
// Scoped to the one league being added -- unlike /api/ingest/all, this doesn't
// re-download the entire player pool or rebuild the global board/profiles.
export const ingestLeague = (sleeperLeagueId: string) =>
  apiFetch(`/api/setup/league/${sleeperLeagueId}`, { method: 'POST' }).then(json<Record<string, unknown>>)

// The other setup stages, called individually rather than through one combined
// call -- claude/user-identity-and-onboarding.md §5d: `all` runs three sequential
// Sleeper crawls plus a rebuild and can exceed a 30-60s platform HTTP timeout on
// a first-ever ingest, and splitting it lets the UI show staged progress instead.
// (The player list is `refreshPlayers` below; there is no browser-facing twin of
// /api/ingest/players any more.)

/**
 * Mirrors RefreshController.players's body (contracts/refresh-api.md). `detail` is
 * null when the day's fetch was already done, and the backend builds this body
 * from a mutable map for that reason. A `FAILED` outcome comes with a 500, so a
 * caller normally sees it as a rejected promise rather than as this value.
 */
export type RefreshPlayersResult = {
  outcome: 'DONE' | 'SKIPPED_ALREADY_TODAY' | 'FAILED'
  detail: string | null
}

/**
 * The setup flow's player-list fetch, gated to once per sport per UTC day so a
 * burst of new leagues costs Sleeper one request, not one each (FR-009).
 * Needs a signed-in identity (the server refuses a header-less caller).
 */
export const refreshPlayers = (sport: Sport) =>
  apiFetch(`/api/refresh/players?sport=${sport}`, { method: 'POST' }).then(json<RefreshPlayersResult>)

export const ingestAdp = (sport: Sport) =>
  apiFetch(`/api/setup/adp?sport=${sport}`, { method: 'POST' }).then(json<Record<string, unknown>>)

export const ingestBoard = (sport: Sport) =>
  apiFetch(`/api/setup/board?sport=${sport}`, { method: 'POST' }).then(json<Record<string, unknown>>)

// Walks a league's `previous_league_id` chain and stores each season's
// standings. Backs the "Load past seasons" button on the history page --
// which used to be a `POST /api/ingest/league-history/{id}` printed on screen
// for the reader to run in a terminal. Now the membership-checked setup twin.
export const ingestLeagueHistory = (sleeperLeagueId: string) =>
  apiFetch(`/api/setup/league-history/${sleeperLeagueId}`, { method: 'POST' }).then(json<Record<string, unknown>>)
