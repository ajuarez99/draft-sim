import { useSyncExternalStore } from 'react'

// The commissioner key is the server's admin token (ADMIN_TOKEN, sent as
// X-Admin-Token). Commissioner-only actions -- the conduct list, the commissioner
// ranking, and "Recompute" -- need it in addition to a commissioner identity
// (claude/audit-2026-09-28/04, option D): the identity is only a Sleeper id anyone
// can read off a public endpoint, so the key is what actually gates.
//
// It is typed in by the person, never built into the bundle (no VITE_ variable, so
// nothing to find in web/dist). It lives in localStorage on this device, guarded,
// because storage can be blocked or throw (private window); the in-memory value
// still works for the page load in that case.
const STORAGE_KEY = 'bk.commissionerKey.v1'

function readStored(): string | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw && raw.trim() ? raw : null
  } catch {
    return null
  }
}

let current: string | null = readStored()
const listeners = new Set<() => void>()

function notify() {
  listeners.forEach((l) => l())
}

/** Synchronous accessor for api.ts's request funnel. Null when no key is saved. */
export function getCommissionerKey(): string | null {
  return current
}

export function setCommissionerKey(key: string) {
  const trimmed = key.trim()
  if (!trimmed) return
  current = trimmed
  try {
    localStorage.setItem(STORAGE_KEY, trimmed)
  } catch {
    // Storage blocked -- the in-memory value still works for this page load.
  }
  notify()
}

export function clearCommissionerKey() {
  current = null
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Nothing stored that we could remove; the in-memory value is already gone.
  }
  notify()
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Re-renders when a key is saved or cleared, so a "Clear key" control can appear and vanish. */
export function useCommissionerKey(): string | null {
  return useSyncExternalStore(subscribe, getCommissionerKey, () => null)
}

// Injectable so a test can answer the prompt without a real window.prompt.
let prompter: (message: string) => string | null = (message) =>
  typeof window !== 'undefined' && typeof window.prompt === 'function' ? window.prompt(message) : null

export function setCommissionerKeyPrompter(p: (message: string) => string | null) {
  prompter = p
}

/** Asks for the key once. Null when the person cancels or leaves it blank. */
export function askForCommissionerKey(wasWrong: boolean): string | null {
  const answer = prompter(
    wasWrong
      ? "That commissioner key wasn't accepted. Enter it again, or cancel."
      : 'This action needs the commissioner key. Enter it once; it is kept on this device only.',
  )
  return answer && answer.trim() ? answer.trim() : null
}
