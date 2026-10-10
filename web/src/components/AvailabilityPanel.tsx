import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { AvailabilityRow, PlayerRef, PlayerWindowKind, Sport, StatLeaderboard } from '../api'
import { RISK_MAX, SAFE_MIN } from '../survivalBands'
import { fetchDraftStats, statsCacheKey } from '../draftStatsCache'
import { filterLikely, joinPoolStats, sortDraftRows } from '../draftRoomStats'
import { COLUMNS, defaultDir, toggleSort, type SortState, type StatMode } from '../statLeaderboard'
import { reasonSentence } from '../statCopy'
import DraftStatsTable from './DraftStatsTable'
import StatPickerModal from './StatPickerModal'
import { readStatChoice, resetStatChoice, writeStatChoice } from '../statChoice'
import { filterPositions } from '../positions'
import { positionRun } from '../pickRun'
import { tierPlayers } from '../tiers'
import PlayerFace from './PlayerFace'
import { needLabel } from '../teamNeeds'
import { posRank } from '../posRank'
import { roundPickLabel } from '../roundPickLabel'
import { matchesSearch } from '../targets'

const STAT_WINDOWS: { kind: PlayerWindowKind; label: string }[] = [
  { kind: 'SEASON', label: 'Season' },
  { kind: 'LAST_10', label: 'Last 10' },
  { kind: 'LAST_5', label: 'Last 5' },
]
const STAT_MODES: { kind: StatMode; label: string }[] = [
  { kind: 'perGame', label: 'Per game' },
  { kind: 'totals', label: 'Totals' },
  { kind: 'per36', label: 'Per 36' },
]

/** The per-row target star. A taken player can't be targeted, so he gets no star. */
function StarButton({ player, on, hidden, onToggle }: { player: PlayerRef; on: boolean; hidden: boolean; onToggle: (p: PlayerRef) => void }) {
  if (hidden) return <span className="star-btn star-gap" aria-hidden="true" />
  return (
    <button
      type="button"
      className={`star-btn${on ? ' on' : ''}`}
      aria-pressed={on}
      aria-label={on ? `Remove ${player.name} from targets` : `Add ${player.name} to targets`}
      title={on ? 'Remove from targets' : 'Add to targets'}
      onClick={() => onToggle(player)}
    >
      {on ? '★' : '☆'}
    </button>
  )
}

/**
 * An honesty caveat as a small "ⓘ" beside the control it explains, so it costs no line of its
 * own. The full text is both the hover title and the accessible name; it is never dropped.
 */
function InfoMark({ text }: { text: string }) {
  // Touch has no hover, so `title` alone hides the caveat from sighted phone users. A tap, click,
  // Enter or Space (native button) toggles a small visible popover with the full text; Escape or
  // a blur closes it. title/aria-label stay, so hover and screen readers are unchanged.
  const [open, setOpen] = useState(false)
  return (
    <span className="info-mark-wrap">
      <button
        type="button"
        className="info-mark"
        title={text}
        aria-label={text}
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        onBlur={() => setOpen(false)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') setOpen(false)
        }}
      >
        ⓘ
      </button>
      {open && (
        <span className="info-pop" aria-hidden="true">
          {text}
        </span>
      )}
    </span>
  )
}

function Segmented<T extends string>({
  label, value, options, onChange,
}: { label: string; value: T; options: { kind: T; label: string }[]; onChange: (k: T) => void }) {
  return (
    <div className="segmented sm" role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.kind} type="button" className={`segment${o.kind === value ? ' on' : ''}`} aria-pressed={o.kind === value} onClick={() => onChange(o.kind)}>
          {o.label}
        </button>
      ))}
    </div>
  )
}

/** The sort to use once the shown columns change: kept if still shown, else FP/G, else the name. */
function sortAfterColumns(sort: SortState, columns: string[]): SortState {
  if (sort.col === 'name' || columns.includes(sort.col)) return sort
  const col = columns.includes('fp') ? 'fp' : 'name'
  return { col, dir: defaultDir(COLUMNS[col]) }
}

type Props = {
  /**
   * The simulation's per-player survival curves. Optional: a mock draft runs no
   * simulation, so it has none, and passes `players` + `noAvailabilityReason`
   * instead and still gets the tiered list.
   */
  availability?: AvailabilityRow[]
  /** The undrafted pool, for rooms with no `availability`. Ignored when `availability` is given. */
  players?: PlayerRef[]
  /**
   * Why there are no survival numbers here, shown in their place. When set, the
   * survival strip and verdict are hidden even if `availability` is present
   * (the live room before the seat is known: a curve computed for an assumed
   * seat would answer a question the reader didn't ask).
   */
  noAvailabilityReason?: string
  /** Recent picks, newest last, for the position-run callout. Omit to show none. */
  recentPicks?: { position: string }[]
  myPicks: number[]
  teams: number
  pickedPlayerIds?: Set<number>
  // Whether there is a draft to have options in yet. Drives the empty copy.
  started: boolean
  // Which sport's positions to filter by (multi-sport-and-rebrand.md Phase 6)
  // -- the caller's own board, never a union of both sports'.
  sport: Sport
  /**
   * The user's own open starting slots, from teamNeeds.openPositions. When
   * given, each row picks up the same "Fills RB" tag PlayerPicker already puts
   * on its rows -- the two answers come from one function (openSlotFor), so
   * the sheet and the picker cannot disagree about what you need.
   *
   * Optional and undefined by default: the rooms that don't know whose seat is
   * whose (or aren't showing your roster) should show no tag rather than a tag
   * computed against somebody else's team.
   */
  openSlots?: Set<string>
  /**
   * The league whose stats the NBA Stats view reads (spec 023). Undefined means the server
   * didn't send it (a backend older than this frontend); either way the Stats option is
   * disabled rather than guessed at. Football never shows the switch.
   */
  sleeperLeagueId?: string | null
  /**
   * The undrafted-candidate universe for the Stats view (NBA live room: the board's head from
   * /pool). `availability` is NOT that: it holds only players the simulation's snapshots
   * surfaced at one of your picks. Falls back to `players` (mocks), then to availability's own
   * players when neither is given.
   */
  statsPool?: PlayerRef[]
  /** Why `statsPool` could not be loaded; shown in place of the Stats table. */
  statsPoolError?: string
  /**
   * The room supplies a `statsPool` but it hasn't arrived yet. The Stats view then says so
   * instead of quietly listing the simulation's smaller subset of players (code review S4).
   */
  statsPoolLoading?: boolean
  /**
   * Drafted players, for the "Hide drafted" toggle (spec 024 FR-010): with it off they are
   * listed, marked "taken". Without it the toggle can only re-show drafted players the room's
   * own source already carries.
   */
  draftedPlayers?: PlayerRef[]
  /**
   * The user's targets, as Sleeper ids, plus add/remove. Optional: with no handlers the
   * rows carry no star (a room, or an older server, without target lists).
   */
  targetIds?: Set<string>
  onAddTarget?: (p: PlayerRef) => void
  onRemoveTarget?: (sleeperId: string) => void
}

// Tiers or Stats, for this browser session only (spec 023). Session, not local: the view is a
// "where was I" convenience, unlike the stat choice, which is a standing preference.
const VIEW_KEY = 'bk.availView'
function readViewPref(): 'tiers' | 'stats' {
  try {
    return sessionStorage.getItem(VIEW_KEY) === 'stats' ? 'stats' : 'tiers'
  } catch {
    return 'tiers'
  }
}
function writeViewPref(v: 'tiers' | 'stats'): void {
  try {
    sessionStorage.setItem(VIEW_KEY, v)
  } catch {
    // The in-memory view still works for this mount.
  }
}

// The Stats view's sort, window, mode, position filter and "likely" toggle, for this browser
// session. The mock room unmounts the sheet between the member's turns, and FR-004 says these
// survive; one versioned key, and anything unreadable or invalid falls back to the defaults.
// The position filter is shared with the Tiers view on purpose: it is one state in the panel.
const STATS_STATE_KEY = 'bk.availStatsState.v1'
type StatsViewState = { sort: SortState; window: PlayerWindowKind; mode: StatMode; filter: string; likelyOnly: boolean }
const DEFAULT_STATS_STATE: StatsViewState = { sort: { col: 'fp', dir: 'desc' }, window: 'SEASON', mode: 'perGame', filter: 'ALL', likelyOnly: false }
function readStatsState(): StatsViewState {
  try {
    const o = JSON.parse(sessionStorage.getItem(STATS_STATE_KEY) ?? 'null')
    if (!o || typeof o !== 'object') return DEFAULT_STATS_STATE
    const sortOk = o.sort && typeof o.sort.col === 'string' && COLUMNS[o.sort.col] && (o.sort.dir === 'asc' || o.sort.dir === 'desc')
    return {
      sort: sortOk ? { col: o.sort.col, dir: o.sort.dir } : DEFAULT_STATS_STATE.sort,
      window: STAT_WINDOWS.some((w) => w.kind === o.window) ? o.window : DEFAULT_STATS_STATE.window,
      mode: STAT_MODES.some((m) => m.kind === o.mode) ? o.mode : DEFAULT_STATS_STATE.mode,
      filter: typeof o.filter === 'string' ? o.filter : 'ALL',
      likelyOnly: o.likelyOnly === true,
    }
  } catch {
    return DEFAULT_STATS_STATE
  }
}
function writeStatsState(v: StatsViewState): void {
  try {
    sessionStorage.setItem(STATS_STATE_KEY, JSON.stringify(v))
  } catch {
    // The in-memory state still works for this mount.
  }
}

// Survival at a given pick, defaulting missing entries to 0 (gone in every
// run) rather than undefined -- every consumer below (the row filter, the
// sort key, the strip, the verdict) needs the same fallback, so it lives in
// one place instead of five `?? 0`s that could drift.
// "ADP 12" or "ADP 12–15", from the tier's own members.
function adpSpan(adps: number[]): string {
  const lo = Math.round(Math.min(...adps))
  const hi = Math.round(Math.max(...adps))
  return lo === hi ? `ADP ${lo}` : `ADP ${lo}–${hi}`
}

const NO_PICKED: Set<number> = new Set()

function survivalAt(row: AvailabilityRow | null, pick: number): number {
  return row?.survivalByPick[String(pick)] ?? 0
}

type ListRow = { player: PlayerRef; row: AvailabilityRow | null; taken: boolean }

function verdict(survivalAtNextPick: number): { label: string; cls: 'risk' | 'even' | 'safe' } {
  if (survivalAtNextPick < RISK_MAX) return { label: 'Act now', cls: 'risk' }
  if (survivalAtNextPick >= SAFE_MIN) return { label: 'Safe', cls: 'safe' }
  return { label: 'Coin flip', cls: 'even' }
}

/**
 * The headline output. For each player, the probability he is still on the
 * board when each of your picks comes up.
 *
 * Renders as the player-list region of DraftRoomLayout (`.avail-region`), under
 * the board and its divider; it scrolls inside itself. It used to float over the
 * board as a collapsible sheet and covered the deep rounds; spec 024 US1 put it
 * in its own region instead, so there is no collapsed state any more.
 *
 * Only your first few picks are shown by default -- past about four picks out
 * the numbers are compounding a lot of model uncertainty and are worth much
 * less than they look.
 */
export default function AvailabilityPanel({
  availability,
  players,
  noAvailabilityReason,
  recentPicks,
  myPicks,
  teams,
  pickedPlayerIds = NO_PICKED,
  started,
  sport,
  openSlots,
  sleeperLeagueId,
  statsPool,
  statsPoolError,
  statsPoolLoading,
  draftedPlayers,
  targetIds,
  onAddTarget,
  onRemoveTarget,
}: Props) {
  const POSITIONS = useMemo(() => filterPositions(sport), [sport])
  // Undefined openSlots means "don't tag", not "nothing is open" -- an empty
  // set is the legitimate reading for a team whose starters are all filled,
  // and the two must not collapse into the same render.
  const need = (position: string) => (openSlots ? needLabel(sport, position, openSlots) : null)
  const [saved] = useState(readStatsState)
  const [filter, setFilter] = useState<string>(() => (POSITIONS.includes(saved.filter) ? saved.filter : 'ALL'))
  const [depth, setDepth] = useState(4)
  // myPicks now shrinks as reactive resimulation locks in each of your picks
  // (DraftView passes only undecided ones), so `depth`'s own state can end up
  // larger than the range input's current max -- clamp what's actually shown/
  // sliced to the live max rather than the raw depth state, or the slider's
  // value/max invert (browsers clamp display silently, `depth` itself would
  // never visibly catch up without this).
  // Capped at 6, not 8: this is a row of chips now rather than a slider, so
  // every extra step is visible chrome, and past ~4 picks out the numbers are
  // compounding enough model uncertainty to be worth less than they look
  // (the panel's own doc comment above). Six columns is also about what the
  // table has room for before the names start clipping.
  // Search and "Hide drafted" apply to both views (spec 024 FR-009, FR-010). Hide is on by
  // default: the list's job is who is still there.
  const [search, setSearch] = useState('')
  const [hideDrafted, setHideDrafted] = useState(true)
  const starred = targetIds
  const starredRef = useRef(starred)
  starredRef.current = starred
  const toggleTarget = useCallback(
    (p: PlayerRef) => {
      if (starredRef.current?.has(p.sleeperId)) onRemoveTarget?.(p.sleeperId)
      else onAddTarget?.(p)
    },
    [onAddTarget, onRemoveTarget],
  )
  const canStar = onAddTarget != null && onRemoveTarget != null
  const maxDepth = Math.max(1, Math.min(6, myPicks.length))
  const shownDepth = Math.min(depth, maxDepth)

  // myPicks belongs in the dependency list: it feeds `picks`, which the filter
  // below reads. It only changes alongside `availability` today, so the stale
  // value was never observable -- but that is a coincidence of the call site,
  // not a property of this component.
  const picks = useMemo(() => myPicks.slice(0, shownDepth), [myPicks, shownDepth])
  // Survival numbers exist only when there are curves AND nothing says to hold
  // them back. Everything survival-shaped below keys off this one flag.
  const showSurvival = availability != null && !noAvailabilityReason
  const rows = useMemo(() => {
    const source: ListRow[] = availability
      ? availability.map((row) => ({ player: row.player, row, taken: pickedPlayerIds.has(row.player.id) }))
      : (players ?? []).map((player) => ({ player, row: null, taken: pickedPlayerIds.has(player.id) }))
    // Drafted players the source doesn't carry, only when the toggle asks for them.
    let merged = source
    if (!hideDrafted && draftedPlayers?.length) {
      const have = new Set(source.map((r) => r.player.id))
      const extra: ListRow[] = draftedPlayers.filter((p) => !have.has(p.id)).map((player) => ({ player, row: null, taken: true }))
      if (extra.length) merged = [...source, ...extra].sort((x, y) => x.player.adp - y.player.adp)
    }
    let live = 0
    const candidates: ListRow[] = []
    for (const r of merged) {
      if (filter !== 'ALL' && r.player.position !== filter) continue
      if (!matchesSearch(search, r.player.name)) continue
      if (r.taken) {
        if (hideDrafted) continue
        candidates.push(r) // a taken player has no survival to filter on
        continue
      }
      if (showSurvival && !picks.some((p) => survivalAt(r.row, p) > 0.01)) continue
      // Board rank still caps *which* players are worth showing at all --
      // top 60 by consensus is a reasonable "in range" pool. Taken rows don't count toward it.
      if (live >= 60) continue
      live++
      candidates.push(r)
    }
    return candidates
  }, [availability, players, filter, picks, pickedPlayerIds, showSurvival, search, hideDrafted, draftedPlayers])

  // Tiers group by consensus ADP (tiers.ts). Inside a tier the sheet's old
  // job survives: when there are survival numbers, the player you are most at
  // risk of losing at your very next pick comes first.
  const tiers = useMemo(() => {
    const t = tierPlayers(rows, (r) => r.player.adp)
    if (!showSurvival || picks.length === 0) return t
    const nextPick = picks[0]
    return t.map((tier) => ({
      ...tier,
      players: [...tier.players].sort((a, b) => survivalAt(a.row, nextPick) - survivalAt(b.row, nextPick)),
    }))
  }, [rows, showSurvival, picks])

  // --- Stats view (spec 023, NBA only) ---------------------------------------------------
  // All view state lives here, not in DraftStatsTable, so a landed pick (which re-renders this
  // panel with a new pickedPlayerIds) changes the rows and nothing else (FR-004).
  const statsOffered = sport === 'nba'
  const statsReady = statsOffered && sleeperLeagueId != null
  // Remembered for the browser session: the mock room mounts this sheet only on your turn, so
  // without it every turn would open back on Tiers (and so would a live-room reload).
  const [view, setViewState] = useState<'tiers' | 'stats'>(readViewPref)
  const setView = useCallback((v: 'tiers' | 'stats') => {
    setViewState(v)
    writeViewPref(v)
  }, [])
  // The chosen columns are the member's, kept per device (statChoice); a stored choice without
  // FP/G starts sorted by name rather than by a column that isn't there.
  const [statIds, setStatIds] = useState<string[]>(readStatChoice)
  const [statSort, setStatSort] = useState<SortState>(() => sortAfterColumns(saved.sort, statIds))
  const [pickerOpen, setPickerOpen] = useState(false)
  const [statWindow, setStatWindow] = useState<PlayerWindowKind>(saved.window)
  const [statMode, setStatMode] = useState<StatMode>(saved.mode)
  const [likelyOnly, setLikelyOnly] = useState(saved.likelyOnly)
  useEffect(() => {
    writeStatsState({ sort: statSort, window: statWindow, mode: statMode, filter, likelyOnly })
  }, [statSort, statWindow, statMode, filter, likelyOnly])
  const inStats = statsReady && view === 'stats'

  // The leaderboard comes from a module-level promise cache (draftStatsCache), one request per
  // (league, window) for the whole session: toggling Tiers/Stats, a mock room's remount between
  // turns and switching back to a window all reuse it. This effect subscribes to the CURRENT key
  // only; a response for a key that is no longer current is dropped (code review S1), so a slow
  // first window can't overwrite a faster second one.
  const statsKey = statsReady ? statsCacheKey(sleeperLeagueId, statWindow) : null
  const [stats, setStats] = useState<{ key: string; board: StatLeaderboard | null } | null>(null)
  useEffect(() => {
    if (!inStats || !statsKey || !sleeperLeagueId) return
    let current = true
    fetchDraftStats(sleeperLeagueId, statWindow).then(
      (board) => current && setStats({ key: statsKey, board }),
      () => current && setStats({ key: statsKey, board: null }),
    )
    return () => {
      current = false
    }
  }, [inStats, statsKey, sleeperLeagueId, statWindow])
  const statsNow = stats && stats.key === statsKey ? stats : null
  const statsBoard = statsNow?.board && statsNow.board.available && !statsNow.board.reason ? statsNow.board : null

  // A primitive, so a parent handing down a fresh-but-equal `myPicks` array doesn't rebuild the rows.
  const nextMyPick = myPicks[0]
  const statRows = useMemo(() => {
    if (!statsBoard) return []
    // Survival comes from availability when the player is tracked there; an untracked player
    // (never surfaced in the simulation's snapshots) has none, and the filter excludes him.
    const survival = new Map<number, number>()
    if (showSurvival && nextMyPick != null && availability) {
      // A taken player has no "chance he's still there": no number for him, even a stale one.
      for (const r of availability) {
        if (!pickedPlayerIds.has(r.player.id)) survival.set(r.player.id, survivalAt(r, nextMyPick))
      }
    }
    // While the room's own pool is loading, show nothing rather than the simulation's subset.
    const universe = statsPool ?? (statsPoolLoading ? [] : (players ?? (availability ?? []).map((r) => r.player)))
    const withDrafted =
      !hideDrafted && draftedPlayers?.length
        ? [...universe, ...draftedPlayers.filter((d) => !universe.some((u) => u.id === d.id))]
        : universe
    const pool = withDrafted.filter(
      (p) =>
        (!hideDrafted || !pickedPlayerIds.has(p.id)) &&
        (filter === 'ALL' || p.position === filter) &&
        matchesSearch(search, p.name),
    )
    const joined = joinPoolStats(
      pool,
      statsBoard.rows,
      (p) => survival.get(p.id) ?? null,
      (p) => (openSlots ? needLabel(sport, p.position, openSlots) : null),
    )
    return sortDraftRows(filterLikely(joined, likelyOnly), statSort, statMode)
  }, [statsBoard, statsPool, statsPoolLoading, players, availability, pickedPlayerIds, filter, showSurvival, nextMyPick, openSlots, sport, likelyOnly, statSort, statMode, hideDrafted, draftedPlayers, search])
  const statColumns = useMemo(() => statIds.map((id) => COLUMNS[id]).filter(Boolean), [statIds])
  // Callbacks and the label below are stable across renders so the memoized table skips the
  // live room's once-a-second re-render (SC-006).
  // A ref, not the render's `statIds`: two clicks landing before React re-renders (a quick
  // double-tap, or ten removes fired back to back as measured in live verification) each read the
  // same stale list, and the last one silently undid the others. An updater resolves against the
  // latest list instead.
  const statIdsRef = useRef(statIds)
  const chooseStats = useCallback((next: string[] | ((cur: string[]) => string[])) => {
    const ids = typeof next === 'function' ? next(statIdsRef.current) : next
    statIdsRef.current = ids
    setStatIds(ids)
    writeStatChoice(ids)
    setStatSort((s) => sortAfterColumns(s, ids))
  }, [])
  const onStatSort = useCallback((id: string) => setStatSort((s) => toggleSort(s, id)), [])
  const onResetColumns = useCallback(() => chooseStats(resetStatChoice()), [chooseStats])
  const nextPickLabel = showSurvival && myPicks.length > 0 ? roundPickLabel(myPicks[0], teams) : null
  const likelyUnavailableReason = showSurvival
    ? myPicks.length === 0
      ? // No projection yet is not "no picks left": every pick is still ahead (code review S3).
        started
        ? 'You have no picks left.'
        : 'Survival appears once the projection is ready.'
      : null
    : (noAvailabilityReason ?? 'Mock drafts don’t project who’ll be there.')

  const run = useMemo(
    () => (recentPicks ? positionRun(recentPicks, 6, 4, sport) : null),
    [recentPicks, sport],
  )

  // Early in a draft, a column for your third or fourth pick out is ~0% for
  // nearly every row shown -- everyone still in range of the board is
  // expected to be long gone by then, so the column carries no information,
  // just width. Trim trailing pick-columns where no currently-*displayed*
  // row clears the same rounding floor the percentages themselves use
  // (anything under 0.5% already rounds to "0%" on screen) -- trailing only,
  // because survival only falls as picks get further away, so a later column
  // is never the one worth keeping if an earlier one wasn't. Recomputed off
  // `rows`, not `availability`, so switching the position filter to a
  // thinner position can un-trim a column that a fuller list had dropped.
  const visiblePicks = useMemo(() => {
    if (rows.length === 0 || !showSurvival) return picks
    let end = picks.length
    while (end > 1 && rows.every((r) => survivalAt(r.row, picks[end - 1]) < 0.005)) end--
    return picks.slice(0, end)
  }, [picks, rows, showSurvival])

  return (
    <section className="panel avail-region">
      <header className="panel-head">
        <h2>{showSurvival ? "Who's still there when you pick" : 'Best available'}</h2>
        {!inStats && noAvailabilityReason && <InfoMark text={noAvailabilityReason} />}
        <div className="controls-inline">
          {statsOffered && (
            <div className="ds-switch">
              <div className="segmented sm" role="group" aria-label="Player list view">
                <button type="button" className={`segment${!inStats ? ' on' : ''}`} aria-pressed={!inStats} onClick={() => setView('tiers')}>
                  Tiers
                </button>
                <button
                  type="button"
                  className={`segment${inStats ? ' on' : ''}`}
                  aria-pressed={inStats}
                  disabled={!statsReady}
                  onClick={() => setView('stats')}
                >
                  Stats
                </button>
              </div>
              {/* Two different absences: undefined is an older backend that doesn't send the
                  field (contract C1); null is a room with no league, i.e. a mock started
                  without one (research R9). */}
              {!statsReady && (
                <InfoMark
                  text={
                    sleeperLeagueId === null
                      ? 'Stats need a league: start the mock from a league to see them.'
                      : 'Stats aren’t available on this server yet.'
                  }
                />
              )}
            </div>
          )}
          <input
            type="search"
            className="avail-search"
            placeholder="Search players"
            aria-label="Search players"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <label className="avail-hide" title="Off: drafted players stay in the list, marked taken">
            <input type="checkbox" checked={hideDrafted} onChange={(e) => setHideDrafted(e.target.checked)} />
            Hide drafted
          </label>
          {POSITIONS.map((p) => (
              <button
                key={p}
                // Position chips carry the same six colors as the board cells
                // and the `.pos` badges, so the filter row reads as part of the
                // board it is lying on. "ALL" has no position, so it stays
                // neutral.
                className={`chip${p === 'ALL' ? '' : ` pos-chip ${p}`}${filter === p ? ' on' : ''}`}
                onClick={() => setFilter(p)}
              >
                {p}
              </button>
            ))}
          {/* A labelled set of chips, not a range input. The slider was the
              one control in the app rendering in the browser's default blue
              -- `input[type=range]` has no style here -- and it paired a
              tuning knob with a loose integer where the question is just "how
              many of my picks ahead". Same values, same clamping. */}
          {showSurvival && !inStats && (
            <span className="depth">
              {myPicks.length === 0 ? (
                <span className="muted">No picks left</span>
              ) : (
                <>
                  <span className="depth-label">Next</span>
                  {Array.from({ length: maxDepth }, (_, i) => i + 1).map((n) => (
                    <button
                      key={n}
                      className={`chip depth-chip${n === shownDepth ? ' on' : ''}`}
                      onClick={() => setDepth(n)}
                      aria-pressed={n === shownDepth}
                      title={`Show the next ${n} of your picks`}
                    >
                      {n}
                    </button>
                  ))}
                </>
              )}
            </span>
          )}
        </div>
      </header>

      <div className="avail-scroll panel-body">
        {run && (
          <p className="avail-run" role="note">
            <strong>{run.position} run:</strong> {run.count} of the last {run.window} picks
          </p>
        )}
        {inStats && (
          <div className="ds-pickers">
            <Segmented label="Stats window" value={statWindow} options={STAT_WINDOWS} onChange={setStatWindow} />
            <Segmented label="Counting stats" value={statMode} options={STAT_MODES} onChange={setStatMode} />
            <button type="button" className="league-link" onClick={() => setPickerOpen(true)}>Choose stats</button>
          </div>
        )}
        {inStats && pickerOpen && (
          <StatPickerModal columns={statIds} onChange={chooseStats} onClose={() => setPickerOpen(false)} />
        )}
        {inStats && statsPoolError && <p className="muted small">{statsPoolError}</p>}
        {inStats && !statsPoolError && statsPoolLoading && !statsPool && (
          <p className="muted small" role="status">Loading the player list…</p>
        )}
        {inStats && !statsPoolError && !statsNow && <p className="muted small" role="status">Loading stats…</p>}
        {inStats && !statsPoolError && statsNow && !statsNow.board && <p className="muted small">Couldn’t load stats.</p>}
        {inStats && !statsPoolError && statsNow?.board && !statsBoard && (
          <p className="muted small">
            {statsNow.board.reason ? reasonSentence(statsNow.board.reason) : 'Player stats aren’t available.'}
          </p>
        )}
        {inStats && !statsPoolError && !(statsPoolLoading && !statsPool) && statsBoard && sleeperLeagueId && (
          <DraftStatsTable
            rows={statRows}
            columns={statColumns}
            board={statsBoard}
            sleeperLeagueId={sleeperLeagueId}
            mode={statMode}
            sort={statSort}
            onSort={onStatSort}
            likelyOnly={likelyOnly}
            onLikelyOnly={setLikelyOnly}
            nextPickLabel={nextPickLabel}
            likelyUnavailableReason={likelyUnavailableReason}
            onResetColumns={onResetColumns}
            takenIds={pickedPlayerIds}
            targetIds={canStar ? starred : undefined}
            onToggleTarget={canStar ? toggleTarget : undefined}
          />
        )}
        {!inStats && (
          <table className="avail">
            <thead>
              <tr>
                <th className="player-col">Player</th>
                <th>Board</th>
                {/* One header for the whole decay curve, not one per pick --
                    the individual pick labels ("2.03", "2.11", ...) that used
                    to head their own column move onto each strip cell's own
                    `title` instead. The header's own title lists them all, for
                    anyone who wants the full run without hovering cell by
                    cell. */}
                {showSurvival && (
                  <th
                    className="strip-col"
                    title={visiblePicks.map((p) => roundPickLabel(p, teams)).join(' · ')}
                  >
                    Next picks
                  </th>
                )}
                {showSurvival && <th className="verdict-col">Verdict</th>}
              </tr>
            </thead>
            {tiers.map((tier) => (
              <tbody key={tier.label}>
                <tr className="tier-head">
                  <th colSpan={showSurvival ? 4 : 2} scope="colgroup">
                    {tier.label}
                    {tier.tier != null && <span className="tier-range"> {adpSpan(tier.players.map((r) => r.player.adp))}</span>}
                  </th>
                </tr>
                {tier.players.map((r) => {
                  // "Next" always means the user's very next pick (picks[0]),
                  // never the last *visible* column -- trimming trailing zero
                  // columns changes what's drawn, not what "next" means, and a
                  // verdict that silently repointed itself when a column
                  // dropped would be a worse bug than the dead width it fixes.
                  // A taken row has neither a strip nor a verdict -- just the "taken" tag.
                  const v = showSurvival && !r.taken ? verdict(picks.length > 0 ? survivalAt(r.row, picks[0]) : 0) : null
                  return (
                    <tr key={r.player.id} className={r.taken ? 'taken' : undefined}>
                      <td className="player-col">
                        {/* One line: the name gives way (ellipsis, full name in the title) before the
                            tags wrap. Without this wrapper the cell wrapped to 35-55px rows. */}
                        <div className="pc">
                          {canStar && (
                            <StarButton
                              player={r.player}
                              on={starred?.has(r.player.sleeperId) ?? false}
                              hidden={r.taken}
                              onToggle={toggleTarget}
                            />
                          )}
                          <span className={`pos ${r.player.position}`}>{posRank(r.player)}</span>
                          <PlayerFace
                            sport={sport}
                            sleeperId={r.player.sleeperId}
                            team={r.player.team}
                            position={r.player.position}
                            name={r.player.name}
                            size={20}
                          />
                          <span className="pc-name" title={r.player.name}>{r.player.name}</span>
                          <span className="team">{r.player.team}</span>
                          {r.taken && <span className="taken-tag">taken</span>}
                          {!r.taken && need(r.player.position) && <span className="need-tag">{need(r.player.position)}</span>}
                        </div>
                      </td>
                      <td className="num">{Math.round(r.player.adp)}</td>
                      {showSurvival && r.taken && <td className="strip-cell" />}
                      {showSurvival && !r.taken && (
                        <td className="strip-cell">
                          <div className="survival-strip">
                            {visiblePicks.map((p) => {
                              const pv = survivalAt(r.row, p)
                              const pct = Math.round(pv * 100)
                              return (
                                <span
                                  key={p}
                                  className="survival-block"
                                  // Teal mixed into the panel color by survival --
                                  // full teal at 100%, fading to plain --panel as
                                  // a player's odds of still being there drop to
                                  // zero. Reusing --teal (generic interaction, per
                                  // the house style) rather than inventing a risk
                                  // hue: this is a decay reading, not an identity
                                  // one, and --crimson is reserved for "you"
                                  // alone. A near-zero cell still reads as a tile
                                  // rather than a gap because `.survival-block`
                                  // carries its own hairline border -- the fill is
                                  // the only thing that goes to nothing.
                                  style={{ background: `color-mix(in oklch, var(--teal) ${Math.round(pv * 92)}%, var(--panel))` }}
                                  title={`${roundPickLabel(p, teams)}: ${pct}% likely still there`}
                                />
                              )
                            })}
                          </div>
                        </td>
                      )}
                      {v && <td className={`verdict verdict-${v.cls}`}>{v.label}</td>}
                      {showSurvival && r.taken && <td className="verdict" />}
                    </tr>
                  )
                })}
              </tbody>
            ))}
          </table>
        )}
        {!inStats && rows.length === 0 && (
          <p className="muted">
            {!started
              ? 'Your realistic options show up here once the draft starts.'
              : showSurvival
                ? 'No players survive to these picks in any run.'
                : 'No players to list.'}
          </p>
        )}
      </div>
    </section>
  )
}
