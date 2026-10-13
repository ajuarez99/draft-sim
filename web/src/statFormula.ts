import type { LeaderboardRow } from './api'
import { missingReason } from './statCells'
import { reasonSentence, type MissingReason } from './statCopy'
import {
  COLUMNS,
  FORMULA_EXTRA_ALIASES,
  FORMULA_INPUTS,
  FORMULA_TOKEN,
  FORMULA_UNIT,
  tieBreak,
  type SortDir,
  type StatMode,
} from './statLeaderboard'

/**
 * specs/032-stat-calculator: the formula language (contract C1), its evaluator, unit inference,
 * standardization and ranking. Plain functions, no React, so every rule is unit-tested.
 *
 * Nothing here rounds before it evaluates: a formula runs on the payload's full values
 * (COLUMNS[id].value), and only `formatResult` shortens a number for display (research R4).
 */

// --- syntax tree -------------------------------------------------------------------------------

type Span = { start: number; end: number }
export type Ast =
  | ({ k: 'num'; v: number; text: string } & Span)
  | ({ k: 'stat'; id: string } & Span)
  | ({ k: 'neg'; e: Ast } & Span)
  /** `opStart` is where the operator character sits, so a `÷` error can cover the operator and its right side. */
  | ({ k: 'bin'; op: '+' | '-' | '*' | '/'; l: Ast; r: Ast; opStart: number } & Span)

export type ParseError = { message: string; start: number; end: number }
export type ParseResult = { ok: true; ast: Ast } | { ok: false; errors: ParseError[] }

// --- lexer -------------------------------------------------------------------------------------

type Tok =
  | ({ t: 'num'; v: number; text: string } & Span)
  | ({ t: 'stat'; id: string } & Span)
  | ({ t: 'op'; op: '+' | '-' | '*' | '/' } & Span)
  | ({ t: 'lp' } & Span)
  | ({ t: 'rp' } & Span)

/** Lower-cased spelling -> column id: ids first, then the printed tokens, then the extra aliases. */
const SPELLINGS: Map<string, string> = (() => {
  const m = new Map<string, string>()
  for (const c of FORMULA_INPUTS) m.set(c.id.toLowerCase(), c.id)
  for (const [id, tok] of Object.entries(FORMULA_TOKEN)) if (!m.has(tok.toLowerCase())) m.set(tok.toLowerCase(), id)
  for (const [alias, id] of Object.entries(FORMULA_EXTRA_ALIASES)) if (!m.has(alias.toLowerCase())) m.set(alias.toLowerCase(), id)
  return m
})()
const SPELLING_LIST = [...SPELLINGS.keys()].sort((a, b) => b.length - a.length)
const WORD_CHAR = /[A-Za-z0-9%]/
const DIGIT = /[0-9]/

class Fail extends Error {
  constructor(readonly err: ParseError) {
    super(err.message)
  }
}
const fail = (message: string, start: number, end: number): never => {
  throw new Fail({ message, start, end })
}

/** The token a stat is printed as: `USG`, `TO`, `FPG`. */
export const tokenOf = (id: string): string => FORMULA_TOKEN[id] ?? id

function lex(src: string): Tok[] {
  const out: Tok[] = []
  let i = 0
  while (i < src.length) {
    const ch = src[i]
    if (/\s/.test(ch)) {
      i++
      continue
    }
    // Longest STAT match first, only at a token boundary (F1): `3PM` is a stat, `usgx` is not `usg` + `x`.
    const low = src.slice(i).toLowerCase()
    // specs/032 code review B8: the table prints FP/G, Pos rank and Pts rank; the formula spells them FPG,
    // POSRANK and PTSRANK. Say so when someone types the printed label back (checked first: `fp` alone is a stat).
    const printed = FORMULA_INPUTS.find((c) => {
      const lab = c.label.toLowerCase()
      return tokenOf(c.id).toLowerCase() !== lab && low.startsWith(lab) && !(i + lab.length < src.length && WORD_CHAR.test(src[i + lab.length]))
    })
    if (printed) fail(`‘${src.slice(i, i + printed.label.length)}’ is how the table prints it. In a formula, write ${tokenOf(printed.id)}`, i, i + printed.label.length)
    const hit = SPELLING_LIST.find((s) => low.startsWith(s) && !(i + s.length < src.length && WORD_CHAR.test(src[i + s.length])))
    if (hit) {
      out.push({ t: 'stat', id: SPELLINGS.get(hit) as string, start: i, end: i + hit.length })
      i += hit.length
      continue
    }
    if (DIGIT.test(ch)) {
      let j = i
      while (j < src.length && DIGIT.test(src[j])) j++
      if (src[j] === '.' && DIGIT.test(src[j + 1] ?? '')) {
        j++
        while (j < src.length && DIGIT.test(src[j])) j++
        // specs/032 code review B8: `1.5.5` is a second decimal point, not a missing leading digit.
        if (src[j] === '.' && DIGIT.test(src[j + 1] ?? '')) {
          let k = j + 1
          while (k < src.length && DIGIT.test(src[k])) k++
          fail(`‘${src.slice(i, k)}’ has two decimal points; a number can have only one`, i, k)
        }
      }
      if (/[eE]/.test(src[j] ?? '') && (DIGIT.test(src[j + 1] ?? '') || (/[+-]/.test(src[j + 1] ?? '') && DIGIT.test(src[j + 2] ?? '')))) {
        let k = j + 1
        if (/[+-]/.test(src[k])) k++
        while (k < src.length && DIGIT.test(src[k])) k++
        const n = Number(src.slice(i, k))
        fail(`Write ${Number.isFinite(n) ? String(n) : 'the number out in full'}; exponents aren't supported`, i, k)
      }
      // specs/032 code review B8: a number run into letters that are not a stat (`3PA`) is reported whole,
      // not as the half (`PA`) the lexer would otherwise trip on. `2usg` still reads as a missing operator.
      if (j < src.length && WORD_CHAR.test(src[j])) {
        const rest = src.slice(j).toLowerCase()
        const startsStat = SPELLING_LIST.some((sp) => rest.startsWith(sp) && !(j + sp.length < src.length && WORD_CHAR.test(src[j + sp.length])))
        if (!startsStat) {
          let k = j
          while (k < src.length && (WORD_CHAR.test(src[k]) || src[k] === '_')) k++
          fail(`‘${src.slice(i, k)}’ isn't a stat or a number. Pick a stat from the list`, i, k)
        }
      }
      const text = src.slice(i, j)
      const v = Number(text)
      // specs/032 code review B3: a literal too large for a number would put Infinity on the page.
      if (!Number.isFinite(v)) fail('That number is too large', i, j)
      out.push({ t: 'num', v, text, start: i, end: j })
      i = j
      continue
    }
    if (ch === '.' && DIGIT.test(src[i + 1] ?? '')) {
      let j = i + 1
      while (j < src.length && DIGIT.test(src[j])) j++
      fail(`Numbers need a leading digit: write 0${src.slice(i, j)}`, i, j)
    }
    if (ch === '+') out.push({ t: 'op', op: '+', start: i, end: i + 1 })
    else if (ch === '-' || ch === '−') out.push({ t: 'op', op: '-', start: i, end: i + 1 })
    else if (ch === '*' || ch === '×') out.push({ t: 'op', op: '*', start: i, end: i + 1 })
    else if (ch === '/' || ch === '÷') out.push({ t: 'op', op: '/', start: i, end: i + 1 })
    else if (ch === '(') out.push({ t: 'lp', start: i, end: i + 1 })
    else if (ch === ')') out.push({ t: 'rp', start: i, end: i + 1 })
    else if (WORD_CHAR.test(ch) || ch === '_') {
      let j = i
      while (j < src.length && (WORD_CHAR.test(src[j]) || src[j] === '_')) j++
      fail(`‘${src.slice(i, j)}’ isn't a stat. Pick one from the list`, i, j)
    } else fail(`Unexpected character ‘${ch}’ at ${i + 1}`, i, i + 1)
    i++
  }
  return out
}

// --- parser ------------------------------------------------------------------------------------

const OP_SYMBOL = { '+': '+', '-': '−', '*': '×', '/': '÷' } as const
const tokText = (t: Tok): string =>
  t.t === 'num' ? t.text : t.t === 'stat' ? tokenOf(t.id) : t.t === 'op' ? OP_SYMBOL[t.op] : t.t === 'lp' ? '(' : ')'

function parseTokens(toks: Tok[], srcLen: number): Ast {
  let p = 0
  const peek = (): Tok | undefined => toks[p]
  const prev = (): Tok | undefined => toks[p - 1]

  const expr = (): Ast => {
    let l = term()
    for (let t = peek(); t && t.t === 'op' && (t.op === '+' || t.op === '-'); t = peek()) {
      p++
      const r = term()
      l = { k: 'bin', op: t.op, l, r, opStart: t.start, start: l.start, end: r.end }
    }
    return l
  }
  const term = (): Ast => {
    let l = unary()
    for (let t = peek(); t && t.t === 'op' && (t.op === '*' || t.op === '/'); t = peek()) {
      p++
      const r = unary()
      l = { k: 'bin', op: t.op, l, r, opStart: t.start, start: l.start, end: r.end }
    }
    return l
  }
  const unary = (): Ast => {
    const t = peek()
    if (t && t.t === 'op' && t.op === '-') {
      p++
      const e = unary()
      return { k: 'neg', e, start: t.start, end: e.end }
    }
    return primary()
  }
  const primary = (): Ast => {
    const t = peek()
    if (!t) {
      const last = prev()
      if (last && last.t === 'op') return fail('The formula ends after an operator', last.start, last.end)
      if (last && last.t === 'lp') return fail(`Nothing inside the bracket opened at ${last.start + 1}`, last.start, last.end)
      return fail('Enter a formula', 0, srcLen)
    }
    let node: Ast
    if (t.t === 'num') {
      p++
      node = { k: 'num', v: t.v, text: t.text, start: t.start, end: t.end }
    } else if (t.t === 'stat') {
      p++
      node = { k: 'stat', id: t.id, start: t.start, end: t.end }
    } else if (t.t === 'lp') {
      p++
      const inner = expr()
      const close = peek()
      if (!close || close.t !== 'rp') {
        if (close) juxtapose(close)
        return fail(`Bracket opened at ${t.start + 1} is never closed`, t.start, t.end)
      }
      p++
      node = { ...inner, start: t.start, end: close.end }
    } else if (t.t === 'rp') {
      const last = prev()
      return fail(
        last && last.t === 'op' ? `A value is missing before the closing bracket at ${t.start + 1}` : `Closing bracket at ${t.start + 1} has no opening bracket`,
        t.start,
        t.end,
      )
    } else {
      const last = prev()
      if (last && last.t === 'op') return fail(`Two operators in a row at ${t.start + 1}`, last.start, t.end)
      return fail(`‘${OP_SYMBOL[t.op]}’ at ${t.start + 1} needs a value before it`, t.start, t.end)
    }
    // No implicit multiplication: a value straight after a value is an error (`2usg`, `(a)(b)`).
    const nxt = peek()
    if (nxt && (nxt.t === 'num' || nxt.t === 'stat' || nxt.t === 'lp')) juxtapose(nxt)
    return node
  }
  const juxtapose = (next: Tok): never => {
    const before = toks[toks.indexOf(next) - 1]
    return fail(`Put an operator between ${tokText(before)} and ${tokText(next)}`, before.start, next.end)
  }

  const ast = expr()
  const rest = peek()
  if (rest) {
    if (rest.t === 'rp') fail(`Closing bracket at ${rest.start + 1} has no opening bracket`, rest.start, rest.end)
    juxtapose(rest)
  }
  return ast
}

/** Parse a formula typed or read from a link (contract C1). Errors carry a message in words and a 0-based span. */
export function parse(src: string): ParseResult {
  try {
    const toks = lex(src)
    if (toks.length === 0) return { ok: false, errors: [{ message: 'Enter a formula', start: 0, end: src.length }] }
    return { ok: true, ast: parseTokens(toks, src.length) }
  } catch (e) {
    if (e instanceof Fail) return { ok: false, errors: [e.err] }
    throw e
  }
}

export const STANDARDIZED_DIVIDE_MESSAGE =
  "With standardize on, dividing by a stat isn't meaningful: about half the players sit below average, so the denominator crosses zero. Turn standardize off, or rewrite with + and −."

/**
 * specs/032 code review B1 (amended after code review): with standardize on, a `×` whose two sides both
 * contain a stat multiplies two z-scores, and two below-average values multiply to a large positive result.
 */
export const STANDARDIZED_MULTIPLY_MESSAGE =
  "With standardize on, multiplying two stats isn't meaningful: a player who is below average on both gets a large positive result, because two negatives multiply to a positive. Turn standardize off, multiply by a number instead, or rewrite with + and −."

/** specs/032 code review B3: a formula with no stat in it has the same value for every player. */
export const NO_STAT_MESSAGE = 'Use at least one stat. A formula made only of numbers gives every player the same result'
export const DIVIDE_BY_ZERO_MESSAGE = 'Dividing by 0 is undefined'

/** The value of a subtree that holds no stat, or null if it holds one or is not finite. */
function constantOf(n: Ast): number | null {
  switch (n.k) {
    case 'num': return n.v
    case 'stat': return null
    case 'neg': {
      const e = constantOf(n.e)
      return e == null ? null : -e
    }
    case 'bin': {
      const l = constantOf(n.l)
      const r = constantOf(n.r)
      if (l == null || r == null) return null
      const v = n.op === '+' ? l + r : n.op === '-' ? l - r : n.op === '*' ? l * r : l / r
      return Number.isFinite(v) ? v : null
    }
  }
}

/**
 * Checks that depend on settings rather than syntax (contract C1's error table). Both modes: a formula
 * with no stat, and a `÷` by a constant that is 0 (B3). With standardize on: a `÷` whose right side
 * contains a stat (F2), and a `×` with a stat on both sides (B1); a constant on one side is fine.
 */
export function validate(ast: Ast, opts: { standardize: boolean }): ParseError[] {
  const errors: ParseError[] = []
  if (inputsOf(ast).length === 0) errors.push({ message: NO_STAT_MESSAGE, start: ast.start, end: ast.end })
  const walk = (n: Ast) => {
    if (n.k === 'neg') walk(n.e)
    else if (n.k === 'bin') {
      if (n.op === '/' && inputsOf(n.r).length === 0 && constantOf(n.r) === 0) {
        errors.push({ message: DIVIDE_BY_ZERO_MESSAGE, start: n.opStart, end: n.r.end })
      }
      if (opts.standardize) {
        if (n.op === '/' && inputsOf(n.r).length > 0) errors.push({ message: STANDARDIZED_DIVIDE_MESSAGE, start: n.opStart, end: n.r.end })
        if (n.op === '*' && inputsOf(n.l).length > 0 && inputsOf(n.r).length > 0) {
          errors.push({ message: STANDARDIZED_MULTIPLY_MESSAGE, start: n.opStart, end: n.r.end })
        }
      }
      walk(n.l)
      walk(n.r)
    }
  }
  walk(ast)
  return errors
}

// --- printing ----------------------------------------------------------------------------------

const PREC = { '+': 1, '-': 1, '*': 2, '/': 2 } as const
const NEG_PREC = 3

function prec(n: Ast): number {
  return n.k === 'bin' ? PREC[n.op] : n.k === 'neg' ? NEG_PREC : 4
}

type Piece = { text: string; id?: string }

/**
 * Print the tree with minimal brackets. Left-associative, so a right child of equal precedence keeps
 * its brackets (`a - (b - c)`), which also keeps print -> parse a tree-for-tree identity.
 */
function print(n: Ast, stat: (id: string) => Piece, sym: Record<'+' | '-' | '*' | '/', string>, neg: string): Piece[] {
  const wrap = (child: Ast, needs: boolean): Piece[] =>
    needs ? [{ text: '(' }, ...print(child, stat, sym, neg), { text: ')' }] : print(child, stat, sym, neg)
  switch (n.k) {
    case 'num': return [{ text: n.text.replace(/^0+(?=\d)/, '') }]
    case 'stat': return [stat(n.id)]
    case 'neg': return [{ text: neg }, ...wrap(n.e, prec(n.e) < NEG_PREC || n.e.k === 'neg')]
    case 'bin': {
      const p = PREC[n.op]
      return [...wrap(n.l, prec(n.l) < p), { text: ` ${sym[n.op]} ` }, ...wrap(n.r, prec(n.r) <= p)]
    }
  }
}
const join = (ps: Piece[]) => ps.map((p) => p.text).join('')

/** The stored form: column ids, single spaces around binary operators, minimal brackets (C1). */
export function canonical(ast: Ast): string {
  return join(print(ast, (id) => ({ text: id, id }), { '+': '+', '-': '-', '*': '*', '/': '/' }, '-'))
}

/** The display form, printed from the tree: `(USG + VOR) ÷ TO`. Never string substitution (F1). */
export function readable(ast: Ast): string {
  return join(readablePieces(ast))
}

/** The same as `readable`, but with each stat kept apart so the page can add its tag as a styled suffix (FR-013). */
export function readablePieces(ast: Ast): { text: string; id?: string }[] {
  return print(ast, (id) => ({ text: tokenOf(id), id }), { '+': '+', '-': '−', '*': '×', '/': '÷' }, '−')
}

/** The column ids a formula reads, in order of first appearance. */
export function inputsOf(ast: Ast): string[] {
  const seen: string[] = []
  const walk = (n: Ast) => {
    if (n.k === 'stat') {
      if (!seen.includes(n.id)) seen.push(n.id)
    } else if (n.k === 'neg') walk(n.e)
    else if (n.k === 'bin') {
      walk(n.l)
      walk(n.r)
    }
  }
  walk(ast)
  return seen
}

// --- evaluation --------------------------------------------------------------------------------

export type Reason =
  | { kind: 'INPUT_MISSING'; id: string; why: MissingReason }
  /** `text` is the whole sentence, e.g. "0 turnovers, so ÷ TO is undefined". */
  | { kind: 'DIVIDE_BY_ZERO'; text: string }
  | { kind: 'NOT_STANDARDIZABLE'; id: string; why: 'GROUP_TOO_SMALL' | 'NO_SPREAD' }
  | { kind: 'NOT_FINITE' }
export type Outcome = { value: number } | { excluded: Reason }

export type StdInfo =
  | { n: number; mean: number; sd: number }
  | { n: number; notStandardizable: 'GROUP_TOO_SMALL' | 'NO_SPREAD' }
export type ZMap = Record<string, StdInfo>

/** Plural nouns for the "0 turnovers" wording; anything else reads "TO is 0". */
const NOUN: Record<string, string> = {
  tov: 'turnovers', ast: 'assists', stl: 'steals', blk: 'blocks', pts: 'points', reb: 'rebounds', tpm: 'threes made', min: 'minutes', gp: 'games',
}

class Excluded extends Error {
  constructor(readonly reason: Reason) {
    super(reason.kind)
  }
}

function ev(n: Ast, row: LeaderboardRow, mode: StatMode, z?: ZMap): number {
  const finite = (v: number): number => {
    if (!Number.isFinite(v)) throw new Excluded({ kind: 'NOT_FINITE' })
    return v === 0 ? 0 : v
  }
  switch (n.k) {
    case 'num': return n.v
    case 'neg': return finite(-ev(n.e, row, mode, z))
    case 'stat': {
      const col = COLUMNS[n.id]
      const v = col.value(row, mode)
      if (typeof v !== 'number') throw new Excluded({ kind: 'INPUT_MISSING', id: n.id, why: missingReason(col, row, mode) })
      const info = z?.[n.id]
      if (!info) return v
      if ('notStandardizable' in info) throw new Excluded({ kind: 'NOT_STANDARDIZABLE', id: n.id, why: info.notStandardizable })
      return finite((v - info.mean) / info.sd)
    }
    case 'bin': {
      const l = ev(n.l, row, mode, z)
      const r = ev(n.r, row, mode, z)
      if (n.op === '/' && r === 0) {
        const text =
          n.r.k === 'stat'
            ? `${NOUN[n.r.id] ? `0 ${NOUN[n.r.id]}` : `${tokenOf(n.r.id)} is 0`}, so ÷ ${tokenOf(n.r.id)} is undefined`
            : `${readable(n.r)} is 0, so ÷ ${n.r.k === 'num' ? readable(n.r) : `(${readable(n.r)})`} is undefined`
        throw new Excluded({ kind: 'DIVIDE_BY_ZERO', text })
      }
      return finite(n.op === '+' ? l + r : n.op === '-' ? l - r : n.op === '*' ? l * r : l / r)
    }
  }
}

/**
 * One player's result: a number, or the reason there isn't one. Short-circuits on the first null
 * input or `÷ 0`; never returns NaN or an infinity (FR-007). `z`, when given, replaces each input
 * `x` by `(x - mean) / sd` (FR-006).
 */
export function evaluate(ast: Ast, row: LeaderboardRow, mode: StatMode, z?: ZMap): Outcome {
  try {
    return { value: ev(ast, row, mode, z) }
  } catch (e) {
    if (e instanceof Excluded) return { excluded: e.reason }
    throw e
  }
}

/** A reason in words, for the "Couldn't compute" list. */
export function reasonText(r: Reason, row?: LeaderboardRow): string {
  switch (r.kind) {
    case 'INPUT_MISSING': return `${tokenOf(r.id)}: ${reasonSentence(r.why, { date: row?.stats.lastGameDate })}`
    case 'DIVIDE_BY_ZERO': return r.text.charAt(0).toUpperCase() + r.text.slice(1)
    case 'NOT_STANDARDIZABLE':
      return r.why === 'GROUP_TOO_SMALL'
        ? `${tokenOf(r.id)} can’t be standardized: fewer than 2 players shown have a value for it.`
        : `${tokenOf(r.id)} can’t be standardized: every player shown has the same value, so there is no spread.`
    case 'NOT_FINITE': return 'The result is too large to be shown as a number.'
  }
}

/** Rows with the same cause are listed together; the key is what makes two reasons "the same". */
export function reasonKey(r: Reason): string {
  switch (r.kind) {
    case 'INPUT_MISSING': return `INPUT_MISSING:${r.id}:${r.why}`
    case 'DIVIDE_BY_ZERO': return `DIVIDE_BY_ZERO:${r.text}`
    case 'NOT_STANDARDIZABLE': return `NOT_STANDARDIZABLE:${r.id}:${r.why}`
    case 'NOT_FINITE': return 'NOT_FINITE'
  }
}

// --- standardization ---------------------------------------------------------------------------

/**
 * Mean and population SD of each input over `group` (the players shown), counting only rows with a
 * value for that input (R7). A group under 2, or with no spread, cannot be standardized.
 */
export function standardize(group: LeaderboardRow[], inputs: string[], mode: StatMode): ZMap {
  const out: ZMap = {}
  for (const id of inputs) {
    const vals: number[] = []
    for (const r of group) {
      const v = COLUMNS[id].value(r, mode)
      if (typeof v === 'number') vals.push(v)
    }
    const n = vals.length
    if (n < 2) {
      out[id] = { n, notStandardizable: 'GROUP_TOO_SMALL' }
      continue
    }
    const mean = vals.reduce((a, b) => a + b, 0) / n
    const sd = Math.sqrt(vals.reduce((a, b) => a + (b - mean) ** 2, 0) / n)
    // Identical values can still leave a rounding-sized sd; treat "all equal" as no spread outright.
    out[id] = Math.min(...vals) === Math.max(...vals) || sd === 0 ? { n, notStandardizable: 'NO_SPREAD' } : { n, mean, sd }
  }
  return out
}

// --- ranking -----------------------------------------------------------------------------------

export type Included = { row: LeaderboardRow; value: number }
export type ExcludedGroup = { key: string; reason: Reason; rows: LeaderboardRow[] }
export type Ranked = { included: Included[]; excluded: ExcludedGroup[] }

/**
 * Included rows ordered by value in `dir`, ties broken by the leaderboard's own tail (games, name,
 * id) so two loads give one chart. Excluded rows are grouped by cause, biggest group first.
 */
export function rankResults(rows: LeaderboardRow[], ast: Ast, mode: StatMode, dir: SortDir, z?: ZMap): Ranked {
  const included: Included[] = []
  const groups = new Map<string, ExcludedGroup>()
  for (const row of rows) {
    const o = evaluate(ast, row, mode, z)
    if ('value' in o) included.push({ row, value: o.value })
    else {
      const key = reasonKey(o.excluded)
      const g = groups.get(key)
      if (g) g.rows.push(row)
      else groups.set(key, { key, reason: o.excluded, rows: [row] })
    }
  }
  const sign = dir === 'asc' ? 1 : -1
  included.sort((a, b) => (a.value !== b.value ? sign * (a.value - b.value) : tieBreak(a.row, b.row)))
  const excluded = [...groups.values()]
  for (const g of excluded) g.rows.sort(tieBreak)
  excluded.sort((a, b) => b.rows.length - a.rows.length || a.key.localeCompare(b.key))
  return { included, excluded }
}

// --- units and warnings ------------------------------------------------------------------------

/** Dimension: unit name -> exponent. `null` is a bare constant, which takes the unit of whatever it meets. */
type Dim = Record<string, number> | null

const UNIT_WORDS: Record<string, string> = {
  pct: 'percentage points',
  'count:perGame': 'counts per game',
  'count:totals': 'season totals',
  'count:per36': 'counts per 36 minutes',
  fp: 'fantasy points per game',
  rank: 'ranks',
  games: 'games',
  'minutes:perGame': 'minutes per game',
  'minutes:totals': 'total minutes',
  gmsc: 'game score per game',
}

/** A stat's unit in `mode`. `FORMULA_UNIT` stores the literal `count:<mode>`; MIN is a total in totals mode, with a unit of its own (B6). */
function unitOf(id: string, mode: StatMode): string {
  const u = FORMULA_UNIT[id] ?? `id:${id}`
  // specs/032 code review B6: total minutes are not points or rebounds, so `pts + min` warns in totals mode too.
  if (id === 'min' && mode === 'totals') return 'minutes:totals'
  return u.replace('<mode>', mode)
}
const unitWord = (u: string): string => UNIT_WORDS[u] ?? (u.startsWith('id:') ? (COLUMNS[u.slice(3)]?.label ?? u.slice(3)) : u)

function clean(d: Record<string, number>): Record<string, number> {
  for (const k of Object.keys(d)) if (d[k] === 0) delete d[k]
  return d
}
const sameDim = (a: Record<string, number>, b: Record<string, number>) => {
  const ka = Object.keys(a)
  return ka.length === Object.keys(b).length && ka.every((k) => a[k] === b[k])
}
function describeDim(d: Record<string, number>): string {
  const top = Object.entries(d).filter(([, e]) => e > 0).map(([u, e]) => (e === 1 ? unitWord(u) : `${unitWord(u)} squared`))
  const bottom = Object.entries(d).filter(([, e]) => e < 0).map(([u, e]) => (e === -1 ? unitWord(u) : `${unitWord(u)} squared`))
  if (top.length === 0 && bottom.length === 0) return 'a plain ratio'
  return `${top.join(' × ') || '1'}${bottom.length ? ` ÷ ${bottom.join(' ÷ ')}` : ''}`
}

/**
 * Mixed-unit warnings (R8, F11): `+`/`−` between different units. Constants are dimensionless and
 * take the other side's unit; `X ÷ X` is a plain ratio. Each warning names both sides and their units.
 */
export function inferUnits(ast: Ast, mode: StatMode): string[] {
  return analyzeUnits(ast, mode).warnings
}

/**
 * The unit of a formula's result in words ("percentage points", "fantasy points per game ÷ counts
 * per game"), for a chart axis (US4); null for a bare constant, which has none.
 */
export function unitLabel(ast: Ast, mode: StatMode): string | null {
  const { dim } = analyzeUnits(ast, mode)
  return dim ? describeDim(dim) : null
}

function analyzeUnits(ast: Ast, mode: StatMode): { warnings: string[]; dim: Dim } {
  const warnings: string[] = []
  const walk = (n: Ast): Dim => {
    switch (n.k) {
      case 'num': return null
      case 'stat': return { [unitOf(n.id, mode)]: 1 }
      case 'neg': return walk(n.e)
      case 'bin': {
        const l = walk(n.l)
        const r = walk(n.r)
        if (n.op === '+' || n.op === '-') {
          if (l && r && !sameDim(l, r)) {
            warnings.push(
              `${readable(n.l)} (${describeDim(l)}) ${OP_SYMBOL[n.op]} ${readable(n.r)} (${describeDim(r)}) mixes units, so the input with the widest spread will drive the ranking, not a balanced blend. Turn on “standardize inputs” to put each on one scale.`,
            )
          }
          return l ?? r
        }
        if (!l || !r) return l ?? r
        const out: Record<string, number> = { ...l }
        for (const [u, e] of Object.entries(r)) out[u] = (out[u] ?? 0) + (n.op === '*' ? e : -e)
        return clean(out)
      }
    }
  }
  const dim = walk(ast)
  return { warnings, dim }
}

/** Added inputs that share a unit are not balanced if one spreads 3 times as wide as the other. Hand-set, not tuned to any measurement. */
export const SPREAD_RATIO = 3

const sdOf = (xs: number[]): number => {
  const m = xs.reduce((a, b) => a + b, 0) / xs.length
  return Math.sqrt(xs.reduce((a, b) => a + (b - m) ** 2, 0) / xs.length)
}

function valuesOver(n: Ast, group: LeaderboardRow[], mode: StatMode): number[] {
  const out: number[] = []
  for (const r of group) {
    try {
      out.push(ev(n, r, mode))
    } catch (e) {
      if (!(e instanceof Excluded)) throw e
    }
  }
  return out
}

/**
 * Raw mode: two added/subtracted sides with the same unit whose standard deviations over the shown
 * group differ by more than SPREAD_RATIO get a note that the wider one dominates (F11). Sides that
 * already trigger the unit warning are skipped; so are constants.
 */
export function spreadNotes(ast: Ast, group: LeaderboardRow[], mode: StatMode): string[] {
  const notes: string[] = []
  const walk = (n: Ast) => {
    if (n.k === 'neg') walk(n.e)
    else if (n.k === 'bin') {
      walk(n.l)
      walk(n.r)
      if ((n.op === '+' || n.op === '-') && inputsOf(n.l).length > 0 && inputsOf(n.r).length > 0 && inferUnits(n, mode).length === 0) {
        const a = valuesOver(n.l, group, mode)
        const b = valuesOver(n.r, group, mode)
        if (a.length >= 2 && b.length >= 2) {
          const sa = sdOf(a)
          const sb = sdOf(b)
          const hi = Math.max(sa, sb)
          const lo = Math.min(sa, sb)
          if (lo > 0 && hi / lo > SPREAD_RATIO) {
            const wide = sa >= sb ? n.l : n.r
            notes.push(
              `${readable(n.l)} and ${readable(n.r)} share a unit but spread very differently (standard deviation ${formatResult(sa)} vs ${formatResult(sb)}), so ${readable(wide)} will dominate the ranking. “Standardize inputs” puts them on one scale.`,
            )
          }
        }
      }
    }
  }
  walk(ast)
  return notes
}

/** Raw mode only: a stat in a denominator that is zero or negative for some shown players (F2). */
export function zeroDenominatorWarning(ast: Ast, group: LeaderboardRow[], mode: StatMode): string | null {
  const parts: string[] = []
  const walk = (n: Ast) => {
    if (n.k === 'neg') walk(n.e)
    else if (n.k === 'bin') {
      walk(n.l)
      walk(n.r)
      if (n.op === '/' && inputsOf(n.r).length > 0) {
        const vals = valuesOver(n.r, group, mode)
        const bad = vals.filter((v) => v <= 0).length
        if (bad > 0) {
          parts.push(
            `${readable(n.r)} is zero or negative for ${bad} of ${vals.length} players shown, and the formula divides by it: zero leaves a player out, and a negative value flips the sign of his result.`,
          )
        }
      }
    }
  }
  walk(ast)
  // specs/032 code review B9: each part is a whole sentence now, so they join with a space.
  return parts.length ? parts.join(' ') : null
}

// --- display -----------------------------------------------------------------------------------

/**
 * A result for display (N9): at least 1000 (after rounding) as a whole number with separators, 2 decimals
 * from 1 up, 3 significant figures below 1, never exponent notation (B4). A minus sign is U+2212, as on the leaderboard.
 */
export function formatResult(v: number): string {
  // specs/032 code review B3: nothing non-finite is ever printed.
  if (!Number.isFinite(v)) return '—'
  const a = Math.abs(v)
  let s: string
  if (a === 0) s = '0.00'
  else if (a < 0.000001) {
    // specs/032 code review B4: toPrecision would print exponent notation here, which the editor rejects.
    return v < 0 ? '> −0.000001' : '< 0.000001'
  } else if (a < 1) s = a.toPrecision(3)
  else {
    // Round first, then pick the style, so 999.996 reads 1,000 and not "1000.00" (B4).
    const fixed = a.toFixed(2)
    s = Number(fixed) >= 1000 ? Math.round(a).toLocaleString('en-US') : fixed
  }
  return v < 0 && s !== '0.00' ? `−${s}` : s
}

/** Inputs that stay per game in every mode (F11). MIN is a total in totals mode, so it is not marked there. */
export function isPerGameInput(id: string, mode: StatMode): boolean {
  return ['min', 'fp', 'vor', 'gameScore'].includes(id) && !(id === 'min' && mode === 'totals')
}

// --- US5 chart helpers (specs/032 US5, T029) -----------------------------------------------------

/**
 * Five-number summary of `values`. Quartiles use linear interpolation between the two nearest ranks
 * (numpy's default, method "linear": position = (n - 1) * p), so a script over the payload agrees.
 * An empty input gives n 0 and NaN fields; callers gate on n before they print.
 */
export type Quantiles = { n: number; min: number; q1: number; median: number; q3: number; max: number }
export function quantiles(values: number[]): Quantiles {
  const v = values.filter(Number.isFinite).sort((a, b) => a - b)
  const n = v.length
  if (n === 0) return { n: 0, min: NaN, q1: NaN, median: NaN, q3: NaN, max: NaN }
  const at = (p: number): number => {
    const pos = (n - 1) * p
    const lo = Math.floor(pos)
    const hi = Math.ceil(pos)
    return v[lo] + (v[hi] - v[lo]) * (pos - lo)
  }
  return { n, min: v[0], q1: at(0.25), median: at(0.5), q3: at(0.75), max: v[n - 1] }
}

/** One addend of a sum-of-terms formula. `id` is a stat id, or '' for the constant part. The part's value is `sign * coef * input`. */
export type AdditiveTerm = { sign: 1 | -1; coef: number; id: string; text: string }
export type AdditiveResult = { terms: AdditiveTerm[] } | { reason: string }

const NOT_ADDITIVE_PREFIX = 'Breakdown needs a sum of terms; this formula '
/** The generic refusal; the real refusals name their cause (amended after US5 review, C6). */
export const NOT_ADDITIVE_MESSAGE = `${NOT_ADDITIVE_PREFIX}multiplies or divides stats.`
const CAUSE_MULTIPLIES = `${NOT_ADDITIVE_PREFIX}multiplies one stat by another.`
const CAUSE_DIVIDES = `${NOT_ADDITIVE_PREFIX}divides by a stat.`
const CAUSE_ZERO = `${NOT_ADDITIVE_PREFIX}divides by zero.`

/** One product term: a constant times at most one stat (`k × stat`, `stat × k`, `stat ÷ k`), or null if it is anything else. */
function termOf(n: Ast): { coef: number; id: string } | null {
  const k = constantOf(n)
  if (k != null) return { coef: k, id: '' }
  switch (n.k) {
    case 'num': return { coef: n.v, id: '' }
    case 'stat': return { coef: 1, id: n.id }
    case 'neg': {
      const t = termOf(n.e)
      return t && { coef: -t.coef, id: t.id }
    }
    case 'bin': {
      if (n.op === '+' || n.op === '-') return null
      const l = termOf(n.l)
      const r = termOf(n.r)
      if (!l || !r) return null
      if (n.op === '*') return l.id && r.id ? null : { coef: l.coef * r.coef, id: l.id || r.id }
      // ÷: only by a constant
      if (r.id || r.coef === 0) return null
      return { coef: l.coef / r.coef, id: l.id }
    }
  }
}

const fmtNum = (v: number): string => String(Number(Math.abs(v).toPrecision(12)))

/**
 * A product term's text as the member wrote it, without its sign (which the caller shows as + or −):
 * `pts / 3` is "PTS ÷ 3", `0.5 * stl` is "0.5 × STL". Numbers keep their typed text (amended after US5 review, C8).
 */
function unsignedText(n: Ast): { neg: boolean; text: string } {
  switch (n.k) {
    case 'num': return { neg: false, text: n.text.replace(/^0+(?=\d)/, '') }
    case 'stat': return { neg: false, text: tokenOf(n.id) }
    case 'neg': {
      const r = unsignedText(n.e)
      return { neg: !r.neg, text: r.text }
    }
    case 'bin': {
      if (n.op === '*' || n.op === '/') {
        const l = unsignedText(n.l)
        const r = unsignedText(n.r)
        return { neg: l.neg !== r.neg, text: `${l.text} ${n.op === '*' ? '×' : '÷'} ${r.text}` }
      }
      const k = constantOf(n) ?? 0
      return { neg: k < 0, text: fmtNum(k) }
    }
  }
}

/**
 * Splits a formula into the terms it adds: top-level `+`, `−` and unary minus are flattened; each
 * term must be a constant times one stat. A constant factor outside a bracketed sum is distributed
 * over it (`2 * (a + b)`, `(a + b) / 2`, `-(a + b)`), which is exact algebra: the parts still add to
 * the result. A bare constant becomes the part "constant". Terms are not merged, so `usg + usg` is
 * two parts. A refusal names its actual cause.
 */
export function additiveTerms(ast: Ast): AdditiveResult {
  const out: AdditiveTerm[] = []
  let cause: string | null = null
  const fail = (c: string) => {
    cause ??= c
  }
  /** `wraps`: the constant factors distributed over the term from outside, innermost last. */
  type Wrap = { op: '*' | '/'; k: number; text: string; left: boolean }
  const walk = (n: Ast, sign: 1 | -1, wraps: Wrap[]) => {
    if (cause) return
    if (n.k === 'bin' && (n.op === '+' || n.op === '-')) {
      walk(n.l, sign, wraps)
      walk(n.r, n.op === '-' ? (sign === 1 ? -1 : 1) : sign, wraps)
      return
    }
    const t = termOf(n)
    if (t) {
      let coef = t.coef
      let text = unsignedText(n).text
      for (let i = wraps.length - 1; i >= 0; i--) {
        const w = wraps[i]
        coef = w.op === '*' ? coef * w.k : coef / w.k
        text = w.op === '/' ? `${text} ÷ ${w.text}` : w.left ? `${w.text} × ${text}` : `${text} × ${w.text}`
      }
      const s: 1 | -1 = coef < 0 ? (sign === 1 ? -1 : 1) : sign
      const mag = Math.abs(coef)
      out.push({ sign: s, coef: mag, id: t.id, text: t.id ? text : `constant ${text}` })
      return
    }
    if (n.k === 'neg') return walk(n.e, sign === 1 ? -1 : 1, wraps)
    if (n.k === 'bin' && n.op === '*') {
      const kl = constantOf(n.l)
      const kr = constantOf(n.r)
      if (kl != null) return walk(n.r, sign, [...wraps, { op: '*', k: kl, text: unsignedText(n.l).text, left: true }])
      if (kr != null) return walk(n.l, sign, [...wraps, { op: '*', k: kr, text: unsignedText(n.r).text, left: false }])
      return fail(CAUSE_MULTIPLIES)
    }
    if (n.k === 'bin' && n.op === '/') {
      const kr = constantOf(n.r)
      if (kr === 0) return fail(CAUSE_ZERO)
      if (kr != null) return walk(n.l, sign, [...wraps, { op: '/', k: kr, text: unsignedText(n.r).text, left: false }])
      return fail(CAUSE_DIVIDES)
    }
    fail(NOT_ADDITIVE_MESSAGE)
  }
  walk(ast, 1, [])
  return cause || out.length === 0 ? { reason: cause ?? NOT_ADDITIVE_MESSAGE } : { terms: out }
}

export type Part = { id: string; text: string; value: number }
export type Contribution = { parts: Part[]; total: number } | { excluded: Reason }

/**
 * Each term's share of one player's result, in the order of `terms`. With `z` the stat in a term is its
 * z-score (a constant stays itself), exactly as `evaluate` does, so the parts sum to `evaluate(...)`'s
 * value (tested to 1e-9). A missing input or a stat that can't be standardized excludes the player, as there.
 */
export function contributions(terms: AdditiveTerm[], row: LeaderboardRow, mode: StatMode, z?: ZMap): Contribution {
  const parts: Part[] = []
  let total = 0
  for (const t of terms) {
    let x = 1
    if (t.id) {
      const col = COLUMNS[t.id]
      const v = col.value(row, mode)
      if (typeof v !== 'number') return { excluded: { kind: 'INPUT_MISSING', id: t.id, why: missingReason(col, row, mode) } }
      const info = z?.[t.id]
      if (info && 'notStandardizable' in info) return { excluded: { kind: 'NOT_STANDARDIZABLE', id: t.id, why: info.notStandardizable } }
      x = info ? (v - info.mean) / info.sd : v
    }
    const value = t.sign * t.coef * x
    if (!Number.isFinite(value)) return { excluded: { kind: 'NOT_FINITE' } }
    parts.push({ id: t.id, text: t.text, value })
    total += value
  }
  return Number.isFinite(total) ? { parts, total } : { excluded: { kind: 'NOT_FINITE' } }
}

export type ChartAvail = { ok: true } | { ok: false; reason: string }
export type AvailabilityKind = 'spread' | 'split' | 'change' | 'changePrev' | 'parts'
export type AvailabilityCtx = {
  /** Players with a computed value in the main view. */
  n: number
  /** The position filter ('' or null = All). */
  position: string | null
  standardize: boolean
  /** The counting-stats mode; Totals can't be compared across views that cover different numbers of games. */
  mode: StatMode
  /** Whether `d.seasons` holds an earlier stored season. */
  hasPrevSeason: boolean
  /** The formula, or null when there is none to chart. */
  ast: Ast | null
}

export const SPREAD_MIN = 5
const NEED_FORMULA = 'Enter a formula first.'

/** Whether a US5 chart fits, with the reason in words when it does not (FR-019). */
export function chartAvailability(kind: AvailabilityKind, c: AvailabilityCtx): ChartAvail {
  const no = (reason: string): ChartAvail => ({ ok: false, reason })
  if (!c.ast) return no(NEED_FORMULA)
  switch (kind) {
    case 'spread':
      return c.n >= SPREAD_MIN ? { ok: true } : no(`Spread needs at least ${SPREAD_MIN} players with a value; ${c.n === 0 ? 'none have' : c.n === 1 ? '1 has' : `${c.n} have`} one.`)
    case 'split':
      return c.position ? no(`Split by position needs the position filter set to All; it is ${c.position}.`) : { ok: true }
    case 'change':
      if (c.standardize) {
        return no('Change compares two windows, and a standardized value is measured against its own window’s players, so the two don’t share a scale. Turn off “Standardize inputs” to use it.')
      }
      // amended after US5 review, C2
      if (c.mode === 'totals') {
        return no('Change compares two views, and totals over different numbers of games can’t be compared. Switch Counting stats to Per game or Per 36 to use it.')
      }
      if (inputsOf(c.ast).includes('gp')) {
        return no('Change compares two views, and games played (GP) counts a different number of games in each, so it can’t be compared. Take GP out of the formula to use it.')
      }
      return { ok: true }
    case 'changePrev':
      return c.hasPrevSeason ? { ok: true } : no('This league has no earlier stored season to compare with.')
    case 'parts': {
      const t = additiveTerms(c.ast)
      return 'reason' in t ? no(t.reason) : { ok: true }
    }
  }
}
