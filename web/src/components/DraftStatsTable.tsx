import { memo } from 'react'
import type { PlayerRef, StatLeaderboard } from '../api'
import { likelyRule, shownSeason, type DraftStatRow } from '../draftRoomStats'
import { posRank } from '../posRank'
import { Cell } from '../statCells'
import { COLUMNS, columnLabel, type SortState, type StatColumn, type StatMode } from '../statLeaderboard'
import PlayerFace from './PlayerFace'

type Props = {
  rows: DraftStatRow[]
  columns: StatColumn[]
  /** The leaderboard response the rows came from: names the season, and `Cell` reads its reasons. */
  board: StatLeaderboard
  sleeperLeagueId: string
  mode: StatMode
  sort: SortState
  onSort: (colId: string) => void
  likelyOnly: boolean
  onLikelyOnly: (on: boolean) => void
  /** "3.07": the member's next pick, for the printed filter rule. Null when unknown. */
  nextPickLabel: string | null
  /** Why the "likely there" filter is off the table (seat unknown, mock); null when it works. */
  likelyUnavailableReason: string | null
  /** Puts the default columns back; offered when none are chosen. */
  onResetColumns: () => void
  /** Drafted players in `rows` (only present when the list's "Hide drafted" is off): marked "taken". */
  takenIds?: Set<number>
  /** Sleeper ids of the user's targets, and the star handler. Without the handler no star is drawn. */
  targetIds?: Set<string>
  onToggleTarget?: (p: PlayerRef) => void
}

const WINDOW_NAMES: Record<StatLeaderboard['window'], string> = {
  SEASON: 'Season',
  LAST_10: 'Last 10',
  LAST_5: 'Last 5',
}

function Head({ col, sort, onSort, label }: { col: StatColumn; sort: SortState; onSort: (id: string) => void; label?: string }) {
  const on = sort.col === col.id
  return (
    <th
      scope="col"
      aria-sort={on ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}
      className={col.id === 'name' ? 'sl-pin' : undefined}
    >
      <button type="button" className={`sl-sort${on ? ' on' : ''}`} title={col.title} onClick={() => onSort(col.id)}>
        {label ?? col.label}
        <span aria-hidden="true" className="sl-arrow">{on ? (sort.dir === 'asc' ? '▲' : '▼') : ''}</span>
      </button>
    </th>
  )
}

type RowProps = {
  r: DraftStatRow
  columns: StatColumn[]
  board: StatLeaderboard
  sleeperLeagueId: string
  mode: StatMode
  seasonLabel: string
  taken: boolean
  starred: boolean
  onToggleTarget?: (p: PlayerRef) => void
}

/**
 * One player's row. `joinPoolStats` builds fresh row objects on every recompute (each pick, each
 * resimulation), so memo can't compare `r` by reference. It compares what the row renders from:
 * the player and the leaderboard row by reference (both come from stable sources: the pool and
 * the cached response), the rest by value. A pick that removes one player then re-renders no
 * surviving row.
 */
const sameRow = (a: RowProps, b: RowProps) =>
  a.r.player === b.r.player &&
  a.r.stats === b.r.stats &&
  a.r.reason === b.r.reason &&
  a.r.survivalNext === b.r.survivalNext &&
  a.r.fillsSlot === b.r.fillsSlot &&
  a.columns === b.columns &&
  a.board === b.board &&
  a.sleeperLeagueId === b.sleeperLeagueId &&
  a.mode === b.mode &&
  a.seasonLabel === b.seasonLabel &&
  a.taken === b.taken &&
  a.starred === b.starred &&
  a.onToggleTarget === b.onToggleTarget

const StatRow = memo(function StatRow({ r, columns, board, sleeperLeagueId, mode, seasonLabel, taken, starred, onToggleTarget }: RowProps) {
  const p = r.player
  const fills = r.fillsSlot
  return (
    <tr className={taken ? 'taken' : undefined}>
      <th scope="row" className="sl-pin">
        <span className="ds-name">
          {onToggleTarget && !taken && (
            <button
              type="button"
              className={`star-btn${starred ? ' on' : ''}`}
              aria-pressed={starred}
              aria-label={starred ? `Remove ${p.name} from targets` : `Add ${p.name} to targets`}
              title={starred ? 'Remove from targets' : 'Add to targets'}
              onClick={() => onToggleTarget(p)}
            >
              {starred ? '★' : '☆'}
            </button>
          )}
          <PlayerFace sport="nba" sleeperId={p.sleeperId} team={p.team} position={p.position} name={p.name} size={20} />
          <span className={`pos ${p.position}`}>{posRank(p)}</span>
          <a href={`/leagues/${sleeperLeagueId}/players/${p.sleeperId}`} target="_blank" rel="noopener">
            {p.name}
          </a>
          {taken && <span className="taken-tag">taken</span>}
          {fills && !taken && <span className="need-tag">{fills}</span>}
          <span className="ds-sub">
            {`ADP ${Math.round(p.adp)}`}
            {r.survivalNext != null && ` · ${Math.round(r.survivalNext * 100)}% there at your next pick`}
          </span>
        </span>
      </th>
      {r.stats == null ? (
        columns.length > 0 && (
          <td className="ds-none" colSpan={columns.length}>
            {`No NBA games in ${seasonLabel}`}
          </td>
        )
      ) : (
        columns.map((c) => (
          <td key={c.id} className={c.id === 'fp' ? 'pp-fant pp-strong' : undefined}>
            <Cell col={c} row={r.stats!} d={board} mode={mode} />
          </td>
        ))
      )}
    </tr>
  )
}, sameRow)

/**
 * The live room's Stats view of the available-players sheet (specs/023 US1): one row per
 * undrafted player, the name column pinned, the stat columns scrolling inside the table's own
 * wrapper. Every number goes through `Cell`, the leaderboard's renderer, so a value can't read
 * differently here than on the stats page (FR-002). A player with no games in the shown season
 * gets one spanning sentence instead of a zero per column.
 */
function DraftStatsTable({
  rows,
  columns,
  board,
  sleeperLeagueId,
  mode,
  sort,
  onSort,
  likelyOnly,
  onLikelyOnly,
  nextPickLabel,
  likelyUnavailableReason,
  onResetColumns,
  takenIds,
  targetIds,
  onToggleTarget,
}: Props) {
  const season = shownSeason(board)
  const windowLabel = board.window === 'SEASON' ? null : WINDOW_NAMES[board.window].toLowerCase()
  const likelyDisabled = likelyUnavailableReason != null
  return (
    <div className="ds">
      <div className="ds-head">
        <p className="small">
          <strong>{`${season.seasonLabel} stats, regular season`}</strong>
          {` · ${WINDOW_NAMES[board.window]} window`}
          {season.fellBack && ' · Last season’s play, not a projection.'}
        </p>
        {season.scoringLabel && <p className="muted small">{season.scoringLabel}</p>}
        {season.scoringChanged && <p className="small pt-note" role="note">This league’s scoring has changed since then.</p>}
      </div>
      <div className="ds-controls">
        <label className="ds-likely" title={likelyUnavailableReason ?? undefined}>
          <input
            type="checkbox"
            checked={likelyOnly && !likelyDisabled}
            disabled={likelyDisabled}
            onChange={(e) => onLikelyOnly(e.target.checked)}
          />
          Likely there at my next pick
        </label>
        <span className="muted small">
          {likelyDisabled
            ? likelyUnavailableReason
            : nextPickLabel
              ? likelyRule(nextPickLabel)
              : null}
        </span>
      </div>
      {columns.length === 0 && (
        <p className="ds-empty small">
          <span className="muted">No stats chosen</span>
          <button type="button" className="league-link" onClick={onResetColumns}>Reset to default</button>
        </p>
      )}
      <div className="sl-wrap ds-wrap">
        <table className="sl-table ds-table">
          <thead>
            <tr>
              <Head col={COLUMNS.name} sort={sort} onSort={onSort} />
              {columns.map((c) => (
                <Head key={c.id} col={c} label={columnLabel(c, windowLabel)} sort={sort} onSort={onSort} />
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <StatRow key={r.player.id} r={r} columns={columns} board={board} sleeperLeagueId={sleeperLeagueId} mode={mode} seasonLabel={season.seasonLabel}
                taken={takenIds?.has(r.player.id) ?? false}
                starred={targetIds?.has(r.player.sleeperId) ?? false}
                onToggleTarget={onToggleTarget}
              />
            ))}
          </tbody>
        </table>
        {rows.length === 0 && columns.length > 0 && <p className="ds-empty muted small">No players match.</p>}
      </div>
    </div>
  )
}

/** Memoized: the live room re-renders about once a second for its clock, and nothing here changes then. */
export default memo(DraftStatsTable)
