import type { AvailabilityRow, DraftTargets, PlayerRef } from './api'

/**
 * One entry in a draft's target list (spec 024 US3). `player` is null for a target
 * whose player is no longer on the sport's board (the server's `missing` list): it
 * still shows, by name, but has no taken state and no survival number.
 */
export type TargetItem = { sleeperId: string; name: string; player: PlayerRef | null }
export type MarkedTarget = TargetItem & { taken: boolean }

/** Lowercase, accents removed: "Nikola Jokić" -> "nikola jokic". New: nothing else in web/src folds accents. */
export function fold(s: string): string {
  return s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
}

/** Fewer than 2 characters (after trimming) is not a search: everything matches. */
export const SEARCH_MIN_CHARS = 2

export function matchesSearch(query: string, name: string): boolean {
  const q = fold(query.trim())
  if (q.length < SEARCH_MIN_CHARS) return true
  return fold(name).includes(q)
}

/** The server's two lists as one ordered list: board players in rank order, then the ones off the board. */
export function itemsFromServer(t: DraftTargets): TargetItem[] {
  return [
    ...t.players.map((p) => ({ sleeperId: p.sleeperId, name: p.name, player: p })),
    ...t.missing.map((m) => ({ sleeperId: m.sleeperId, name: m.name, player: null })),
  ]
}

/** Keeps every target in place and flags the ones any seat has taken (FR-013). */
export function markTaken(items: TargetItem[], takenPlayerIds: Set<number>): MarkedTarget[] {
  return items.map((t) => ({ ...t, taken: t.player != null && takenPlayerIds.has(t.player.id) }))
}

/** The first target still on the board: what auto-pick would take. */
export function topAvailable(items: TargetItem[], takenPlayerIds: Set<number>): TargetItem | undefined {
  return items.find((t) => t.player != null && !takenPlayerIds.has(t.player.id))
}

/**
 * The chance this player is still there at the user's next pick, or undefined.
 * Undefined, never 0, when the seat is unknown, there is no next pick, the player is
 * absent from the availability table, or the table has no entry for that pick: a 0
 * would read as "he will be gone" when the truth is "we don't know" (FR-012).
 */
export function survivalFor(
  player: PlayerRef | null,
  availability: AvailabilityRow[] | undefined,
  nextPick: number | null | undefined,
  slotKnown: boolean,
): number | undefined {
  if (!slotKnown || nextPick == null || player == null || !availability) return undefined
  const row = availability.find((r) => r.player.id === player.id)
  return row?.survivalByPick[String(nextPick)]
}
