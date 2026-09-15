import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import StartMockModal, { type MockableLeague } from './StartMockModal'

/**
 * The modal's one job is that the sport of a mock draft is always chosen
 * explicitly and never defaulted to football
 * (design_handoff_multisport_mock_drafts). It shipped with the NBA chip
 * disabled behind a "Soon" badge because the backend refused basketball;
 * claude/nba-mock-drafts.md removed that refusal, and these tests pin the
 * behaviour that replaced it.
 */
const LEAGUES: MockableLeague[] = [
  {
    sleeperLeagueId: 'nfl-1',
    leagueName: '(Foot) Ball Knowers',
    sport: 'nfl',
    teams: 12,
    rounds: 15,
    season: 2026,
  },
  {
    sleeperLeagueId: 'nba-1',
    leagueName: 'Ball Knowers',
    sport: 'nba',
    teams: 12,
    rounds: 14,
    season: 2026,
  },
]

const onStart = vi.fn()
const onClose = vi.fn()

beforeEach(() => {
  onStart.mockReset()
  onClose.mockReset()
})

function renderModal(initialSport: 'nfl' | 'nba' = 'nfl', initialLeagueId?: string) {
  render(
    <StartMockModal
      leagues={LEAGUES}
      initialSport={initialSport}
      initialLeagueId={initialLeagueId}
      onClose={onClose}
      onStart={onStart}
    />,
  )
}

describe('StartMockModal', () => {
  it('offers NBA as a real choice, not a disabled "Soon" chip', () => {
    renderModal()

    const nba = screen.getByRole('button', { name: 'NBA' })
    expect(nba).toBeEnabled()
    expect(screen.queryByText(/soon/i)).toBeNull()
  })

  it('reports the chosen sport and league, so the backend never has to guess', async () => {
    const user = userEvent.setup()
    renderModal('nba')

    await user.click(screen.getByRole('button', { name: 'Start NBA mock' }))

    expect(onStart).toHaveBeenCalledWith({
      sport: 'nba',
      teams: 12,
      rounds: 14,
      sourceSleeperLeagueId: 'nba-1',
      sourceLeagueName: 'Ball Knowers',
    })
  })

  it('shows only the selected sport’s leagues, and re-defaults when the sport changes', async () => {
    const user = userEvent.setup()
    renderModal('nfl')

    expect(screen.getByText('(Foot) Ball Knowers')).toBeInTheDocument()
    expect(screen.queryByText('Ball Knowers')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'NBA' }))

    expect(screen.getByText('Ball Knowers')).toBeInTheDocument()
    expect(screen.queryByText('(Foot) Ball Knowers')).toBeNull()

    // The football league that was selected a moment ago must not survive the
    // switch -- starting an "NBA mock" off NFL settings is the same bug as
    // defaulting to football, wearing the other sport's label.
    await user.click(screen.getByRole('button', { name: 'Start NBA mock' }))
    expect(onStart).toHaveBeenCalledWith(expect.objectContaining({ sport: 'nba', sourceSleeperLeagueId: 'nba-1' }))
  })

  it('labels the CTA with the sport being started', async () => {
    const user = userEvent.setup()
    renderModal('nfl')

    expect(screen.getByRole('button', { name: 'Start NFL mock' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'NBA' }))

    expect(screen.getByRole('button', { name: 'Start NBA mock' })).toBeInTheDocument()
  })

  it('disables the CTA when the chosen sport has no leagues to clone', () => {
    render(
      <StartMockModal
        leagues={[LEAGUES[0]]}
        initialSport="nba"
        onClose={onClose}
        onStart={onStart}
      />,
    )

    expect(screen.getByRole('button', { name: 'Start NBA mock' })).toBeDisabled()
    expect(screen.getByText(/No NBA leagues to clone yet/i)).toBeInTheDocument()
  })
})
