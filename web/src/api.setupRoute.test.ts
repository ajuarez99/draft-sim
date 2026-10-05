import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ingestLeague } from './api'

/**
 * /api/ingest/** is operator-only (admin token, claude/audit-2026-09-28/01); the browser's
 * "Add a league" and "Refresh" flows go to the membership-checked /api/setup routes instead.
 * Kept from api.commissionerKey.test.ts when the commissioner key was removed on 2026-10-05.
 */
let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetchMock = vi.fn().mockResolvedValue(
    new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
  )
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('setup flows', () => {
  it('go to the membership-checked /api/setup routes, not /api/ingest', async () => {
    await ingestLeague('L1')

    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/setup/league/L1')
    expect(String(fetchMock.mock.calls[0][0])).not.toContain('/api/ingest')
  })

  it('never send an admin token', async () => {
    await ingestLeague('L1')

    expect(new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers).get('X-Admin-Token')).toBeNull()
  })
})
