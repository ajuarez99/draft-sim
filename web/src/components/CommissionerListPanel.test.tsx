import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CommissionerListPanel, { formatWeeks, normalizeName, weekRange } from './CommissionerListPanel'
import type { ConductCandidate, ConductEntry, ConductList } from '../api'

vi.mock('react-router-dom', () => ({
  Link: ({ to, children, className }: { to: string; children: React.ReactNode; className?: string }) => (
    <a href={to} className={className}>
      {children}
    </a>
  ),
}))

const fetchConductList = vi.fn()
const saveConductEntry = vi.fn()
const updateConductEntry = vi.fn()
const deleteConductEntry = vi.fn()
vi.mock('../api', () => ({
  fetchConductList: (...a: unknown[]) => fetchConductList(...a),
  saveConductEntry: (...a: unknown[]) => saveConductEntry(...a),
  updateConductEntry: (...a: unknown[]) => updateConductEntry(...a),
  deleteConductEntry: (...a: unknown[]) => deleteConductEntry(...a),
}))

const team = { rosterId: 4, managerId: 12, teamName: 'Dunk Tank', username: 'dunker', avatarId: null }

function cand(over: Partial<ConductCandidate> = {}): ConductCandidate {
  return { playerId: 'p1', playerName: 'Joel Embiid', position: 'C', proTeam: 'PHI', fantasyTeam: team, ...over }
}

function entry(over: Partial<ConductEntry> = {}): ConductEntry {
  return {
    id: 1, playerId: 'p1', playerName: 'Joel Embiid', position: 'C', proTeam: 'PHI', reason: '3-game suspension',
    appliesFromWeek: 2, appliesThroughWeek: 3, addedBy: 'Allan', createdAt: '2026-10-20T00:00:00Z',
    charges: [{ team, weeks: [2, 3] }], playerWeeks: 2, alsoFlaggedBySleeperWeeks: [],
    ...over,
  }
}

function list(over: Partial<ConductList> = {}): ConductList {
  return { season: 2026, canEdit: true, commissionerKnown: true, entries: [], candidates: [], ...over }
}

function mount(l: ConductList, props: { cardsSeason?: number; onChanged?: () => void; onCount?: (n: number) => void } = {}) {
  fetchConductList.mockResolvedValue(l)
  return render(
    <CommissionerListPanel
      leagueSleeperId="L1"
      cardsSeason={props.cardsSeason ?? 2026}
      sport="nba"
      open
      panelId="panel"
      onCount={props.onCount}
      onChanged={props.onChanged ?? (() => {})}
    />,
  )
}

describe('helpers', () => {
  it('normalizes case and diacritics', () => {
    expect(normalizeName('Nikola Jokić')).toBe('nikola jokic')
    expect(normalizeName('  LUKA Dončić ')).toBe('luka doncic')
  })
  it('names the week range', () => {
    expect(weekRange(2, null)).toBe('from wk 2')
    expect(weekRange(2, 2)).toBe('wk 2')
    expect(weekRange(2, 3)).toBe('wk 2–3')
  })
  it('compresses runs of weeks', () => {
    expect(formatWeeks([2, 3, 4, 7])).toBe('2–4, 7')
    expect(formatWeeks([5])).toBe('5')
    expect(formatWeeks([1, 3, 5])).toBe('1, 3, 5')
  })
})

describe('CommissionerListPanel', () => {
  beforeEach(() => {
    fetchConductList.mockReset()
    saveConductEntry.mockReset()
    updateConductEntry.mockReset()
    deleteConductEntry.mockReset()
  })

  it('renders nothing while closed but still reports the count', async () => {
    fetchConductList.mockResolvedValue(list({ entries: [entry(), entry({ id: 2, playerId: 'p2' })] }))
    const onCount = vi.fn()
    const { container } = render(
      <CommissionerListPanel leagueSleeperId="L1" cardsSeason={2026} sport="nba" open={false} panelId="x"
        onCount={onCount} onChanged={() => {}} />,
    )
    await waitFor(() => expect(onCount).toHaveBeenCalledWith(2))
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the charges, week range and player-weeks of an entry', async () => {
    mount(list({ entries: [entry({ alsoFlaggedBySleeperWeeks: [3] })] }))

    expect(await screen.findByText('Joel Embiid')).toBeInTheDocument()
    expect(screen.getByText('wk 2–3', { selector: '.sl-cl-pill' })).toBeInTheDocument()
    expect(screen.getByText('2 player-weeks')).toBeInTheDocument()
    expect(screen.getByText('3-game suspension')).toBeInTheDocument()
    expect(screen.getByText('Dunk Tank')).toBeInTheDocument()
    expect(within(screen.getByText('Dunk Tank').closest('.sl-cl-chip') as HTMLElement).getByText('wk 2–3')).toBeInTheDocument()
    expect(screen.getByText(/also flagged by Sleeper in weeks 3/)).toBeInTheDocument()
  })

  it('says so when an entry charges nobody yet', async () => {
    mount(list({ entries: [entry({ charges: [], playerWeeks: 0 })] }))
    expect(await screen.findByText('not rostered in these weeks yet')).toBeInTheDocument()
    expect(screen.getByText('0 player-weeks')).toBeInTheDocument()
  })

  it('finds a player regardless of case and diacritics', async () => {
    const user = userEvent.setup()
    mount(list({ candidates: [cand({ playerId: 'j', playerName: 'Nikola Jokić', position: 'C', proTeam: 'DEN' }), cand()] }))

    await user.click(await screen.findByRole('button', { name: 'Add to the list' }))
    const box = screen.getByRole('combobox', { name: 'Player' })
    await user.type(box, 'JOKIC')

    const options = screen.getAllByRole('option')
    expect(options).toHaveLength(1)
    expect(options[0]).toHaveAccessibleName(/Nikola Jokić, C, DEN, Dunk Tank/)
  })

  it('is operable from the keyboard: arrows move, Enter picks', async () => {
    const user = userEvent.setup()
    saveConductEntry.mockResolvedValue(entry())
    mount(list({ candidates: [cand({ playerId: 'a', playerName: 'Aaron A' }), cand({ playerId: 'b', playerName: 'Aaron B' })] }))

    await user.click(await screen.findByRole('button', { name: 'Add to the list' }))
    await user.type(screen.getByRole('combobox', { name: 'Player' }), 'aaron')
    await user.keyboard('{ArrowDown}{Enter}')

    // second option picked, list closed, the chosen player shown with a way to change
    expect(await screen.findByText('Aaron B')).toBeInTheDocument()
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Reason'), 'why')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(saveConductEntry).toHaveBeenCalledWith('L1', {
        playerId: 'b', reason: 'why', appliesFromWeek: 1, appliesThroughWeek: null,
      }),
    )
  })

  it('saves a week range with a through week', async () => {
    const user = userEvent.setup()
    saveConductEntry.mockResolvedValue(entry())
    mount(list({ candidates: [cand()] }))

    await user.click(await screen.findByRole('button', { name: 'Add to the list' }))
    await user.type(screen.getByRole('combobox', { name: 'Player' }), 'embi')
    await user.click(screen.getByRole('option', { name: /Joel Embiid/ }))
    await user.type(screen.getByLabelText('Reason'), 'suspended')
    await user.clear(screen.getByLabelText('Applies from week'))
    await user.type(screen.getByLabelText('Applies from week'), '2')
    await user.type(screen.getByLabelText('Through week (optional)'), '4')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(saveConductEntry).toHaveBeenCalledWith('L1', {
        playerId: 'p1', reason: 'suspended', appliesFromWeek: 2, appliesThroughWeek: 4,
      }),
    )
  })

  it('blocks a through week before the from week without calling the server', async () => {
    const user = userEvent.setup()
    mount(list({ candidates: [cand()] }))

    await user.click(await screen.findByRole('button', { name: 'Add to the list' }))
    await user.type(screen.getByRole('combobox', { name: 'Player' }), 'embi')
    await user.click(screen.getByRole('option', { name: /Joel Embiid/ }))
    await user.type(screen.getByLabelText('Reason'), 'x')
    await user.clear(screen.getByLabelText('Applies from week'))
    await user.type(screen.getByLabelText('Applies from week'), '5')
    await user.type(screen.getByLabelText('Through week (optional)'), '3')

    expect(screen.getByText(/through week can't be before the from week/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(saveConductEntry).not.toHaveBeenCalled()
  })

  it('offers no player-id box and says rosters have not loaded when there are no candidates', async () => {
    const user = userEvent.setup()
    mount(list({ candidates: [] }))

    await user.click(await screen.findByRole('button', { name: 'Add to the list' }))
    expect(screen.getByText("Rosters haven't loaded yet — refresh the league.")).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/id/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })

  it('marks an already-listed player and jumps to editing that entry', async () => {
    const user = userEvent.setup()
    mount(list({ entries: [entry()], candidates: [cand()] }))

    await user.click(await screen.findByRole('button', { name: 'Add to the list' }))
    await user.type(screen.getByRole('combobox', { name: 'Player' }), 'embi')
    const option = screen.getByRole('option', { name: /on the list — edit/ })
    await user.click(option)

    // now the edit form for the existing entry, with its values filled in
    expect(await screen.findByText(/Editing/)).toBeInTheDocument()
    expect(screen.getByLabelText('Reason')).toHaveValue('3-game suspension')
    expect(screen.getByLabelText('Through week (optional)')).toHaveValue(3)
    expect(saveConductEntry).not.toHaveBeenCalled()
  })

  it('does not refetch the cards after a write to a season they are not showing', async () => {
    const user = userEvent.setup()
    const onChanged = vi.fn()
    deleteConductEntry.mockResolvedValue(undefined)
    mount(list({ season: 2027, entries: [entry()] }), { cardsSeason: 2026, onChanged })

    expect(await screen.findByText(/The awards above show 2026\. This 2027 list starts counting/)).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Remove Joel Embiid' }))
    await user.click(within(screen.getByRole('group', { name: 'Remove Joel Embiid?' })).getByRole('button', { name: 'Yes' }))

    await waitFor(() => expect(deleteConductEntry).toHaveBeenCalledWith('L1', 1))
    expect(onChanged).not.toHaveBeenCalled()
  })

  it('refetches the cards after a write to the season they show', async () => {
    const user = userEvent.setup()
    const onChanged = vi.fn()
    deleteConductEntry.mockResolvedValue(undefined)
    mount(list({ entries: [entry()] }), { onChanged })

    await user.click(await screen.findByRole('button', { name: 'Remove Joel Embiid' }))
    await user.click(screen.getByRole('button', { name: 'Yes' }))

    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1))
  })

  // ---- code-review L1-L3 (spec 040)

  function panel(id: string, onChanged: () => void = () => {}) {
    return (
      <CommissionerListPanel leagueSleeperId={id} cardsSeason={2026} sport="nba" open panelId="panel" onChanged={onChanged} />
    )
  }

  it('L1: drops the previous leagues list as soon as the league id changes', async () => {
    fetchConductList.mockResolvedValueOnce(list({ entries: [entry({ reason: 'league A reason' })] }))
    const { rerender } = render(panel('A'))
    expect(await screen.findByText('league A reason')).toBeInTheDocument()

    let resolveB: (l: ConductList) => void = () => {}
    fetchConductList.mockReturnValueOnce(new Promise<ConductList>((r) => (resolveB = r)))
    rerender(panel('B'))

    expect(screen.queryByText('league A reason')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add to the list' })).not.toBeInTheDocument()
    await act(async () => resolveB(list({ entries: [entry({ id: 9, reason: 'league B reason' })] })))
    expect(await screen.findByText('league B reason')).toBeInTheDocument()
  })

  it('L1: a write that resolves after the league changed does not touch the new league', async () => {
    const user = userEvent.setup()
    const onChanged = vi.fn()
    let finishDelete: () => void = () => {}
    deleteConductEntry.mockReturnValue(new Promise<void>((r) => (finishDelete = r)))
    fetchConductList.mockResolvedValueOnce(list({ entries: [entry({ reason: 'league A reason' })] }))
    const { rerender } = render(panel('A', onChanged))

    await user.click(await screen.findByRole('button', { name: 'Remove Joel Embiid' }))
    await user.click(screen.getByRole('button', { name: 'Yes' }))
    await waitFor(() => expect(deleteConductEntry).toHaveBeenCalledWith('A', 1))

    fetchConductList.mockResolvedValueOnce(list({ entries: [entry({ id: 9, reason: 'league B reason' })] }))
    rerender(panel('B', onChanged))
    expect(await screen.findByText('league B reason')).toBeInTheDocument()
    const callsBefore = fetchConductList.mock.calls.length

    await act(async () => finishDelete())
    expect(fetchConductList.mock.calls.length).toBe(callsBefore) // no refetch for A
    expect(onChanged).not.toHaveBeenCalled()
    expect(screen.getByText('league B reason')).toBeInTheDocument()
  })

  it('L2: a failed refresh after a successful write closes the form, still tells the cards, and says so', async () => {
    const user = userEvent.setup()
    const onChanged = vi.fn()
    deleteConductEntry.mockResolvedValue(undefined)
    fetchConductList.mockResolvedValueOnce(list({ entries: [entry()] }))
    render(panel('L1', onChanged))

    await user.click(await screen.findByRole('button', { name: 'Remove Joel Embiid' }))
    fetchConductList.mockRejectedValueOnce(new Error('network down'))
    await user.click(screen.getByRole('button', { name: 'Yes' }))

    expect(await screen.findByText(/Saved, but the list couldn't refresh — reload the page\./)).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Remove Joel Embiid?' })).not.toBeInTheDocument()
    expect(onChanged).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('network down')).not.toBeInTheDocument()
  })

  it('L2: a failed write still shows the error and keeps the form open', async () => {
    const user = userEvent.setup()
    const onChanged = vi.fn()
    updateConductEntry.mockRejectedValue(new Error('nope'))
    mount(list({ entries: [entry()] }), { onChanged })

    await user.click(await screen.findByRole('button', { name: 'Edit Joel Embiid' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('nope')).toBeInTheDocument()
    expect(screen.getByText(/Editing/)).toBeInTheDocument()
    expect(onChanged).not.toHaveBeenCalled()
  })

  it('shows no inputs or buttons to a non-commissioner', async () => {
    mount(list({ canEdit: false, entries: [entry()], candidates: [cand()] }))

    expect(await screen.findByText('Joel Embiid')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })
})
