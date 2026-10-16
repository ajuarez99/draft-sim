import { describe, expect, it } from 'vitest'
import { POSITIONS_BY_SPORT } from './positions'

// Spec 039. The Team strength bars and rank matrix colour themselves through
// per-position CSS rules, and both lists only ever named football's six -- so
// every basketball segment rendered transparent over the grey track. This walks
// the app's one per-sport position list rather than a copy of it, so a sport or
// position added there without a colour here fails loudly.
describe('Team strength position colours', () => {
  // vitest stubs stylesheet imports, so read the file itself (no node types in this tsconfig).
  async function stylesheet(): Promise<string[]> {
    // @ts-expect-error node:fs has no types here
    const { readFileSync } = (await import('node:fs')) as { readFileSync: (p: string, enc: string) => string }
    return readFileSync('src/styles.css', 'utf8').split(String.fromCharCode(10))
  }

  const every = Object.entries(POSITIONS_BY_SPORT).flatMap(([sport, ps]) => ps.map((p) => [sport, p] as const))

  for (const block of ['.analysis-seg', '.analysis-cell']) {
    it.each(every)(`${block}: %s %s has its position colour`, async (_sport, pos) => {
      const css = await stylesheet()
      const rule = css.find((l) => l.startsWith(`${block}.pos-${pos} {`)) ?? ''
      expect(rule, `${block}.pos-${pos}`).toContain(`var(--${pos.toLowerCase()})`)
    })
  }
})
