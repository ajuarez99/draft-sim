/**
 * Deterministic colour from an identity.
 *
 * <p>There are two rules here and they are named apart on purpose. Picking the
 * wrong one is how a fourteen-team league ended up with a two-colour legend,
 * and `claude/lessons.md` §16 is the same shape of mistake shipping three times
 * because one rule had two implementations that looked interchangeable.
 */

/**
 * An evenly-spaced hue for one member of a KNOWN set.
 *
 * <p>Use this for anything whose full membership is on hand -- a league's
 * rosters, a draft's seats. Spacing is guaranteed: `360 / count` between
 * neighbours, so fourteen rosters sit 25.7 degrees apart.
 *
 * <p>Why not hash? Because a hash cannot promise separation. The old rule here
 * put sequential ids one degree apart, and even a good avalanche hash (FNV-1a
 * with an xorshift finaliser) still collided at two degrees across fourteen
 * ids -- uniform-random placement collides by the birthday problem. Knowing the
 * set is what makes spacing possible at all.
 *
 * @throws if the set is empty or the index is outside it. Returning 0 there
 *   would paint every line the same colour and still look like a working chart.
 */
export function hueForIndex(index: number, count: number): number {
  if (!Number.isInteger(index) || !Number.isInteger(count)) {
    throw new Error(`hueForIndex needs integers, got index=${index} count=${count}`)
  }
  if (count <= 0) throw new Error('hueForIndex: an empty set has no hues')
  if (index < 0 || index >= count) {
    throw new Error(`hueForIndex: index ${index} is outside a set of ${count}`)
  }
  return Math.round((360 * index) / count)
}

/**
 * A hashed hue for a seed with NO known set -- league names, mostly.
 *
 * <p>Unchanged from the original `hueFor`, renamed so a caller has to choose.
 *
 * <p><b>Known weakness, deliberately kept:</b> `h * 31 + charCode` lets the last
 * character dominate short seeds, so sequential-ish seeds cluster about a degree
 * apart ("1" is 49, "9" is 57). That is survivable for league names, which are
 * not sequential and where a shared hue is cosmetic.
 *
 * <p><b>Not for manager identity.</b> Use {@link hueForIndex} with the league's
 * roster set. This function is why the projected-week chart drew fourteen
 * rosters in two colours.
 */
export function hueForName(seed: string): number {
  let h = 0
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) % 360
  return h
}

/**
 * The character a league crests with.
 *
 * <p>Leading punctuation is common in league names -- "(Foot) Ball Knowers"
 * would crest as "(" -- so this takes the first character that actually
 * carries identity.
 *
 * <p>It lives here, beside {@link hueForName}, because the two always travel
 * together: a crest is this letter in that hue. It is one rule because a
 * league has to crest identically wherever it appears -- the home grid, the
 * picker, the rail, the palette. It was written out by hand in three places
 * before the palette would have made it four, which is the same shape as the
 * Analysis bug this feature exists to fix.
 */
export function crestLetter(name: string): string {
  return (name.match(/[\p{L}\p{N}]/u)?.[0] ?? '?').toUpperCase()
}
