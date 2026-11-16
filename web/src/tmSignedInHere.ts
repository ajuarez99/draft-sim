// Spec 073 FR-019: how the Time Machine site tells "signed in here" from "signed in back when this address
// was the combined site". Sign-in on the Time Machine site writes the marker; a signed-in browser that
// arrives without it signed in before the switch and is shown the one-time "your leagues moved" notice.
// Detection by marker, not by guessing at league-era storage keys: many league-mates have none.

export const SIGNED_IN_HERE_KEY = 'bk.tm.signedInHere.v1'
export const MOVED_DISMISSED_KEY = 'bk.tm.movedNotice.dismissed.v1'

export function markSignedInHere(): void {
  try {
    localStorage.setItem(SIGNED_IN_HERE_KEY, '1')
  } catch {
    // Storage blocked: the notice may show to someone who did sign in here. Harmless.
  }
}

/** True when the marker is present. Blocked storage reads as absent. */
export function signedInHere(): boolean {
  try {
    return localStorage.getItem(SIGNED_IN_HERE_KEY) != null
  } catch {
    return false
  }
}

export function movedNoticeDismissed(): boolean {
  try {
    return localStorage.getItem(MOVED_DISMISSED_KEY) != null
  } catch {
    return false
  }
}

export function dismissMovedNotice(): void {
  try {
    localStorage.setItem(MOVED_DISMISSED_KEY, '1')
  } catch {
    // Blocked: dismissal lasts for this page load only (the component keeps its own state).
  }
}
