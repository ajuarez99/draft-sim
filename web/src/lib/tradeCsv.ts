import type { LeagueTransactions, TradeAsset, TradePlayerAsset, TradeSide } from '../api'
import { easternDate } from './easternDate'

const HEADER = [
  'accepted date (US Eastern)',
  'txId',
  'receiving team',
  'giving team(s)',
  'asset kind',
  'asset',
  'status',
  'first week',
  'last week',
  'started points',
  'bench points',
]

/** RFC 4180: quote when the field has a comma, quote, CR or LF; double embedded quotes. */
function field(v: string | number | null): string {
  if (v == null) return ''
  const s = String(v)
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

/** Points and weeks exist only for a COUNTED player; anything else is blank, never a made-up 0. */
function playerCells(p: TradePlayerAsset | null): (string | number | null)[] {
  if (!p || p.status !== 'COUNTED') return [null, null, null, null]
  return [p.firstWeek, p.lastWeek, p.startedPoints.toFixed(1), p.benchPoints.toFixed(1)]
}

function row(
  date: string,
  txId: string,
  side: TradeSide,
  giving: string,
  a: TradeAsset,
  teamOf: (rosterId: number) => string,
): (string | number | null)[] {
  const head = [date, txId, side.teamName, giving]
  if (a.kind === 'PLAYER') return [...head, 'PLAYER', a.playerName, a.status, ...playerCells(a)]
  if (a.kind === 'FAAB') return [...head, 'FAAB', `$${a.amount} FAAB`, '', null, null, null, null]
  const pick = `${a.season} R${a.round} (from ${teamOf(a.originalRosterId)})`
  const desc = a.player ? `${pick} -> ${a.player.playerName}` : pick
  return [...head, 'PICK', desc, a.status, ...playerCells(a.player)]
}

/** One row per (trade, asset), a header row, RFC 4180 quoting, `\n` line endings. */
export function tradeCsv(tx: LeagueTransactions): string {
  const lines = [HEADER.map(field).join(',')]
  for (const t of tx.trades) {
    const date = easternDate(t.acceptedAt)
    const teamOf = (id: number) => t.sides.find((s) => s.rosterId === id)?.teamName ?? `Roster ${id}`
    for (const side of t.sides) {
      const giving = t.sides
        .filter((s) => s.rosterId !== side.rosterId)
        .map((s) => s.teamName)
        .join('; ')
      for (const a of side.assets) {
        // A FAAB asset names who sent it, which is more exact than "the other sides".
        const from = a.kind === 'FAAB' ? a.fromTeamName : giving
        lines.push(row(date, t.txId, side, from, a, teamOf).map(field).join(','))
      }
    }
  }
  return lines.join('\n') + '\n'
}
