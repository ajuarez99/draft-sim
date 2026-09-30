import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import OnBrandPanel, { OnBrandLine } from './OnBrandPanel'
import type { OnBrandRead } from '../onBrand'

function read(over: Partial<OnBrandRead> = {}): OnBrandRead {
  return {
    slot: 1, manager: 'Ann', avatarId: null, provenance: 'FITTED', picks: 4,
    reachSoFar: 3.4, profileReach: 8, reachVerdict: 'on', reachReason: null, reachLabel: 'vs board ADP',
    lean: { position: 'RB', tilt: 1.4 }, leanShare: 0.5, roomShare: 0.25, leanVerdict: 'on', leanReason: null,
    mix: { RB: 2, WR: 1, QB: 1 },
    ...over,
  }
}

describe('OnBrandPanel', () => {
  it('is collapsed by default and opens on click', async () => {
    const { container } = render(<OnBrandPanel reads={[read()]} />)
    const details = container.querySelector('details')!
    expect(details.open).toBe(false)
    await userEvent.click(screen.getByText('Room read'))
    expect(details.open).toBe(true)
  })

  it('renders one row per seat with name, mix, signed reach and verdicts', () => {
    render(<OnBrandPanel reads={[read(), read({ slot: 2, manager: 'Bo' })]} />)
    expect(screen.getByText('Ann')).toBeInTheDocument()
    expect(screen.getByText('Bo')).toBeInTheDocument()
    expect(screen.getAllByText('2 RB · 1 QB · 1 WR')).toHaveLength(2)
    expect(screen.getAllByText(/reach \+3\.4 vs \+8\.0/)).toHaveLength(2)
    expect(screen.getAllByText('vs board ADP')).toHaveLength(2)
    expect(screen.getAllByText('on brand').length).toBe(4)
  })
})

describe('OnBrandLine', () => {
  it('shows reasons when a verdict is absent, and labels stated seats', () => {
    render(
      <OnBrandLine
        read={read({
          provenance: 'STATED', reachSoFar: null, reachVerdict: null, reachReason: 'too early',
          reachLabel: 'vs what you entered', lean: null, leanVerdict: null, leanReason: 'lean not fitted',
        })}
      />,
    )
    expect(screen.getByText('too early')).toBeInTheDocument()
    expect(screen.getByText('lean not fitted')).toBeInTheDocument()
    expect(screen.getByText('vs what you entered')).toBeInTheDocument()
  })

  it('says off brand, and hand-set thresholds carry a title', () => {
    render(<OnBrandLine read={read({ reachVerdict: 'off', reachSoFar: -6 })} />)
    const v = screen.getByText('off brand')
    expect(v).toHaveAttribute('title', expect.stringContaining('Hand-set'))
    expect(screen.getByText(/reach −6\.0 vs \+8\.0/)).toBeInTheDocument()
  })
})
