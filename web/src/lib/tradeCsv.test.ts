import { describe, expect, it } from 'vitest'
import { tradeCsv } from './tradeCsv'
import type { LeagueTrade, LeagueTransactions, TradeAsset, TradePlayerAsset, TradeSide } from '../api'

const player = (id: string, name: string, over: Partial<TradePlayerAsset> = {}): TradePlayerAsset => ({
  kind: 'PLAYER', playerId: id, playerName: name, position: 'PG', status: 'COUNTED',
  firstWeek: 2, lastWeek: 5, startedPoints: 10.4, benchPoints: 1.2, weeksCounted: 4,
  duplicateWeeks: [], postMovePositionalRank: null, stretches: [], ...over,
})
const side = (rosterId: number, teamName: string, startedTotal: number, assets: TradeAsset[]): TradeSide => ({
  rosterId, teamName, startedTotal, benchTotal: 0, weeksCounted: 4, assets,
})
const trade = (over: Partial<LeagueTrade> = {}): LeagueTrade => ({
  txId: 'tx1', week: 3, acceptedAt: '2026-10-14T03:30:00Z', assetsLoaded: true,
  verdict: { state: 'WINNER', winnerRosterId: 1, margin: 1, weeksCounted: 4, final: false },
  sides: [], ...over,
})
const tx = (trades: LeagueTrade[]): LeagueTransactions => ({
  available: true, season: 2026, sport: 'nba', byManager: [], trades, adds: [], rankDirection: 'LOWER_IS_BETTER',
  seasonFinal: false, tradeAssetsLoaded: true, throughWeek: 5, reconciliation: null,
})

/** Minimal RFC 4180 reader, so the tests check what a spreadsheet would see. */
function parse(csv: string): string[][] {
  const rows: string[][] = []
  let cur: string[] = []
  let f = ''
  let q = false
  for (let i = 0; i < csv.length; i++) {
    const c = csv[i]
    if (q) {
      if (c === '"' && csv[i + 1] === '"') { f += '"'; i++ }
      else if (c === '"') q = false
      else f += c
    } else if (c === '"') q = true
    else if (c === ',') { cur.push(f); f = '' }
    else if (c === '\n') { cur.push(f); rows.push(cur); cur = []; f = '' }
    else f += c
  }
  return rows
}

describe('tradeCsv', () => {
  it('writes a header row, \\n endings and a trailing newline', () => {
    const csv = tradeCsv(tx([]))
    expect(csv.endsWith('\n')).toBe(true)
    expect(csv).not.toContain('\r')
    expect(parse(csv)).toHaveLength(1)
    expect(parse(csv)[0][1]).toBe('txId')
  })

  it('quotes commas, quotes and apostrophes survive, and the Eastern date is used', () => {
    const t = trade({
      sides: [
        side(1, 'Smith, "Big" Al\'s', 10.4, [player('1', "D'Angelo, Jr.")]),
        side(2, 'Bravo', 0, []),
      ],
    })
    const rows = parse(tradeCsv(tx([t])))
    expect(rows).toHaveLength(2)
    expect(rows[1][0]).toBe('Oct 13, 2026')
    expect(rows[1][2]).toBe('Smith, "Big" Al\'s')
    expect(rows[1][3]).toBe('Bravo')
    expect(rows[1][5]).toBe("D'Angelo, Jr.")
  })

  it('writes FAAB and pick rows, blank weeks and points when nothing counted', () => {
    const t = trade({
      sides: [
        side(1, 'Alpha', 0, [
          { kind: 'FAAB', amount: 15, fromRosterId: 2, fromTeamName: 'Bravo' },
          { kind: 'PICK', season: 2026, round: 1, originalRosterId: 2, status: 'NOT_DRAFTED_YET', pickNo: null, player: null },
        ]),
        side(2, 'Bravo', 0, []),
      ],
    })
    const rows = parse(tradeCsv(tx([t])))
    expect(rows[1].slice(2, 11)).toEqual(['Alpha', 'Bravo', 'FAAB', '$15 FAAB', '', '', '', '', ''])
    expect(rows[2].slice(4, 11)).toEqual(['PICK', '2026 R1 (from Bravo)', 'NOT_DRAFTED_YET', '', '', '', ''])
  })

  it('a resolved pick carries its drafted player and points', () => {
    const t = trade({
      sides: [
        side(1, 'Alpha', 3.5, [
          { kind: 'PICK', season: 2026, round: 2, originalRosterId: 2, status: 'COUNTED', pickNo: 14,
            player: player('9', 'Drafted Guy', { startedPoints: 3.5, benchPoints: 0 }) },
        ]),
        side(2, 'Bravo', 0, []),
      ],
    })
    const rows = parse(tradeCsv(tx([t])))
    expect(rows[1][5]).toBe('2026 R2 (from Bravo) -> Drafted Guy')
    expect(rows[1].slice(7, 11)).toEqual(['2', '5', '3.5', '0.0'])
  })

  it('per-side started sums equal the payload startedTotal', () => {
    const t = trade({
      sides: [
        side(1, 'Alpha', 13.9, [
          player('1', 'A One', { startedPoints: 10.4 }),
          { kind: 'PICK', season: 2026, round: 2, originalRosterId: 2, status: 'COUNTED', pickNo: 14,
            player: player('9', 'Drafted Guy', { startedPoints: 3.5 }) },
          { kind: 'FAAB', amount: 5, fromRosterId: 2, fromTeamName: 'Bravo' },
        ]),
        side(2, 'Bravo', 7.0, [player('2', 'B One', { startedPoints: 7.0 })]),
      ],
    })
    const rows = parse(tradeCsv(tx([t]))).slice(1)
    for (const s of t.sides) {
      const sum = rows.filter((r) => r[2] === s.teamName).reduce((n, r) => n + (r[9] === '' ? 0 : Number(r[9])), 0)
      // The backend rounds each asset and each side total to 1 dp separately, so the sums can
      // differ by rounding; 0.05 is half the last digit.
      expect(Math.abs(sum - s.startedTotal)).toBeLessThanOrEqual(0.05 + 1e-9)
    }
  })
})
