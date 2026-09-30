import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ManagerTendencies from './ManagerTendencies'
import type { ManagerSummary } from '../api'

const getManagers = vi.fn()
vi.mock('../api', () => ({
  getManagers: (...args: unknown[]) => getManagers(...args),
}))

function m(over: Partial<ManagerSummary>): ManagerSummary {
  return {
    managerId: 1,
    manager: 'A',
    avatarId: null,
    provenance: 'FITTED',
    effectiveReachBias: 6,
    empiricalReachBias: 9,
    relativeReachBias: null,
    relativeReachStdErr: null,
    unpredictability: 1,
    positionalTilt: {},
    note: null,
    draftsObserved: 1,
    picksScored: 15,
    stated: { reachBias: null, unpredictability: null, note: null },
    draftHistory: [],
    careers: [],
    ...over,
  } as ManagerSummary
}

beforeEach(() => {
  localStorage.clear()
  getManagers.mockReset()
})

describe('ManagerTendencies reach display (audit 11)', () => {
  it('states the room-relative frame and never falls back to the absolute figure', async () => {
    getManagers.mockImplementation((sport: string) =>
      Promise.resolve(
        sport === 'nfl'
          ? [
              m({ managerId: 1, manager: 'Early Eddie', relativeReachBias: 12, relativeReachStdErr: 4 }),
              m({ managerId: 2, manager: 'Late Larry', relativeReachBias: -9.5, relativeReachStdErr: 3 }),
              m({ managerId: 3, manager: 'Middle Mike', relativeReachBias: 2, relativeReachStdErr: 5 }),
              m({ managerId: 4, manager: 'No Score', picksScored: 0, relativeReachBias: null, effectiveReachBias: 9.9 }),
            ]
          : [],
      ),
    )
    render(
      <MemoryRouter>
        <ManagerTendencies />
      </MemoryRouter>,
    )
    expect(await screen.findByText(/12\.0 picks earlier than their draft room/)).toBeInTheDocument()
    expect(screen.getByText(/9\.5 picks later than their draft room/)).toBeInTheDocument()
    // Inside one standard error: no number claimed.
    expect(screen.getAllByText(/drafts like the room/).length).toBeGreaterThanOrEqual(1)
    expect(screen.queryByText(/(^|\D)2\.0 picks/)).not.toBeInTheDocument()
    // The manager with nothing scoreable gets the reason, not a bar built from effectiveReachBias (9.9).
    expect(screen.queryByText(/9\.9 picks/)).not.toBeInTheDocument()
    // The page says what the numbers are measured against.
    expect(screen.getByText(/same draft, not the market board/)).toBeInTheDocument()
    // Most extreme first: |12| before |-9.5| before |2|.
    const order = screen.getAllByRole('link').map((a) => a.textContent)
    expect(order.indexOf('Early Eddie')).toBeLessThan(order.indexOf('Late Larry'))
    expect(order.indexOf('Late Larry')).toBeLessThan(order.indexOf('Middle Mike'))
  })
})
