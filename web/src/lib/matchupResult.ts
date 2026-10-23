/**
 * The one word for a matchup from the reader's side. A game that is not final is not a result, so the word
 * is Leading/Trailing/Level until the week is final (spec 045 B1). Shared by the home page and Matchups so
 * the rule lives in one place.
 */
export type MatchupWord = 'Won' | 'Lost' | 'Tied' | 'Leading' | 'Trailing' | 'Level'

export function matchupWord(mine: number, theirs: number, final: boolean): MatchupWord {
  if (final) return mine > theirs ? 'Won' : mine < theirs ? 'Lost' : 'Tied'
  return mine > theirs ? 'Leading' : mine < theirs ? 'Trailing' : 'Level'
}
