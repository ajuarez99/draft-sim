/**
 * Board-cell name abbreviation.
 *
 * The board runs 14 columns at `minmax(96px, 1fr)`, which at 1440px lands
 * every column exactly on its floor -- measured 2026-09-07 in the running app,
 * 23 of 28 revealed names were ellipsized. Widening the columns is not the
 * fix: a fantasy player's surname is his identity ("Gibbs", "Nacua",
 * "Smith-Njigba") and the first name is what you can afford to lose, so
 * "J. Gibbs" reads faster in the same 96px than a truncated "Jahmyr Gi…".
 *
 * Only DraftBoard uses this. Everywhere with real width -- PlayerCard, the
 * availability sheet, the picker, PickPrompt's buttons -- keeps the full name.
 */

// Surname particles: tokens that belong to the surname rather than being a
// middle name to drop. Without this "Amon-Ra St. Brown" abbreviates to
// "A. Brown", which is a different-sounding player.
const PARTICLES = new Set(['st.', 'st', 'van', 'von', 'de', 'del', 'della', 'der', 'di', 'du', 'la', 'le', 'da', 'dos'])

// Kept, not dropped: "Kenneth Walker III" and "Kenneth Walker" are two people
// in the same player pool often enough that the suffix is load-bearing.
const SUFFIXES = new Set(['jr', 'jr.', 'sr', 'sr.', 'ii', 'iii', 'iv', 'v'])

export type ShortName = {
  /** "J. " -- empty for a team defense. Rendered de-emphasised by the cell. */
  lead: string
  /** "Gibbs", "St. Brown", "Walker III", "Seahawks". Never empty. */
  rest: string
}

import type { Sport } from './api'

type Named = { name: string; position: string }

/**
 * Splits a full name into a de-emphasised leading initial and the part that
 * identifies the player.
 *
 *   Jahmyr Gibbs           -> J. | Gibbs
 *   Amon-Ra St. Brown      -> A. | St. Brown      (particle stays with the surname)
 *   John Michael Gyllenborg-> J. | Gyllenborg     (middle name dropped)
 *   J. Michael Sturdivant  -> J. | Sturdivant     (already an initial, not "J.. ")
 *   Kenneth Walker III     -> K. | Walker III     (suffix kept)
 *   Seattle Seahawks (DEF) ->    | Seahawks       (see below)
 *
 * Team defenses are the one position whose "name" is not a person's name --
 * Sleeper gives them as "Seattle Seahawks", where an initial would produce the
 * nonsense "S. Seahawks". The mascot alone is unique across the league and the
 * cell already carries the team code, so DEF drops the city instead.
 *
 * `sport` guards this: "DEF" is a football-only position code (domain/
 * Position.java), and a raw NBA player position string never legitimately
 * reaches here as "DEF" either -- Position.fromSleeper drops the ~30 nba
 * entries Sleeper itself mistags "DEF" rather than mapping them onto
 * football's DEF (see that method's own comment). But that is an invariant
 * living on the backend, one hop away from this file; checking `sport` here
 * too means a bug in that invariant can't make a basketball name print as
 * though it were a defense. Defaults to 'nfl' so every existing caller (and
 * test) that predates multi-sport keeps behaving exactly as before.
 */
export function shortName({ name, position }: Named, sport: Sport = 'nfl'): ShortName {
  const tokens = name.trim().split(/\s+/).filter(Boolean)
  // `rest` is what the cell renders, so it must never come back empty -- an
  // em dash is what an empty board cell already shows, which is the honest
  // thing to fall back to for a player whose name didn't survive ingest.
  if (tokens.length === 0) return { lead: '', rest: '—' }
  if (tokens.length === 1) return { lead: '', rest: tokens[0] }

  if (sport === 'nfl' && position === 'DEF') return { lead: '', rest: tokens[tokens.length - 1] }

  // Peel a trailing suffix off before deciding what the surname is, so the
  // "III" in "Kenneth Walker III" isn't mistaken for the surname itself.
  const hasSuffix = SUFFIXES.has(tokens[tokens.length - 1].toLowerCase())
  const suffix = hasSuffix ? tokens[tokens.length - 1] : null
  const core = hasSuffix ? tokens.slice(0, -1) : tokens
  if (core.length < 2) return { lead: '', rest: name }

  // Walk backwards from the last core token, absorbing particles.
  let start = core.length - 1
  while (start > 1 && PARTICLES.has(core[start - 1].toLowerCase())) start--

  const given = core[0]
  const surname = core.slice(start).join(' ')
  const lead = given.endsWith('.') ? `${given} ` : `${given.charAt(0)}. `
  return { lead, rest: suffix ? `${surname} ${suffix}` : surname }
}

/** The same abbreviation as one string, for `title` attributes and tests. */
export function shortNameString(p: Named, sport: Sport = 'nfl'): string {
  const { lead, rest } = shortName(p, sport)
  return `${lead}${rest}`
}
