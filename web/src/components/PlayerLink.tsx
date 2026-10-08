import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { Sport } from '../api'
import { playerPagesFor } from '../destinations'

type Props = {
  sleeperLeagueId: string
  sleeperPlayerId: string
  /** The league's sport. Required, never defaulted: it decides whether the name is a link at all. */
  sport: Sport
  children: ReactNode
  className?: string
}

/**
 * A player's name that opens his page (specs/022). The gate is
 * `playerPagesFor(sport)` from the destinations table, so a sport with no
 * player page gets its name back as plain children, and no caller compares a
 * sport string to decide.
 */
export default function PlayerLink({ sleeperLeagueId, sleeperPlayerId, sport, children, className }: Props) {
  if (!playerPagesFor(sport)) return <>{children}</>
  return (
    <Link
      to={`/leagues/${encodeURIComponent(sleeperLeagueId)}/players/${encodeURIComponent(sleeperPlayerId)}`}
      className={`player-link${className ? ` ${className}` : ''}`}
    >
      {children}
    </Link>
  )
}
