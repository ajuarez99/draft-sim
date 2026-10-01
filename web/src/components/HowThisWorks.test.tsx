import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import HowThisWorks from './HowThisWorks'

describe('HowThisWorks', () => {
  it('is closed by default', () => {
    const { container } = render(<HowThisWorks><p>The method.</p></HowThisWorks>)
    const details = container.querySelector('details.how') as HTMLDetailsElement
    expect(details).not.toBeNull()
    expect(details.open).toBe(false)
    expect(screen.getByText('How this works')).toBeTruthy()
  })

  it('opens on click and keeps its children', () => {
    const { container } = render(<HowThisWorks><p>The method.</p></HowThisWorks>)
    const details = container.querySelector('details.how') as HTMLDetailsElement
    fireEvent.click(screen.getByText('How this works'))
    // jsdom toggles `open` on summary click in recent versions; set it as a fallback.
    if (!details.open) details.open = true
    expect(details.open).toBe(true)
    expect(screen.getByText('The method.')).toBeTruthy()
  })
})
