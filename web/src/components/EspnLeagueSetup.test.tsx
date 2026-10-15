import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import EspnLeagueSetup, { espnErrorText } from './EspnLeagueSetup'
import PlatformBadge from './PlatformBadge'
import { EspnSetupError, type EspnAddResult } from '../api'

let fetchMock: ReturnType<typeof vi.fn>

const result = (written: boolean): EspnAddResult => ({
  leagueKey: 'espn-fba-1-2026', platform: 'ESPN', sport: 'nba', name: 'Hoops',
  seasons: [{ season: 2026, leagueKey: 'espn-fba-1-2026', status: 'in_season' }],
  missingSeasons: [],
  teams: [
    { rosterId: 1, name: 'Team One', managerName: 'Pat', claimedByYou: false, claimedByOther: false },
    { rosterId: 2, name: 'Team Two', managerName: null, claimedByYou: false, claimedByOther: true },
  ],
  players: { matched: 1, unmatched: 0, ambiguous: 0, espnOnly: 0 },
  unavailable: { draftRoom: 'PLATFORM_NOT_SUPPORTED' },
  credentialStored: false,
  written,
  unmatched: [],
})

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => vi.unstubAllGlobals())

function open() {
  render(<MemoryRouter><EspnLeagueSetup /></MemoryRouter>)
  fireEvent.change(screen.getByPlaceholderText(/fantasy.espn.com/), {
    target: { value: 'https://fantasy.espn.com/basketball/team?leagueId=1&seasonId=2027' },
  })
}

describe('EspnLeagueSetup', () => {
  it('shows the team list first and only writes when a team is claimed', async () => {
    fetchMock.mockResolvedValueOnce(json(result(false))).mockResolvedValueOnce(json(result(true)))
    open()
    fireEvent.click(screen.getByText('Look up league'))
    await screen.findByText('Team One')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).claimRosterId).toBeNull()
    expect(screen.getByText('Claimed')).toBeTruthy()   // a team someone else holds has no button

    fireEvent.click(screen.getByText('This is my team'))
    await waitFor(() => expect(screen.getByText(/Open league/)).toBeTruthy())
    expect(JSON.parse(fetchMock.mock.calls[1][1].body).claimRosterId).toBe(1)
  })

  it('names the players that could not be matched, before and after the claim (FR-007)', async () => {
    const withUnmatched = (written: boolean): EspnAddResult => ({
      ...result(written),
      unmatched: [
        { name: 'Zed Ambig', position: 'SF', team: 'ATL', outcome: 'AMBIGUOUS' },
        { name: 'Ghost Player', position: null, team: null, outcome: 'UNMATCHED' },
      ],
    })
    fetchMock.mockResolvedValueOnce(json(withUnmatched(false))).mockResolvedValueOnce(json(withUnmatched(true)))
    open()
    fireEvent.click(screen.getByText('Look up league'))
    await screen.findByText('Team One')
    expect(screen.getByText(/2 players on these rosters could not be matched/)).toBeTruthy()
    expect(screen.getByText(/Zed Ambig/)).toBeTruthy()
    expect(screen.getByText(/more than one player shares this name/)).toBeTruthy()
    expect(screen.getByText(/Ghost Player/)).toBeTruthy()

    fireEvent.click(screen.getByText('This is my team'))
    await waitFor(() => expect(screen.getByText(/Open league/)).toBeTruthy())
    expect(screen.getByText(/2 players on these rosters could not be matched/)).toBeTruthy()
  })

  it('reveals the cookie fields, with the plain warning, only when the league is private', async () => {
    fetchMock.mockResolvedValueOnce(json({ error: 'ESPN_PRIVATE_NO_ACCESS', message: 'x' }, 403))
    open()
    expect(screen.queryByText(/ESPN login cookies/)).toBeNull()
    fireEvent.click(screen.getByText('Look up league'))
    await screen.findByText(/login cookies, not a league-only key/)
    const fields = document.querySelectorAll('input[type="password"]')
    expect(fields.length).toBe(2)
    fields.forEach((f) => expect(f.getAttribute('autocomplete')).toBe('off'))
  })

  it('has human text for every contract error code', () => {
    for (const code of ['MALFORMED_REF', 'ESPN_NOT_FOUND', 'ESPN_PRIVATE_NO_ACCESS', 'ESPN_CREDENTIAL_REJECTED',
      'CREDENTIALS_NOT_CONFIGURED', 'ESPN_SHAPE_CHANGED', 'ALREADY_CLAIMED', 'TEAM_NOT_FOUND', 'ESPN_HTTP_503']) {
      const t = espnErrorText(new EspnSetupError(code, 400, 'server text'))
      expect(t.length).toBeGreaterThan(10)
      expect(t).not.toBe(code)
    }
    expect(espnErrorText(new EspnSetupError('UNSUPPORTED_SCORING', 422, 'only points leagues (this one is H2H_CATEGORY)')))
      .toContain('H2H_CATEGORY')
  })
})

describe('PlatformBadge', () => {
  it('marks ESPN and says nothing for Sleeper', () => {
    const { container, rerender } = render(<PlatformBadge platform="ESPN" />)
    expect(container.textContent).toBe('ESPN')
    rerender(<PlatformBadge platform="SLEEPER" />)
    expect(container.textContent).toBe('')
    rerender(<PlatformBadge platform={undefined} />)
    expect(container.textContent).toBe('')
  })
})
