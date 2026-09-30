/**
 * Thresholds for the live room's pick insight (spec 012).
 *
 * These are HAND-SET, not fitted. Nobody measured a "wide open" share or a
 * "reach" tolerance against real drafts; they are the smallest round numbers
 * that read sensibly. Changing one to make a freshly measured number agree with
 * an earlier guess is forbidden (AGENTS.md: never retune a hand-set constant) --
 * if a value is wrong, say so and change it on its own merits.
 */
export const INSIGHT = Object.freeze({
  /** Top candidate share under this reads "Wide open". */
  WIDE_OPEN_SHARE: 0.25,
  /** A pick the model gave less than this share is a "Surprise". */
  SURPRISE_SHARE: 0.05,
  /** Fewer picks than this gives no on-brand verdict. */
  MIN_PICKS_FOR_VERDICT: 3,
  /** Picks of ADP difference treated as "the same side" for a reach verdict. */
  REACH_TOLERANCE: 3,
  /** Smallest positionalTilt counted as a lean. */
  LEAN_TILT: 1.1,
  /** Show the scarcity line on a card when this few or fewer are left. */
  SCARCE_LEFT: 3,
  /** Auto-dismiss delay for a pick card. */
  CARD_DISMISS_MS: 8000,
  /** Client-side clamp for the pool request; mirrors the endpoint's own cap. */
  POOL_LIMIT_MAX: 400,
})

/**
 * NOT hand-set: mirrors MonteCarloRunner.SNAPSHOT_DEPTH on the backend (how many
 * players per pick the survival snapshot covers). Pinned by
 * MonteCarloRunnerSnapshotDepthTest.java, which fails and names this constant if
 * the backend value moves.
 */
export const SNAPSHOT_DEPTH_MIRROR = 75
