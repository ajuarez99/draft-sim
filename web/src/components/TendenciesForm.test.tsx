import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TendenciesForm from './TendenciesForm'

// The save/clear logic used to be hand-duplicated in SeatPopover.tsx and
// ManagerTendencies.tsx (claude/design-review-next-steps.md A3). These cover
// the extracted behaviour once, off the shared component, rather than twice
// off two call sites that happen to render the same markup.

const setTendencies = vi.fn()
const clearTendencies = vi.fn()
vi.mock('../api', () => ({
  setTendencies: (...args: unknown[]) => setTendencies(...args),
  clearTendencies: (...args: unknown[]) => clearTendencies(...args),
}))

beforeEach(() => {
  setTendencies.mockReset()
  clearTendencies.mockReset()
})

const emptyInitial = { reachBias: null, unpredictability: null, note: null }

describe('TendenciesForm', () => {
  it('hydrates the note from `initial`, with no reach/unpredictability inputs', () => {
    render(
      <TendenciesForm
        managerId={7}
        sport="nfl"
        initial={{ reachBias: 4.8, unpredictability: 1.6, note: 'Loves a QB run' }}
        canClear
        onDone={vi.fn()}
        onCancel={vi.fn()}
      />,
    )
    expect(screen.getByLabelText('Note')).toHaveValue('Loves a QB run')
    // reachBias/unpredictability are model internals, not something a user
    // types in -- see the file-level comment on TendenciesForm.tsx.
    expect(screen.queryByLabelText('Picks early')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Unpredictability')).not.toBeInTheDocument()
  })

  it('saves the note and round-trips reachBias/unpredictability from initial unchanged', async () => {
    const user = userEvent.setup()
    const onDone = vi.fn()
    setTendencies.mockResolvedValue(undefined)
    render(
      <TendenciesForm
        managerId={9}
        sport="nba"
        initial={{ reachBias: 4.8, unpredictability: 1.6, note: null }}
        canClear
        onDone={onDone}
        onCancel={vi.fn()}
      />,
    )
    await user.type(screen.getByLabelText('Note'), 'Reaches for his old team')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    // `sport` is the middle argument and it is 'nba' here on purpose: manual
    // tendencies are stored per (manager, sport), and this form used to call an
    // endpoint that defaults the sport to nfl -- so editing a note on a
    // basketball seat wrote to that manager's football row instead. Ten of the
    // twelve Ball Knowers managers are the same Sleeper id in both leagues, so
    // that was the common case, not the edge (multi-sport-and-rebrand.md 6b).
    expect(setTendencies).toHaveBeenCalledWith(9, 'nba', {
      reachBias: 4.8,
      unpredictability: 1.6,
      note: 'Reaches for his old team',
    })
    expect(onDone).toHaveBeenCalled()
  })

  it('saves a blank note as null without inventing a reach/unpredictability value', async () => {
    const user = userEvent.setup()
    const onDone = vi.fn()
    setTendencies.mockResolvedValue(undefined)
    render(<TendenciesForm managerId={9} sport="nfl" initial={emptyInitial} canClear={false} onDone={onDone} onCancel={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(setTendencies).toHaveBeenCalledWith(9, 'nfl', { reachBias: null, unpredictability: null, note: null })
    expect(onDone).toHaveBeenCalled()
  })

  it('only offers Clear when canClear is true, and it calls clearTendencies', async () => {
    const user = userEvent.setup()
    const onDone = vi.fn()
    clearTendencies.mockResolvedValue(undefined)
    const { rerender } = render(
      <TendenciesForm managerId={3} sport="nba" initial={emptyInitial} canClear={false} onDone={onDone} onCancel={vi.fn()} />,
    )
    expect(screen.queryByRole('button', { name: 'Clear' })).not.toBeInTheDocument()

    rerender(<TendenciesForm managerId={3} sport="nba" initial={emptyInitial} canClear onDone={onDone} onCancel={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Clear' }))
    expect(clearTendencies).toHaveBeenCalledWith(3, 'nba')
    expect(onDone).toHaveBeenCalled()
  })

  it('cancel discards without touching the API', async () => {
    const user = userEvent.setup()
    const onCancel = vi.fn()
    render(<TendenciesForm managerId={3} sport="nba" initial={emptyInitial} canClear onDone={vi.fn()} onCancel={onCancel} />)
    await user.type(screen.getByLabelText('Note'), 'draft')
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onCancel).toHaveBeenCalled()
    expect(setTendencies).not.toHaveBeenCalled()
    expect(clearTendencies).not.toHaveBeenCalled()
  })
})
