/**
 * The player page's number formatting, shared with the comparison page (spec 049 A10) so a figure is printed the
 * same way in both. Moved out of pages/PlayerPage.tsx unchanged: a second copy would let the two pages round
 * differently, and the comparison marks a row by comparing these displayed strings.
 */

export const DASH = '—'

export const MINUS = '−'
/** toFixed with the page's one minus sign (U+2212) instead of a hyphen. */
export const fixed = (n: number, digits: number) => n.toFixed(digits).replace('-', MINUS)
export const num = (n: number | null | undefined, digits = 1) => (n == null ? DASH : fixed(n, digits))
export const minutes = (n: number) => fixed(n, 1)

/** The makes-attempts text printed beneath a shooting percentage ("7-15"). */
export const madeAttempted = (made: number, att: number) => `${Math.round(made)}-${Math.round(att)}`
