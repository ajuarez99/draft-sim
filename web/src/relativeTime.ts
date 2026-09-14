// Mock-drafts list needs "2 hours ago" / "Yesterday" / "Sep 6" rather than a
// raw timestamp (design_handoff_multisport_mock_drafts). No existing helper
// in the app does this -- LiveStatusBar's freshness pill is seconds-only, for
// a stream that's always fresh or stale within a couple of minutes.
export function relativeTime(iso: string, now: Date = new Date()): string {
  const then = new Date(iso)
  const diffMs = now.getTime() - then.getTime()
  const diffMin = Math.round(diffMs / 60000)

  if (diffMin < 1) return 'Just now'
  if (diffMin < 60) return `${diffMin} minute${diffMin === 1 ? '' : 's'} ago`

  const diffHr = Math.round(diffMin / 60)
  if (diffHr < 24) return `${diffHr} hour${diffHr === 1 ? '' : 's'} ago`

  // Calendar-day difference, not a 24h bucket -- 11pm yesterday and 1am today
  // are both "Yesterday" even though they're 2 hours apart.
  const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate())
  const diffDays = Math.round((startOfDay(now).getTime() - startOfDay(then).getTime()) / 86400000)
  if (diffDays === 0) return 'Today'
  if (diffDays === 1) return 'Yesterday'
  if (diffDays < 7) return `${diffDays} days ago`

  return then.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}
