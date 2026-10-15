import { Link } from 'react-router-dom'
import type { LeagueUnavailable, Platform } from '../api'

const NAMES: Record<Platform, string> = { SLEEPER: 'Sleeper', ESPN: 'ESPN' }

/**
 * What a page shows INSTEAD of its body when this league can't honestly produce it (spec 035 FR-008): one
 * line naming the platform, plus the server's own reason. Never an empty chart and never a zero that reads
 * as a result.
 */
export default function NotAvailableForPlatform({
  platform,
  reason,
  leagueKey,
}: {
  platform: Platform
  reason: LeagueUnavailable
  /** When given, a way back to the league's home. */
  leagueKey?: string
}) {
  return (
    <div className="content">
      <section className="panel not-available" role="status" data-reason={reason.code}>
        <p>
          <strong>Not available for {NAMES[platform]} leagues yet.</strong>
        </p>
        <p className="muted">{reason.message}</p>
        {leagueKey && (
          <p>
            <Link to={`/leagues/${encodeURIComponent(leagueKey)}`}>Back to league home</Link>
          </p>
        )}
      </section>
    </div>
  )
}

/** A single muted line for a block that is hidden inside a page that otherwise renders (league home). */
export function BlockNote({ label, reason }: { label: string; reason: LeagueUnavailable }) {
  return (
    <li className="muted small block-note" data-reason={reason.code}>
      <strong>{label}</strong>: {reason.message}
    </li>
  )
}
