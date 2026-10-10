import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { PlayerRef, RealPick } from '../api'
import { buildFactInsight, type PickInsight } from '../pickInsight'
import { mkPlayer, mkRealPick, mkSeats, NFL_TEMPLATE } from '../testLiveRoom'
import PickInsightCard from './PickInsightCard'

const TEAMS = 12

function insightFor(player: PlayerRef, pickNo = 16, prior: PlayerRef[] = []): PickInsight {
  const slot = 3
  const priorPicks: RealPick[] = prior.map((p, i) => mkRealPick(i + 1, TEAMS, p, slot))
  const pick = mkRealPick(pickNo, TEAMS, player, slot)
  return buildFactInsight(pick, [...priorPicks, pick], mkSeats(TEAMS).seats, 'nfl', NFL_TEMPLATE)
}

function renderCard(insight: PickInsight, over: Partial<Parameters<typeof PickInsightCard>[0]> = {}) {
  const onClose = vi.fn()
  const utils = render(
    <PickInsightCard insight={insight} teams={TEAMS} autoFocus={false} onClose={onClose} {...over} sport={over.sport ?? 'nfl'} />,
  )
  return { onClose, ...utils }
}

describe('PickInsightCard rows 1-3', () => {
  it('names the manager, pick, player, position and team, then the fit', () => {
    const rb = mkPlayer('RB', 'Bijan Robinson', 30)
    renderCard(insightFor(rb, 16, [mkPlayer('RB')]))

    expect(screen.getByText('Bijan Robinson')).toBeTruthy()
    expect(screen.getByText('Mgr3')).toBeTruthy()
    expect(screen.getByText('2.04')).toBeTruthy() // pick 16 of 12 teams
    expect(screen.getByText('RB')).toBeTruthy()
    expect(screen.getByText('SEA')).toBeTruthy()
    expect(screen.getByText('Fills RB2')).toBeTruthy()
    expect(screen.getByText(/^Still needs:/).textContent).toContain('QB')
  })

  it('says Depth when no starting slot was filled', () => {
    const prior = [mkPlayer('WR', undefined, 1), mkPlayer('WR', undefined, 2), mkPlayer('WR', undefined, 3), mkPlayer('WR', undefined, 4)]
    renderCard(insightFor(mkPlayer('WR', 'Fifth Receiver', 99), 40, prior))
    expect(screen.getByText('Depth')).toBeTruthy()
  })

  it('says Roster complete and no "Still needs" when nothing is open', () => {
    const base = insightFor(mkPlayer('DEF'))
    renderCard({ ...base, fills: 'DEF', openAfter: [], rosterComplete: true })
    expect(screen.getByText('Roster complete')).toBeTruthy()
    expect(screen.queryByText(/Still needs/)).toBeNull()
  })

  it('leaves the fit rows out entirely when fit is not known', () => {
    const base = insightFor(mkPlayer('RB'))
    const { container } = renderCard({ ...base, fitKnown: false, summary: '' })
    expect(screen.queryByText(/Fills|Depth|Still needs|Roster complete/)).toBeNull()
    expect(container.querySelector('.pick-card-fit')).toBeNull()
    expect(container.querySelector('.pick-card-summary')).toBeNull()
  })

  it('is a polite, non-modal dialog with a close button', async () => {
    const { onClose } = renderCard(insightFor(mkPlayer('RB')))
    const dialog = screen.getByRole('dialog')
    expect(dialog.getAttribute('aria-modal')).toBe('false')
    expect(dialog.getAttribute('aria-live')).toBe('polite')
    await userEvent.click(screen.getByRole('button', { name: /close/i }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('Escape closes it', async () => {
    const { onClose } = renderCard(insightFor(mkPlayer('RB')))
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('does not take focus on auto-open, but does when opened from the feed', () => {
    const { unmount } = renderCard(insightFor(mkPlayer('RB')), { autoFocus: false })
    expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(false)
    unmount()
    renderCard(insightFor(mkPlayer('RB')), { autoFocus: true })
    expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true)
  })

  it('reports hover and focus so the page can pause its dismiss timer', async () => {
    const onPauseChange = vi.fn()
    renderCard(insightFor(mkPlayer('RB')), { onPauseChange })
    await userEvent.hover(screen.getByRole('dialog'))
    expect(onPauseChange).toHaveBeenLastCalledWith(true)
    await userEvent.unhover(screen.getByRole('dialog'))
    expect(onPauseChange).toHaveBeenLastCalledWith(false)
  })

  it('renders an extension row only when its content is supplied', () => {
    const { container, rerender } = renderCard(insightFor(mkPlayer('RB')))
    expect(container.querySelector('.pick-card-onbrand')).toBeNull()
    rerender(
      <PickInsightCard
        insight={insightFor(mkPlayer('RB'))}
        sport="nfl"
        teams={TEAMS}
        autoFocus={false}
        onClose={() => {}}
        extras={{ onBrand: <span>next up</span> }}
      />,
    )
    expect(screen.getByText('next up')).toBeTruthy()
  })
})

describe('PickInsightCard ADP chip (row 4)', () => {
  it('a player with ADP 30 taken at pick 16 reads "14 before ADP"', () => {
    renderCard(insightFor(mkPlayer('WR', 'Early Taken', 30), 16))
    expect(screen.getByText('14 before ADP')).toBeTruthy()
    expect(screen.queryByText(/past ADP/)).toBeNull()
  })

  it('a player with ADP 10 taken at pick 19 reads "9 past ADP"', () => {
    renderCard(insightFor(mkPlayer('WR', 'Slid', 10), 19))
    expect(screen.getByText('9 past ADP')).toBeTruthy()
  })

  it('omits the chip, and the tags row, when the player has no ADP', () => {
    const { container } = renderCard(insightFor({ ...mkPlayer('WR', 'Off Board'), adp: 999, positionalRank: 999 }, 16))
    expect(screen.queryByText(/ADP/)).toBeNull()
    expect(container.querySelector('.pick-card-tags')).toBeNull()
  })
})
