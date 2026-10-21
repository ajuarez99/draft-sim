import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import OwnershipMark, { ownershipFromTrend } from './OwnershipMark'
import type { PlayerOwnership } from '../api'

const base: PlayerOwnership = { state: 'ROSTERED', rosterId: 3, ownerName: 'Dunk Tank', avatarId: null, isMe: false, asOf: null }

describe('OwnershipMark', () => {
  it('shows the owner by name, with no Yours pill, when the player is rostered by someone else', () => {
    render(<OwnershipMark o={base} />)
    expect(screen.getByText('Dunk Tank')).toBeInTheDocument()
    expect(screen.queryByText('Yours')).not.toBeInTheDocument()
  })

  it('spells Yours out as text when the viewer owns him', () => {
    render(<OwnershipMark o={{ ...base, isMe: true }} />)
    expect(screen.getByText('Yours')).toBeInTheDocument()
    expect(screen.getByText('Dunk Tank')).toBeInTheDocument()
  })

  it('says Free agent for a free agent, and carries the as-of in the tooltip', () => {
    render(
      <OwnershipMark
        o={{ ...base, state: 'FREE_AGENT', ownerName: null, asOf: { kind: 'CURRENT', fetchedAt: '2026-10-20T15:00:00Z', week: null } }}
      />,
    )
    expect(screen.getByText('Free agent')).toHaveAttribute('title', expect.stringMatching(/^Rosters as of Oct 20/))
  })

  it('keeps the not-drafted reason in a tooltip in a table, and prints it on the player page', () => {
    const { rerender } = render(<OwnershipMark o={{ ...base, state: 'NOT_DRAFTED' }} />)
    expect(screen.getByText('not drafted')).toHaveAttribute('title')
    rerender(<OwnershipMark o={{ ...base, state: 'NOT_DRAFTED' }} verbose />)
    expect(screen.queryByText('not drafted')).not.toBeInTheDocument()
  })

  it('shows a dash with the reason in a tooltip when ownership is unavailable', () => {
    render(<OwnershipMark o={{ ...base, state: 'UNAVAILABLE' }} />)
    expect(screen.getByText('–')).toHaveAttribute('title')
  })
})

describe('ownershipFromTrend', () => {
  it('maps the Trends trio: unknown, free agent, theirs, mine', () => {
    expect(ownershipFromTrend({ rostered: null, rosteredBy: null, rosteredByMe: null }).state).toBe('UNAVAILABLE')
    expect(ownershipFromTrend({ rostered: false, rosteredBy: null, rosteredByMe: false }).state).toBe('FREE_AGENT')
    const theirs = ownershipFromTrend({ rostered: true, rosteredBy: 'Casey', rosteredByMe: false })
    expect(theirs).toMatchObject({ state: 'ROSTERED', ownerName: 'Casey', isMe: false })
    expect(ownershipFromTrend({ rostered: true, rosteredBy: 'Me', rosteredByMe: true }).isMe).toBe(true)
  })
})
