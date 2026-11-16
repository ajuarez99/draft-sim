import { useEffect, useState } from 'react'

// Spec 073 follow-up, second pass (2026-11-16), reworked in the lock fix pass: the league site is private to
// Allan, and so is the league API (backend LeagueLockInterceptor). "Unlocked" means: this browser holds a league
// session token whose expiry is in the future. The token is minted by POST /api/admin/session from the admin
// token and is accepted by the backend only while it is unexpired, correctly signed and inside its absolute
// lifetime, so, unlike the front-door flag this replaces, it cannot be set by hand in devtools. (Anyone holding
// the admin token has full access.)
//
// WHAT IS STORED, AND WHY IN localStorage: `{ token, expiresAt, windowSeconds }` under bk.leagueSession.v1.
// It is a credential, but a 5-minute one that the server renews only while Allan is active, so it is stored
// where a new tab opened inside the window can use it. THE ADMIN TOKEN IS NEVER STORED by the lock screen: not
// in localStorage, not in sessionStorage (CR-11). It is typed, traded for a session, and dropped. The
// /admin/digest page keeps its own prompt and its own sessionStorage behaviour, unchanged. With storage
// blocked, the session lasts for this page load (module memory).
//
// "Locked" is the absence of a live session. The idle watcher (useIdleLock.ts) and the 401 handler in api.ts
// both end in lock(); the lock screen then replaces whatever league data was on screen, and lock() empties
// every in-memory league cache that registered itself with onLock() so nothing reappears after the next unlock.

export const SESSION_KEY = 'bk.leagueSession.v1'
/** The 073 front-door flag this replaces. Forgeable by hand, so it is ignored and deleted on load. */
export const OLD_FLAG_KEY = 'bk.leagues.unlocked.v1'

/**
 * A renewed token from a response is adopted only if the user did something within this long. Ensures a
 * background poll (rail refresh status, live-draft fetches) cannot swap in a token for an idle session.
 * ARBITRARY, hand-set: 120 s, deliberately larger than every check interval in useIdleLock (the 30 s safety
 * tick and the 15 s renewal spacing), so a renewal the watcher itself sends is never dropped by this check
 * because the two constants happen to be equal. The client-side expiry is separately capped at
 * lastActivity + window (renewSession), so even an adopted poll token cannot hold the screen open past one
 * window after the last input.
 */
export const ACTIVE_GRACE_MS = 120_000

type Session = { token: string; expiresAt: number; windowSeconds: number }

function parse(raw: string | null): Session | null {
  if (!raw) return null
  try {
    const v: unknown = JSON.parse(raw)
    if (!v || typeof v !== 'object') return null
    const { token, expiresAt, windowSeconds } = v as Record<string, unknown>
    if (typeof token !== 'string' || !token) return null
    if (typeof expiresAt !== 'number' || !Number.isFinite(expiresAt)) return null
    if (typeof windowSeconds !== 'number' || !(windowSeconds > 0)) return null
    return { token, expiresAt, windowSeconds }
  } catch {
    return null
  }
}

function readStored(): Session | null {
  try {
    return parse(localStorage.getItem(SESSION_KEY))
  } catch {
    return null
  }
}

function removeStored() {
  try {
    localStorage.removeItem(SESSION_KEY)
  } catch {
    // Nothing to clean up if storage was never reachable.
  }
}

function writeStored(s: Session) {
  try {
    localStorage.setItem(SESSION_KEY, JSON.stringify(s))
  } catch {
    // Storage blocked: the session lasts for this page load only.
  }
}

let current: Session | null = null
// Minutes shown on the lock screen after an idle lock; null on first visit and after an explicit sign-out.
let idleMinutes: number | null = null
let lastActivityAt = Date.now()
const listeners = new Set<() => void>()
const lockCleanups = new Set<() => void>()

function notify() {
  listeners.forEach((l) => l())
}

/**
 * Registers something that must be emptied when the site locks (a module-level league cache). Called once at
 * module load by each cache's owner, so leaguesLock does not import them (they import api, which imports this).
 */
export function onLock(cleanup: () => void) {
  lockCleanups.add(cleanup)
}

function minutesOf(windowSeconds: number): number {
  return Math.max(1, Math.round(windowSeconds / 60))
}

/**
 * The expiry (epoch seconds) a session token carries: the canonical number before its first dot. Null for a token
 * that does not start that way (an opaque string in a test, a token from an older backend).
 */
export function tokenExpiry(token: string): number | null {
  const m = /^(0|[1-9][0-9]{0,11})\./.exec(token)
  return m ? Number(m[1]) : null
}

function load() {
  // The old forgeable flag is not a session. Delete it so it cannot linger.
  try {
    localStorage.removeItem(OLD_FLAG_KEY)
  } catch {
    // Blocked storage: nothing to delete.
  }
  const stored = readStored()
  if (stored && stored.expiresAt > Date.now()) {
    current = stored
    return
  }
  if (stored) {
    // A session that expired while no tab was open: that is an idle lock, say so on the lock screen.
    idleMinutes = minutesOf(stored.windowSeconds)
    removeStored()
  }
  current = null
}
load()

// A second tab renewing or locking is seen here, so the tabs agree.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key !== SESSION_KEY) return
    const stored = readStored()
    current = stored && stored.expiresAt > Date.now() ? stored : null
    notify()
  })
}

/** True while a session whose expiry is in the future is held. */
export function isUnlocked(): boolean {
  return current !== null && current.expiresAt > Date.now()
}

/** A session RECORD is held, expired or not. A 401 `league_session_required` locks whenever this is true. */
export function holdsSession(): boolean {
  return current !== null
}

/** The token to send as X-League-Session, or null when none is held or it has expired. */
export function getSessionToken(): string | null {
  return isUnlocked() ? current!.token : null
}

/** Epoch ms the held session expires, or null. For the idle watcher and tests. */
export function sessionExpiresAt(): number | null {
  return current ? current.expiresAt : null
}

/** The held session's window in ms, or null. */
export function sessionWindowMs(): number | null {
  return current ? current.windowSeconds * 1000 : null
}

/** A session is held but its expiry has passed (the watcher locks on this). */
export function sessionExpired(): boolean {
  return current !== null && current.expiresAt <= Date.now()
}

/** The lock screen's "after N minutes" line: set by an idle lock, null on first visit and after sign-out. */
export function lockedAfterIdleMinutes(): number | null {
  return idleMinutes
}

/** Records that the user did something (called by the idle watcher's input listeners). */
export function noteActivity() {
  lastActivityAt = Date.now()
}

export function lastActivity(): number {
  return lastActivityAt
}

/**
 * Starts a session after POST /api/admin/session answered. `sentAt` is when the request was sent (the expiry is
 * measured from there, not from when the response landed, so the client never believes the session lasts longer
 * than the server will honour it). The admin token is NOT a parameter: nothing here stores it.
 */
export function startSession(token: string, expiresInSeconds: number, sentAt: number = Date.now()) {
  current = { token, expiresAt: sentAt + expiresInSeconds * 1000, windowSeconds: expiresInSeconds }
  idleMinutes = null
  lastActivityAt = Date.now()
  writeStored(current)
  notify()
}

/**
 * Adopts the refreshed token a response carried (sliding window). `sentAt` is when the request that got it was
 * sent. A no-op when
 *  - no session is held (a response that lands after lock() must not resurrect it);
 *  - the user has been inactive for longer than ACTIVE_GRACE_MS (a background poll must not renew an idle session);
 *  - the token's own embedded expiry is not strictly greater than the held token's (CR-04: a slow response that
 *    carries an OLDER token must not replace a newer one), or the token carries no readable expiry at all.
 * The new client-side expiry is the earliest of: the held expiry moved by the same number of seconds the token's
 * embedded expiry moved (so a server-side cap shortens it too), `sentAt + window`, and `lastActivity + window`;
 * and never earlier than the expiry already held. So the client never believes in a later expiry than the newest
 * token implies, and an idle session is shown no longer than one window after the last input.
 */
export function renewSession(token: string, sentAt: number = Date.now()) {
  if (!current || !token) return
  if (Date.now() - lastActivityAt > ACTIVE_GRACE_MS) return
  const heldExp = tokenExpiry(current.token)
  const newExp = tokenExpiry(token)
  if (newExp === null) return
  if (heldExp !== null && newExp <= heldExp) return
  const windowMs = current.windowSeconds * 1000
  let expiresAt = Math.min(sentAt + windowMs, lastActivityAt + windowMs)
  if (heldExp !== null) expiresAt = Math.min(expiresAt, current.expiresAt + (newExp - heldExp) * 1000)
  expiresAt = Math.max(expiresAt, current.expiresAt)
  current = { token, expiresAt, windowSeconds: current.windowSeconds }
  writeStored(current)
}

/**
 * Ends the session. `reason: 'idle'` (the idle watcher, or the server refusing the session) makes the lock
 * screen say so; the default is an explicit sign-out, which says nothing. Also empties every cache registered
 * with onLock(), so the league list, the jump-to index and similar do not reappear for the next unlock.
 */
export function lock(reason: 'idle' | 'signout' = 'signout') {
  idleMinutes = reason === 'idle' && current ? minutesOf(current.windowSeconds) : reason === 'idle' ? idleMinutes : null
  current = null
  removeStored()
  lockCleanups.forEach((clean) => {
    try {
      clean()
    } catch {
      // One cache failing to empty must not stop the lock.
    }
  })
  notify()
}

/** Re-renders on unlock/lock, unlike isUnlocked(). Same shape as user.ts's useUser. */
export function useUnlocked(): boolean {
  const [value, setValue] = useState(isUnlocked())
  useEffect(() => {
    const listener = () => setValue(isUnlocked())
    listeners.add(listener)
    // Covers a change between the first render and this effect.
    setValue(isUnlocked())
    return () => {
      listeners.delete(listener)
    }
  }, [])
  return value
}
