import { SAVED_KEY, SAVED_MAX, parseStore, type SavedFormula } from './statCalcSaved'

// Spec 073 FR-020 (plan "Decisions put to Allan", PR-04): saved stat-calculator formulas live in one
// browser's storage for one origin, so a league-mate forwarded from the old main address to the league
// site would lose them. When the Time Machine site forwards someone to the league site, the formulas ride
// along in the URL fragment (never sent to a server, never in an access log) and the league site saves
// them if it has never saved any of its own. Display preferences and the analytics opt-out are not carried.
//
// Pure apart from the storage, location and history objects it is handed, so tests pass fakes.

export const CARRY_PARAM = 'bk-carry'
/**
 * A hand-set limit on the encoded carry, not a measured browser one. Measured on formulas made by the real
 * saveFormula: about 320 encoded characters each, so the app's 50-formula cap (SAVED_MAX) is about 16,000
 * (15,952 measured for 50 short-named ones, which leaves no room for longer names). 24,000 is that with
 * about 50% headroom: arbitrary, chosen so a full store always travels. The fragment never reaches a server.
 * Over the limit, the newest formulas (by savedAt) that fit are carried and the older ones are left behind.
 */
export const CARRY_MAX_CHARS = 24000

type ReadStorage = Pick<Storage, 'getItem'>
type WriteStorage = Pick<Storage, 'getItem' | 'setItem'>

function toBase64Url(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromBase64Url(data: string): string {
  const b64 = data.replace(/-/g, '+').replace(/_/g, '/')
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4))
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)))
}

const encode = (formulas: SavedFormula[]) => toBase64Url(JSON.stringify({ v: 1, formulas }))

/** The whole list if it fits; else the newest (by savedAt, later entry on a tie) that do, kept oldest first. */
function encodeNewestThatFit(formulas: SavedFormula[]): string | null {
  const whole = encode(formulas)
  if (whole.length <= CARRY_MAX_CHARS) return whole
  const newestFirst = formulas
    .map((f, i) => ({ f, i }))
    .sort((a, b) => (a.f.savedAt < b.f.savedAt ? 1 : a.f.savedAt > b.f.savedAt ? -1 : b.i - a.i))
    .map((x) => x.f)
  // Encoded size grows with the count, so binary-search the largest count that fits.
  let lo = 0
  let hi = newestFirst.length - 1
  let best: string | null = null
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    const n = mid + 1
    const keep = new Set(newestFirst.slice(0, n))
    const data = encode(formulas.filter((f) => keep.has(f)))
    if (data.length <= CARRY_MAX_CHARS) {
      best = data
      lo = mid + 1
    } else hi = mid - 1
  }
  return best
}

/**
 * `url` with this browser's saved formulas added to its fragment as `#bk-carry=<data>;<original fragment>`.
 * Returned unchanged when there is nothing valid to carry or the storage cannot be read. When the whole list
 * encodes to more than CARRY_MAX_CHARS, the newest formulas that fit are carried. Never deletes anything
 * from the source.
 */
export function withCarry(url: string, storage: ReadStorage): string {
  try {
    const formulas = parseStore(storage.getItem(SAVED_KEY))
    if (formulas.length === 0) return url
    const data = encodeNewestThatFit(formulas)
    if (data === null) return url
    const hashAt = url.indexOf('#')
    const base = hashAt === -1 ? url : url.slice(0, hashAt)
    const original = hashAt === -1 ? '' : url.slice(hashAt + 1)
    return `${base}#${CARRY_PARAM}=${data}${original ? `;${original}` : ''}`
  } catch {
    return url
  }
}

/**
 * Called once before the app renders, in the league build only. Takes a carry out of the fragment,
 * saves it only if this site has never saved formulas, and ALWAYS strips it from the address bar,
 * restoring any fragment the link had before. Bad or oversized data is dropped. Never throws.
 */
export function acceptCarry(
  location: Pick<Location, 'pathname' | 'search' | 'hash'>,
  history: Pick<History, 'replaceState' | 'state'>,
  storage: WriteStorage,
): void {
  try {
    const hash = location.hash.startsWith('#') ? location.hash.slice(1) : location.hash
    if (!hash.startsWith(`${CARRY_PARAM}=`)) return
    const rest = hash.slice(CARRY_PARAM.length + 1)
    const sep = rest.indexOf(';')
    const data = sep === -1 ? rest : rest.slice(0, sep)
    const original = sep === -1 ? '' : rest.slice(sep + 1)
    // Strip first: whatever happens below, the carry must not stay in the address bar.
    history.replaceState(history.state, '', `${location.pathname}${location.search}${original ? `#${original}` : ''}`)
    try {
      if (data.length > CARRY_MAX_CHARS) return
      const incoming = parseStore(fromBase64Url(data)).slice(-SAVED_MAX)
      if (incoming.length === 0) return
      // Only when this site has never saved formulas: an empty list the member left after deleting
      // their own (`{"v":1,"formulas":[]}`) must not be refilled by a later forward (CR-03).
      let neverSaved = false
      try {
        const current = storage.getItem(SAVED_KEY)
        neverSaved = current == null
        if (!neverSaved) {
          try {
            JSON.parse(current as string)
          } catch {
            neverSaved = true // garbage reads as empty, the same as statCalcSaved.readSaved
          }
        }
      } catch {
        neverSaved = false // unreadable storage: write nothing
      }
      if (!neverSaved) return
      storage.setItem(SAVED_KEY, JSON.stringify({ v: 1, formulas: incoming }))
    } catch {
      // Garbage, or storage blocked: dropped. The address bar is already clean.
    }
  } catch {
    // Never reaches the page.
  }
}
