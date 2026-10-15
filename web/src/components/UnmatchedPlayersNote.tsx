import type { UnmatchedPlayer } from '../api'

/** Plain words for why the matcher refused a player (spec 035 FR-007). */
export function unmatchedReason(outcome: string): string {
  switch (outcome) {
    case 'AMBIGUOUS':
      return 'more than one player shares this name'
    case 'ESPN_ONLY_POSITION':
      return "a position Ball Knowers doesn't model"
    default:
      return 'no matching player found'
  }
}

/**
 * The players Ball Knowers refused to match to its own player table, counted and named (spec 035 FR-007:
 * "a visible count and list"). A refused player is left out of lineups and projections rather than guessed
 * at, so the owner needs to see who. Renders nothing when there are none.
 */
export default function UnmatchedPlayersNote({
  count,
  players,
}: {
  count: number
  players: UnmatchedPlayer[]
}) {
  const shown = count > 0 ? count : players.length
  if (shown === 0) return null
  return (
    <details className="unmatched-players small">
      <summary>
        {shown} player{shown === 1 ? '' : 's'} on these rosters could not be matched
      </summary>
      <p className="muted">
        They are left out of lineups and projections instead of being guessed at.
      </p>
      <ul>
        {players.map((p, i) => (
          <li key={`${p.name}-${p.team ?? ''}-${i}`}>
            {p.name}
            <span className="muted">
              {' '}
              {[p.position, p.team].filter(Boolean).join(' · ')} {p.position || p.team ? '· ' : ''}
              {unmatchedReason(p.outcome)}
            </span>
          </li>
        ))}
      </ul>
    </details>
  )
}
