import { describe, expect, it } from 'vitest'
import { ordinal } from './format'

/**
 * specs/021-codebase-cleanup T018. Four `ordinal` copies are merged into format.ts.
 * Two algorithms were in use, and they must agree everywhere before either is
 * deleted. Both are pasted here verbatim as the oracle, so they outlive the code
 * they came from.
 *
 * The fifth copy, PowerRankings.tsx's `n === 1 ? 'st' : …`, is deliberately NOT
 * in this agreement set: it disagrees ("21th"), and replacing it is a labelled fix
 * in its own commit (T026, the spec FR-001 exception).
 */

// draftGrades.ts:87 and rankOrder.ts:133 (the same switch, formatted differently).
function switchOrdinal(n: number): string {
  const m100 = n % 100
  if (m100 >= 11 && m100 <= 13) return `${n}th`
  switch (n % 10) {
    case 1: return `${n}st`
    case 2: return `${n}nd`
    case 3: return `${n}rd`
    default: return `${n}th`
  }
}

// ExpectedWins.tsx:274 and Superlatives.tsx:656 (the same table, verbatim).
function tableOrdinal(n: number): string {
  const s = ['th', 'st', 'nd', 'rd']
  const v = n % 100
  return n + (s[(v - 20) % 10] ?? s[v] ?? s[0])
}

const INPUTS: number[] = [
  ...Array.from({ length: 1001 }, (_, i) => i),
  -1, -2, -3, -4, -5, -11, -21, 1.5, 2.5, 11.5, 0.1,
  Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY, 1e21,
]

describe('ordinal (format.ts)', () => {
  it('the two old algorithms agree on every input, including the odd ones', () => {
    const differ = INPUTS.filter((n) => switchOrdinal(n) !== tableOrdinal(n))
    expect(differ).toEqual([])
  })

  it('format.ts matches the old switch algorithm on every input', () => {
    for (const n of INPUTS) expect(ordinal(n)).toBe(switchOrdinal(n))
  })

  it('reads correctly where it matters', () => {
    expect([1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 101, 111, 112].map(ordinal)).toEqual([
      '1st', '2nd', '3rd', '4th', '11th', '12th', '13th', '21st', '22nd', '23rd', '101st', '111th', '112th',
    ])
  })
})
