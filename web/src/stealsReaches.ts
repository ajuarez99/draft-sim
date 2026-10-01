/**
 * Steals and reaches for a finished draft.
 *
 * The comparison is the pick number against the player's ADP *when the pick was
 * made* (`RealPick.adpAtDraft`, captured from `draft_pick.adp_at_time`), never
 * against today's `player.adp`: a player's ADP moves after the draft, and
 * judging a September pick by a January ADP says nothing about September.
 *
 *   delta = pickNo - adpAtDraft
 *   delta > 0  taken AFTER his ADP: he fell, the drafter got a steal
 *   delta < 0  taken BEFORE his ADP: the drafter reached
 *
 * A null/missing `adpAtDraft` is "unknown", not zero: it is never tinted.
 */
export type PickValueKind = 'steal' | 'reach' | 'even' | 'unknown'

export type PickValue = {
  kind: PickValueKind
  /** pickNo - adpAtDraft, unrounded; null when unknown. */
  delta: number | null
}

export function pickValue(pickNo: number, adpAtDraft: number | null | undefined): PickValue {
  if (adpAtDraft == null || !Number.isFinite(adpAtDraft)) return { kind: 'unknown', delta: null }
  const delta = pickNo - adpAtDraft
  // Under half a pick either way rounds to 0 on screen, so call it even rather
  // than tint a difference the cell would print as "0".
  if (Math.abs(delta) < 0.5) return { kind: 'even', delta }
  return { kind: delta > 0 ? 'steal' : 'reach', delta }
}

/** "+12" / "-8" / "0", with a real minus sign, from a delta. */
export function signedPicks(delta: number): string {
  const n = Math.round(delta)
  if (n === 0) return '0'
  return n > 0 ? `+${n}` : `−${Math.abs(n)}`
}

/** Tint strength in percent, growing with the size of the difference and capped. */
export function tintPercent(delta: number): number {
  return Math.min(26, 8 + Math.abs(delta) * 1.2)
}

/** True when at least one pick carries a draft-time ADP, i.e. the view can say anything. */
export function hasAnyAdpAtDraft(adps: Iterable<number | null | undefined>): boolean {
  for (const a of adps) if (a != null) return true
  return false
}
