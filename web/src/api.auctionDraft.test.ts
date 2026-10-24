import { afterEach, describe, expect, it, vi } from 'vitest'
import { streamSimulation } from './api'
import { ApiError } from './apiError'

/**
 * Spec 046 C10: the simulator answers 409 {"error":"auction_draft","message":<sentence>} for an auction draft.
 * The page shows ApiError.message, so it has to be the sentence, not the code.
 */
afterEach(() => vi.unstubAllGlobals())

const stub = (body: unknown) =>
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({ ok: false, status: 409, body: null, json: () => Promise.resolve(body) }),
  )

const run = () =>
  streamSimulation({ draftSleeperId: 'd1', mySlot: 1, iterations: 500, temperature: 1 }, () => {}).catch((e: unknown) => e)

describe('streamSimulation on an auction draft', () => {
  it('surfaces the server sentence, not the auction_draft code', async () => {
    stub({ error: 'auction_draft', message: 'This is an auction draft. The simulator models snake pick order.' })
    const err = (await run()) as ApiError
    expect(err).toBeInstanceOf(ApiError)
    expect(err.status).toBe(409)
    expect(err.message).toBe('This is an auction draft. The simulator models snake pick order.')
  })

  it('falls back to the code if a body ever arrives without the sentence', async () => {
    stub({ error: 'auction_draft' })
    expect(((await run()) as ApiError).message).toBe('auction_draft')
  })

  it('leaves every other error body alone: `error` still wins', async () => {
    stub({ error: 'The simulator is busy with other runs right now.', message: 'ignored' })
    expect(((await run()) as ApiError).message).toMatch(/busy/)
  })
})
