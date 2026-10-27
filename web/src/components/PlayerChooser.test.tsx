import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PlayerChooser from './PlayerChooser'
import { board, row } from '../testStatBoard'

const fetchDraftStats = vi.fn()
vi.mock('../draftStatsCache', () => ({
  fetchDraftStats: (...args: unknown[]) => fetchDraftStats(...args),
}))

const onPick = vi.fn()

function mount(excludeId?: string) {
  return render(<PlayerChooser leagueId="L25" excludeId={excludeId} onPick={onPick} />)
}

async function openAndType(q: string) {
  fireEvent.click(screen.getByRole('button', { name: 'Choose a player' }))
  const input = await screen.findByRole('combobox')
  fireEvent.change(input, { target: { value: q } })
  return input
}

beforeEach(() => {
  onPick.mockReset()
  fetchDraftStats.mockReset()
  fetchDraftStats.mockResolvedValue(board([
    row('1', { name: 'Nikola Jokić', team: 'DEN' }),
    row('2', { name: 'Player Two' }),
    row('3', { name: null }),
    row('4', { name: 'Player Four' }),
  ]))
})

describe('PlayerChooser', () => {
  it('fetches nothing until it is opened', async () => {
    mount()
    expect(fetchDraftStats).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Choose a player' }))
    await screen.findByRole('combobox')
    expect(fetchDraftStats).toHaveBeenCalledWith('L25', 'SEASON', 0)
  })

  it('finds a name without its accent', async () => {
    mount()
    await openAndType('jokic')
    expect(await screen.findByRole('option', { name: /Nikola Jokić/ })).toBeInTheDocument()
  })

  it('does not offer a row with no name', async () => {
    mount()
    await openAndType('player')
    expect(await screen.findAllByRole('option')).toHaveLength(2)
    expect(screen.queryByText(/null/)).not.toBeInTheDocument()
  })

  it('does not offer the player already in the other column', async () => {
    mount('2')
    await openAndType('player')
    const opts = await screen.findAllByRole('option')
    expect(opts).toHaveLength(1)
    expect(opts[0]).toHaveTextContent('Player Four')
  })

  it('asks for two letters before it lists anything', async () => {
    mount()
    await openAndType('p')
    expect(await screen.findByText(/Type at least 2 letters/)).toBeInTheDocument()
    expect(screen.queryByRole('option')).not.toBeInTheDocument()
  })

  it('picks with the keyboard: arrows move, Enter chooses', async () => {
    mount()
    const input = await openAndType('player')
    await screen.findAllByRole('option')
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onPick).toHaveBeenCalledWith('4')
  })

  it('says so on a fallback board', async () => {
    fetchDraftStats.mockResolvedValue(board([row('1', { name: 'Player One' })], { requestedSeason: 2026 }))
    mount()
    await openAndType('player')
    expect(await screen.findByText(/has no games yet; showing/)).toBeInTheDocument()
  })

  it('says it could not load rather than listing nothing silently', async () => {
    fetchDraftStats.mockRejectedValue(new Error('boom'))
    mount()
    fireEvent.click(screen.getByRole('button', { name: 'Choose a player' }))
    expect(await screen.findByText('Couldn’t load the player list.')).toBeInTheDocument()
  })
})
