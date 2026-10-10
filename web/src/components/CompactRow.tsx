import { useState, type ReactNode } from 'react'
import type { Sport } from '../api'
import type { SlotStatus } from '../teamNeeds'
import PickFeed, { type FeedPick } from './PickFeed'
import TeamStrip from './TeamStrip'

type Props = {
  /** Picks that have landed, oldest first -- the same list PickFeed takes. */
  feedPicks?: FeedPick[]
  teams: number
  sport: Sport
  onPickClick?: (pickNo: number) => void
  /** From computeTeamNeeds. Omitted (or empty) when there is no seat to show a team for. */
  needs?: SlotStatus[]
  /** A ready-made ScarcityMeter. */
  scarcity?: ReactNode
  /** The room read's rows (OnBrandPanel bare), opened from a "Room read" toggle as a popover. */
  roomRead?: ReactNode
}

/** How many picks the ticker shows once expanded. ARBITRARY: the spec says "the last few". */
const EXPANDED_PICKS = 5

/**
 * Spec 024 FR-001c: the feed, your team and the scarcity chips that used to
 * take ~440px above the board, folded into one row. Every part is optional --
 * a room passes only what it has -- and a part with nothing to show renders
 * nothing, not an empty box.
 *
 * The ticker is PickFeed itself (limit 1, then 5) rather than a second copy of
 * its rows, so the announcement, the position-run callout and the click-to-open
 * pick card all behave as they did.
 */
export default function CompactRow({ feedPicks, teams, sport, onPickClick, needs, scarcity, roomRead }: Props) {
  const [expanded, setExpanded] = useState(false)
  const [readOpen, setReadOpen] = useState(false)

  const hasFeed = feedPicks != null && feedPicks.length > 0
  const hasTeam = needs != null && needs.length > 0
  const startersSet = needs?.filter((n) => n.player != null).length ?? 0
  if (!hasFeed && !hasTeam && !scarcity && !roomRead) return null

  return (
    <div className="compact-row">
      {hasFeed && (
        <div className="compact-ticker">
          <PickFeed
            picks={feedPicks}
            teams={teams}
            sport={sport}
            limit={expanded ? EXPANDED_PICKS : 1}
            onPickClick={onPickClick}
            compact
          />
          {feedPicks.length > 1 && (
            <button
              type="button"
              className="chip compact-ticker-toggle"
              aria-expanded={expanded}
              onClick={() => setExpanded((e) => !e)}
              title={expanded ? 'Show only the latest pick' : `Show the last ${EXPANDED_PICKS} picks`}
            >
              {expanded ? '▴ Latest' : `▾ Last ${Math.min(EXPANDED_PICKS, feedPicks.length)}`}
            </button>
          )}
        </div>
      )}
      {hasTeam && (
        <div className="compact-team">
          <strong className="cond">Your team</strong>
          <span className="muted tiny">
            {startersSet} of {needs.length} starters
          </span>
          <TeamStrip needs={needs} sport={sport} />
        </div>
      )}
      {scarcity && <div className="compact-scarcity">{scarcity}</div>}
      {roomRead && (
        <div className="compact-read">
          <button
            type="button"
            className={`chip${readOpen ? ' on' : ''}`}
            aria-expanded={readOpen}
            onClick={() => setReadOpen((o) => !o)}
          >
            Room read
          </button>
          {readOpen && <div className="compact-popover">{roomRead}</div>}
        </div>
      )}
    </div>
  )
}
