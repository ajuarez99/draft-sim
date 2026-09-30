import { afterEach, describe, expect, it, vi } from 'vitest'
import { streamSimulation } from './api'
import { ApiError } from './apiError'

/**
 * audit 05: the backend now answers 429 (with a JSON `error`) when every
 * simulation permit is taken. That has to reach the page as a readable
 * ApiError, not a crash or a bare "HTTP 429".
 */
afterEach(() => vi.unstubAllGlobals())

describe('streamSimulation when the simulator is busy', () => {
  it('rejects with an ApiError carrying the 429 status and the server message', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 429,
        body: null,
        json: () => Promise.resolve({ error: 'The simulator is busy with other runs right now.' }),
      }),
    )
    const err = await streamSimulation(
      { draftSleeperId: 'd1', mySlot: 1, iterations: 500, temperature: 1 },
      () => {},
    ).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(429)
    expect((err as ApiError).message).toMatch(/busy/)
  })
})
