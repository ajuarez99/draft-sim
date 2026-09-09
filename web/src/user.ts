import { useEffect, useState } from 'react'

// Identity, not auth (claude/user-identity-and-onboarding.md §2/§7): typing
// `popsharky` gets you popsharky's seat highlighting and league list, with no
// password and nothing hidden. Versioned key so a future shape change is a
// clean reset (treated as signed out) rather than a crash on someone's stale
// JSON.
const STORAGE_KEY = 'bk.user.v1'

export type BkUser = {
  sleeperUserId: string
  username: string
  displayName: string
  avatar: string | null
}

function readStored(): BkUser | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (typeof parsed?.sleeperUserId !== 'string') return null
    return parsed as BkUser
  } catch {
    // Corrupt JSON, or storage blocked entirely (private window) -- both are
    // "signed out", not a crash.
    return null
  }
}

// Module-level so api.ts's apiFetch can read the current id synchronously on
// every request without importing React state. Kept in sync with
// localStorage by setUser/clearUser below -- those are the only writers.
let current: BkUser | null = readStored()
const listeners = new Set<() => void>()

/** Synchronous accessor for api.ts's request-header funnel. Null when signed out. */
export function currentUserId(): string | null {
  return current?.sleeperUserId ?? null
}

function notify() {
  listeners.forEach((l) => l())
}

export function setUser(user: BkUser) {
  current = user
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(user))
  } catch {
    // Storage blocked -- the in-memory value still works for this page load.
  }
  notify()
}

export function clearUser() {
  current = null
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Nothing to clean up if storage was never reachable.
  }
  notify()
}

/** Re-renders on sign-in/sign-out/switch-user, unlike currentUserId(). */
export function useUser(): BkUser | null {
  const [user, setLocal] = useState(current)
  useEffect(() => {
    const listener = () => setLocal(current)
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  }, [])
  return user
}
