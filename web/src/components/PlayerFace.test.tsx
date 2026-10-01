import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import PlayerFace from './PlayerFace'

const base = { sleeperId: '4046', team: 'KC', position: 'QB', name: 'Patrick Mahomes', size: 20 } as const

describe('PlayerFace', () => {
  it('walks photo, then team logo, then initials, only forward on error', () => {
    const { container } = render(<PlayerFace sport="nfl" {...base} />)

    let img = screen.getByRole('img', { name: 'Patrick Mahomes' }) as HTMLImageElement
    expect(img.src).toBe('https://sleepercdn.com/content/nfl/players/thumb/4046.jpg')
    expect(img.getAttribute('loading')).toBe('lazy')
    expect(img.getAttribute('width')).toBe('20')

    fireEvent.error(img)
    img = screen.getByRole('img', { name: 'Patrick Mahomes' }) as HTMLImageElement
    expect(img.src).toBe('https://sleepercdn.com/images/team_logos/nfl/kc.png')

    fireEvent.error(img)
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByRole('img', { name: 'Patrick Mahomes' })).toHaveTextContent('P')
  })

  it('starts a defense at the team logo', () => {
    render(<PlayerFace sport="nfl" sleeperId="KC" team="KC" position="DEF" name="Kansas City" size={20} />)
    const img = screen.getByRole('img', { name: 'Kansas City' }) as HTMLImageElement
    expect(img.src).toBe('https://sleepercdn.com/images/team_logos/nfl/kc.png')
  })

  it('goes from photo straight to initials when there is no team', () => {
    const { container } = render(<PlayerFace sport="nfl" {...base} team={null} />)
    fireEvent.error(screen.getByRole('img', { name: 'Patrick Mahomes' }))
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByRole('img', { name: 'Patrick Mahomes' })).toHaveTextContent('P')
  })

  it('shows initials at once for a defense with no team', () => {
    const { container } = render(<PlayerFace sport="nfl" sleeperId="KC" team={null} position="DEF" name="Kansas City" size={20} />)
    expect(container.querySelector('img')).toBeNull()
  })

  it('uses the NBA folders for basketball', () => {
    render(<PlayerFace sport="nba" sleeperId="6450" team="DEN" position="C" name="Nikola Jokic" size={20} />)
    let img = screen.getByRole('img', { name: 'Nikola Jokic' }) as HTMLImageElement
    expect(img.src).toBe('https://sleepercdn.com/content/nba/players/thumb/6450.jpg')
    fireEvent.error(img)
    img = screen.getByRole('img', { name: 'Nikola Jokic' }) as HTMLImageElement
    expect(img.src).toBe('https://sleepercdn.com/images/team_logos/nba/den.png')
  })

  it('keeps one fixed box in every state', () => {
    const { container } = render(<PlayerFace sport="nfl" {...base} />)
    const box = () => container.querySelector('.pface') as HTMLElement
    expect(box().style.width).toBe('20px')
    fireEvent.error(container.querySelector('img')!)
    fireEvent.error(container.querySelector('img')!)
    expect(box().style.width).toBe('20px')
    expect(box().style.height).toBe('20px')
  })
})
