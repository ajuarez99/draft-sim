import { useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useLocation, useParams, useSearchParams } from 'react-router-dom'
import NotFound from '../components/NotFound'
import PageHeader from '../components/PageHeader'
import PlayerLink from '../components/PlayerLink'
import RankedBars from '../components/RankedBars'
import SpreadStrip, { type SpreadGroup } from '../components/SpreadStrip'
import ChangeDumbbell, { type ChangeRow } from '../components/ChangeDumbbell'
import ContributionBars, { type BreakdownRow } from '../components/ContributionBars'
import StatScatter, { type ScatterPoint } from '../components/StatScatter'
import { FilterBar, MODES, Segmented, SeasonPicker, StatsPagesNav, WINDOWS, PlayerCell } from '../components/statControls'
import { getStatLeaderboard, type PlayerWindowKind, type LeaderboardRow, type StatLeaderboard as Board } from '../api'
import { Cell, DASH } from '../statCells'
import { AXIS_FORMULA, readCalcState, stripRoster, writeCalcState, clampTop, NAME_MAX, TOP_MAX, TOP_MIN, type CalcState, type ChartKind, type CompareKind } from '../statCalcUrl'
import { PRESETS, type Preset } from '../statCalcPresets'
import { readSaved, removeSaved, renameSaved, saveFormula, type SavedEntry } from '../statCalcSaved'
import {
  additiveTerms,
  canonical,
  chartAvailability,
  contributions,
  evaluate,
  formatResult,
  inferUnits,
  inputsOf,
  isPerGameInput,
  parse,
  rankResults,
  readable,
  readablePieces,
  reasonText,
  spreadNotes,
  standardize,
  tokenOf,
  unitLabel,
  validate,
  zeroDenominatorWarning,
  type Ast,
  type ParseError,
} from '../statFormula'
import {
  COLUMNS,
  FORMULA_INPUTS,
  FORMULA_TAG,
  PICKABLE,
  applyFilters,
  boardContext,
  compareRows,
  defaultDir,
  formatValue,
  tieBreak,
  type SortState,
  type StatMode,
} from '../statLeaderboard'
import { fallbackNote, longDate, qualificationRule, reasonSentence, seasonLabel, shownSeason } from '../statCopy'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { useBlock } from '../useBlock'

/**
 * specs/032-stat-calculator US1: type a formula over the leaderboard's stat columns, see a ranked
 * bar chart and the table behind it.
 *
 * Everything the chart depends on that is not data lives in the URL (contract C2); the data is the
 * leaderboard payload, fetched per season and window only. Changing the mode, the formula or any
 * filter re-evaluates in the browser and makes no request (SC-006).
 *
 * Data reloads (a window change) must not cost the member their typing or the last result (R16,
 * F4): the page holds the last good payload and the last good formula, and gates the WHOLE page
 * only on the payload's `reason`, so football and not-configured boards get no partial layout.
 *
 * US2 presets, US3 copy-link and the browser-local saved list, and US4 the scatter view (chart=scatter
 * in the URL) sit on the same state: a preset, a saved formula and a shared link all arrive as URL params.
 */

type Active = { ast: Ast; z: boolean }
type ShownError = { errors: ParseError[]; text: string }

const MODE_PHRASE: Record<StatMode, string> = { perGame: 'per game', totals: 'totals', per36: 'per 36' }
const WINDOW_PHRASE = { SEASON: 'full season', LAST_10: 'last 10 games', LAST_5: 'last 5 games' } as const
const TITLE_MAX = 60
/** A window as the Change chart names it (specs/032 US5). */
const WINDOW_LABEL: Record<PlayerWindowKind, string> = { SEASON: 'Full season', LAST_10: 'Last 10', LAST_5: 'Last 5' }
/** How recent a view is: a later season beats an earlier one, then a shorter trailing window beats a longer one (US5 review, C1). */
const WINDOW_RECENCY: Record<PlayerWindowKind, number> = { SEASON: 0, LAST_10: 1, LAST_5: 2 }
const viewRecency = (b: Board): number => b.season * 10 + WINDOW_RECENCY[b.window]
/** Position order for the Spread split (basketball's guard-to-centre line); anything else follows alphabetically. */
const POS_ORDER = ['PG', 'SG', 'SF', 'PF', 'C']
const AXIS_MAX = 36
/** The scatter's axes when the link names none (hand-set): usage against true shooting, or the formula. */
const DEFAULT_X = 'usg'
const DEFAULT_Y = 'ts'

const truncate = (s: string, max: number) => (s.length > max ? `${s.slice(0, max - 1)}…` : s)

/** Tags printed after an input: "fantasy" for VOR (FR-013), "per game" where the mode does not change it (F11). */
function tagsOf(id: string, mode: StatMode): string | null {
  const t = [FORMULA_TAG[id], isPerGameInput(id, mode) ? 'per game' : null].filter(Boolean)
  return t.length ? t.join(', ') : null
}

/** The formula under its error, with carets under the offending span. */
function Caret({ text, err }: { text: string; err: ParseError }) {
  const width = Math.max(1, err.end - err.start)
  return (
    <pre className="sc-caret" aria-hidden="true">
      {text}
      {'\n'}
      {' '.repeat(err.start)}
      {'^'.repeat(width)}
    </pre>
  )
}

/** A scatter axis: its label with unit, and the value for a row (null = missing). */
type Axis = { key: string; label: string; format: (v: number) => string; value: (r: LeaderboardRow) => number | null }

/** The unit of a built-in stat, in words, from the same inference the formula warnings use. */
function statUnit(id: string, mode: StatMode): string | null {
  const r = parse(tokenOf(id))
  return r.ok ? unitLabel(r.ast, mode) : null
}

/** A preset's expression as the page prints formulas ("(USG + VOR) ÷ TO"). */
function readableOf(expr: string): string {
  const r = parse(expr)
  return r.ok ? readable(r.ast) : expr
}

/** The stat picker shared by the scatter's two axes. */
function AxisSelect({ label, value, onChange, hasFormula }: { label: string; value: string; onChange: (v: string) => void; hasFormula: boolean }) {
  return (
    <label className="sl-field">
      <span className="muted small">{label}</span>
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        {/* specs/032 code review B10: when the axis is the formula and there is none, say so instead of showing GP. */}
        {hasFormula && <option value={AXIS_FORMULA}>Your formula</option>}
        {!hasFormula && value === AXIS_FORMULA && <option value={AXIS_FORMULA} disabled>Your formula (none yet)</option>}
        {PICKABLE.map((g) => (
          <optgroup key={g.id} label={g.label}>
            {g.columns.filter((c) => FORMULA_INPUTS.includes(c)).map((c) => (
              <option key={c.id} value={c.id}>{`${tokenOf(c.id)} — ${c.title}`}</option>
            ))}
          </optgroup>
        ))}
      </select>
    </label>
  )
}

function CalcHeader({
  id, label, title, sort, onSort, pin,
}: { id: string; label: string; title: string; sort: SortState; onSort: (id: string) => void; pin?: boolean }) {
  const on = sort.col === id
  return (
    <th scope="col" aria-sort={on ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'} className={pin ? 'sl-pin' : undefined}>
      <button type="button" className={`sl-sort${on ? ' on' : ''}`} title={title} onClick={() => onSort(id)}>
        {label}
        <span aria-hidden="true" className="sl-arrow">{on ? (sort.dir === 'asc' ? '▲' : '▼') : ''}</span>
      </button>
    </th>
  )
}

export default function StatCalculator() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const leagueId = sleeperLeagueId ?? ''
  const version = useLeagueDataVersion(sleeperLeagueId)
  const [params, setParams] = useSearchParams()
  const location = useLocation()

  // The window picks the request, so it is read before the rows exist; the filters need the rows.
  const win = readCalcState(params, null).state.w
  const block = useBlock(leagueId ? () => getStatLeaderboard(leagueId, win) : null, [leagueId, win, version])
  const [lastOk, setLastOk] = useState<Board | null>(null)
  useEffect(() => {
    if (block.status === 'ok') setLastOk(block.data)
  }, [block])
  const d = block.status === 'ok' ? block.data : lastOk
  const updating = block.status === 'loading' && lastOk != null

  // Memoised on the query string and the payload, so typing in the editor does not re-run the ranking.
  const { state, dropped } = useMemo(() => readCalcState(params, d?.rows ?? null), [params, d])
  /** The `f` this page last wrote to the URL; a different `f` in the URL came from outside (specs/032 code review B2). */
  const lastWrittenF = useRef(state.f)
  const update = (patch: Partial<CalcState>) => {
    if (patch.f !== undefined) lastWrittenF.current = patch.f
    setParams(writeCalcState({ ...state, ...patch }), { replace: true })
  }

  // --- the formula ---------------------------------------------------------------------------
  const [text, setText] = useState(state.f)
  const [runError, setRunError] = useState<ShownError | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const parsed = useMemo(() => (state.f.trim() === '' ? null : parse(state.f)), [state.f])
  const linkErrors = useMemo(
    () => (parsed ? (parsed.ok ? validate(parsed.ast, { standardize: state.z }) : parsed.errors) : []),
    [parsed, state.z],
  )
  const active = useMemo<Active | null>(
    () => (parsed && parsed.ok && linkErrors.length === 0 ? { ast: parsed.ast, z: state.z } : null),
    [parsed, linkErrors, state.z],
  )
  const [lastGood, setLastGood] = useState<Active | null>(null)
  // specs/032 code review B2: the URL is authoritative. When `f` changes from outside (the Calculator nav link
  // clears it, a pasted link replaces it), the editor and the held "previous formula" follow it.
  useEffect(() => {
    if (state.f === lastWrittenF.current) return
    lastWrittenF.current = state.f
    setText(state.f)
    setRunError(null)
    setLastGood(null)
  }, [state.f])
  useEffect(() => {
    if (active) setLastGood(active)
  }, [active])
  const display = active ?? lastGood
  const shownError: ShownError | null = runError ?? (linkErrors.length > 0 ? { errors: linkErrors, text: state.f } : null)

  const run = (e?: FormEvent) => {
    e?.preventDefault()
    const r = parse(text)
    if (!r.ok) return setRunError({ errors: r.errors, text })
    const problems = validate(r.ast, { standardize: state.z })
    if (problems.length > 0) return setRunError({ errors: problems, text })
    setRunError(null)
    setPresetNote(null)
    const f = canonical(r.ast)
    // Editing a loaded preset's expression drops its label (US2 scenario 3); the name is the member's again.
    const dropLabel = loadedPreset != null && f !== loadedPreset.expr
    update({ f, ...(dropLabel ? { n: '' } : {}) })
  }

  // --- presets (US2) ---------------------------------------------------------------------------
  const [showPresets, setShowPresets] = useState(false)
  /** The preset the link currently holds: its name and its exact expression. Anything else is the member's own. */
  const loadedPreset: Preset | null = PRESETS.find((p) => p.name === state.n && p.expr === state.f) ?? null
  const [presetNote, setPresetNote] = useState<string | null>(null)
  const loadPreset = (p: Preset) => {
    const r = parse(p.expr)
    // A preset is valid with standardize off; with it on, a divide or multiply by a stat is blocked (F2, B1), so it goes off.
    const z = r.ok && validate(r.ast, { standardize: state.z }).length === 0 ? state.z : false
    setPresetNote(state.z && !z ? `“Standardize inputs” was turned off for ${p.name}: it multiplies or divides by a stat, which isn’t meaningful on standardized values.` : null)
    setText(p.expr)
    setRunError(null)
    setShowPresets(false)
    update({ f: p.expr, n: p.name, m: p.mode, z })
  }

  // --- copy link (US3) -------------------------------------------------------------------------
  const shareUrl = `${window.location.origin}${location.pathname}${location.search}`
  const [copied, setCopied] = useState<'idle' | 'done' | 'fallback'>('idle')
  const copyLink = async () => {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('no clipboard')
      await navigator.clipboard.writeText(shareUrl)
      setCopied('done')
    } catch {
      setCopied('fallback')
    }
  }
  // Any change to the link makes a "copied" notice stale.
  useEffect(() => setCopied('idle'), [location.search])

  // --- saved formulas (US3): this browser only ---------------------------------------------------
  const [saved, setSaved] = useState<SavedEntry[]>(() => readSaved())
  const [saveNote, setSaveNote] = useState<string | null>(null)
  const [saveName, setSaveName] = useState<string | null>(null)
  const [renaming, setRenaming] = useState<{ id: string; name: string } | null>(null)
  const settled = (r: { saved: boolean; list: SavedEntry[]; notice: string | null }) => {
    setSaved(r.list)
    setSaveNote(
      r.saved
        ? r.notice
        : 'Couldn’t save to this browser (storage may be blocked, as in a private window). The list works until you leave this page; share links still work.',
    )
  }
  const commitSave = () => {
    if (!active) return
    settled(
      saveFormula(
        {
          name: saveName ?? '',
          expr: canonical(active.ast),
          settings: { window: state.w, mode: state.m, top: state.top, qualifiedOnly: state.q, standardize: active.z, dir: state.dir },
        },
        saved,
      ),
    )
    setSaveName(null)
  }
  const loadSaved = (f: SavedEntry) => {
    setText(f.expr)
    setPresetNote(null)
    // specs/032 code review B1: a saved formula is checked with its own saved settings. If standardize makes it
    // invalid, the error is shown and no chart is, because the link carries both.
    const parsedSaved = parse(f.expr)
    const problems = parsedSaved.ok ? validate(parsedSaved.ast, { standardize: f.settings.standardize }) : []
    setRunError(problems.length > 0 ? { errors: problems, text: f.expr } : null)
    update({
      f: f.expr, n: f.name, w: f.settings.window, m: f.settings.mode, top: f.settings.top,
      q: f.settings.qualifiedOnly, z: f.settings.standardize, dir: f.settings.dir,
    })
  }

  const insertStat = (id: string) => {
    const el = inputRef.current
    const at = el?.selectionStart ?? text.length
    const to = el?.selectionEnd ?? at
    const tok = tokenOf(id)
    const pre = /[A-Za-z0-9%]$/.test(text.slice(0, at)) ? ' ' : ''
    const post = /^[A-Za-z0-9%]/.test(text.slice(to)) ? ' ' : ''
    const ins = `${pre}${tok}${post}`
    setText(text.slice(0, at) + ins + text.slice(to))
    setRunError(null)
    const pos = at + ins.length
    setTimeout(() => {
      el?.focus()
      el?.setSelectionRange(pos, pos)
    }, 0)
  }

  const [topDraft, setTopDraft] = useState(String(state.top))
  useEffect(() => setTopDraft(String(state.top)), [state.top])
  const commitTop = () => {
    const n = Number(topDraft)
    if (topDraft.trim() === '' || !Number.isFinite(n)) return setTopDraft(String(state.top))
    update({ top: clampTop(n) })
  }

  // --- results (browser-side only) -------------------------------------------------------------
  const rows = d?.rows ?? []
  // The players shown: filters, then the qualification switch. `fp` as the sort column makes the switch bite.
  const shown = useMemo(
    () => applyFilters(rows, state.filters, { qualifiedOnly: state.q, sortCol: 'fp' }),
    [rows, state.filters, state.q],
  )
  const inputs = useMemo(() => (display ? inputsOf(display.ast) : []), [display])
  const zmap = useMemo(() => (display?.z ? standardize(shown, inputs, state.m) : undefined), [display, shown, inputs, state.m])
  const ranked = useMemo(
    () => (display ? rankResults(shown, display.ast, state.m, state.dir, zmap) : null),
    [display, shown, state.m, state.dir, zmap],
  )
  const unitWarnings = useMemo(() => (display && !display.z ? inferUnits(display.ast, state.m) : []), [display, state.m])
  const spread = useMemo(() => (display && !display.z ? spreadNotes(display.ast, shown, state.m) : []), [display, shown, state.m])
  const zeroDen = useMemo(() => (display && !display.z ? zeroDenominatorWarning(display.ast, shown, state.m) : null), [display, shown, state.m])

  // --- US5 (FR-019): Spread, Change and Breakdown, each only when it fits --------------------------
  /** The newest earlier season with games, for "previous season" (same window, that season's league id). */
  const prevSeason = useMemo(
    () => (d ? ([...d.seasons].filter((o) => o.season < d.season && o.hasGames).sort((a, b) => b.season - a.season)[0] ?? null) : null),
    [d],
  )
  const availCtx = {
    n: ranked?.included.length ?? 0,
    position: state.filters.position,
    standardize: state.z || display?.z === true,
    mode: state.m,
    hasPrevSeason: prevSeason != null,
    ast: display?.ast ?? null,
  }
  const avail = {
    spread: chartAvailability('spread', availCtx),
    split: chartAvailability('split', availCtx),
    change: chartAvailability('change', availCtx),
    prev: chartAvailability('changePrev', availCtx),
    parts: chartAvailability('parts', availCtx),
  }
  const usable = d != null && d.available && !d.reason
  const wantChange = state.chart === 'change' && avail.change.ok && (state.cmp !== 'PREV' || avail.prev.ok) && usable
  /** The board on screen is the window the link names. While a switch loads, `d` is the held old board (specs/032 US5 review, C4). */
  const mainCurrent = d != null && d.window === state.w
  const changeFits = wantChange && mainCurrent
  const cmpLeague = state.cmp === 'PREV' ? (prevSeason?.sleeperLeagueId ?? null) : leagueId
  const cmpWin: PlayerWindowKind = state.cmp === 'PREV' ? state.w : state.cmp
  // The second request exists only while Change is shown and the main board is current; a mode, filter or ranking change re-uses it (request-free, SC-006).
  const cmpBlock = useBlock(changeFits && cmpLeague ? () => getStatLeaderboard(cmpLeague, cmpWin) : null, [changeFits, cmpLeague, cmpWin, version])
  // Only a comparison board that matches the window named in the chart (and the season, for "previous season") is used;
  // useBlock can hand back the previous board for one render after its inputs change (C4/C9).
  const cmpD =
    cmpBlock.status === 'ok' && d != null && cmpBlock.data.window === cmpWin && (state.cmp === 'PREV' ? cmpBlock.data.season !== d.season : cmpBlock.data.season === d.season)
      ? cmpBlock.data
      : null

  const change = useMemo(() => {
    if (!changeFits || !display || !ranked || !cmpD || !d || !cmpD.available || cmpD.reason) return null
    const ast = display.ast
    // Each board names itself; the previous season's labels carry the season because both sides read "Full season".
    const labelOf = (b: Board, other: Board) =>
      b.season !== other.season ? `${seasonLabel(b.season)} ${WINDOW_LABEL[b.window].toLowerCase()}` : WINDOW_LABEL[b.window]
    const mainLabel = labelOf(d, cmpD)
    const cmpLabel = labelOf(cmpD, d)
    // C1: the change is (more recent view) minus (older view), whichever control picked which.
    const mainIsRecent = viewRecency(d) > viewRecency(cmpD)
    // Filters and the qualification switch apply in both views. Roster ids differ between seasons, so for the
    // previous season the availability filter is applied to the current view only.
    const cmpFilters = state.cmp === 'PREV' ? { ...state.filters, availability: { kind: 'all' as const } } : state.filters
    const cmpShown = new Map(applyFilters(cmpD.rows, cmpFilters, { qualifiedOnly: state.q, sortCol: 'fp' }).map((r) => [r.sleeperPlayerId, r]))
    const inCmpBoard = new Set(cmpD.rows.map((r) => r.sleeperPlayerId))
    const items: { row: LeaderboardRow; nowV: number; thenV: number }[] = []
    const missing = new Map<string, string[]>()
    const miss = (reason: string, r: LeaderboardRow) => missing.set(reason, [...(missing.get(reason) ?? []), r.name ?? 'Unknown player'])
    // C5: the join starts from the players the member is looking at; only they can be "left out".
    for (const a of shown) {
      const b = cmpShown.get(a.sleeperPlayerId)
      if (!b) {
        miss(inCmpBoard.has(a.sleeperPlayerId) ? `Filtered out or not qualified in the ${cmpLabel} view` : `Not in the ${cmpLabel} data`, a)
        continue
      }
      const oa = evaluate(ast, a, state.m)
      const ob = evaluate(ast, b, state.m)
      if (!('value' in oa)) miss(`${mainLabel}: ${reasonText(oa.excluded, a)}`, a)
      else if (!('value' in ob)) miss(`${cmpLabel}: ${reasonText(ob.excluded, b)}`, a)
      else items.push(mainIsRecent ? { row: a, nowV: oa.value, thenV: ob.value } : { row: a, nowV: ob.value, thenV: oa.value })
    }
    const sign = state.chg === 'up' ? -1 : 1
    items.sort((x, y) => {
      const dx = x.nowV - x.thenV
      const dy = y.nowV - y.thenV
      return dx !== dy ? sign * (dx - dy) : tieBreak(x.row, y.row)
    })
    return {
      items,
      missing: [...missing.entries()].sort((p, q) => q[1].length - p[1].length),
      nowLabel: mainIsRecent ? mainLabel : cmpLabel,
      thenLabel: mainIsRecent ? cmpLabel : mainLabel,
    }
  }, [changeFits, display, ranked, cmpD, d, state.cmp, state.filters, state.q, state.m, state.chg, shown])

  const spreadGroups = useMemo<SpreadGroup[] | null>(() => {
    if (state.chart !== 'spread' || !ranked || !avail.spread.ok) return null
    const pts = ranked.included
    const toPoint = (x: (typeof pts)[number]) => ({ id: x.row.sleeperPlayerId, name: x.row.name, value: x.value })
    if (!(state.split && avail.split.ok)) {
      const narrowed = state.filters.position || state.filters.team || state.filters.availability.kind !== 'all'
      return [{ key: 'all', label: narrowed ? 'Players shown' : 'All players', points: pts.map(toPoint) }]
    }
    const positions = [...new Set(pts.flatMap((x) => x.row.positions))].sort((p, q) => {
      const ip = POS_ORDER.indexOf(p)
      const iq = POS_ORDER.indexOf(q)
      return (ip < 0 ? 99 : ip) - (iq < 0 ? 99 : iq) || p.localeCompare(q)
    })
    return positions.map((pos) => ({ key: pos, label: pos, points: pts.filter((x) => x.row.positions.includes(pos)).map(toPoint) }))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.chart, state.split, state.filters, ranked, avail.spread.ok, avail.split.ok])
  const spreadTop = useMemo(() => new Set((ranked?.included ?? []).slice(0, state.top).map((x) => x.row.sleeperPlayerId)), [ranked, state.top])

  const breakdown = useMemo<BreakdownRow[] | null>(() => {
    if (state.chart !== 'parts' || !display || !ranked || !avail.parts.ok) return null
    const t = additiveTerms(display.ast)
    if (!('terms' in t)) return null
    const out: BreakdownRow[] = []
    for (const x of ranked.included.slice(0, state.top)) {
      const c = contributions(t.terms, x.row, state.m, zmap)
      if ('parts' in c) out.push({ id: x.row.sleeperPlayerId, name: x.row.name, parts: c.parts, total: x.value })
    }
    return out
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.chart, display, ranked, state.top, state.m, zmap, avail.parts.ok])

  /** Why each unavailable chart is unavailable, one line per distinct reason (FR-019). */
  const chartReasons = (() => {
    const by = new Map<string, string[]>()
    for (const [label, a] of [['Spread', avail.spread], ['Change', avail.change], ['Breakdown', avail.parts]] as const) {
      if (!a.ok) by.set(a.reason, [...(by.get(a.reason) ?? []), label])
    }
    return [...by.entries()].map(([reason, labels]) => `${labels.length > 1 ? `${labels.slice(0, -1).join(', ')} and ${labels[labels.length - 1]}` : labels[0]}: ${reason}`)
  })()
  /** The selected chart does not fit (a link can carry it): say so instead of drawing it. */
  const selectedWhy =
    state.chart === 'spread' && !avail.spread.ok ? avail.spread.reason
      : state.chart === 'change' && !avail.change.ok ? avail.change.reason
        : state.chart === 'change' && state.cmp === 'PREV' && !avail.prev.ok ? avail.prev.reason
          : state.chart === 'parts' && !avail.parts.ok ? avail.parts.reason
            : null

  // --- scatter (US4, FR-018): each axis is a built-in stat or the current formula --------------------
  const wantX = state.x || DEFAULT_X
  const wantY = state.y || (display ? AXIS_FORMULA : DEFAULT_Y)
  const axisNotes: string[] = []
  const buildAxis = (want: string, side: 'x' | 'y'): Axis | null => {
    const word = side === 'x' ? 'X' : 'Y'
    if (want === AXIS_FORMULA) {
      if (!display) {
        axisNotes.push(`The ${word} axis is the formula, and there isn’t one yet. Type a formula, or pick a stat for this axis.`)
        return null
      }
      const unit = display.z ? 'standardized' : (unitLabel(display.ast, state.m) ?? 'formula result')
      const name = truncate(state.n || readable(display.ast), AXIS_MAX)
      return {
        key: AXIS_FORMULA,
        label: `${name} (${unit})`,
        format: formatResult,
        value: (r) => {
          const o = evaluate(display.ast, r, state.m, zmap)
          return 'value' in o ? o.value : null
        },
      }
    }
    const col = COLUMNS[want]
    if (!col || !FORMULA_INPUTS.includes(col)) {
      axisNotes.push(`The ${word} axis in this link (“${want}”) isn’t a stat here, so the default is used.`)
      return buildAxis(side === 'x' ? DEFAULT_X : DEFAULT_Y, side)
    }
    const unit = statUnit(want, state.m)
    return {
      key: want,
      // specs/032 code review B8: the formula token (FPG, POSRANK), so what is read here can be typed back.
      label: `${tokenOf(want)}${unit ? ` (${unit})` : ''}`,
      format: (v) => formatValue(col, v, state.m),
      value: (r) => {
        const v = col.value(r, state.m)
        return typeof v === 'number' && Number.isFinite(v) ? v : null
      },
    }
  }
  const xAxis = state.chart === 'scatter' ? buildAxis(wantX, 'x') : null
  const yAxis = state.chart === 'scatter' ? buildAxis(wantY, 'y') : null
  const scatter = useMemo(() => {
    if (!xAxis || !yAxis) return null
    const points: ScatterPoint[] = []
    for (const r of shown) {
      const x = xAxis.value(r)
      const y = yAxis.value(r)
      if (x != null && y != null) points.push({ id: r.sleeperPlayerId, name: r.name, x, y })
    }
    return { points, excluded: shown.length - points.length }
    // xAxis/yAxis are rebuilt every render; what they depend on is listed here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shown, wantX, wantY, state.chart, state.m, display, zmap, state.n])

  const [tableSort, setTableSort] = useState<SortState | null>(null)
  const eff: SortState = tableSort ?? { col: 'result', dir: state.dir }
  const onSort = (id: string) => {
    if (eff.col === id) return setTableSort({ col: id, dir: eff.dir === 'asc' ? 'desc' : 'asc' })
    setTableSort({ col: id, dir: id === 'result' ? 'desc' : defaultDir(COLUMNS[id]) })
  }
  const tableRows = useMemo(() => {
    if (!ranked) return []
    if (!tableSort) return ranked.included
    const out = [...ranked.included]
    const sign = tableSort.dir === 'asc' ? 1 : -1
    if (tableSort.col === 'result') out.sort((a, b) => (a.value !== b.value ? sign * (a.value - b.value) : tieBreak(a.row, b.row)))
    else {
      const cmp = compareRows(COLUMNS[tableSort.col], tableSort.dir, state.m)
      out.sort((a, b) => cmp(a.row, b.row))
    }
    return out
  }, [ranked, tableSort, state.m])

  if (block.status === 'error' && block.notFound) return <NotFound what="page" />

  const gated = d && (!d.available || d.reason)
  const ctx = d ? boardContext(d, leagueId) : null
  const season = d ? shownSeason(d) : null
  const windowPhrase = d ? WINDOW_PHRASE[d.window] : ''
  const usesFantasy = inputs.some((id) => PICKABLE.find((g) => g.id === 'fantasy')?.columns.some((c) => c.id === id))

  return (
    <div className="content">
      <PageHeader
        eyebrow={d ? `League · ${seasonLabel(d.season)}` : 'League'}
        title="Stat calculator"
        sub="Combine any of the leaderboard’s stats into a new one and see who leads it."
      />

      {!d && (block.status === 'loading' || block.status === 'idle') && <p className="muted small" role="status">Loading…</p>}
      {!d && block.status === 'error' && <p className="muted small">Couldn’t load the player stats.</p>}

      {gated && (
        <section className="section pp-reason">
          <p className="muted small">{d.reason ? reasonSentence(d.reason) : 'Player stats aren’t available.'}</p>
        </section>
      )}

      {d && !gated && ctx && season && (
        <>
          <StatsPagesNav leagueId={leagueId} current="calculator" />
          <SeasonPicker d={d} leagueId={ctx.shownLeagueId} suffix="/calculator" keepSearch={stripRoster} />
          {d.requestedSeason != null && (
            <p className="small pt-note" role="note">{fallbackNote(seasonLabel(d.requestedSeason), seasonLabel(d.season))}</p>
          )}
          {updating && <p className="muted small" role="status">Updating…</p>}
          {block.status === 'error' && <p className="muted small" role="status">Couldn’t refresh the player stats; showing the last ones loaded.</p>}

          <div className="sl-controls">
            <Segmented label="Window" value={state.w} options={WINDOWS} onChange={(w) => update({ w })} />
            <Segmented label="Counting stats" value={state.m} options={MODES} onChange={(m) => update({ m })} />
            <Segmented
              label="Rank by"
              value={state.dir}
              options={[{ kind: 'desc', label: 'Highest first' }, { kind: 'asc', label: 'Lowest first' }]}
              onChange={(dir) => update({ dir })}
            />
            <Segmented
              label="Chart"
              value={state.chart}
              describedBy="sc-chart-why"
              options={[
                { kind: 'ranked' as ChartKind, label: 'Ranked' },
                { kind: 'spread' as ChartKind, label: 'Spread', disabled: !avail.spread.ok },
                { kind: 'change' as ChartKind, label: 'Change', disabled: !avail.change.ok },
                { kind: 'parts' as ChartKind, label: 'Breakdown', disabled: !avail.parts.ok },
                { kind: 'scatter' as ChartKind, label: 'Scatter' },
              ]}
              onChange={(chart) => update({ chart })}
            />
          </div>
          <div id="sc-chart-why" className="sc-picker-why muted small">
            {chartReasons.map((r) => <p key={r}>{r}</p>)}
          </div>
          {state.chart === 'spread' && (
            <div className="sc-chart-controls">
              <Segmented
                label="Split by position"
                value={state.split ? 'pos' : 'all'}
                describedBy="sc-split-why"
                options={[{ kind: 'all', label: 'All together' }, { kind: 'pos', label: 'By position', disabled: !avail.split.ok }]}
                onChange={(v) => update({ split: v === 'pos' })}
              />
              {!avail.split.ok && <span id="sc-split-why" className="muted small">{avail.split.reason}</span>}
            </div>
          )}
          {state.chart === 'change' && (
            <div className="sc-chart-controls">
              <Segmented
                label="Compare with"
                value={state.cmp}
                describedBy="sc-cmp-why"
                options={([
                  { kind: 'LAST_10', label: 'Last 10' },
                  { kind: 'LAST_5', label: 'Last 5' },
                  { kind: 'SEASON', label: 'Full season' },
                  { kind: 'PREV', label: 'Previous season', disabled: !avail.prev.ok },
                ] as { kind: CompareKind; label: string; disabled?: boolean }[]).filter((o) => o.kind !== state.w)}
                onChange={(cmp) => update({ cmp })}
              />
              <Segmented
                label="Sort changes"
                value={state.chg}
                options={[{ kind: 'up', label: 'Biggest rise' }, { kind: 'down', label: 'Biggest fall' }]}
                onChange={(chg) => update({ chg })}
              />
              {!avail.prev.ok && <span id="sc-cmp-why" className="muted small">{`Previous season: ${avail.prev.reason}`}</span>}
            </div>
          )}

          <form className="sc-editor" onSubmit={run}>
            <label className="sl-field sc-formula-field">
              <span className="muted small">Formula</span>
              <input
                ref={inputRef}
                type="text"
                className="sc-input"
                value={text}
                spellCheck={false}
                autoComplete="off"
                placeholder="(USG + VOR) ÷ TO"
                aria-invalid={shownError ? true : undefined}
                aria-describedby={shownError ? 'sc-formula-error' : undefined}
                onChange={(e) => {
                  setText(e.target.value)
                  setRunError(null)
                }}
              />
            </label>
            <label className="sl-field sc-insert">
              <span className="muted small">Insert a stat</span>
              <select
                value=""
                onChange={(e) => {
                  if (e.target.value) insertStat(e.target.value)
                }}
              >
                <option value="">Pick a stat…</option>
                {PICKABLE.map((g) => (
                  <optgroup key={g.id} label={g.label}>
                    {g.columns.filter((c) => FORMULA_INPUTS.includes(c)).map((c) => (
                      <option key={c.id} value={c.id}>
                        {`${tokenOf(c.id)} — ${c.title}${c.lowerIsBetter && !/is better/.test(c.title) ? ' (lower is better)' : ''}${FORMULA_TAG[c.id] ? ' (fantasy)' : ''}`}
                      </option>
                    ))}
                  </optgroup>
                ))}
              </select>
            </label>
            <label className="sl-field">
              <span className="muted small">Name (optional)</span>
              <input
                type="text"
                className="sc-input"
                value={state.n}
                maxLength={NAME_MAX}
                onChange={(e) => update({ n: e.target.value.slice(0, NAME_MAX) })}
              />
            </label>
            <button type="submit" className="pp-tog sc-run">Run</button>
            <button type="button" className="pp-tog" aria-expanded={showPresets} onClick={() => setShowPresets((v) => !v)}>Presets</button>
            <button type="button" className="pp-tog" onClick={copyLink} disabled={!state.f}>Copy link</button>
          </form>
          {copied === 'done' && <p className="muted small" role="status">Link copied. It restores this formula, season, window, mode and filters.</p>}
          {copied === 'fallback' && (
            <label className="sl-field">
              <span className="muted small">Couldn’t copy automatically. Select this link and copy it:</span>
              <input
                type="text"
                readOnly
                className="sc-input sc-copy-fallback"
                aria-label="Link to copy"
                value={shareUrl}
                onFocus={(e) => e.currentTarget.select()}
                autoFocus
              />
            </label>
          )}

          {(showPresets || (!state.f && text.trim() === '')) && (
            <section className="sc-presets" aria-label="Presets">
              <h3 className="pp-h">Presets</h3>
              <ul className="sc-preset-list">
                {PRESETS.map((p) => (
                  <li key={p.id} className="sc-preset">
                    <p><strong>{p.name}</strong> <span className="muted small">{`${readableOf(p.expr)} · ${MODE_PHRASE[p.mode]}`}</span></p>
                    <p className="muted small">{p.description}</p>
                    <div><button type="button" className="pp-tog" onClick={() => loadPreset(p)} aria-label={`Use the ${p.name} preset`}>Use this</button></div>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {shownError && (
            <div className="sc-error" role="alert" id="sc-formula-error">
              {shownError.errors.map((e, i) => (
                <div key={i}>
                  <p className="small">{e.message}</p>
                  <Caret text={shownError.text} err={e} />
                </div>
              ))}
              {display && <p className="muted small">The result below is the previous formula’s.</p>}
            </div>
          )}

          {display && (
            <p className="sc-readable" aria-label="Formula as read">
              {readablePieces(display.ast).map((p, i) => {
                const tags = p.id ? tagsOf(p.id, state.m) : null
                return (
                  <span key={i}>
                    {p.text}
                    {tags && <span className="sc-tag">{` (${tags})`}</span>}
                  </span>
                )
              })}
              <span className="muted small">{` (${MODE_PHRASE[state.m]})`}</span>
              {display.z && <span className="sc-chip">standardized</span>}
              {shownError && <span className="sc-chip">previous formula</span>}
            </p>
          )}
          {presetNote && <p className="small pt-note" role="note">{presetNote}</p>}
          {loadedPreset && display && <p className="muted small sc-preset-note">{loadedPreset.description}</p>}
          {!display && !shownError && (
            <p className="muted small">Type a formula such as (USG + VOR) ÷ TO, or pick stats from the list. Use + − × ÷ and brackets.</p>
          )}

          <div className="sl-controls">
            <label className="sl-check">
              <input type="checkbox" checked={state.q} onChange={(e) => update({ q: e.target.checked })} />
              <span>Qualified players only</span>
            </label>
            <label className="sl-check">
              <input type="checkbox" checked={state.z} onChange={(e) => { setRunError(null); setPresetNote(null); update({ z: e.target.checked }) }} />
              <span>Standardize inputs</span>
            </label>
            <label className="sl-field sc-top">
              <span className="muted small">{`Bars (${TOP_MIN}–${TOP_MAX})`}</span>
              <input
                type="number"
                min={TOP_MIN}
                max={TOP_MAX}
                value={topDraft}
                onChange={(e) => setTopDraft(e.target.value)}
                onBlur={commitTop}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault()
                    commitTop()
                  }
                }}
              />
            </label>
          </div>
          <FilterBar rows={rows} f={state.filters} onChange={(filters) => update({ filters })} nowLabel={ctx.nowLabel} />
          {dropped.length > 0 && (
            <p className="small pt-note" role="note">{`This link named a filter that isn’t in this season’s data, so it was left out: ${dropped.join(', ')}.`}</p>
          )}

          <div className="sc-notes muted small">
            <p>
              {`Showing ${shown.length} of ${rows.length} players. `}
              {d.qualification && state.q ? `${qualificationRule(d.qualification)} ` : ''}
              {state.q
                ? 'Qualification applies to every formula here: it is the guard against a tiny sample or a tiny denominator topping a ratio.'
                : 'Qualification is off, so a short sample can top a ratio.'}
            </p>
            {usesFantasy && season.scoringLabel && <p>{`${season.scoringLabel} The same formula can rank players differently in another league.`}</p>}
            {usesFantasy && !season.scoringLabel && <p>Fantasy figures use this league’s scoring, and the same formula can rank players differently in another league.</p>}
            {usesFantasy && season.scoringChanged && <p>This league’s scoring has changed since then.</p>}
            <p>
              {ctx.fellBack
                ? `Owners are ${ctx.nowLabel} (the ${seasonLabel(d.requestedSeason as number)} league), not the ${seasonLabel(d.season)} stats season's.${ctx.asOf ? ` ${ctx.asOf}.` : ''}`
                : ctx.asOf ? `Ownership: ${ctx.asOf}.` : 'Ownership is not available for this season.'}
              {d.dataAsOf ? ` Data refreshed ${longDate(d.dataAsOf)}.` : ''}
            </p>
          </div>

          {state.chart === 'scatter' && (
            <section className="pp-card" aria-label="Scatter chart">
              <div className="sc-editor">
                <AxisSelect label="X axis" value={xAxis?.key ?? wantX} hasFormula={display != null} onChange={(x) => update({ x })} />
                <AxisSelect label="Y axis" value={yAxis?.key ?? wantY} hasFormula={display != null} onChange={(y) => update({ y })} />
              </div>
              {[...new Set(axisNotes)].map((n) => <p key={n} className="small pt-note" role="note">{n}</p>)}
              {scatter && xAxis && yAxis && (
                shown.length === 0
                  ? <p className="muted small">No players match these filters.</p>
                  : (
                    <StatScatter
                      title={`${truncate(xAxis.label, 50)} against ${truncate(yAxis.label, 50)} · ${seasonLabel(d.season)}, ${windowPhrase}`}
                      xLabel={xAxis.label}
                      yLabel={yAxis.label}
                      points={scatter.points}
                      excluded={scatter.excluded}
                      formatX={xAxis.format}
                      formatY={yAxis.format}
                    />
                  )
              )}
            </section>
          )}

          {display && ranked && (
            <>
              {display.z && zmap && (
                <div className="sc-notes muted small">
                  <p>
                    {`Standardized: each input is replaced by how many standard deviations the player sits above or below the average of the players shown who have a value for it (population SD; each input's count is listed below, out of ${shown.length} players shown), and then the formula runs on those. Raw values are beside each z-value.`}
                  </p>
                  <ul aria-label="Standardization groups">
                    {inputs.map((id) => {
                      const s = zmap[id]
                      return (
                        <li key={id}>
                          {'notStandardizable' in s
                            ? `${tokenOf(id)}: can’t be standardized (${s.notStandardizable === 'GROUP_TOO_SMALL' ? 'fewer than 2 players' : 'no spread'})`
                            : `${tokenOf(id)}: n ${s.n}, mean ${formatResult(s.mean)}, SD ${formatResult(s.sd)}`}
                        </li>
                      )
                    })}
                  </ul>
                </div>
              )}
              {(unitWarnings.length > 0 || spread.length > 0 || zeroDen) && (
                <ul className="sc-warnings small" role="note" aria-label="Formula warnings">
                  {/* specs/032 code review B5: the same sentence can arise twice (`usg + vor - (usg + vor)`); show it once, keyed by text. */}
                  {[...new Set([...unitWarnings, ...spread, ...(zeroDen ? [zeroDen] : [])])].map((w) => <li key={w}>{w}</li>)}
                </ul>
              )}

              {shown.length === 0 && <p className="muted small">No players match these filters.</p>}
              {shown.length > 0 && ranked.included.length === 0 && (
                <p className="muted small">
                  {`No player has a computable value for ${readable(display.ast)}. `}
                  {ranked.excluded[0] ? `Most often: ${reasonText(ranked.excluded[0].reason)}` : ''}
                </p>
              )}

              {ranked.included.length > 0 && (
                <>
                  {selectedWhy && <p className="small pt-note" role="note">{`This chart isn’t shown: ${selectedWhy}`}</p>}
                  {spreadGroups && (
                    <section className="pp-card" aria-label="Spread chart">
                      <SpreadStrip
                        title={`${truncate(state.n || readable(display.ast), TITLE_MAX)} · ${seasonLabel(d.season)}, ${windowPhrase} · spread of ${ranked.included.length} players`}
                        groups={spreadGroups}
                        top={spreadTop}
                        format={formatResult}
                      />
                      {spreadGroups.length > 1 && (
                        <p className="muted small">A player listed at more than one position appears in each of his positions’ strips, so the counts add up to more than the players shown.</p>
                      )}
                    </section>
                  )}
                  {wantChange && (
                    <section className="pp-card" aria-label="Change chart">
                      {!mainCurrent && <p className="muted small" role="status">Updating…</p>}
                      {mainCurrent && (cmpBlock.status === 'loading' || (cmpBlock.status === 'ok' && !cmpD)) && <p className="muted small" role="status">Loading the comparison…</p>}
                      {mainCurrent && cmpBlock.status === 'error' && <p className="muted small">Couldn’t load the comparison view.</p>}
                      {cmpD && (!cmpD.available || cmpD.reason) && (
                        <p className="muted small">{cmpD.reason ? reasonSentence(cmpD.reason) : 'The comparison view isn’t available.'}</p>
                      )}
                      {cmpD?.requestedSeason != null && <p className="small pt-note" role="note">{fallbackNote(seasonLabel(cmpD.requestedSeason), seasonLabel(cmpD.season))}</p>}
                      {change && cmpD && (
                        <>
                          {usesFantasy && state.cmp === 'PREV' && (
                            <p className="small pt-note" role="note">
                              {`Fantasy inputs in the previous season use that season’s scoring.${shownSeason(cmpD).scoringLabel ? ` ${shownSeason(cmpD).scoringLabel}` : ''}`}
                            </p>
                          )}
                          {usesFantasy && state.cmp !== 'PREV' && shownSeason(cmpD).scoringLabel !== season.scoringLabel && (
                            <p className="small pt-note" role="note">{`The comparison view’s fantasy points use different scoring. ${shownSeason(cmpD).scoringLabel ?? ''}`}</p>
                          )}
                          {state.cmp === 'PREV' && state.filters.availability.kind !== 'all' && (
                            <p className="small pt-note" role="note">The availability filter applies to the current view only; owners and roster ids differ between seasons.</p>
                          )}
                          {change.items.length === 0 ? (
                            <p className="muted small">No player has a value in both views.</p>
                          ) : (
                            <ChangeDumbbell
                              title={`${truncate(state.n || readable(display.ast), TITLE_MAX)} · ${change.thenLabel} → ${change.nowLabel} · ${state.chg === 'up' ? 'biggest rise' : 'biggest fall'} first`}
                              rows={change.items.slice(0, state.top).map<ChangeRow>((x) => ({ id: x.row.sleeperPlayerId, name: x.row.name, now: x.nowV, then: x.thenV }))}
                              nowLabel={change.nowLabel}
                              thenLabel={change.thenLabel}
                              sport={d.sport}
                              leagueId={ctx.shownLeagueId}
                              format={formatResult}
                            />
                          )}
                          <p className="muted small">{`${change.items.length} ${change.items.length === 1 ? 'player has' : 'players have'} a value in both views.`}</p>
                          {change.missing.length > 0 && (
                            <div className="sc-excluded-group">
                              <p className="small">{`Left out of Change: ${change.missing.reduce((a, [, n]) => a + n.length, 0)} of the players shown have no value in both views.`}</p>
                              {change.missing.map(([reason, names]) => (
                                <p key={reason} className="muted small">{`${reason} (${names.length}): ${names.join(', ')}`}</p>
                              ))}
                            </div>
                          )}
                        </>
                      )}
                    </section>
                  )}
                  {breakdown && (
                    <section className="pp-card" aria-label="Breakdown chart">
                      <ContributionBars
                        title={`${truncate(state.n || readable(display.ast), TITLE_MAX)} · ${seasonLabel(d.season)}, ${windowPhrase} · what each term adds`}
                        rows={breakdown}
                        sport={d.sport}
                        leagueId={ctx.shownLeagueId}
                        format={formatResult}
                      />
                      {display.z && <p className="muted small">Standardized: each part is that term’s z-score contribution, so a long bar shows the input that moves the result most.</p>}
                    </section>
                  )}
                  {state.chart === 'ranked' && (
                  <section className="pp-card" aria-label="Ranked chart">
                    <RankedBars
                      title={`${truncate(state.n || readable(display.ast), TITLE_MAX)} · ${seasonLabel(d.season)}, ${windowPhrase} · ${state.dir === 'desc' ? 'highest' : 'lowest'} first`}
                      fullTitle={`${state.n || readable(display.ast)} · ${seasonLabel(d.season)}, ${windowPhrase} · ${state.dir === 'desc' ? 'highest' : 'lowest'} first`}
                      bars={ranked.included.slice(0, state.top).map((x) => ({ id: x.row.sleeperPlayerId, name: x.row.name, value: x.value }))}
                      sport={d.sport}
                      leagueId={ctx.shownLeagueId}
                      format={formatResult}
                    />
                  </section>
                  )}

                  <section className="pp-card" aria-label="Calculator results">
                    <p className="muted small pp-line">
                      {`${ranked.included.length} players with a value. Click a header to sort the table; the chart always follows the ranking above.`}
                    </p>
                    <div className="sl-wrap">
                      <table className="sl-table">
                        <thead>
                          <tr>
                            <CalcHeader id="name" label="Player" title="Player name" sort={eff} onSort={onSort} pin />
                            <CalcHeader id="result" label={display.z ? 'Result (standardized)' : 'Result'} title={readable(display.ast)} sort={eff} onSort={onSort} />
                            {inputs.map((id) => (
                              <CalcHeader
                                key={id}
                                id={id}
                                label={`${tokenOf(id)}${tagsOf(id, state.m) ? ` (${tagsOf(id, state.m)})` : ''}`}
                                title={tokenOf(id) === COLUMNS[id].label ? COLUMNS[id].title : `${COLUMNS[id].title} (${COLUMNS[id].label} in the leaderboard; ${tokenOf(id)} in a formula)`}
                                sort={eff}
                                onSort={onSort}
                              />
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {tableRows.map((x) => (
                            <tr key={x.row.sleeperPlayerId}>
                              <th scope="row" className="sl-pin">
                                <PlayerCell row={x.row} sport={d.sport} leagueId={ctx.shownLeagueId} />
                              </th>
                              <td className="pp-strong">{formatResult(x.value)}</td>
                              {inputs.map((id) => {
                                const col = COLUMNS[id]
                                const v = col.value(x.row, state.m)
                                const info = zmap?.[id]
                                const z = typeof v === 'number' && info && !('notStandardizable' in info) ? (v - info.mean) / info.sd : null
                                return (
                                  <td key={id}>
                                    {typeof v === 'number' ? <span>{formatValue(col, v, state.m)}</span> : <Cell col={col} row={x.row} d={d} mode={state.m} />}
                                    {z != null && <span className="pp-made muted small">{`z ${formatResult(z)}`}</span>}
                                  </td>
                                )
                              })}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </section>
                </>
              )}

              {ranked.excluded.length > 0 && (
                <section className="pp-card sc-excluded" aria-label="Couldn’t compute">
                  <h3 className="pp-h">Couldn’t compute</h3>
                  <p className="muted small pp-line">Left out of the ranking, never shown as 0.</p>
                  {ranked.excluded.map((g) => (
                    <div key={g.key} className="sc-excluded-group">
                      <p className="small">{`${reasonText(g.reason)} (${g.rows.length})`}</p>
                      <ul className="sc-excluded-list">
                        {g.rows.map((r) => (
                          <li key={r.sleeperPlayerId}>
                            <PlayerLink sleeperLeagueId={ctx.shownLeagueId} sleeperPlayerId={r.sleeperPlayerId} sport={d.sport}>
                              {r.name ?? 'Unknown player'}
                            </PlayerLink>
                            <span className="muted small">{` ${[r.positions.join('/'), r.team].filter(Boolean).join(' · ') || DASH}`}</span>
                          </li>
                        ))}
                      </ul>
                    </div>
                  ))}
                </section>
              )}
            </>
          )}

          <section className="pp-card sc-saved" aria-label="Saved in this browser">
            <h3 className="pp-h">Saved in this browser</h3>
            <p className="muted small pp-line">
              This list is saved in this browser and is not synced to your account or other devices. Share links work everywhere.
            </p>
            {saveName == null ? (
              <button type="button" className="pp-tog" disabled={!active} onClick={() => setSaveName(state.n)}>Save this formula</button>
            ) : (
              <form
                className="sc-editor"
                onSubmit={(e) => {
                  e.preventDefault()
                  commitSave()
                }}
              >
                <label className="sl-field">
                  <span className="muted small">{`Name (up to ${NAME_MAX} characters)`}</span>
                  <input
                    type="text"
                    className="sc-input"
                    value={saveName}
                    maxLength={NAME_MAX}
                    autoFocus
                    onChange={(e) => setSaveName(e.target.value.slice(0, NAME_MAX))}
                  />
                </label>
                <button type="submit" className="pp-tog">Save</button>
                <button type="button" className="pp-tog" onClick={() => setSaveName(null)}>Cancel</button>
              </form>
            )}
            {!active && saveName == null && <p className="muted small">Run a valid formula to save it.</p>}
            {saveNote && <p className="small pt-note" role="status">{saveNote}</p>}
            {saved.length === 0 ? (
              <p className="muted small">Nothing saved here yet.</p>
            ) : (
              <ul className="sc-saved-list">
                {[...saved].reverse().map((f) => (
                  <li key={f.id} className="sc-saved-item">
                    {renaming?.id === f.id ? (
                      <form
                        className="sc-editor"
                        onSubmit={(e) => {
                          e.preventDefault()
                          settled(renameSaved(f.id, renaming.name, saved))
                          setRenaming(null)
                        }}
                      >
                        <label className="sl-field">
                          <span className="muted small">New name</span>
                          <input
                            type="text"
                            className="sc-input"
                            value={renaming.name}
                            maxLength={NAME_MAX}
                            autoFocus
                            onChange={(e) => setRenaming({ id: f.id, name: e.target.value.slice(0, NAME_MAX) })}
                          />
                        </label>
                        <button type="submit" className="pp-tog">Rename</button>
                        <button type="button" className="pp-tog" onClick={() => setRenaming(null)}>Cancel</button>
                      </form>
                    ) : (
                      <p><strong>{f.name || 'Unnamed formula'}</strong></p>
                    )}
                    <p className="muted small">
                      {f.stale ? `${f.expr} — uses a stat that no longer exists` : `${readableOf(f.expr)} · ${MODE_PHRASE[f.settings.mode]}`}
                    </p>
                    <div className="sc-saved-actions">
                      <button type="button" className="pp-tog" disabled={f.stale} onClick={() => loadSaved(f)} aria-label={`Load ${f.name || f.expr}`}>Load</button>
                      <button type="button" className="pp-tog" onClick={() => setRenaming({ id: f.id, name: f.name })} aria-label={`Rename ${f.name || f.expr}`}>Rename</button>
                      <button type="button" className="pp-tog" onClick={() => settled(removeSaved(f.id, saved))} aria-label={`Delete ${f.name || f.expr}`}>Delete</button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </div>
  )
}
