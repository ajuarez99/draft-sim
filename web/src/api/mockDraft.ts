import type { PlayerRef, Sport } from './draft'
import { apiFetch, json } from './http'

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
