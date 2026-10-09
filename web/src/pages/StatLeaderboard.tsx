import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import Avatar from '../components/Avatar'
import NotFound from '../components/NotFound'
import PageHeader from '../components/PageHeader'
import PlayerLink from '../components/PlayerLink'
import {
  getStatLeaderboard,
  type LeaderboardRow,
  type PlayerOwnership,
  type PlayerWindowKind,
  type StatLeaderboard as Board,
} from '../api'
import { Cell, DASH, GRADES_REASONS, adpSentence } from '../statCells'
import { useLeagueDataVersion } from '../leagueDataVersion'
import {
  COLUMNS,
  GROUPS,
  LEADER_CATEGORIES,
  LEADERS_SIZE,
  NO_FILTERS,
  applyFilters,
  categoryLeaders,
  formatValue,
  columnLabel,
  groupDef,
  hasKnownOwnership,
  leaderRows as leadersOf,
  ownershipOf,
  qualificationApplies,
  resolveSort,
  sortRows,
  toggleSort,
  type Availability,
  type ColumnGroup,
  type Filters,
  type SortState,
  type StatColumn,
  type StatMode,
} from '../statLeaderboard'
import {
  fallbackNote,
  longDate,
  ownershipAsOf,
  qualificationRule,
  reasonSentence,
  seasonLabel,
} from '../statCopy'
import { useBlock } from '../useBlock'

/**
 * specs/022-player-stat-analysis US4: every player in the league's pool, sortable and filterable,
 * with real stats, this league's fantasy numbers and the draft side by side.
 *
 * The table scrolls sideways INSIDE its own container with the player column pinned; the page
 * itself never scrolls sideways. Every cell that cannot show a number says why (or carries the
 * reason in its tooltip) -- never a blank, never a 0 standing in for "unknown".
 */

const WINDOWS: { kind: PlayerWindowKind; label: string }[] = [
  { kind: 'SEASON', label: 'Season' },
  { kind: 'LAST_10', label: 'Last 10' },
  { kind: 'LAST_5', label: 'Last 5' },
]
const MODES: { kind: StatMode; label: string }[] = [
  { kind: 'perGame', label: 'Per game' },
  { kind: 'totals', label: 'Totals' },
  { kind: 'per36', label: 'Per 36' },
]

function OwnerCell({ o }: { o: PlayerOwnership }) {
  switch (o.state) {
    case 'ROSTERED':
      return (
        <span className="pp-owner">
          <Avatar avatarId={o.avatarId} seed={o.ownerName ?? String(o.rosterId ?? '')} label={o.ownerName} isMe={o.isMe} />
          <span className="pp-owner-name">{o.ownerName ?? 'A team in this league'}</span>
        </span>
      )
    case 'FREE_AGENT':
      return <span className="lh-fa">Free agent</span>
    case 'NOT_DRAFTED':
      return <span className="muted small" title={reasonSentence('NOT_DRAFTED')}>not drafted</span>
    case 'UNAVAILABLE':
      return <span className="muted small" title={reasonSentence('UNAVAILABLE')}>{DASH}</span>
  }
}

function PlayerCell({ row, d, leagueId }: { row: LeaderboardRow; d: Board; leagueId: string }) {
  const name = row.name ?? 'Unknown player'
  return (
    <>
      <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={row.sleeperPlayerId} sport={d.sport}>
        {name}
      </PlayerLink>
      <span className="sl-sub muted small">
        {[row.positions.join('/'), row.team].filter(Boolean).join(' · ') || DASH}
      </span>
      {!row.qualified && row.reason && (
        <span className="sl-sub sl-unranked small">
          {reasonSentence(row.reason, { date: row.stats.lastGameDate })}
        </span>
      )}
    </>
  )
}

function DraftNotes({ d }: { d: Board }) {
  const g = d.draftGrades
  const adp = adpSentence(d)
  const gradesHref = d.draft?.draftId ? `/drafts/${encodeURIComponent(d.draft.draftId)}/board` : null
  return (
    <ul className="muted small pp-notes sl-notes" aria-label="Draft notes">
      {d.draft && d.draft.state !== 'NONE' && (
        <li>{`Pick, round, drafted-by and ADP are from the ${seasonLabel(d.draft.draftSeason)} draft.`}</li>
      )}
      {d.draft?.state === 'NOT_HAPPENED' && <li>The draft hasn’t happened yet, so there are no picks to show.</li>}
      {d.draft?.state === 'NONE' && <li>This league has no draft.</li>}
      {d.draft?.state === 'COMPLETE' && <li>“Undrafted” means no pick in this league’s draft.</li>}
      {adp && <li>{adp}</li>}
      <li>
        {'Draft value: fantasy points over the pick’s slot, summed over the league’s counted weeks, from '}
        {gradesHref ? <Link to={gradesHref}>Draft Grades</Link> : 'Draft Grades'}
        {g && g.available ? ` (${g.weeksCounted} ${g.weeksCounted === 1 ? 'week' : 'weeks'} counted). ` : '. '}
        {'It is a season total, not a per-week figure.'}
      </li>
      {g && !g.available && <li>{GRADES_REASONS[g.reason ?? ''] ?? 'Draft value is unavailable.'}</li>}
      {g && g.available && g.gradesEarly && (
        <li>{`Early: only ${g.weeksCounted} ${g.weeksCounted === 1 ? 'week is' : 'weeks are'} counted so far, so draft values will move.`}</li>
      )}
    </ul>
  )
}

function ReplacementNote({ d }: { d: Board }) {
  const r = d.replacement
  if (!r) return null
  const levels = Object.entries(r.byPosition)
  return (
    <p className="muted small pp-line sl-notes" role="note">
      {'VOR is fantasy points per game over the replacement level at his position: '}
      {levels.map(([pos, lvl], i) => (
        <span key={pos}>
          {i > 0 && ' · '}
          {`${pos} ${lvl == null ? 'none' : lvl.toFixed(1)}`}
        </span>
      ))}
      {`. Found by filling ${r.teams} teams’ starting slots greedily with the best qualified players; labelled a simplification, not a projection.`}
    </p>
  )
}

function SortHeader({ col, sort, onSort, label }: { col: StatColumn; sort: SortState; onSort: (id: string) => void; label?: string }) {
  const on = sort.col === col.id
  return (
    <th
      scope="col"
      aria-sort={on ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}
      className={col.id === 'name' ? 'sl-pin' : undefined}
    >
      <button type="button" className={`sl-sort${on ? ' on' : ''}`} title={label && label !== col.label ? `${col.title} (${label})` : col.title} onClick={() => onSort(col.id)}>
        {label ?? col.label}
        <span aria-hidden="true" className="sl-arrow">{on ? (sort.dir === 'asc' ? '▲' : '▼') : ''}</span>
      </button>
    </th>
  )
}

function Segmented<T extends string>({
  label, value, options, onChange,
}: { label: string; value: T; options: { kind: T; label: string }[]; onChange: (k: T) => void }) {
  return (
    <div className="segmented sm" role="group" aria-label={label}>
      {options.map((o) => (
        <button
          key={o.kind}
          type="button"
          className={`segment${o.kind === value ? ' on' : ''}`}
          aria-pressed={o.kind === value}
          onClick={() => onChange(o.kind)}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

const availKey = (a: Availability) => (a.kind === 'roster' ? `roster:${a.rosterId}` : a.kind)
const parseAvail = (k: string): Availability =>
  k.startsWith('roster:') ? { kind: 'roster', rosterId: Number(k.slice(7)) } : k === 'free' ? { kind: 'free' } : k === 'rostered' ? { kind: 'rostered' } : { kind: 'all' }

function FilterBar({ rows, f, onChange, nowLabel }: { rows: LeaderboardRow[]; f: Filters; onChange: (f: Filters) => void; nowLabel: string | null }) {
  const known = useMemo(() => hasKnownOwnership(rows), [rows])
  const positions = useMemo(() => [...new Set(rows.flatMap((r) => r.positions))].sort(), [rows])
  const teams = useMemo(() => [...new Set(rows.map((r) => r.team).filter((t): t is string => !!t))].sort(), [rows])
  const rosters = useMemo(() => {
    const m = new Map<number, string>()
    for (const r of rows) {
      const o = ownershipOf(r)
      if (o.state === 'ROSTERED' && o.rosterId != null) {
        m.set(o.rosterId, o.ownerName ?? `Roster ${o.rosterId}`)
      }
    }
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]))
  }, [rows])
  return (
    <div className="sl-filters">
      <label className="sl-field">
        <span className="muted small">Position</span>
        <select value={f.position ?? ''} onChange={(e) => onChange({ ...f, position: e.target.value || null })}>
          <option value="">All</option>
          {positions.map((p) => <option key={p} value={p}>{p}</option>)}
        </select>
      </label>
      <label className="sl-field">
        <span className="muted small">NBA team</span>
        <select value={f.team ?? ''} onChange={(e) => onChange({ ...f, team: e.target.value || null })}>
          <option value="">All</option>
          {teams.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
      </label>
      <label className="sl-field">
        <span className="muted small">Availability</span>
        <select
          value={availKey(f.availability)}
          onChange={(e) => onChange({ ...f, availability: parseAvail(e.target.value) })}
          disabled={!known}
          aria-describedby={known ? undefined : 'sl-avail-why'}
        >
          <option value="all">All players</option>
          <option value="free">{nowLabel ? `Free agents (${nowLabel})` : 'Free agents'}</option>
          <option value="rostered">{nowLabel ? `Rostered (${nowLabel})` : 'Rostered'}</option>
          {rosters.map(([id, name]) => <option key={id} value={`roster:${id}`}>{`${name}’s roster`}</option>)}
        </select>
      </label>
      {!known && (
        <span id="sl-avail-why" className="muted small">
          {nowLabel ? `Nobody is on a roster yet (${nowLabel}), so there is no ownership to filter by.` : 'Ownership isn’t known for this season, so there is nothing to filter by.'}
        </span>
      )}
    </div>
  )
}

function Leaders({ rows, d, leagueId }: { rows: LeaderboardRow[]; d: Board; leagueId: string }) {
  return (
    <section className="sl-leaders" aria-label="Stat leaders">
      {LEADER_CATEGORIES.map((cat) => {
        const top = categoryLeaders(rows, cat, LEADERS_SIZE)
        return (
          <div key={cat.id} className="sl-leader" role="group" aria-label={cat.label}>
            <h3 className="pp-h">{cat.label}</h3>
            {top.length === 0 ? (
              <p className="muted small">No qualified players.</p>
            ) : (
              <ol className="sl-leader-list">
                {top.map(({ row, value }) => (
                  <li key={row.sleeperPlayerId}>
                    <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={row.sleeperPlayerId} sport={d.sport}>
                      {row.name ?? 'Unknown player'}
                    </PlayerLink>
                    <span className="muted small sl-leader-team">{row.team ?? ''}</span>
                    <span className="pp-strong sl-leader-val">{formatValue(cat.col, value, cat.mode)}</span>
                  </li>
                ))}
              </ol>
            )}
          </div>
        )
      })}
    </section>
  )
}

function SeasonPicker({ d, leagueId }: { d: Board; leagueId: string }) {
  if (d.seasons.length === 0) return null
  return (
    <nav className="pp-seasons" aria-label="Season">
      {d.seasons.map((o) => {
        const here = o.sleeperLeagueId === leagueId
        return (
          <Link
            key={o.sleeperLeagueId}
            to={`/leagues/${encodeURIComponent(o.sleeperLeagueId)}/stats`}
            className={`pp-season${here ? ' on' : ''}${o.hasGames ? '' : ' empty'}`}
            aria-current={here ? 'page' : undefined}
          >
            {seasonLabel(o.season)}
            {!o.hasGames && <span className="pp-season-note">no games yet</span>}
          </Link>
        )
      })}
    </nav>
  )
}

export default function StatLeaderboard() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const leagueId = sleeperLeagueId ?? ''
  const version = useLeagueDataVersion(sleeperLeagueId)

  const [win, setWin] = useState<PlayerWindowKind>('SEASON')
  const [mode, setMode] = useState<StatMode>('perGame')
  const [group, setGroup] = useState<ColumnGroup>('fantasy')
  const [sort, setSort] = useState<SortState>({ col: 'fp', dir: 'desc' })
  const [filters, setFilters] = useState<Filters>(NO_FILTERS)
  const [qualifiedOnly, setQualifiedOnly] = useState(true)
  const [view, setView] = useState<'table' | 'leaders'>('table')

  const block = useBlock(leagueId ? () => getStatLeaderboard(leagueId, win) : null, [leagueId, win, version])
  const d = block.status === 'ok' ? block.data : null

  const rows = d?.rows ?? []
  const shown = useMemo(
    () => sortRows(applyFilters(rows, filters, { qualifiedOnly, sortCol: sort.col }), sort, mode),
    [rows, filters, qualifiedOnly, sort, mode],
  )
  const leaderRows = useMemo(() => leadersOf(rows, filters), [rows, filters])

  if (block.status === 'error' && block.notFound) return <NotFound what="page" />

  // The league id of the season actually shown, so a player link opens the same season's page.
  const shownLeagueId = d?.seasons.find((s) => s.season === d.season)?.sleeperLeagueId ?? leagueId
  const g = groupDef(group)
  const cols = g.columns
  const fellBack = d?.requestedSeason != null
  const nowLabel = d && fellBack ? `now ${seasonLabel(d.requestedSeason as number)}` : null
  // On a fallback the owners come from the requested season's league (`currentOwnership`), read now.
  const currentAsOf = fellBack ? (rows.find((r) => r.currentOwnership?.asOf)?.currentOwnership?.asOf ?? null) : null
  const asOf = d
    ? fellBack
      ? ownershipAsOf(currentAsOf, d.requestedSeason as number)
      : ownershipAsOf(d.ownershipAsOf, d.season)
    : null
  const windowLabel = win === 'SEASON' ? null : (WINDOWS.find((w) => w.kind === win)?.label.toLowerCase() ?? null)
  const sortCol = COLUMNS[sort.col]
  const qualApplies = sortCol ? qualificationApplies(sortCol) : false

  const changeGroup = (next: ColumnGroup) => {
    setGroup(next)
    setSort((s) => resolveSort(next, s))
  }

  return (
    <div className="content">
      <PageHeader
        eyebrow={d ? `League · ${seasonLabel(d.season)}` : 'League'}
        title="Player stats"
        sub="Real stats, this league’s fantasy numbers and the draft, side by side."
      />

      {(block.status === 'loading' || block.status === 'idle') && (
        <p className="muted small" role="status">Loading…</p>
      )}
      {block.status === 'error' && <p className="muted small">Couldn’t load the player stats.</p>}

      {d && (!d.available || d.reason) && (
        <section className="section pp-reason">
          <p className="muted small">{d.reason ? reasonSentence(d.reason) : 'Player stats aren’t available.'}</p>
        </section>
      )}

      {d && d.available && !d.reason && (
        <>
          <SeasonPicker d={d} leagueId={shownLeagueId} />
          {d.requestedSeason != null && (
            <p className="small pt-note" role="note">
              {fallbackNote(seasonLabel(d.requestedSeason), seasonLabel(d.season))}
            </p>
          )}

          <div className="sl-controls">
            <Segmented label="Window" value={win} options={WINDOWS} onChange={setWin} />
            <Segmented
              label="View"
              value={view}
              options={[{ kind: 'table', label: 'Table' }, { kind: 'leaders', label: 'Stat leaders' }]}
              onChange={setView}
            />
            {view === 'table' && <Segmented label="Counting stats" value={mode} options={MODES} onChange={setMode} />}
          </div>
          {view === 'table' && (
            <div className="sl-controls">
              <div className="segmented sm" role="group" aria-label="Column group">
                {GROUPS.map((x) => (
                  <button
                    key={x.id}
                    type="button"
                    className={`segment${x.id === group ? ' on' : ''}`}
                    aria-pressed={x.id === group}
                    onClick={() => changeGroup(x.id)}
                  >
                    {x.label}
                  </button>
                ))}
              </div>
            </div>
          )}

          <FilterBar rows={rows} f={filters} onChange={setFilters} nowLabel={nowLabel} />

          {view === 'table' ? (
            <>
              <div className="sl-qual">
                <label className="sl-check">
                  <input type="checkbox" checked={qualifiedOnly} onChange={(e) => setQualifiedOnly(e.target.checked)} />
                  <span>Qualified players only</span>
                </label>
                {d.qualification && <span className="muted small">{qualificationRule(d.qualification)}</span>}
              </div>
              <p className="muted small pp-line">
                {'Applies to rate and rank columns (percentages, FP/G, ranks, VOR); counting stats, totals and text columns list everyone. '}
                {qualifiedOnly
                  ? qualApplies
                    ? 'Sorted by a rate or rank column, so unqualified players are hidden.'
                    : 'Sorted by a column it does not apply to, so every player is listed.'
                  : 'Unqualified players are listed too; they have no ranks or value over replacement, and a short sample can top a rate column.'}
              </p>
            </>
          ) : (
            d.qualification && (
              <p className="muted small pp-line">
                {`Leaders use qualified players only (${qualificationRule(d.qualification)})`}
              </p>
            )
          )}
          <p className="muted small pp-line">
            {fellBack
              ? `Owners are ${nowLabel} (the ${seasonLabel(d.requestedSeason as number)} league), not the ${seasonLabel(d.season)} stats season's.${asOf ? ` ${asOf}.` : ''}`
              : asOf ? `Ownership: ${asOf}.` : 'Ownership is not available for this season.'}
            {d.dataAsOf ? ` Data refreshed ${longDate(d.dataAsOf)}.` : ''}
          </p>

          {view === 'leaders' ? (
            <>
              <p className="muted small pp-line">
                {`Top ${LEADERS_SIZE} per category, per game (${WINDOWS.find((w) => w.kind === win)?.label.toLowerCase()}).`}
              </p>
              <Leaders rows={leaderRows} d={d} leagueId={shownLeagueId} />
            </>
          ) : (
            <section className="pp-card" aria-label="Player stats">
              <p className="muted small pp-line">
                {`${shown.length} of ${rows.length} players. `}
                {mode === 'perGame' ? 'Counting stats per game.' : mode === 'totals' ? 'Counting stats are season totals for the window.' : 'Counting stats per 36 minutes.'}
                {' Click a header to sort; click again to reverse. Players with no value sort last.'}
                {windowLabel && ` Rank columns are over the ${windowLabel} window’s qualified group; the player page shows season ranks.`}
              </p>
              <div className="sl-wrap">
                <table className="sl-table">
                  <thead>
                    <tr>
                      <SortHeader col={COLUMNS.name} sort={sort} onSort={(id) => setSort((s) => toggleSort(s, id))} />
                      <th scope="col">{nowLabel ? `Owner (${nowLabel})` : 'Owner'}</th>
                      {cols.map((c) => (
                        <SortHeader key={c.id} col={c} label={columnLabel(c, windowLabel)} sort={sort} onSort={(id) => setSort((s) => toggleSort(s, id))} />
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {shown.map((r) => (
                      <tr key={r.sleeperPlayerId}>
                        <th scope="row" className="sl-pin">
                          <PlayerCell row={r} d={d} leagueId={shownLeagueId} />
                        </th>
                        <td className="sl-owner"><OwnerCell o={ownershipOf(r)} /></td>
                        {cols.map((c) => (
                          <td key={c.id} className={c.id === 'fp' ? 'pp-fant pp-strong' : undefined}>
                            <Cell col={c} row={r} d={d} mode={mode} />
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {shown.length === 0 && <p className="muted small">No players match these filters.</p>}
              {group === 'draft' && <DraftNotes d={d} />}
              {(group === 'fantasy' || group === 'draft') && <ReplacementNote d={d} />}
            </section>
          )}
        </>
      )}
    </div>
  )
}
