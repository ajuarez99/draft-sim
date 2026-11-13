import { describe, expect, it } from 'vitest'
import { slotForPick } from './draftSlot'

/*
 * Spec 063 T020. The backend writes __fixtures__/draft-slot-parity.json from DraftSlot.slot (every pick of a 12x15
 * draft for reversal rounds 0 and 3). Shape: `{ teams, rounds, cases: [{ reversalRound, slots: number[] }] }`
 * where pick n is `slots[n - 1]`. This replays it. If the file is absent the parity test is SKIPPED (reported as
 * such); the plain snake checks below always run.
 */
// The fixture is read, not imported, so its absence skips the test instead of breaking the build. vitest runs with
// cwd = web/, and node:fs is loaded dynamically and untyped because the app has no @types/node.
const FIXTURE = 'src/__fixtures__/draft-slot-parity.json'

async function readFixture(): Promise<string | null> {
  // @ts-expect-error node:fs has no types here
  const fs = (await import('node:fs')) as { existsSync: (p: string) => boolean; readFileSync: (p: string, enc: string) => string }
  return fs.existsSync(FIXTURE) ? fs.readFileSync(FIXTURE, 'utf8') : null
}

type Fixture = { teams: number; rounds: number; cases: { reversalRound: number; slots: number[] }[] }

describe('slotForPick', () => {
  it('plain snake: odd rounds forward, even rounds back', () => {
    expect([1, 2, 12, 13, 14, 24, 25].map((n) => slotForPick(n, 12, 0))).toEqual([1, 2, 12, 12, 11, 1, 1])
  })

  it('reversal round 3 flips the parity from round 3 on (rounds 1 and 2 stay plain)', () => {
    const slots = (round: number) => [1, 12].map((i) => slotForPick((round - 1) * 12 + i, 12, 3))
    expect(slots(1)).toEqual([1, 12])
    expect(slots(2)).toEqual([12, 1])
    expect(slots(3)).toEqual([12, 1]) // plain snake would run round 3 forward
    expect(slots(4)).toEqual([1, 12])
  })

  it('every round is a permutation of the teams', () => {
    for (const rev of [0, 3]) {
      for (let r = 1; r <= 15; r++) {
        const s = Array.from({ length: 12 }, (_, i) => slotForPick((r - 1) * 12 + i + 1, 12, rev))
        expect([...s].sort((a, b) => a - b)).toEqual(Array.from({ length: 12 }, (_, i) => i + 1))
      }
    }
  })
})

describe('slotForPick against the backend fixture', () => {
  it('matches DraftSlot.slot for every pick in the fixture (skipped when the file is absent)', async (ctx) => {
    const raw = await readFixture()
    if (raw == null) return ctx.skip()
    const fx = JSON.parse(raw) as Fixture
    expect(fx.cases.map((c) => c.reversalRound)).toEqual(expect.arrayContaining([0, 3]))
    let checked = 0
    for (const c of fx.cases) {
      expect(c.slots).toHaveLength(fx.teams * fx.rounds)
      c.slots.forEach((slot, i) => {
        expect(slotForPick(i + 1, fx.teams, c.reversalRound), `pick ${i + 1}, reversal ${c.reversalRound}`).toBe(slot)
        checked++
      })
    }
    expect(checked).toBeGreaterThanOrEqual(2 * 12 * 15)
  })
})
