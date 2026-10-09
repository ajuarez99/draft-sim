/**
 * Survival bands shared by the availability sheet's verdict and the draft room's Stats filter.
 * Their own module so the pure stats rules can read them without importing a component.
 */

// Verdict thresholds for "survival at your very next pick", named because
// 0.35 and 0.65 mean nothing on their own. Below one-in-three, the player is
// gone more often than not by a wide margin -- if you want him, this is
// probably your last look, hence "act now". At or above two-in-three he
// survives more often than not, comfortably -- "safe" to wait on. The 30-point
// band between is deliberately wide rather than split at 50%: anything in it
// is close enough to call that treating it as a coin flip is more honest than
// implying either side of it means something.
export const RISK_MAX = 0.35
export const SAFE_MIN = 0.65
