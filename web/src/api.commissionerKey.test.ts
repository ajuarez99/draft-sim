import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, computePowerRankings, ingestLeague, saveConductEntry, getBallot } from './api'
import {
  clearCommissionerKey,
  getCommissionerKey,
  setCommissionerKey,
  setCommissionerKeyPrompter,
} from './commissionerKey'

/**
 * The commissioner key (claude/audit-2026-09-28/04, option D): commissioner-only calls send it as
 * X-Admin-Token, ask for it once when the server says it is missing or wrong, and nothing else
 * ever carries it. The server marks that refusal with `code: 'admin_token_required'` so it can be
 * told apart from "you are not this league's commissioner", which is also a 403.
 */
const REFUSAL = { code: 'admin_token_required', message: 'this action needs the commissioner key (admin token)' }

function response(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

let fetchMock: ReturnType<typeof vi.fn>

function adminHeaderOf(call: number): string | null {
  const init = fetchMock.mock.calls[call][1] as RequestInit
  return new Headers(init.headers).get('X-Admin-Token')
}

beforeEach(() => {
  clearCommissionerKey()
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearCommissionerKey()
})

describe('commissioner-only calls', () => {
  it('prompts once when refused for want of a key, saves it, retries with it, and succeeds', async () => {
    const prompter = vi.fn().mockReturnValue('  the-key ')
    setCommissionerKeyPrompter(prompter)
    fetchMock
      .mockResolvedValueOnce(response(403, REFUSAL))
      .mockResolvedValueOnce(response(200, { week0: 0, realized: 12 }))

    const result = await computePowerRankings('L1', 2026, 2)

    expect(result.realized).toBe(12)
    expect(prompter).toHaveBeenCalledTimes(1)
    expect(adminHeaderOf(0)).toBeNull()
    expect(adminHeaderOf(1)).toBe('the-key')
    expect(getCommissionerKey()).toBe('the-key')
  })

  it('sends a saved key straight away and does not prompt', async () => {
    setCommissionerKey('saved-key')
    const prompter = vi.fn()
    setCommissionerKeyPrompter(prompter)
    fetchMock.mockResolvedValueOnce(response(200, { id: 1 }))

    await saveConductEntry('L1', { playerId: 'p', reason: 'r', appliesFromWeek: 1 })

    expect(adminHeaderOf(0)).toBe('saved-key')
    expect(prompter).not.toHaveBeenCalled()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('forgets a key the server refuses again, rather than resending it forever', async () => {
    setCommissionerKey('stale-key')
    setCommissionerKeyPrompter(vi.fn().mockReturnValue('still-wrong'))
    fetchMock.mockResolvedValue(response(403, REFUSAL))

    await expect(computePowerRankings('L1', 2026, 2)).rejects.toBeInstanceOf(ApiError)

    expect(fetchMock).toHaveBeenCalledTimes(2)   // one refusal, one retry, then stop
    expect(getCommissionerKey()).toBeNull()
  })

  it('gives up without a retry when the person cancels the prompt', async () => {
    setCommissionerKeyPrompter(vi.fn().mockReturnValue(null))
    fetchMock.mockResolvedValue(response(403, REFUSAL))

    await expect(computePowerRankings('L1', 2026, 2)).rejects.toMatchObject({ status: 403 })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(getCommissionerKey()).toBeNull()
  })

  it('does not prompt for an ordinary 403 such as "not the commissioner"', async () => {
    const prompter = vi.fn()
    setCommissionerKeyPrompter(prompter)
    fetchMock.mockResolvedValue(response(403, { message: "only this league's Sleeper commissioner may edit" }))

    await expect(saveConductEntry('L1', { playerId: 'p', reason: 'r', appliesFromWeek: 1 })).rejects.toThrow(
      /only this league/,
    )

    expect(prompter).not.toHaveBeenCalled()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe('every other call', () => {
  it('never carries the commissioner key', async () => {
    setCommissionerKey('saved-key')
    // A fresh Response per call: a body can only be read once.
    fetchMock.mockImplementation(() => Promise.resolve(response(200, {})))

    await getBallot('L1')
    await ingestLeague('L1')

    for (let i = 0; i < fetchMock.mock.calls.length; i++) expect(adminHeaderOf(i)).toBeNull()
  })

  it('sends the setup flows to the membership-checked /api/setup routes, not /api/ingest', async () => {
    fetchMock.mockResolvedValue(response(200, {}))

    await ingestLeague('L1')

    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/setup/league/L1')
    expect(String(fetchMock.mock.calls[0][0])).not.toContain('/api/ingest')
  })
})
