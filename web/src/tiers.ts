/**
 * Tier grouping for the "who should I take" list.
 *
 * A tier is a run of players whose consensus ADP sits close together; a bigger
 * jump in ADP starts a new tier. It is a display grouping over the consensus
 * rank, not a model output and not a claim that the players inside a tier are
 * equal.
 */

/**
 * ARBITRARY, hand-set display grouping. A new tier starts when the next
 * player's ADP is more than this many picks past the previous player's.
 * 4 was chosen so that a 12-team round (12 picks) breaks into about three
 * tiers at the top of a draft, where ADP is dense, without every player
 * becoming his own tier. Not fitted to anything, not backtested; changing it
 * only changes how the list is grouped on screen.
 */
export const TIER_ADP_GAP = 4

/**
 * ARBITRARY, hand-set: the widest ADP span one tier may cover. Gaps alone chain
 * through a dense list: measured on a live mock (spec 013 parent review), one
 * "tier" ran from ADP 23 to 83, which implies a sameness 60 picks wide that
 * nobody would claim. A new tier also starts once a player's ADP is more than
 * this past the tier's first player. 12 = one round of a 12-team draft.
 */
export const TIER_MAX_SPAN = 12

/** Sleeper's own "no rank" sentinel (see PlayerRef.positionalRank). */
export const UNRANKED_ADP = 999

export type Tier<T> = {
  /** 1-based tier number among ranked players; null for the unranked group. */
  tier: number | null
  /** "Tier 1", "Tier 2", ... or "Unranked". */
  label: string
  players: T[]
}


/**
 * Sort by ADP and cut into tiers. A gap exactly equal to TIER_ADP_GAP does not
 * split; a greater one does. Players at the 999 sentinel go in one trailing
 * "Unranked" group and never take part in gap arithmetic (999 - 40 is not a
 * real gap).
 */
export function tierPlayers<T>(players: T[], adpOf: (p: T) => number): Tier<T>[] {
  const ranked = players.filter((p) => adpOf(p) < UNRANKED_ADP).sort((a, b) => adpOf(a) - adpOf(b))
  const unranked = players.filter((p) => adpOf(p) >= UNRANKED_ADP)
  const tiers: Tier<T>[] = []
  let prev: number | null = null
  let start: number | null = null
  for (const p of ranked) {
    const adp = adpOf(p)
    if (prev == null || start == null || adp - prev > TIER_ADP_GAP || adp - start > TIER_MAX_SPAN) {
      start = adp
      const n = tiers.length + 1
      tiers.push({ tier: n, label: `Tier ${n}`, players: [] })
    }
    tiers[tiers.length - 1].players.push(p)
    prev = adp
  }
  if (unranked.length > 0) tiers.push({ tier: null, label: 'Unranked', players: unranked })
  return tiers
}

