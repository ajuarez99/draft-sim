import type { PlayerStatsPage } from '../api'
import { ownershipAsOf, seasonLabel } from '../statCopy'
import OwnershipMark from './OwnershipMark'

/**
 * The pieces of a player's header the player page and the comparison both draw (spec 049 code-review B3/S4):
 * one copy, so the two pages can't word the owner or the teams disclosure differently.
 */

/** A player we have games for but no player record for. */
export function UnknownPlayerNote({ d }: { d: PlayerStatsPage }) {
  if (d.player.known) return null
  return <span className="muted small">{`We have games for this player but no player record (id ${d.player.sleeperPlayerId}).`}</span>
}

/** "Played for X, Y in season" and the team games he missed. */
export function TeamLines({ d }: { d: PlayerStatsPage }) {
  return (
    <>
      {d.teamsThisSeason.length > 1 && (
        <p className="muted small pp-line">{`Played for ${d.teamsThisSeason.join(', ')} in ${seasonLabel(d.season)}.`}</p>
      )}
      {d.teamGamesMissed > 0 && (
        <p className="muted small pp-line">{`Missed ${d.teamGamesMissed} of his team’s games in ${seasonLabel(d.season)}.`}</p>
      )}
    </>
  )
}

/** The answered season's owner with its as-of label, and on a fallback season the URL league's current owner. */
export function OwnerList({ d }: { d: PlayerStatsPage }) {
  const own = d.ownership
  const ownLabel = own ? (ownershipAsOf(own.asOf, d.season) ?? 'Ownership') : null
  const nowLabel = d.currentOwnership && d.requestedSeason != null ? `Now (${seasonLabel(d.requestedSeason)})` : null
  const nowAsOf = d.currentOwnership ? ownershipAsOf(d.currentOwnership.asOf, d.requestedSeason ?? d.season) : null
  return (
    <div className="pp-own-list">
      {own && (
        <div className="pp-own">
          <span className="pp-own-label">{ownLabel}</span>
          <OwnershipMark o={own} verbose />
        </div>
      )}
      {d.currentOwnership && nowLabel && (
        <div className="pp-own pp-own-now">
          <span className="pp-own-label">{nowLabel}</span>
          <OwnershipMark o={d.currentOwnership} verbose />
          {nowAsOf && <span className="muted small">{nowAsOf}</span>}
        </div>
      )}
    </div>
  )
}
