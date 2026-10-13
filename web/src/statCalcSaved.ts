import type { PlayerWindowKind } from './api'
import { NAME_MAX, TOP_DEFAULT, clampTop } from './statCalcUrl'
import { parse } from './statFormula'
import type { SortDir, StatMode } from './statLeaderboard'

/**
 * specs/032-stat-calculator US3, contract C3 (research R14): formulas the member kept, in this
 * browser's storage. Same try/catch discipline as statChoice: a blocked store reads as empty and a
 * failed write reports `saved: false`, so the page can keep the in-memory list for this page load
 * and say it was not saved.
 *
 * Not synced to the account and not league-scoped. Filters are not saved: they are league-specific.
 * Each write re-reads the store first, which narrows (does not close) the two-tabs race: last write
 * wins, accepted (plan review N7).
 */

export const SAVED_KEY = 'bk.statCalc.saved.v1'
/** A hand-set cap, not a principled one. */
export const SAVED_MAX = 50

export type SavedSettings = {
  window: PlayerWindowKind
  mode: StatMode
  top: number
  qualifiedOnly: boolean
  standardize: boolean
  dir: SortDir
}

export type SavedFormula = {
  id: string
  name: string
  /** Canonical spelling. */
  expr: string
  settings: SavedSettings
  /** ISO timestamp. */
  savedAt: string
}

/** A saved formula as read: `stale` is true when `expr` no longer parses against the current stat set. */
export type SavedEntry = SavedFormula & { stale: boolean }

export type SaveResult = {
  /** True when the write reached the browser's storage. */
  saved: boolean
  /** The list after the operation, oldest first; usable in memory even when `saved` is false. */
  list: SavedEntry[]
  /** Set when the 51st save pushed the oldest one out. */
  notice: string | null
}

const WINDOWS: PlayerWindowKind[] = ['SEASON', 'LAST_10', 'LAST_5']
const MODES: StatMode[] = ['perGame', 'totals', 'per36']

export const DEFAULT_SAVED_SETTINGS: SavedSettings = {
  window: 'SEASON', mode: 'perGame', top: TOP_DEFAULT, qualifiedOnly: true, standardize: false, dir: 'desc',
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

function cleanSettings(raw: unknown): SavedSettings {
  const s = isObj(raw) ? raw : {}
  const d = DEFAULT_SAVED_SETTINGS
  return {
    window: WINDOWS.includes(s.window as PlayerWindowKind) ? (s.window as PlayerWindowKind) : d.window,
    mode: MODES.includes(s.mode as StatMode) ? (s.mode as StatMode) : d.mode,
    top: typeof s.top === 'number' && Number.isFinite(s.top) ? clampTop(s.top) : d.top,
    qualifiedOnly: typeof s.qualifiedOnly === 'boolean' ? s.qualifiedOnly : d.qualifiedOnly,
    standardize: typeof s.standardize === 'boolean' ? s.standardize : d.standardize,
    dir: s.dir === 'asc' ? 'asc' : 'desc',
  }
}

/** Parses the stored value; malformed entries are skipped, never thrown on. `null` = the store reads as empty. */
function parseStore(raw: string | null): SavedFormula[] {
  if (raw == null) return []
  const parsed: unknown = JSON.parse(raw)
  if (!isObj(parsed) || parsed.v !== 1 || !Array.isArray(parsed.formulas)) return []
  const seen = new Set<string>()
  const out: SavedFormula[] = []
  for (const e of parsed.formulas) {
    if (!isObj(e)) continue
    if (typeof e.id !== 'string' || e.id === '' || seen.has(e.id)) continue
    if (typeof e.name !== 'string' || typeof e.expr !== 'string' || e.expr.trim() === '') continue
    seen.add(e.id)
    out.push({
      id: e.id,
      name: e.name.slice(0, NAME_MAX),
      expr: e.expr,
      settings: cleanSettings(e.settings),
      savedAt: typeof e.savedAt === 'string' ? e.savedAt : '',
    })
  }
  return out
}

const withStale = (list: SavedFormula[]): SavedEntry[] => list.map((f) => ({ ...f, stale: !parse(f.expr).ok }))

/** Oldest first. A blocked or unreadable store reads as empty. */
export function readSaved(): SavedEntry[] {
  try {
    return withStale(parseStore(localStorage.getItem(SAVED_KEY)))
  } catch {
    return []
  }
}

/** The stored list, or null when the store cannot be read at all (blocked), so a caller can fall back to its in-memory list. */
function readStored(): SavedFormula[] | null {
  try {
    return parseStore(localStorage.getItem(SAVED_KEY))
  } catch {
    return null
  }
}

function write(list: SavedFormula[]): boolean {
  try {
    localStorage.setItem(SAVED_KEY, JSON.stringify({ v: 1, formulas: list }))
    return true
  } catch {
    return false
  }
}

const strip = (e: SavedEntry | SavedFormula): SavedFormula => ({
  id: e.id, name: e.name, expr: e.expr, settings: e.settings, savedAt: e.savedAt,
})

/**
 * Re-reads the store, applies `change`, and writes. `inMemory` is the page's own list: it is the base
 * when the store cannot be read, so a blocked store still behaves like a list for this page load.
 */
function mutate(inMemory: SavedEntry[], change: (list: SavedFormula[]) => { list: SavedFormula[]; notice?: string | null }): SaveResult {
  const base = readStored() ?? inMemory.map(strip)
  const { list, notice } = change(base)
  return { saved: write(list), list: withStale(list), notice: notice ?? null }
}

const newId = (taken: Set<string>): string => {
  let id: string
  do id = Math.random().toString(36).slice(2, 8) || 'x'
  while (taken.has(id))
  return id
}

export const cleanName = (name: string): string => name.trim().slice(0, NAME_MAX)

/** Saves a formula as the newest entry. The 51st drops the oldest and says so in `notice`. */
export function saveFormula(
  input: { name: string; expr: string; settings: SavedSettings },
  inMemory: SavedEntry[] = [],
  now: Date = new Date(),
): SaveResult {
  return mutate(inMemory, (list) => {
    const entry: SavedFormula = {
      id: newId(new Set(list.map((f) => f.id))),
      name: cleanName(input.name),
      expr: input.expr,
      settings: cleanSettings(input.settings),
      savedAt: now.toISOString(),
    }
    const next = [...list, entry]
    const dropped = next.length > SAVED_MAX ? next.splice(0, next.length - SAVED_MAX) : []
    return {
      list: next,
      notice: dropped.length
        ? `You can keep ${SAVED_MAX} formulas in this browser, so the oldest one (${dropped[0].name || dropped[0].expr}) was removed to make room.`
        : null,
    }
  })
}

export function renameSaved(id: string, name: string, inMemory: SavedEntry[] = []): SaveResult {
  return mutate(inMemory, (list) => ({ list: list.map((f) => (f.id === id ? { ...f, name: cleanName(name) } : f)) }))
}

export function removeSaved(id: string, inMemory: SavedEntry[] = []): SaveResult {
  return mutate(inMemory, (list) => ({ list: list.filter((f) => f.id !== id) }))
}
