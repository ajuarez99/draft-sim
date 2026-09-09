import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import PickFeed, { type FeedPick } from './PickFeed'
import type { PlayerRef } from '../api'

/**
 * The newest row is the live room's pick announcement (see
 * claude/live-pick-names-and-team-fit.md). What is worth pinning is which of
 * its two possible trailing clauses wins, and that the manager survives either
 * way -- the row used to drop "who took him" entirely whenever a run showed,
 * which is half of what an announcement is for.
 */

let nextId = 1
function player(position: string, name: string): PlayerRef {
  const id = nextId++
  return {
    id,
    sleeperId: String(id),
    name,
    position: position as PlayerRef['position'],
    team: 'SEA',
    adp: id,
    positionalRank: id,
  }
}

function pick(pickNo: number, position: string, name: string, manager: string, fit?: string): FeedPick {
  return { pickNo, player: player(position, name), manager, fit }
}

const TEAMS = 14

describe('the announcement row', () => {
  it('names the newest pick in full, with who took him and how he fits', () => {
    render(
      <PickFeed
        teams={TEAMS}
        picks={[
          pick(1, 'RB', 'Bijan Robinson', 'Sam'),
          pick(2, 'WR', "Ja'Marr Chase", 'Allan', 'Fills WR1'),
        ]}
      />,
    )

    expect(screen.getByText("Ja'Marr Chase")).toBeTruthy()
    expect(screen.getByText('Allan')).toBeTruthy()
    expect(screen.getByText('Fills WR1')).toBeTruthy()
  })

  it('abbreviates the older rows and leaves their fit unsaid', () => {
    render(
      <PickFeed
        teams={TEAMS}
        // Both carry a fit; only the newest may render one.
        picks={[
          pick(1, 'RB', 'Bijan Robinson', 'Sam', 'Fills RB1'),
          pick(2, 'WR', "Ja'Marr Chase", 'Allan', 'Fills WR1'),
        ]}
      />,
    )

    expect(screen.queryByText('Fills RB1')).toBeNull()
    // shortName's split: the older row shows an initial plus the surname, not
    // the whole name the announcement row gets.
    expect(screen.queryByText('Bijan Robinson')).toBeNull()
    expect(screen.getByText('Robinson')).toBeTruthy()
  })

  it('gives the trailing slot to a position run, but keeps the manager', () => {
    // Four of the last six at one position is the run threshold PickFeed uses.
    const picks = [
      pick(1, 'QB', 'Josh Allen', 'Sam'),
      pick(2, 'WR', 'Player Two', 'Kim'),
      pick(3, 'WR', 'Player Three', 'Lee'),
      pick(4, 'WR', 'Player Four', 'Ray'),
      pick(5, 'TE', 'Player Five', 'Jo'),
      pick(6, 'WR', 'Player Six', 'Allan', 'Fills WR2'),
    ]
    render(<PickFeed teams={TEAMS} picks={picks} />)

    expect(screen.getByText('4 of the last 6 were WR')).toBeTruthy()
    expect(screen.queryByText('Fills WR2')).toBeNull()
    expect(screen.getByText('Allan')).toBeTruthy()
  })

  it('renders nothing at all before the first pick', () => {
    const { container } = render(<PickFeed teams={TEAMS} picks={[]} />)
    expect(container.firstChild).toBeNull()
  })
})
