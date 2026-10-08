import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import PlayerLink from './PlayerLink'

const renderLink = (sport: 'nba' | 'nfl') =>
  render(
    <MemoryRouter>
      <PlayerLink sleeperLeagueId="L1" sleeperPlayerId="1350" sport={sport}>
        Rudy Gobert
      </PlayerLink>
    </MemoryRouter>,
  )

describe('PlayerLink', () => {
  it('links a basketball name to the player page', () => {
    renderLink('nba')
    expect(screen.getByRole('link', { name: 'Rudy Gobert' })).toHaveAttribute('href', '/leagues/L1/players/1350')
  })

  it('renders plain text, no link, for a sport without player pages', () => {
    renderLink('nfl')
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText('Rudy Gobert')).toBeInTheDocument()
  })
})
