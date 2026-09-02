// Types mirror the Java records in engine/SimulationResult.java. They are
// hand-maintained; if you change a record over there, change it here.

export type PlayerRef = {
  id: number
  sleeperId: string
  name: string
  position: 'QB' | 'RB' | 'WR' | 'TE' | 'K' | 'DEF'
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
}

export type SimRequest = {
  draftSleeperId: string
  mySlot: number
  iterations: number
  temperature: number
  startState?: Record<number, string>
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }))
    throw new Error(body.error ?? `HTTP ${res.status}`)
  }
  return res.json() as Promise<T>
}

export const getSeats = (draftId: string) =>
  fetch(`/api/drafts/${draftId}/seats`).then(json<SeatsResponse>)

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
}

export const getDrafts = () => fetch('/api/drafts').then(json<DraftSummary[]>)

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
  fetch(`/api/drafts/${sleeperDraftId}/track`, { method: 'POST' }).then(json<TrackResponse>)

// Scoped to the one league being added -- unlike /api/ingest/all, this doesn't
// re-download the entire player pool or rebuild the global board/profiles.
export const ingestLeague = (sleeperLeagueId: string) =>
  fetch(`/api/ingest/league/${sleeperLeagueId}`, { method: 'POST' }).then(json<Record<string, unknown>>)

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
  unpredictability: number
  positionalTilt: Record<string, number>
  note: string | null
  draftsObserved: number
  picksScored: number
  stated: ManualTendencies
}

export const getManagers = () => fetch('/api/managers').then(json<ManagerSummary[]>)

export const setTendencies = (managerId: number, body: ManualTendencies) =>
  fetch(`/api/managers/${managerId}/tendencies`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then(json<unknown>)

export const clearTendencies = (managerId: number) =>
  fetch(`/api/managers/${managerId}/tendencies`, { method: 'DELETE' }).then(json<unknown>)

export const getBoard = (limit = 60) =>
  fetch(`/api/board?limit=${limit}`).then(json<{ capturedOn: string; entries: unknown[] }>)

/**
 * The backend streams Server-Sent Events, but the request is a POST and the
 * browser's built-in EventSource only does GET. So we read the response body as
 * a stream and parse the SSE framing ourselves.
 *
 * SSE framing is simple: events are separated by a blank line, and within an
 * event each line is "field: value". We only care about "event:" and "data:".
 */
export async function streamSimulation(
  req: SimRequest,
  onProgress: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<SimulationResult> {
  const res = await fetch('/api/sims/stream', {
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
