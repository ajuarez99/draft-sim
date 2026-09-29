import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { streamSimulationQuietly } from './api'
import { ApiError } from './apiError'

const REQ = { draftSleeperId: 'd1', mySlot: 1, iterations: 500, temperature: 1 }

const busy = () => ({
  ok: false,
  status: 429,
  body: null,
  headers: { get: (h: string) => (h === 'Retry-After' ? '2' : null) },
  json: () => Promise.resolve({ error: 'busy' }),
})

const sse = (payload: unknown) => {
  const text = `event: result\ndata: ${JSON.stringify(payload)}\n\n`
  return {
    ok: true,
    status: 200,
    body: new ReadableStream<Uint8Array>({
      start(c) {
        c.enqueue(new TextEncoder().encode(text))
        c.close()
      },
    }),
  }
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('streamSimulationQuietly', () => {
  it('retries a 429 after Retry-After and returns the eventual result', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(busy()).mockResolvedValueOnce(sse({ iterations: 500 }))
    vi.stubGlobal('fetch', fetchMock)
    const p = streamSimulationQuietly(REQ, () => {})
    await vi.advanceTimersByTimeAsync(2500)
    await expect(p).resolves.toMatchObject({ iterations: 500 })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('gives up after three retries and throws the 429', async () => {
    const fetchMock = vi.fn().mockResolvedValue(busy())
    vi.stubGlobal('fetch', fetchMock)
    const p = streamSimulationQuietly(REQ, () => {}).catch((e: unknown) => e)
    await vi.advanceTimersByTimeAsync(60_000)
    const err = await p
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(429)
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })

  it('does not retry anything but a 429', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      body: null,
      headers: { get: () => null },
      json: () => Promise.resolve({ error: 'nope' }),
    })
    vi.stubGlobal('fetch', fetchMock)
    await expect(streamSimulationQuietly(REQ, () => {})).rejects.toMatchObject({ status: 409 })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('exposes Retry-After on the ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(busy()))
    const p = streamSimulationQuietly(REQ, () => {}, undefined, { maxRetries: 0 }).catch((e: unknown) => e)
    const err = (await p) as ApiError
    expect(err.retryAfterSeconds).toBe(2)
  })
})
