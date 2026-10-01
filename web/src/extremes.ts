export type Extremes = { best: number; worst: number } | null

/**
 * Best and worst VALUE in one column. Null when there is no spread (every row
 * equal, or fewer than two rows with a value): marking a best and a worst among
 * identical numbers would state a difference that is not there. Callers mark
 * every row whose value equals `best` / `worst`, so ties are all marked.
 *
 * The one implementation of this rule (League history and League analysis).
 */
export function extremes(values: (number | null | undefined)[], higherIsBetter: boolean): Extremes {
  const v = values.filter((x): x is number => x != null)
  if (v.length < 2) return null
  const hi = Math.max(...v)
  const lo = Math.min(...v)
  if (hi === lo) return null
  return higherIsBetter ? { best: hi, worst: lo } : { best: lo, worst: hi }
}
