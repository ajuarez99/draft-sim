import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import JumpTo from './JumpTo'
import type { SearchDestination } from '../searchIndex'

/*
 * The palette has to be operable by keyboard alone (NFR-003): open, type,
 * arrow, Enter, Escape. Those are the parts of a hand-rolled overlay that
 * actually go wrong, so they are what is tested here -- not that it renders.
 */

const INDEX: SearchDestination[] = [
  {
    id: 'a',
    kind: 'league-page',
    label: 'History',
    context: 'Ball Knowers',
    href: '/leagues/L1/history',
    sports: ['nfl'],
    terms: ['history', 'ball knowers'],
  },
  {
    id: 'b',
    kind: 'league-page',
    label: 'Power rankings',
    context: 'Ball Knowers',
    href: '/leagues/L1/power',
    sports: ['nfl'],
    terms: ['power rankings', 'ball knowers'],
  },
  {
    id: 'c',
    kind: 'manager',
    label: 'kieriskash',
    context: 'Manager',
    href: '/managers/4/history',
    sports: ['nfl', 'nba'],
    terms: ['kieriskash', 'manager'],
  },
]

function Here() {
  const loc = useLocation()
  return <p data-testid="here">{loc.pathname}</p>
}

function Harness({ onClose = () => {} }: { onClose?: () => void }) {
  return (
    <MemoryRouter initialEntries={['/start']}>
      <Routes>
        <Route path="*" element={<Here />} />
      </Routes>
      <JumpTo index={INDEX} loading={false} onClose={onClose} />
    </MemoryRouter>
  )
}

beforeEach(() => vi.restoreAllMocks())
afterEach(() => vi.restoreAllMocks())

describe('opening', () => {
  it('puts focus in the input', async () => {
    render(<Harness />)
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('textbox')))
  })

  it('lists everything before you type, so it works as a plain list', () => {
    render(<Harness />)
    expect(screen.getAllByRole('option')).toHaveLength(INDEX.length)
  })
})

describe('typing', () => {
  it('narrows to what matches', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.type(screen.getByRole('textbox'), 'power')

    const options = screen.getAllByRole('option')
    expect(options).toHaveLength(1)
    expect(options[0].textContent).toContain('Power rankings')
  })

  it('finds a manager who is in no league you are looking at', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.type(screen.getByRole('textbox'), 'kieris')

    expect(screen.getAllByRole('option')[0].textContent).toContain('kieriskash')
  })

  it('says so when nothing matches, rather than showing an empty box', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.type(screen.getByRole('textbox'), 'zzzz')

    expect(screen.queryAllByRole('option')).toHaveLength(0)
    expect(screen.getByText(/nothing matches/i)).toBeTruthy()
  })

  it('names which league each page belongs to', () => {
    render(<Harness />)
    // Two leagues both have a "History"; the context is what tells them apart.
    expect(screen.getAllByText('Ball Knowers').length).toBeGreaterThan(0)
  })
})

describe('keyboard navigation', () => {
  it('starts with the first result selected', () => {
    render(<Harness />)
    expect(screen.getAllByRole('option')[0].getAttribute('aria-selected')).toBe('true')
  })

  it('moves the selection with the arrow keys', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.keyboard('{ArrowDown}')
    expect(screen.getAllByRole('option')[1].getAttribute('aria-selected')).toBe('true')

    await user.keyboard('{ArrowUp}')
    expect(screen.getAllByRole('option')[0].getAttribute('aria-selected')).toBe('true')
  })

  it('wraps around rather than stopping at the ends', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.keyboard('{ArrowUp}')
    const options = screen.getAllByRole('option')
    expect(options[options.length - 1].getAttribute('aria-selected')).toBe('true')
  })

  it('navigates to the selected row on Enter', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.keyboard('{ArrowDown}{Enter}')

    await waitFor(() => expect(screen.getByTestId('here').textContent).toBe('/leagues/L1/power'))
  })

  // A stale selection is how Enter takes you somewhere you never looked at:
  // type, watch the list change, press Enter on the index you had before.
  it('resets the selection when the query changes', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.keyboard('{ArrowDown}{ArrowDown}')
    await user.type(screen.getByRole('textbox'), 'ball')

    expect(screen.getAllByRole('option')[0].getAttribute('aria-selected')).toBe('true')
  })
})

describe('closing', () => {
  it('closes on Escape without navigating', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<Harness onClose={onClose} />)

    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledOnce()
    expect(screen.getByTestId('here').textContent).toBe('/start')
  })

  it('closes when the backdrop is clicked', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    const { container } = render(<Harness onClose={onClose} />)

    await user.click(container.querySelector('.jumpto-backdrop')!)

    expect(onClose).toHaveBeenCalledOnce()
  })

  it('does not close when the panel itself is clicked', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<Harness onClose={onClose} />)

    await user.click(screen.getByRole('textbox'))

    expect(onClose).not.toHaveBeenCalled()
  })

  it('closes before navigating when a row is clicked', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<Harness onClose={onClose} />)

    await user.click(screen.getAllByRole('option')[0])

    expect(onClose).toHaveBeenCalledOnce()
    await waitFor(() => expect(screen.getByTestId('here').textContent).toBe('/leagues/L1/history'))
  })
})

describe('loading', () => {
  it('distinguishes "still loading" from "nothing matches"', () => {
    render(
      <MemoryRouter>
        <JumpTo index={[]} loading onClose={() => {}} />
      </MemoryRouter>,
    )
    expect(screen.getByText(/loading your leagues/i)).toBeTruthy()
    expect(screen.queryByText(/nothing matches/i)).toBeNull()
  })
})
