import { fireEvent, render, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { sgEligible, top108Players, top108Rows } from '../testPositionShapes'
import OnTheClockPickInput from './OnTheClockPickInput'
import PlayerPicker from './PlayerPicker'

const NBA_TEMPLATE = ['PG', 'SG', 'SF', 'PF', 'C', 'UTIL', 'BN']
const names = (c: HTMLElement) => [...c.querySelectorAll('tbody .player-col')].map((e) => e.textContent ?? '')
const chip = (c: HTMLElement, label: string) =>
  within(c).getAllByRole('button').find((b) => b.textContent === label) as HTMLElement

describe('position filters honor every eligible position (spec 025 US2)', () => {
  const picker = () =>
    render(
      <PlayerPicker
        pausedAt={20}
        teams={12}
        availability={top108Rows()}
        alreadyPicked={new Set()}
        rosterPositions={NBA_TEMPLATE}
        draftedPlayers={[]}
        onPick={() => {}}
        onClose={() => {}}
        sport="nba"
      />,
    )
  const onClock = () =>
    render(
      <OnTheClockPickInput
        pickNo={20}
        round={2}
        available={top108Players()}
        rosterPositions={NBA_TEMPLATE}
        draftedPlayers={[]}
        onPick={() => {}}
        onClose={() => {}}
        sport="nba"
      />,
    )

  it('PlayerPicker: ALL lists each of the 108 once; the SG filter has 100% recall of SG-eligible players', () => {
    const { container } = picker()
    expect(names(container)).toHaveLength(108)
    expect(new Set(names(container)).size).toBe(108)
    fireEvent.click(chip(container, 'SG'))
    const want = sgEligible(top108Players()).length
    expect(want).toBe(42)
    expect(names(container)).toHaveLength(want)
  })

  it('PlayerPicker: a PG/SG player appears under PG and under SG', () => {
    const { container } = picker()
    fireEvent.click(chip(container, 'PG'))
    expect(names(container).some((n) => n.includes('PG/SG'))).toBe(true)
    fireEvent.click(chip(container, 'SG'))
    expect(names(container).some((n) => n.includes('PG/SG'))).toBe(true)
  })

  it('OnTheClockPickInput: SG filter has 100% recall; ALL lists each once', () => {
    const { container } = onClock()
    expect(names(container)).toHaveLength(108)
    fireEvent.click(chip(container, 'SG'))
    expect(names(container)).toHaveLength(42)
  })

  it('the multi-position pill reads the full label, with no rank number', () => {
    const { container } = picker()
    const pill = container.querySelector('.pos.multi') as HTMLElement
    expect(pill).not.toBeNull()
    expect(pill.textContent).toMatch(/^[A-Z]{1,2}(\/[A-Z]{1,2})+$/)
    expect(pill.getAttribute('style')).toContain('--pos-a')
  })
})
