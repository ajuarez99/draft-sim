import { INSIGHT } from '../insightConstants'
import type { OnBrandRead, Verdict } from '../onBrand'
import Avatar from './Avatar'

const signed = (n: number) => `${n > 0 ? '+' : n < 0 ? '−' : '±'}${Math.abs(n).toFixed(1)}`
const pct = (n: number) => `${Math.round(n * 100)}%`

const REACH_TITLE =
  `Hand-set thresholds, not fitted: needs ${INSIGHT.MIN_PICKS_FOR_VERDICT}+ picks; ` +
  `"on brand" = same sign, or both within ±${INSIGHT.REACH_TOLERANCE} picks.`
const LEAN_TITLE =
  `Hand-set thresholds, not fitted: a lean is a tilt of ${INSIGHT.LEAN_TILT.toFixed(2)} or more; ` +
  `needs ${INSIGHT.MIN_PICKS_FOR_VERDICT}+ picks; "on brand" = taking it more often than the room does.`

function verdictText(v: Verdict, reason: string | null): string {
  if (v === 'on') return 'on brand'
  if (v === 'off') return 'off brand'
  return reason ?? ''
}

export function mixText(mix: Record<string, number>): string {
  const parts = Object.entries(mix)
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([pos, n]) => `${n} ${pos}`)
  return parts.length ? parts.join(' · ') : 'no picks yet'
}

/** ONE seat's read, compactly. Reused by the pick card and SeatPopover. */
export function OnBrandLine({ read }: { read: OnBrandRead }) {
  const reach =
    read.reachSoFar == null
      ? `reach so far n/a vs ${signed(read.profileReach)}`
      : `reach ${signed(read.reachSoFar)} vs ${signed(read.profileReach)}`
  return (
    <span className="onbrand-line">
      <span className="onbrand-mix mono">{mixText(read.mix)}</span>
      <span className="onbrand-reach">
        {reach} <span className="muted">{read.reachLabel}</span>
      </span>
      <span className={`onbrand-verdict ${read.reachVerdict ?? 'none'}`} title={REACH_TITLE}>
        {verdictText(read.reachVerdict, read.reachReason)}
      </span>
      <span
        className="onbrand-lean"
        title="By each player's first listed position, the same way the manager's lean was fitted (Sleeper lists positions alphabetically, so for multi-position players this is not necessarily their main position)."
      >
        {read.lean
          ? `leans ${read.lean.position} (${pct(read.leanShare)} of picks vs room ${pct(read.roomShare)})`
          : 'no positional lean'}
      </span>
      <span className={`onbrand-verdict ${read.leanVerdict ?? 'none'}`} title={LEAN_TITLE}>
        {verdictText(read.leanVerdict, read.leanReason)}
      </span>
    </span>
  )
}

type Props = {
  reads: OnBrandRead[]
  myManager?: string | null
  /** Just the rows, no <details>/summary: for a host that supplies its own toggle (CompactRow's popover). */
  bare?: boolean
}

/** "Room read": collapsed by default; one row per seat. */
export default function OnBrandPanel({ reads, myManager, bare }: Props) {
  const rows = (
    <ul className="onbrand-rows">
      {reads.map((r) => (
        <li key={r.slot} className="onbrand-row">
          <Avatar avatarId={r.avatarId} seed={String(r.slot)} label={r.manager} isMe={myManager === r.manager} />
          <span className="onbrand-name">{r.manager}</span>
          <OnBrandLine read={r} />
        </li>
      ))}
    </ul>
  )
  if (bare) return <div className="onbrand-panel bare">{rows}</div>
  return (
    <details className="onbrand-panel">
      <summary className="onbrand-summary">Room read</summary>
      {rows}
    </details>
  )
}
