// Spec 074 T032: a guest is a browser, not a person. The first time a Redraft request needs an identity and nobody
// is signed in, this makes a random id and keeps it in localStorage; every /api/history request then carries it as
// `X-Guest-Id`. Signing in with a Sleeper username claims the games made under it (api.ts claimGuestGames), and the
// id is dropped only after the server answered 200.
//
// The id holds nothing about the person, but it IS a bearer credential: knowing it is enough to read and play that
// guest's games and to move them to a Sleeper id (CR-11). So it is generated from a CSPRNG, never logged, and never
// put in a URL. Storage can be blocked (a private window) or throw; then the id lives in memory for this page load only.

export const GUEST_KEY = 'bk.guest.v1'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function readStored(): string | null {
  try {
    const raw = localStorage.getItem(GUEST_KEY)
    return raw && UUID.test(raw) ? raw.toLowerCase() : null
  } catch {
    return null
  }
}

// Module-level so apiFetch can read it synchronously. Only a real id is cached: a "none yet" answer is never kept
// (CR-06), because another tab may have made one a moment later. A `storage` event keeps this tab in step with it.
let current: string | null | undefined

function make(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    // No randomUUID (older browsers): a version-4 UUID from the CSPRNG.
    const b = crypto.getRandomValues(new Uint8Array(16))
    b[6] = (b[6] & 0x0f) | 0x40
    b[8] = (b[8] & 0x3f) | 0x80
    const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
    return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
  }
  // No CSPRNG at all: refuse rather than hand out a guessable credential. The caller treats this as "no guest".
  throw new Error('This browser cannot make a private id.')
}

/** The stored id without making one. Null when this browser has never needed one. */
export function peekGuestId(): string | null {
  if (current) return current
  const stored = readStored()
  if (stored) current = stored
  return stored
}

/** The id, made (and stored, if storage works) on first need. */
export function guestId(): string {
  const existing = peekGuestId()
  if (existing) return existing
  const id = make().toLowerCase()
  current = id
  try {
    localStorage.setItem(GUEST_KEY, id)
    // Two tabs can both get here: whichever setItem ran last is what storage holds, so both keep that one.
    const kept = readStored()
    if (kept) current = kept
  } catch {
    // Storage blocked: in-memory for this page load; the next load makes another, and the first one's games stay behind.
  }
  return current as string
}

if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener('storage', (e) => {
    if (e.key === GUEST_KEY || e.key === null) current = readStored()
  })
}

/** Forgets the id (after a claim answered 200). */
export function dropGuestId(): void {
  current = null
  try {
    localStorage.removeItem(GUEST_KEY)
  } catch {
    // Nothing to clean up.
  }
}

/** Test-only: back to "not looked up yet", so the next call re-reads storage. */
export function __resetGuestForTests(): void {
  current = undefined
}

// ---- The result of the last claim, for the home page's one line ---------------------------------------------

export type ClaimNote = { moved: number; leftBehind: number }
let lastClaim: ClaimNote | null = null
const listeners = new Set<() => void>()

export function noteClaim(c: ClaimNote | null): void {
  lastClaim = c
  listeners.forEach((l) => l())
}
export function lastClaimNote(): ClaimNote | null {
  return lastClaim
}
export function onClaimNote(l: () => void): () => void {
  listeners.add(l)
  return () => {
    listeners.delete(l)
  }
}
