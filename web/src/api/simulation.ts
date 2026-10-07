import { ApiError } from '../apiError'
import type { SimRequest, SimulationResult } from './draft'
import { apiError, apiFetch } from './http'

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
    throw await apiError(res)
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

/**
 * `streamSimulation` for a resim the user did not explicitly ask for (a pick
 * landing, a locked-in choice). The backend waits briefly for a simulation
 * permit and then answers 429 + Retry-After (audit 05); on draft night every
 * tab resimulates at once, so a 429 here is expected weather, not a failure.
 * Retry quietly, up to `maxRetries` times, honouring Retry-After with a capped
 * backoff. The caller keeps its previous board on screen meanwhile. Anything
 * other than a 429, or a 429 that survives every retry, is thrown as-is.
 * A user-initiated "Run" should call `streamSimulation` directly and show the
 * error at once.
 */
export async function streamSimulationQuietly(
  req: SimRequest,
  onProgress: (fraction: number) => void,
  signal?: AbortSignal,
  opts: { maxRetries?: number; capMs?: number } = {},
): Promise<SimulationResult> {
  const maxRetries = opts.maxRetries ?? 3
  const capMs = opts.capMs ?? 8000
  for (let attempt = 0; ; attempt++) {
    try {
      return await streamSimulation(req, onProgress, signal)
    } catch (e) {
      if (!(e instanceof ApiError) || e.status !== 429 || attempt >= maxRetries || signal?.aborted) throw e
      const waitMs = Math.min(capMs, (e.retryAfterSeconds ?? 2) * 1000 * (attempt + 1))
      await new Promise<void>((resolve, reject) => {
        const id = setTimeout(resolve, waitMs)
        signal?.addEventListener('abort', () => { clearTimeout(id); reject(e) }, { once: true })
      })
    }
  }
}
