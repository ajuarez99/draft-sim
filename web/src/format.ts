// Small display formatters shared across pages. specs/021-codebase-cleanup: each
// lives here once, rather than once per page that needed it.

/**
 * 1st, 2nd, 3rd, 4th, 11th, 12th, 13th, 21st, 111th... Handles the 11–13
 * exception, which is easy to get wrong (not "11st"). Before spec 021 there were
 * five copies; one of them printed "21th".
 */
export function ordinal(n: number): string {
  const m100 = n % 100
  if (m100 >= 11 && m100 <= 13) return `${n}th`
  switch (n % 10) {
    case 1: return `${n}st`
    case 2: return `${n}nd`
    case 3: return `${n}rd`
    default: return `${n}th`
  }
}
