import type { Platform } from '../api'

/**
 * A small tinted pill naming where a league comes from (spec 035, FR-003). Renders nothing for a
 * Sleeper league: Sleeper is the unmarked default, so only the exception wears a label.
 */
export default function PlatformBadge({ platform }: { platform: Platform | undefined }) {
  if (platform !== 'ESPN') return null
  return (
    <span className="platform-pill espn" title="This league is on ESPN">
      ESPN
    </span>
  )
}
