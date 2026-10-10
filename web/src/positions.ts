/**
 * One per-sport position list, replacing the three duplicated `POSITIONS`
 * constants that used to live in AvailabilityPanel.tsx, PlayerPicker.tsx and
 * OnTheClockPickInput.tsx (plus pickRun.ts's own `RUNNABLE`) -- all four independently
 * hardcoded football's six positions. Mirrors backend/.../domain/Position.java's
 * enum exactly (forSport order): football QB,RB,WR,TE,K,DEF; basketball
 * PG,SG,SF,PF,C. See claude/multi-sport-and-rebrand.md Phase 6.
 *
 * Every position-scoped list in the frontend must ask for ONE sport's list,
 * never the union of both -- a football board showing a `C` filter chip, or a
 * basketball one showing `DEF`, would be a bug.
 */

import type { CSSProperties } from 'react'
import type { PlayerRef, Sport } from './api'

export const POSITIONS_BY_SPORT: Record<Sport, readonly string[]> = {
  nfl: ['QB', 'RB', 'WR', 'TE', 'K', 'DEF'],
  nba: ['PG', 'SG', 'SF', 'PF', 'C'],
}

/** The shape every position-filter chip row wants: the sport's positions, `ALL` leading. */
export function filterPositions(sport: Sport): readonly string[] {
  return ['ALL', ...POSITIONS_BY_SPORT[sport]]
}

// ---- Multi-position eligibility (spec 025) ----------------------------------

type PosLike = Pick<PlayerRef, 'position' | 'positions'>

/**
 * THE one fallback rule: `p.positions` when present and non-empty, else
 * `[p.position]`. Football: always `[p.position]`. Exception (review note #13): the backend's no-position
 * fallback (Player.primary()) reports WR; for an NBA player that is "no
 * position", so when `sport` is 'nba' and position is 'WR' this returns [].
 * Pass `sport` wherever it is known.
 */
export function eligiblePositions(p: PosLike, sport: Sport): string[] {
  // Football is single-position by rule: should Sleeper ever list an NFL player at two
  // positions, football screens keep treating him as his primary one (spec 025 R2).
  if (sport === 'nfl') return [p.position]
  if (p.positions && p.positions.length > 0) return [...p.positions]
  if (sport === 'nba' && p.position === 'WR') return []
  return [p.position]
}

const NBA_ORDER = ['PG', 'SG', 'SF', 'PF', 'C']

function nbaSorted(ps: string[]): string[] {
  const rank = (x: string) => {
    const i = NBA_ORDER.indexOf(x)
    return i < 0 ? NBA_ORDER.length : i
  }
  return [...ps].sort((a, b) => rank(a) - rank(b))
}

/** "PG/SF/PF" style label. NBA: fixed PG,SG,SF,PF,C order; NFL: stored order. */
export function positionLabel(p: PosLike, sport: Sport): string {
  const ps = eligiblePositions(p, sport)
  return (sport === 'nba' ? nbaSorted(ps) : ps).join('/')
}

export function isMultiPosition(p: PosLike, sport: Sport): boolean {
  return eligiblePositions(p, sport).length > 1
}

/**
 * Pill / cell styling for a player (A7). Single position: the existing
 * `pos QB` class and no inline style -- unchanged. Multi-position: `pos multi`
 * (or `multi` for a cell tint) plus --pos-a/--pos-b/--pos-c set to each
 * position's colour variable, in label order. Never the first position alone.
 */
export function multiVars(p: PosLike, sport: Sport): CSSProperties | undefined {
  const ps = eligiblePositions(p, sport)
  if (ps.length < 2) return undefined
  const ordered = sport === 'nba' ? nbaSorted(ps) : ps
  const style: Record<string, string> = {}
  ;['--pos-a', '--pos-b', '--pos-c', '--pos-d'].forEach((k, i) => {
    if (ordered[i]) style[k] = `var(--${ordered[i].toLowerCase()})`
  })
  // Hard-stop positions for the pill gradient: halves for two, thirds for three,
  // quarters for four.
  const n = Math.min(ordered.length, 4)
  style['--pos-s1'] = ['', '', '50%', '34%', '25%'][n]
  style['--pos-s2'] = ['', '', '50%', '67%', '50%'][n]
  style['--pos-s3'] = ['', '', '50%', '100%', '75%'][n]
  // The board cell's smooth tint: b/c default to 50%/100% in the stylesheet (two and
  // three positions); four positions spread evenly.
  if (n === 4) {
    style['--pos-t1'] = '33%'
    style['--pos-t2'] = '67%'
  }
  return style as CSSProperties
}

export function posPill(p: PosLike, sport: Sport): { className: string; style: CSSProperties | undefined } {
  const style = multiVars(p, sport)
  // An NBA player with no position has nothing to colour by: a bare `pos` pill, not the
  // football `pos WR` the backend's fallback position would give him (review N3).
  if (!style && eligiblePositions(p, sport).length === 0) return { className: 'pos', style }
  return { className: style ? 'pos multi' : `pos ${p.position}`, style }
}

/**
 * The `--lead-hue` inline style for the pick feed / pick card accent (A7).
 * Single position: that position's colour. Multi-position: no property at all,
 * so the stylesheet's neutral fallback applies -- never the first position's colour.
 */
export function leadHueStyle(p: PosLike, sport: Sport): CSSProperties | undefined {
  if (isMultiPosition(p, sport) || eligiblePositions(p, sport).length === 0) return undefined
  return { '--lead-hue': `var(--${p.position.toLowerCase()})` } as CSSProperties
}

const FAMILY: Record<string, string> = { PG: 'G', SG: 'G', SF: 'F', PF: 'F', C: 'C' }

/**
 * Compact-cell code (A9). Single position -> itself; otherwise the distinct
 * families (G={PG,SG}, F={SF,PF}, C={C}) joined in G,F,C order, e.g.
 * PG/SG -> "G", C/PF -> "F/C", PF/SF/SG -> "G/F".
 */
export function familyCode(p: PosLike, sport: Sport): string {
  const ps = eligiblePositions(p, sport)
  if (ps.length <= 1) return ps[0] ?? ''
  const fams = new Set(ps.map((x) => FAMILY[x] ?? x))
  return ['G', 'F', 'C'].filter((f) => fams.has(f)).concat([...fams].filter((f) => !'GFC'.includes(f))).join('/')
}
