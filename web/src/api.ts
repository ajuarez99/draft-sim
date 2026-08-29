// Types mirror the Java records in engine/SimulationResult.java. They are
// hand-maintained; if you change a record over there, change it here.

export type PlayerRef = {
  id: number
  name: string
  position: 'QB' | 'RB' | 'WR' | 'TE' | 'K' | 'DEF'
  team: string | null
  adp: number
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
}

export type SimRequest = {
  draftSleeperId: string
  mySlot: number
  iterations: number
  temperature: number
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

  if (!result) throw new Error('stream ended without a result')
  return result
}
