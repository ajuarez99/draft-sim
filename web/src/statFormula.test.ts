import { describe, expect, it } from 'vitest'
import {
  SPREAD_RATIO,
  additiveTerms,
  chartAvailability,
  contributions,
  quantiles,
  DIVIDE_BY_ZERO_MESSAGE,
  NO_STAT_MESSAGE,
  STANDARDIZED_DIVIDE_MESSAGE,
  STANDARDIZED_MULTIPLY_MESSAGE,
  canonical,
  evaluate,
  formatResult,
  inferUnits,
  unitLabel,
  inputsOf,
  isPerGameInput,
  parse,
  rankResults,
  readable,
  reasonText,
  spreadNotes,
  standardize,
  tokenOf,
  validate,
  zeroDenominatorWarning,
  type Ast,
  type Reason,
} from './statFormula'
import { FORMULA_INPUTS, NO_FILTERS, applyFilters, type StatMode } from './statLeaderboard'
import { rate, row, win } from './testStatBoard'

/** specs/032 T009-T011: the formula language, the evaluator, units and z-scores. */

function ast(src: string): Ast {
  const r = parse(src)
  if (!r.ok) throw new Error(`${src}: ${r.errors[0].message}`)
  return r.ast
}
const err = (src: string) => {
  const r = parse(src)
  if (r.ok) throw new Error(`${src} parsed`)
  return r.errors[0]
}
const val = (src: string, r = row('a'), mode: StatMode = 'perGame') => evaluate(ast(src), r, mode)
const reasonOf = (o: ReturnType<typeof evaluate>): Reason => {
  if (!('excluded' in o)) throw new Error('expected an exclusion')
  return o.excluded
}

describe('grammar: the C1 error table', () => {
  it.each([
    ['(usg + vor', 'Bracket opened at 1 is never closed', 0, 1],
    ['usg + * tov', 'Two operators in a row at 7', 4, 7],
    ['2usg', 'Put an operator between 2 and USG', 0, 4],
    ['usg + foo', "‘foo’ isn't a stat. Pick one from the list", 6, 9],
    ['', 'Enter a formula', 0, 0],
    ['usg +', 'The formula ends after an operator', 4, 5],
    ['.5', 'Numbers need a leading digit: write 0.5', 0, 2],
    ['1e3', "Write 1000; exponents aren't supported", 0, 3],
    // specs/032 code review B3 and B8
    ['9'.repeat(400), 'That number is too large', 0, 400],
    ['1.5.5', '‘1.5.5’ has two decimal points; a number can have only one', 0, 5],
    ['3PA + usg', "‘3PA’ isn't a stat or a number. Pick a stat from the list", 0, 3],
    ['FP/G + usg', '‘FP/G’ is how the table prints it. In a formula, write FPG', 0, 4],
    ['usg + Pos rank', '‘Pos rank’ is how the table prints it. In a formula, write POSRANK', 6, 14],
    ['Pts rank', '‘Pts rank’ is how the table prints it. In a formula, write PTSRANK', 0, 8],
  ])('%j -> %s', (src, message, start, end) => {
    expect(err(src)).toEqual({ message, start, end })
  })

  it('unary minus is valid', () => {
    for (const s of ['usg + -tov', 'usg * -2', '-(usg)']) expect(parse(s).ok, s).toBe(true)
    expect(canonical(ast('-(usg)'))).toBe('-usg')
    expect(val('usg * -2')).toEqual({ value: -40 })
  })

  it('3PM, 3P% and 3PAr lex as stats, fp/gp as fp / gp, and usgx is unknown', () => {
    expect(inputsOf(ast('3pm + 3p% + 3par'))).toEqual(['tpm', 'tpPctMA', 'tpar'])
    expect(canonical(ast('fp/gp'))).toBe('fp / gp')
    expect(err('usgx').message).toBe("‘usgx’ isn't a stat. Pick one from the list")
  })

  it('accepts − × ÷ and the USG% alias', () => {
    expect(canonical(ast('usg − tov × 2 ÷ 4'))).toBe('usg - tov * 2 / 4')
    expect(canonical(ast('usg%'))).toBe('usg')
  })

  it('has standard precedence and brackets, left-associative', () => {
    expect(val('2 + 3 * 4')).toEqual({ value: 14 })
    expect(val('(2 + 3) * 4')).toEqual({ value: 20 })
    expect(val('10 - 4 - 3')).toEqual({ value: 3 })
    expect(val('12 / 3 / 2')).toEqual({ value: 2 })
  })

  it('has no implicit multiplication, including between brackets', () => {
    expect(err('(usg)(tov)').message).toBe('Put an operator between ) and (')
    expect(err('usg tov').message).toBe('Put an operator between USG and TO')
  })

  it('reports an unmatched closing bracket', () => {
    expect(err('usg)').message).toBe('Closing bracket at 4 has no opening bracket')
  })

  it('round-trips canonical <-> readable, keeping the brackets that matter', () => {
    expect(canonical(ast('(USG+VOR)/TO'))).toBe('(usg + vor) / tov')
    expect(readable(ast('(usg + vor) / tov'))).toBe('(USG + VOR) ÷ TO')
    expect(canonical(ast('(USG + VOR) ÷ TO'))).toBe('(usg + vor) / tov')
    expect(canonical(ast('usg - (tov - ast)'))).toBe('usg - (tov - ast)')
    expect(canonical(ast('(usg * 2) + 1'))).toBe('usg * 2 + 1')
    expect(canonical(ast('-(usg + 1) * 2'))).toBe('-(usg + 1) * 2')
  })

  it('round-trips every formula input: id -> readable -> parse -> id (F1)', () => {
    for (const c of FORMULA_INPUTS) {
      const printed = readable(ast(c.id))
      expect(printed, c.id).toBe(tokenOf(c.id))
      const back = ast(printed)
      expect(back.k === 'stat' && back.id, c.id).toBe(c.id)
      // and inside an expression, where a `/` or a space in a label would have broken it
      expect(canonical(ast(`${printed} ÷ 2`)), c.id).toBe(`${c.id} / 2`)
    }
  })
})

describe('validate with standardize', () => {
  it('blocks dividing by a stat, with the operator and its right side as the span', () => {
    const errs = validate(ast('usg / tov'), { standardize: true })
    expect(errs).toEqual([{ message: STANDARDIZED_DIVIDE_MESSAGE, start: 4, end: 9 }])
    expect(validate(ast('usg / tov'), { standardize: false })).toEqual([])
  })
  it('covers a bracketed right side up to its closing bracket', () => {
    const [e] = validate(ast('pts / (ast + 1)'), { standardize: true })
    expect([e.start, e.end]).toEqual([4, 15])
  })
  it('allows dividing by a constant', () => {
    expect(validate(ast('usg / 2'), { standardize: true })).toEqual([])
    expect(validate(ast('pts / 100 + ts'), { standardize: true })).toEqual([])
  })
  // specs/032 code review B1
  it('blocks multiplying two stats, with the operator and its right side as the span (B1)', () => {
    const errs = validate(ast('pts * ts / 100'), { standardize: true })
    expect(errs).toEqual([{ message: STANDARDIZED_MULTIPLY_MESSAGE, start: 4, end: 8 }])
    expect(STANDARDIZED_MULTIPLY_MESSAGE).toContain('two negatives multiply to a positive')
    expect(validate(ast('pts * ts / 100'), { standardize: false })).toEqual([])
    expect(validate(ast('(usg + ts) * (tov - 1)'), { standardize: true })).toHaveLength(1)
  })
  it('allows a stat times a constant when standardized (B1)', () => {
    for (const s of ['usg * -2', '(usg + ts) * 2', '2 * usg', 'usg * 1.5 + tov']) expect(validate(ast(s), { standardize: true }), s).toEqual([])
  })
  it('ORDERING: a below-average player never outranks the leader under standardize (B1, bug class 1)', () => {
    const star = row('star', { name: 'Star', stats: win({ pg: { pts: 32 }, adv: { ts: rate(62) } }) })
    const bad = row('bad', { name: 'Bad', stats: win({ pg: { pts: 4 }, adv: { ts: rate(45) } }) })
    const avg = [18, 20, 22].map((p, i) => row(`a${i}`, { name: `Avg${i}`, stats: win({ pg: { pts: p }, adv: { ts: rate(54 + i) } }) }))
    const group = [star, bad, ...avg]
    const f = ast('pts * ts / 100')
    // What the page used to do: the multiplied z-scores put Bad (two big negatives) first.
    const z = standardize(group, inputsOf(f), 'perGame')
    expect(rankResults(group, f, 'perGame', 'desc', z).included[0].row.name).toBe('Bad')
    // Now the combination is an error, so that ranking can never be shown ...
    expect(validate(f, { standardize: true })).toHaveLength(1)
    // ... and raw mode, which is allowed, ranks correctly.
    const raw = rankResults(group, f, 'perGame', 'desc').included.map((x) => x.row.name)
    expect(raw[0]).toBe('Star')
    expect(raw[raw.length - 1]).toBe('Bad')
    // The additive form that remains valid under standardize ranks Star first, Bad last.
    const sum = ast('pts + ts')
    expect(validate(sum, { standardize: true })).toEqual([])
    const zs = standardize(group, inputsOf(sum), 'perGame')
    const sumRank = rankResults(group, sum, 'perGame', 'desc', zs).included.map((x) => x.row.name)
    expect(sumRank[0]).toBe('Star')
    expect(sumRank[sumRank.length - 1]).toBe('Bad')
  })
  // specs/032 code review B3
  it('requires at least one stat, in either mode (B3)', () => {
    for (const standardize of [true, false]) {
      expect(validate(ast('1 + 1'), { standardize })).toEqual([{ message: NO_STAT_MESSAGE, start: 0, end: 5 }])
      expect(validate(ast('usg'), { standardize })).toEqual([])
    }
  })
  it('rejects dividing by a constant 0, however it is written (B3)', () => {
    expect(validate(ast('usg / 0'), { standardize: false })).toEqual([{ message: DIVIDE_BY_ZERO_MESSAGE, start: 4, end: 7 }])
    expect(validate(ast('usg / (2 - 2)'), { standardize: false })).toHaveLength(1)
    expect(validate(ast('usg / 2'), { standardize: false })).toEqual([])
    expect(DIVIDE_BY_ZERO_MESSAGE).toBe('Dividing by 0 is undefined')
  })
  it('uses the contract wording', () => {
    expect(STANDARDIZED_DIVIDE_MESSAGE).toBe(
      "With standardize on, dividing by a stat isn't meaningful: about half the players sit below average, so the denominator crosses zero. Turn standardize off, or rewrite with + and −.",
    )
  })
})

describe('evaluate', () => {
  it('ranks a higher ratio first, and asc reverses it (preference ordering, bug class 1)', () => {
    const hi = row('hi', { name: 'High', stats: win({ pg: { ast: 10, tov: 2 } }) })
    const lo = row('lo', { name: 'Low', stats: win({ pg: { ast: 6, tov: 2 } }) })
    const f = ast('ast / tov')
    expect(rankResults([lo, hi], f, 'perGame', 'desc').included.map((x) => x.row.name)).toEqual(['High', 'Low'])
    expect(rankResults([hi, lo], f, 'perGame', 'asc').included.map((x) => x.row.name)).toEqual(['Low', 'High'])
    expect(rankResults([lo, hi], f, 'perGame', 'desc').included.map((x) => x.value)).toEqual([5, 3])
  })

  it('÷ 0 gives DIVIDE_BY_ZERO in words, never Infinity or NaN', () => {
    const r = row('z', { stats: win({ pg: { ast: 4, tov: 0 } }) })
    const o = evaluate(ast('ast / tov'), r, 'perGame')
    expect(o).toEqual({ excluded: { kind: 'DIVIDE_BY_ZERO', text: '0 turnovers, so ÷ TO is undefined' } })
    expect(reasonText(reasonOf(o), r)).toBe('0 turnovers, so ÷ TO is undefined')
    expect(val('0 / 0')).toMatchObject({ excluded: { kind: 'DIVIDE_BY_ZERO' } })
  })

  it('a null input gives INPUT_MISSING with the shared reason', () => {
    const r = row('n', { stats: win({ adv: { usg: rate(null, 'NO_TEAM_ROW') } }) })
    expect(evaluate(ast('usg + 1'), r, 'perGame')).toEqual({ excluded: { kind: 'INPUT_MISSING', id: 'usg', why: 'NO_TEAM_ROW' } })
    const u = row('u', { qualified: false, reason: 'NOT_QUALIFIED_STALE', valueOverReplacement: null, stats: win({ lastGameDate: '2026-01-05' }) })
    const o = evaluate(ast('vor * 2'), u, 'perGame')
    expect(o).toEqual({ excluded: { kind: 'INPUT_MISSING', id: 'vor', why: 'NOT_QUALIFIED_STALE' } })
    expect(reasonText(reasonOf(o), u)).toContain('Jan 5, 2026')
  })

  it('never yields NaN, an infinity or a negative zero', () => {
    const big = '1' + '0'.repeat(300)
    expect(val(`${big} * ${big}`)).toEqual({ excluded: { kind: 'NOT_FINITE' } })
    expect(val('usg / (usg - usg)')).toMatchObject({ excluded: { kind: 'DIVIDE_BY_ZERO' } })
    const z = val('0 * -1') as { value: number }
    expect(Object.is(z.value, -0)).toBe(false)
    for (const src of ['pts / ast', 'usg - ts', '1 / 3', big]) {
      const o = val(src) as { value: number }
      expect(Number.isFinite(o.value), src).toBe(true)
    }
  })

  it('breaks ties on games played, then name, then id', () => {
    const mk = (id: string, name: string | null, games: number) => row(id, { name, stats: win({ games }) })
    const rows = [mk('3', 'Zed', 50), mk('2', 'Amy', 50), mk('1', 'Amy', 50), mk('4', 'Bob', 60), mk('5', null, 50)]
    const out = rankResults(rows, ast('1 + 1'), 'perGame', 'desc').included.map((x) => x.row.sleeperPlayerId)
    expect(out).toEqual(['4', '1', '2', '3', '5'])
    // direction does not touch the tie order
    expect(rankResults(rows, ast('1 + 1'), 'perGame', 'asc').included.map((x) => x.row.sleeperPlayerId)).toEqual(out)
  })

  it('per 36 changes counting inputs but not rate inputs; min reads per game', () => {
    const r = row('p', { stats: win({ pg: { pts: 10 }, minutesPerGame: 30 }) })
    expect(val('pts', r, 'perGame')).toEqual({ value: 10 })
    expect(val('pts', r, 'per36')).toEqual({ value: 12 })
    expect(val('usg', r, 'per36')).toEqual(val('usg', r, 'perGame'))
    expect(val('min', r, 'per36')).toEqual({ value: 30 })
    expect(val('min', r, 'totals')).toEqual({ value: 1800 })
  })

  it('evaluates on unrounded payload values', () => {
    const r = row('d', { stats: win({ pg: { ast: 8.96, tov: 1.04 } }) })
    expect((val('ast / tov', r) as { value: number }).value).toBeCloseTo(8.615384615, 6)
  })
})

describe('standardize', () => {
  const g = [1, 2, 3, 4].map((n) => row(String(n), { stats: win({ pg: { pts: n * 10 } }) }))
  it('gives the population mean, SD and z by hand', () => {
    const z = standardize(g, ['pts'], 'perGame')
    // values 10,20,30,40: mean 25, population variance (225+25+25+225)/4 = 125
    expect(z.pts).toEqual({ n: 4, mean: 25, sd: Math.sqrt(125) })
    expect((evaluate(ast('pts'), g[3], 'perGame', z) as { value: number }).value).toBeCloseTo(15 / Math.sqrt(125), 12)
    expect((evaluate(ast('pts'), g[0], 'perGame', z) as { value: number }).value).toBeCloseTo(-15 / Math.sqrt(125), 12)
    expect((evaluate(ast('pts * 2 + 1'), g[3], 'perGame', z) as { value: number }).value).toBeCloseTo((2 * 15) / Math.sqrt(125) + 1, 12)
  })
  it('a group of 1, or no spread, is NOT_STANDARDIZABLE', () => {
    expect(standardize([g[0]], ['pts'], 'perGame').pts).toEqual({ n: 1, notStandardizable: 'GROUP_TOO_SMALL' })
    const same = [1, 2, 3].map((n) => row(String(n), { stats: win({ pg: { pts: 0.1 } }) }))
    expect(standardize(same, ['pts'], 'perGame').pts).toEqual({ n: 3, notStandardizable: 'NO_SPREAD' })
    const z = standardize([g[0]], ['pts'], 'perGame')
    expect(evaluate(ast('pts'), g[0], 'perGame', z)).toEqual({
      excluded: { kind: 'NOT_STANDARDIZABLE', id: 'pts', why: 'GROUP_TOO_SMALL' },
    })
  })
  it("leaves a null-input row out of that input's group and excludes it as INPUT_MISSING", () => {
    const nul = row('n', { stats: win({ adv: { usg: rate(null, 'NO_TEAM_ROW') } }) })
    const withUsg = [10, 20, 30].map((n) => row(`u${n}`, { stats: win({ adv: { usg: rate(n) } }) }))
    const z = standardize([...withUsg, nul], ['usg'], 'perGame')
    expect(z.usg).toMatchObject({ n: 3, mean: 20 })
    expect(evaluate(ast('usg'), nul, 'perGame', z)).toMatchObject({ excluded: { kind: 'INPUT_MISSING', id: 'usg' } })
  })
  it('follows the filters and the qualification switch', () => {
    const rows = [
      row('1', { team: 'DEN', stats: win({ pg: { pts: 10 } }) }),
      row('2', { team: 'DEN', stats: win({ pg: { pts: 30 } }) }),
      row('3', { team: 'LAL', stats: win({ pg: { pts: 50 } }) }),
      row('4', { team: 'DEN', qualified: false, reason: 'NOT_QUALIFIED', stats: win({ pg: { pts: 90 } }) }),
    ]
    const group = (qualifiedOnly: boolean, team: string | null) =>
      applyFilters(rows, { ...NO_FILTERS, team }, { qualifiedOnly, sortCol: 'fp' })
    expect(standardize(group(true, null), ['pts'], 'perGame').pts).toMatchObject({ n: 3, mean: 30 })
    expect(standardize(group(false, null), ['pts'], 'perGame').pts).toMatchObject({ n: 4, mean: 45 })
    expect(standardize(group(true, 'DEN'), ['pts'], 'perGame').pts).toMatchObject({ n: 2, mean: 20 })
  })
})

describe('unit inference (R8, F11)', () => {
  it('usg + vor warns, naming both sides and units', () => {
    const w = inferUnits(ast('usg + vor'), 'perGame')
    expect(w).toHaveLength(1)
    expect(w[0]).toContain('USG (percentage points)')
    expect(w[0]).toContain('VOR (fantasy points per game)')
  })
  it("the user's example (usg + vor) / tov warns once", () => {
    expect(inferUnits(ast('(usg + vor) / tov'), 'perGame')).toHaveLength(1)
  })
  it.each(['2 * usg + usg', 'ast/tov + stl/tov', 'pts * ts / 100', 'usg + 5', 'fp / vor', 'pts + stl'])('%s does not warn', (s) => {
    expect(inferUnits(ast(s), 'perGame')).toEqual([])
  })
  it('min is per game outside totals mode, a total inside it', () => {
    expect(inferUnits(ast('min + pts'), 'perGame')).toHaveLength(1)
    // B6: total minutes are not points, so the mix warns in totals mode too.
    expect(inferUnits(ast('min + pts'), 'totals')).toHaveLength(1)
    expect(inferUnits(ast('min + pts'), 'totals')[0]).toContain('total minutes')
    expect(inferUnits(ast('min + min'), 'totals')).toEqual([])
    expect(isPerGameInput('min', 'per36')).toBe(true)
    expect(isPerGameInput('min', 'totals')).toBe(false)
    expect(isPerGameInput('vor', 'totals')).toBe(true)
    expect(isPerGameInput('pts', 'perGame')).toBe(false)
  })
  it('pts + stl gets a spread note when the SDs differ by more than 3x, and not otherwise', () => {
    const wide = [1, 2, 3, 4].map((n) => row(String(n), { stats: win({ pg: { pts: n * 10, stl: n * 0.5 } }) }))
    const notes = spreadNotes(ast('pts + stl'), wide, 'perGame')
    expect(notes).toHaveLength(1)
    expect(notes[0]).toContain('PTS will dominate')
    expect(SPREAD_RATIO).toBe(3)
    const even = [1, 2, 3, 4].map((n) => row(String(n), { stats: win({ pg: { pts: n * 2, stl: n * 1.5 } }) }))
    expect(spreadNotes(ast('pts + stl'), even, 'perGame')).toEqual([])
    // a mixed-unit pair is the unit warning's job, not the spread note's
    expect(spreadNotes(ast('usg + vor'), wide, 'perGame')).toEqual([])
  })
  it('fp / vor warns about a non-positive denominator when a fixture VOR is <= 0', () => {
    const g = [5, 0, -3].map((v, i) => row(String(i), { valueOverReplacement: v }))
    expect(zeroDenominatorWarning(ast('fp / vor'), g, 'perGame')).toContain('VOR is zero or negative for 2 of 3 players')
    // B9: a whole sentence, not "Dividing by VOR is zero or negative for ..."
    expect(zeroDenominatorWarning(ast('fp / vor'), g, 'perGame')).toMatch(/^VOR is zero or negative for 2 of 3 players shown, and the formula divides by it: /)
    expect(zeroDenominatorWarning(ast('fp / vor'), [row('x', { valueOverReplacement: 4 })], 'perGame')).toBeNull()
    expect(zeroDenominatorWarning(ast('fp / 2'), g, 'perGame')).toBeNull()
  })
})

describe('rankResults groups the excluded rows by cause', () => {
  it('lists each reason once with its rows', () => {
    const rows = [
      row('1', { stats: win({ pg: { tov: 0 } }) }),
      row('2', { stats: win({ pg: { tov: 0 } }) }),
      row('3', { stats: win({ pg: { tov: 2 } }) }),
    ]
    const out = rankResults(rows, ast('ast / tov'), 'perGame', 'desc')
    expect(out.included).toHaveLength(1)
    expect(out.excluded).toHaveLength(1)
    expect(out.excluded[0].rows.map((r) => r.sleeperPlayerId)).toEqual(['1', '2'])
  })
})

describe('formatResult (N9)', () => {
  it.each([
    [4.5, '4.50'], [0.123456, '0.123'], [-0.5, '−0.500'], [1234.56, '1,235'], [-2500, '−2,500'], [0, '0.00'], [12, '12.00'],
    // specs/032 code review B4: round first, then choose the style; never exponent notation
    [999.995, '1,000'], [999.996, '1,000'], [999.9999, '1,000'], [999.994, '999.99'], [1000, '1,000'], [-999.996, '−1,000'],
    [1e-7, '< 0.000001'], [-1e-7, '> −0.000001'], [5e-324, '< 0.000001'], [0.000001, '0.00000100'], [0.9996, '1.00'],
  ])('%d -> %s', (v, s) => expect(formatResult(v)).toBe(s))
})

describe('formatResult never prints an exponent (B4)', () => {
  it.each([1e-7, 5e-324, 1.5e-9, 123456789012, 1e21, 0.00001234, 999.995])('%d', (v) => {
    expect(formatResult(v)).not.toMatch(/e[+-]?\d/i)
    expect(formatResult(-v)).not.toMatch(/e[+-]?\d/i)
  })
})

describe('unitLabel (US4 axis units)', () => {
  it('names a single stat’s unit, in the current mode', () => {
    expect(unitLabel(ast('usg'), 'perGame')).toBe('percentage points')
    expect(unitLabel(ast('pts'), 'totals')).toBe('season totals')
    expect(unitLabel(ast('pts'), 'per36')).toBe('counts per 36 minutes')
  })
  it('combines units through × and ÷, and calls a same-unit ratio a plain ratio', () => {
    expect(unitLabel(ast('vor / tov'), 'perGame')).toBe('fantasy points per game ÷ counts per game')
    expect(unitLabel(ast('ast / tov'), 'perGame')).toBe('a plain ratio')
  })
  it('has none for a bare constant', () => {
    expect(unitLabel(ast('3'), 'perGame')).toBeNull()
  })
})

// --- US5 (specs/032 T029) ----------------------------------------------------------------------

const astOf = (f: string): Ast => {
  const r = parse(f)
  if (!r.ok) throw new Error(`bad test formula ${f}`)
  return r.ast
}
const us5Rows = () => [
  row('1', { name: 'A', valueOverReplacement: 10, stats: win({ pg: { tov: 2, pts: 10, ast: 10 }, adv: { usg: rate(20) } }) }),
  row('2', { name: 'B', valueOverReplacement: 6, stats: win({ pg: { tov: 3, pts: 20, ast: 6 }, adv: { usg: rate(30) } }) }),
  row('3', { name: 'C', valueOverReplacement: 4, stats: win({ pg: { tov: 1, pts: 30, ast: 3 }, adv: { usg: rate(10) } }) }),
  row('4', { name: 'D', valueOverReplacement: -2, stats: win({ pg: { tov: 4, pts: 5, ast: 1 }, adv: { usg: rate(25) } }) }),
]

describe('quantiles (linear interpolation, numpy default)', () => {
  it('odd n: [1,2,3,4,5] -> 1, 2, 3, 4, 5', () => {
    expect(quantiles([5, 1, 3, 2, 4])).toEqual({ n: 5, min: 1, q1: 2, median: 3, q3: 4, max: 5 })
  })
  it('even n: [1,2,3,4] -> q1 1.75, median 2.5, q3 3.25', () => {
    const q = quantiles([4, 2, 1, 3])
    expect(q.q1).toBeCloseTo(1.75, 12)
    expect(q.median).toBeCloseTo(2.5, 12)
    expect(q.q3).toBeCloseTo(3.25, 12)
  })
  it('interpolates between ranks: [10,20,30,40,50,60] -> q1 22.5, median 35, q3 47.5', () => {
    const q = quantiles([10, 20, 30, 40, 50, 60])
    expect([q.q1, q.median, q.q3]).toEqual([22.5, 35, 47.5])
  })
  it('one value and no values', () => {
    expect(quantiles([7])).toEqual({ n: 1, min: 7, q1: 7, median: 7, q3: 7, max: 7 })
    expect(quantiles([]).n).toBe(0)
  })
})

describe('additiveTerms', () => {
  it('usg + vor -> two positive terms', () => {
    const r = additiveTerms(astOf('usg + vor'))
    expect(r).toEqual({ terms: [
      { sign: 1, coef: 1, id: 'usg', text: 'USG' },
      { sign: 1, coef: 1, id: 'vor', text: 'VOR' },
    ] })
  })
  it('2 * ast - tov, tov * 3, pts / 4, unary minus and a constant', () => {
    const r = additiveTerms(astOf('2 * ast - tov + pts / 4 - -3 * stl + 5'))
    if (!('terms' in r)) throw new Error(r.reason)
    expect(r.terms.map((t) => [t.sign, t.coef, t.id])).toEqual([[1, 2, 'ast'], [-1, 1, 'tov'], [1, 0.25, 'pts'], [1, 3, 'stl'], [1, 5, '']])
    expect(r.terms[0].text).toBe('2 × AST')
    expect(r.terms[2].text).toBe('PTS ÷ 4')
    expect(r.terms[4].text).toBe('constant 5')
  })
  it('a minus in front of a bracketed sum flips every term', () => {
    const r = additiveTerms(astOf('-(ast + tov)'))
    if (!('terms' in r)) throw new Error(r.reason)
    expect(r.terms.map((t) => t.sign)).toEqual([-1, -1])
  })
  it('stat × stat and stat ÷ stat are refused, and the reason names the actual cause (C6)', () => {
    expect(additiveTerms(astOf('ast * tov'))).toEqual({ reason: expect.stringContaining('multiplies one stat by another') })
    expect(additiveTerms(astOf('ast / tov'))).toEqual({ reason: expect.stringContaining('divides by a stat') })
    expect(additiveTerms(astOf('(usg + vor) / tov'))).toEqual({ reason: expect.stringContaining('divides by a stat') })
    expect(additiveTerms(astOf('(usg + vor) * tov'))).toEqual({ reason: expect.stringContaining('multiplies one stat by another') })
    expect(additiveTerms(astOf('10 / ast'))).toEqual({ reason: expect.stringContaining('divides by a stat') })
  })
  it('C6: a constant over a bracketed sum is distributed: k*(a+b), (a+b)/k, -(-(a+b)), nested', () => {
    const texts = (f: string) => {
      const r = additiveTerms(astOf(f))
      if (!('terms' in r)) throw new Error(`${f}: ${r.reason}`)
      return r.terms.map((t) => `${t.sign > 0 ? '+' : '−'}${t.text}`)
    }
    expect(texts('(usg + vor) / 2')).toEqual(['+USG ÷ 2', '+VOR ÷ 2'])
    expect(texts('2 * (usg + vor)')).toEqual(['+2 × USG', '+2 × VOR'])
    expect(texts('(usg - vor) * 3')).toEqual(['+USG × 3', '−VOR × 3'])
    expect(texts('-(-(usg + vor))')).toEqual(['+USG', '+VOR'])
    expect(texts('ast - (2 * tov - (stl + blk) / 1)')).toEqual(['+AST', '−2 × TO', '+STL ÷ 1', '+BLK ÷ 1'])
    expect(texts('-2 * (usg - vor)')).toEqual(['−2 × USG', '+2 × VOR'])
  })
  it('C6: the distributed parts still sum to evaluate() within 1e-9, raw and standardized', () => {
    for (const f of ['(usg + vor) / 2', '2 * (usg + vor)', '-(-(usg + vor))', '3 * (usg - 2 * vor) + 1', '(ast - (tov + 4)) / -2', '0.5 * (pts + (ast - 1) * 2)']) {
      for (const std of [false, true]) {
        const ast = astOf(f)
        const t = additiveTerms(ast)
        if (!('terms' in t)) throw new Error(`${f}: ${t.reason}`)
        const rs = us5Rows()
        const z = std ? standardize(rs, inputsOf(ast), 'perGame') : undefined
        for (const r of rs) {
          const c = contributions(t.terms, r, 'perGame', z)
          const o = evaluate(ast, r, 'perGame', z)
          if (!('parts' in c) || !('value' in o)) continue
          expect(Math.abs(c.parts.reduce((a, p) => a + p.value, 0) - o.value)).toBeLessThan(1e-9)
        }
      }
    }
  })
  it('C8: the term text is the coefficient as typed, never a rounded decimal', () => {
    const r = additiveTerms(astOf('pts / 3 - 0.5 * stl'))
    if (!('terms' in r)) throw new Error(r.reason)
    expect(r.terms.map((t) => t.text)).toEqual(['PTS ÷ 3', '0.5 × STL'])
    const q = additiveTerms(astOf('ast / 4'))
    if (!('terms' in q)) throw new Error(q.reason)
    expect(q.terms[0].text).toBe('AST ÷ 4')
  })
})

describe('contributions: the parts sum to evaluate() within 1e-9', () => {
  const formulas = ['usg + vor', '2 * ast - tov', 'pts / 4 - 3 * ast + 7', '-(ast + tov) + usg', 'usg - usg + ast']
  for (const f of formulas) {
    for (const std of [false, true]) {
      it(`${f}, ${std ? 'standardized' : 'raw'}`, () => {
        const ast = astOf(f)
        const t = additiveTerms(ast)
        if (!('terms' in t)) throw new Error(t.reason)
        const rs = us5Rows()
        const z = std ? standardize(rs, inputsOf(ast), 'perGame') : undefined
        for (const r of rs) {
          const c = contributions(t.terms, r, 'perGame', z)
          const o = evaluate(ast, r, 'perGame', z)
          if (!('parts' in c) || !('value' in o)) throw new Error('expected values')
          expect(Math.abs(c.parts.reduce((a, p) => a + p.value, 0) - o.value)).toBeLessThan(1e-9)
          expect(Math.abs(c.total - o.value)).toBeLessThan(1e-9)
        }
      })
    }
  }
  it('a missing input excludes the player, as evaluate does', () => {
    const t = additiveTerms(astOf('usg + vor'))
    if (!('terms' in t)) throw new Error(t.reason)
    const noVor = row('9', { valueOverReplacement: null, stats: win({ adv: { usg: rate(20) } }) })
    expect(contributions(t.terms, noVor, 'perGame')).toMatchObject({ excluded: { kind: 'INPUT_MISSING', id: 'vor' } })
  })
})

describe('chartAvailability', () => {
  const ast = astOf('usg + vor')
  const ctx = { n: 10, position: null, standardize: false, mode: 'perGame' as StatMode, hasPrevSeason: true, ast }
  it('is ok when everything fits', () => {
    for (const k of ['spread', 'split', 'change', 'changePrev', 'parts'] as const) expect(chartAvailability(k, ctx)).toEqual({ ok: true })
  })
  it('Spread needs 5 values', () => {
    expect(chartAvailability('spread', { ...ctx, n: 4 })).toEqual({ ok: false, reason: 'Spread needs at least 5 players with a value; 4 have one.' })
    expect(chartAvailability('spread', { ...ctx, n: 5 })).toEqual({ ok: true })
  })
  it('the split needs the position filter All', () => {
    expect(chartAvailability('split', { ...ctx, position: 'C' })).toMatchObject({ ok: false, reason: expect.stringContaining('position filter set to All') })
  })
  it('Change needs standardize off, and the previous season needs an earlier season', () => {
    expect(chartAvailability('change', { ...ctx, standardize: true })).toMatchObject({ ok: false, reason: expect.stringContaining('standardized value') })
    expect(chartAvailability('changePrev', { ...ctx, hasPrevSeason: false })).toMatchObject({ ok: false, reason: expect.stringContaining('no earlier stored season') })
  })
  it('C2: Change is disabled in Totals mode and when the formula reads GP, each with its own reason; Per 36 is fine', () => {
    expect(chartAvailability('change', { ...ctx, mode: 'totals' })).toMatchObject({ ok: false, reason: expect.stringContaining('totals over different numbers of games can’t be compared') })
    expect(chartAvailability('change', { ...ctx, mode: 'per36' })).toEqual({ ok: true })
    for (const mode of ['perGame', 'per36'] as const) {
      expect(chartAvailability('change', { ...ctx, mode, ast: astOf('pts + gp') })).toMatchObject({ ok: false, reason: expect.stringContaining('games played (GP)') })
    }
    expect(chartAvailability('change', { ...ctx, mode: 'totals', ast: astOf('pts + gp') })).toMatchObject({ ok: false })
  })
  it('Breakdown needs a sum of terms', () => {
    expect(chartAvailability('parts', { ...ctx, ast: astOf('usg * vor') })).toEqual({ ok: false, reason: expect.stringContaining('multiplies one stat by another') })
  })
  it('with no formula, everything says so', () => {
    expect(chartAvailability('parts', { ...ctx, ast: null })).toEqual({ ok: false, reason: 'Enter a formula first.' })
  })
})
