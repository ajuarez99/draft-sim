import { fireEvent, render, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { AvailabilityRow, PlayerRef } from '../api'
import { isRookie, rookieFlagKnown } from '../rookie'
import AvailabilityPanel from './AvailabilityPanel'
import OnTheClockPickInput from './OnTheClockPickInput'
import PlayerPicker from './PlayerPicker'

// Four football players: one rookie RB, one veteran RB, one rookie WR, one veteran WR.
function player(id: number, name: string, position: PlayerRef['position'], rookie: boolean | undefined): PlayerRef {
  return { id, sleeperId: String(id), name, position, positions: [position], team: 'NYJ', adp: id, positionalRank: id, rookie }
}
const POOL = [
  player(1, 'Vet Back', 'RB', false),
  player(2, 'Rookie Back', 'RB', true),
  player(3, 'Vet Wideout', 'WR', false),
  player(4, 'Rookie Wideout', 'WR', true),
]
// The same four as an older backend sends them: no `rookie` field at all.
const OLD_SERVER_POOL = POOL.map((p) => ({ ...p, rookie: undefined }))
const rows = (ps: PlayerRef[]): AvailabilityRow[] => ps.map((p) => ({ player: p, survivalByPick: { '5': 0.9 } }))

const NFL_TEMPLATE = ['QB', 'RB', 'WR', 'TE', 'FLEX', 'BN']
const chip = (c: HTMLElement, label: string) =>
  within(c).getAllByRole('button').find((b) => b.textContent === label)
const listed = (c: HTMLElement) =>
  ['Vet Back', 'Rookie Back', 'Vet Wideout', 'Rookie Wideout'].filter((n) => within(c).queryByText(n) != null)

describe('rookie.ts', () => {
  it('only an explicit true is a rookie: false and a missing flag are not', () => {
    expect(isRookie({ rookie: true })).toBe(true)
    expect(isRookie({ rookie: false })).toBe(false)
    expect(isRookie({})).toBe(false)
  })

  it('the flag is "known" when any player carries it, including a list of all veterans', () => {
    expect(rookieFlagKnown(POOL)).toBe(true)
    expect(rookieFlagKnown([{ rookie: false }])).toBe(true)
    expect(rookieFlagKnown(OLD_SERVER_POOL)).toBe(false)
    expect(rookieFlagKnown([])).toBe(false)
  })
})

describe('Rookies chip in the draft-room player lists', () => {
  const picker = (pool: PlayerRef[]) =>
    render(
      <PlayerPicker
        pausedAt={5}
        teams={12}
        availability={rows(pool)}
        alreadyPicked={new Set()}
        rosterPositions={NFL_TEMPLATE}
        draftedPlayers={[]}
        onPick={() => {}}
        onClose={() => {}}
        sport="nfl"
      />,
    )
  const onClock = (pool: PlayerRef[]) =>
    render(
      <OnTheClockPickInput
        pickNo={5}
        round={1}
        available={pool}
        rosterPositions={NFL_TEMPLATE}
        draftedPlayers={[]}
        onPick={() => {}}
        onClose={() => {}}
        sport="nfl"
      />,
    )
  const panel = (pool: PlayerRef[]) =>
    render(
      <AvailabilityPanel
        players={pool}
        noAvailabilityReason="Mock drafts don’t project who’ll be there."
        myPicks={[]}
        teams={12}
        started
        sport="nfl"
      />,
    )

  for (const [name, mount] of [
    ['PlayerPicker', picker],
    ['OnTheClockPickInput', onClock],
    ['AvailabilityPanel', panel],
  ] as const) {
    it(`${name}: Rookies keeps only rookies, and narrows the position chip rather than replacing it`, () => {
      sessionStorage.clear()
      const { container } = mount(POOL)
      expect(listed(container)).toHaveLength(4)

      fireEvent.click(chip(container, 'Rookies')!)
      expect(chip(container, 'Rookies')).toHaveAttribute('aria-pressed', 'true')
      expect(listed(container)).toEqual(['Rookie Back', 'Rookie Wideout'])

      fireEvent.click(chip(container, 'RB')!)
      expect(listed(container)).toEqual(['Rookie Back'])

      fireEvent.click(chip(container, 'Rookies')!)
      expect(listed(container)).toEqual(['Vet Back', 'Rookie Back'])
    })

    it(`${name}: marks rookies, and only rookies, with an R`, () => {
      sessionStorage.clear()
      const { container } = mount(POOL)
      const tags = [...container.querySelectorAll('.rookie-tag')]
      expect(tags).toHaveLength(2)
      for (const t of tags) expect(t.closest('tr')?.textContent).toContain('Rookie')
    })

    it(`${name}: offers no Rookies chip when the server doesn't send the flag`, () => {
      sessionStorage.clear()
      const { container } = mount(OLD_SERVER_POOL)
      expect(chip(container, 'Rookies')).toBeUndefined()
      expect(container.querySelector('.rookie-tag')).toBeNull()
      expect(listed(container)).toHaveLength(4)
    })
  }

  it('AvailabilityPanel: a remembered Rookies choice survives a remount (the mock room unmounts it between turns)', () => {
    sessionStorage.clear()
    const first = panel(POOL)
    fireEvent.click(chip(first.container, 'Rookies')!)
    first.unmount()
    const second = panel(POOL)
    expect(listed(second.container)).toEqual(['Rookie Back', 'Rookie Wideout'])
  })

  it('AvailabilityPanel: a remembered choice filters nothing against a server without the flag', () => {
    sessionStorage.clear()
    const first = panel(POOL)
    fireEvent.click(chip(first.container, 'Rookies')!)
    first.unmount()
    const second = panel(OLD_SERVER_POOL)
    expect(listed(second.container)).toHaveLength(4)
  })

  it('AvailabilityPanel: says so when the filter leaves nobody', () => {
    sessionStorage.clear()
    const { container } = panel(POOL.filter((p) => !p.rookie))
    fireEvent.click(chip(container, 'Rookies')!)
    expect(within(container).getByText('No rookies to list.')).toBeInTheDocument()
  })
})
