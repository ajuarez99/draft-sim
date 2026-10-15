import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../apiError'

const getProjectionAccuracy = vi.fn()
vi.mock('../api', () => ({ getProjectionAccuracy: (...a: unknown[]) => getProjectionAccuracy(...a) }))
vi.mock('../pages/LeagueHome', () => ({ formatIsoDate: (d: string) => d }))

import ProjectionAccuracyPanel from './ProjectionAccuracyPanel'

// Spec 035 FR-008, at the 036 merge: an ESPN league's accuracy is refused (422), and the panel must say why
// rather than "couldn't be loaded just now", which reads as a transient glitch.
describe('ProjectionAccuracyPanel refusal', () => {
  it('names the untranslated-scoring reason on a 422', async () => {
    getProjectionAccuracy.mockRejectedValueOnce(new ApiError(422, 'SCORING_UNTRANSLATED'))
    render(<ProjectionAccuracyPanel sleeperLeagueId="espn-fba-1-2026" />)
    expect(await screen.findByText(/ESPN scoring rules aren't translated yet/)).toBeInTheDocument()
    expect(screen.queryByText(/couldn't be loaded just now/)).not.toBeInTheDocument()
  })

  it('keeps the transient message for any other failure', async () => {
    getProjectionAccuracy.mockRejectedValueOnce(new ApiError(500))
    render(<ProjectionAccuracyPanel sleeperLeagueId="123" />)
    expect(await screen.findByText(/couldn't be loaded just now/)).toBeInTheDocument()
  })
})
