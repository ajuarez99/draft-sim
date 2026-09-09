import type { SlotStatus } from '../teamNeeds'

type Props = {
  /** Straight from computeTeamNeeds -- template order, one entry per starting slot. */
  needs: SlotStatus[]
}

/**
 * A roster against its starting-lineup template: one pill per starting slot,
 * filled ones naming the player, open ones naming the slot.
 *
 * Lifted out of PlayerPicker unchanged when the live room needed the same
 * thing (claude/live-pick-names-and-team-fit.md, phase 3). Two rooms drawing
 * "your team so far" from two copies of this markup is exactly how they end up
 * seating a third RB differently -- the seating rule already lives in one
 * place (teamNeeds.computeTeamNeeds) and now so does the picture of it.
 *
 * Renders nothing for an empty template: a league whose roster settings never
 * synced has `rosterPositions: []` (see SeatsResponse), and an empty strip
 * would read as "you have no starters" rather than "we don't know the shape".
 */
export default function TeamStrip({ needs }: Props) {
  if (needs.length === 0) return null
  return (
    <div className="team-strip">
      {needs.map((n, i) => (
        <div key={i} className={n.player ? 'team-slot filled' : 'team-slot open'}>
          {n.player ? (
            <>
              <span className={`pos ${n.player.position}`}>{n.player.position}</span>
              <span className="team-slot-name">{n.player.name}</span>
            </>
          ) : (
            <span className="team-slot-empty">{n.slot}</span>
          )}
        </div>
      ))}
    </div>
  )
}
