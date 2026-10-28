import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SignIn from './SignIn'
import { analyticsLine, groups, headingOf, headline, platformsNote } from '../landingCopy'

const getSleeperUser = vi.fn()
vi.mock('../api', () => ({
  getSleeperUser: (...args: unknown[]) => getSleeperUser(...args),
}))

const trackAction = vi.fn()
vi.mock('../analytics', () => ({
  trackAction: (...args: unknown[]) => trackAction(...args),
}))

const setUser = vi.fn()
vi.mock('../user', () => ({
  setUser: (...args: unknown[]) => setUser(...args),
}))

beforeEach(() => {
  getSleeperUser.mockReset()
  setUser.mockReset()
  trackAction.mockReset()
})

const sampleUser = {
  sleeperUserId: '1122386008709910528',
  username: 'popsharky',
  displayName: 'popsharky',
  avatar: 'e1d4ebf9ea0760f248119d4ec2ac5a63',
}

describe('SignIn', () => {
  it('a real username stores the user, with no navigation call needed', async () => {
    getSleeperUser.mockResolvedValue(sampleUser)
    const user = userEvent.setup()
    render(<SignIn />)

    await user.type(screen.getByLabelText(/your sleeper username/i), 'popsharky')
    await user.click(screen.getByRole('button', { name: /continue/i }))

    expect(getSleeperUser).toHaveBeenCalledWith('popsharky')
    expect(setUser).toHaveBeenCalledWith(sampleUser)
    // Spec 052: counted once, after success.
    expect(trackAction).toHaveBeenCalledTimes(1)
    expect(trackAction).toHaveBeenCalledWith('sign-in')
  })

  it('an unknown username shows the "no such user" state, not a generic error', async () => {
    getSleeperUser.mockResolvedValue(null)
    const user = userEvent.setup()
    render(<SignIn />)

    await user.type(screen.getByLabelText(/your sleeper username/i), 'not-a-real-user')
    await user.click(screen.getByRole('button', { name: /continue/i }))

    expect(await screen.findByText(/no sleeper user called/i)).toBeInTheDocument()
    expect(setUser).not.toHaveBeenCalled()
    expect(trackAction).not.toHaveBeenCalled()
  })

  it('a network failure shows the unreachable state with a retry, distinct from a typo', async () => {
    getSleeperUser.mockRejectedValue(new Error('network down'))
    const user = userEvent.setup()
    render(<SignIn />)

    await user.type(screen.getByLabelText(/your sleeper username/i), 'popsharky')
    await user.click(screen.getByRole('button', { name: /continue/i }))

    expect(await screen.findByText(/couldn.t reach sleeper/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
  })

  it('a backend that 404s the route is unreachable, not a misspelled username', async () => {
    // The regression this exists for: getSleeperUser used to map any 404 to
    // "no such user", so a frontend deployed ahead of its backend told a
    // visitor with a perfectly valid username to check their spelling -- on
    // the gate, with no retry offered. getSleeperUser now throws on a 404 and
    // reserves null for "Sleeper genuinely has no such name", so the two
    // outcomes land in different states here.
    getSleeperUser.mockRejectedValue(new Error('HTTP 404'))
    const user = userEvent.setup()
    render(<SignIn />)

    await user.type(screen.getByLabelText(/your sleeper username/i), 'popsharky')
    await user.click(screen.getByRole('button', { name: /continue/i }))

    expect(await screen.findByText(/couldn.t reach sleeper/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
    expect(screen.queryByText(/check the spelling/i)).not.toBeInTheDocument()
  })

  it('accepts a pasted Sleeper profile URL, extracting just the username', async () => {
    getSleeperUser.mockResolvedValue(sampleUser)
    const user = userEvent.setup()
    render(<SignIn />)

    await user.type(screen.getByLabelText(/your sleeper username/i), 'https://sleeper.com/user/popsharky')
    await user.click(screen.getByRole('button', { name: /continue/i }))

    expect(getSleeperUser).toHaveBeenCalledWith('popsharky')
  })
})

// spec 013 US9 (T093): a centered welcome.
describe('SignIn welcome', () => {
  it('leads with the landing headline, and keeps the no-password line', () => {
    render(<SignIn />)

    // Spec 050: the product name is the kicker above the h1 (FR-002), shown exactly once;
    // the headline is asserted by role.
    expect(screen.getByText('Ball Knowers')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(headline)
    expect(screen.getByText(/No password -- this app never asks Sleeper for anything private/)).toBeInTheDocument()
    // Spec 052 FR-012: says what is counted, beside the no-password line.
    expect(screen.getByText(analyticsLine)).toBeInTheDocument()
  })

  it('has one text field and one primary Continue button, and Enter submits', async () => {
    getSleeperUser.mockResolvedValue(sampleUser)
    const user = userEvent.setup()
    const { container } = render(<SignIn />)

    expect(container.querySelectorAll('input')).toHaveLength(1)
    expect(container.querySelectorAll('button.signin-continue')).toHaveLength(1)
    await user.type(screen.getByLabelText(/your sleeper username/i), 'popsharky{Enter}')
    expect(getSleeperUser).toHaveBeenCalledWith('popsharky')
  })
})

// Spec 050: the signed-out root is a landing page, not just a form.
describe('SignIn landing', () => {
  it('renders the four feature groups in rail order', () => {
    render(<SignIn />)
    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
    expect(headings).toEqual(groups.map((g) => headingOf(g.key)))
    expect(headings).toEqual(['This week', 'The season', 'Draft', 'History'])
  })

  it('tags a basketball-only feature NBA and leaves a two-sport feature untagged', () => {
    render(<SignIn />)
    const tile = (name: string) => screen.getByRole('heading', { level: 3, name }).closest('li') as HTMLElement
    expect(within(tile('Schedule grid')).getByText('NBA')).toBeInTheDocument()
    expect(within(tile('Power rankings')).queryByText('NBA')).not.toBeInTheDocument()
    expect(within(tile('Power rankings')).queryByText('NFL')).not.toBeInTheDocument()
  })

  it('is Sleeper-first, and ESPN appears only in the platform note', () => {
    const { container } = render(<SignIn />)
    expect(container.textContent).toContain('Sleeper')
    expect(screen.getByText(platformsNote)).toBeInTheDocument()
    expect((container.textContent ?? '').replace(platformsNote, '')).not.toContain('ESPN')
  })

  it('never carries the prerender class, so signing out in the same tab shows it', () => {
    const { container } = render(<SignIn />)
    expect(container.querySelector('.landing-prerender')).toBeNull()
  })
})
