import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TendenciesForm from './TendenciesForm'

// The validation and save/clear logic used to be hand-duplicated in
// SeatPopover.tsx and ManagerTendencies.tsx (claude/design-review-next-steps.md
// A3). These cover the extracted behaviour once, off the shared component,
// rather than twice off two call sites that happen to render the same markup.

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
  it('hydrates the fields from `initial` and previews the reach in words', () => {
    render(
      <TendenciesForm
        managerId={7}
        initial={{ reachBias: 4.8, unpredictability: 1.6, note: 'Loves a QB run' }}
        canClear
        onDone={vi.fn()}
        onCancel={vi.fn()}
      />,
    )
    expect(screen.getByLabelText('Picks early')).toHaveValue(4.8)
    expect(screen.getByLabelText('Unpredictability')).toHaveValue(1.6)
    expect(screen.getByLabelText('Note')).toHaveValue('Loves a QB run')
    // The bare numbers are model internals -- the point of A3 is that a
    // reader sees words, not a signed float or a 0.1-3.0 range.
    expect(screen.getByText('Reads as ~4.8 picks early.')).toBeInTheDocument()
    expect(screen.getByText('Wild card (1.6)')).toBeInTheDocument()
  })

  it('reads a negative reach bias as going late, not as a negative number', async () => {
    const user = userEvent.setup()
    render(<TendenciesForm managerId={7} initial={emptyInitial} canClear={false} onDone={vi.fn()} onCancel={vi.fn()} />)
    await user.type(screen.getByLabelText('Picks early'), '-3.2')
    expect(screen.getByText('Reads as ~3.2 picks late.')).toBeInTheDocument()
  })

  it('rejects a non-numeric value inline and never calls the API', async () => {
    const user = userEvent.setup()
    const onDone = vi.fn()
    render(<TendenciesForm managerId={7} initial={emptyInitial} canClear={false} onDone={onDone} onCancel={vi.fn()} />)
    // A real <input type="number"> sanitizes any non-numeric keystroke back to
    // '' per the HTML spec (confirmed against jsdom: "abc", "-", "1.", "5,5"
    // etc. all round-trip to an empty value, never a string Number() would
    // choke on) -- so `save`'s Number.isNaN guard can't be reached by typing.
    // It's still worth keeping as a backstop against a future input-type
    // change, so exercise it the only way that's possible: flip the DOM node
    // to type="text" first, the way an older or non-compliant UA might not
    // sanitize at all, then fire the same change React would see.
    const input = screen.getByLabelText('Picks early')
    input.setAttribute('type', 'text')
    fireEvent.change(input, { target: { value: 'abc' } })
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(screen.getByText('Picks early and unpredictability must both be numbers.')).toBeInTheDocument()
    expect(setTendencies).not.toHaveBeenCalled()
    expect(onDone).not.toHaveBeenCalled()
  })

  it('saves a signed reachBias unchanged and null for blank fields', async () => {
    const user = userEvent.setup()
    const onDone = vi.fn()
    setTendencies.mockResolvedValue(undefined)
    render(<TendenciesForm managerId={9} initial={emptyInitial} canClear={false} onDone={onDone} onCancel={vi.fn()} />)
    await user.type(screen.getByLabelText('Picks early'), '-4.5')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(setTendencies).toHaveBeenCalledWith(9, { reachBias: -4.5, unpredictability: null, note: null })
    expect(onDone).toHaveBeenCalled()
  })

  it('only offers Clear when canClear is true, and it calls clearTendencies', async () => {
    const user = userEvent.setup()
    const onDone = vi.fn()
    clearTendencies.mockResolvedValue(undefined)
    const { rerender } = render(
      <TendenciesForm managerId={3} initial={emptyInitial} canClear={false} onDone={onDone} onCancel={vi.fn()} />,
    )
    expect(screen.queryByRole('button', { name: 'Clear' })).not.toBeInTheDocument()

    rerender(<TendenciesForm managerId={3} initial={emptyInitial} canClear onDone={onDone} onCancel={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Clear' }))
    expect(clearTendencies).toHaveBeenCalledWith(3)
    expect(onDone).toHaveBeenCalled()
  })

  it('cancel discards without touching the API', async () => {
    const user = userEvent.setup()
    const onCancel = vi.fn()
    render(<TendenciesForm managerId={3} initial={emptyInitial} canClear onDone={vi.fn()} onCancel={onCancel} />)
    await user.type(screen.getByLabelText('Picks early'), '9.9')
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onCancel).toHaveBeenCalled()
    expect(setTendencies).not.toHaveBeenCalled()
    expect(clearTendencies).not.toHaveBeenCalled()
  })
})
