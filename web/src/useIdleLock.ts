import { useEffect } from 'react'
import { renewLeagueSession } from './api'
import { lastActivity, lock, noteActivity, sessionExpired, sessionExpiresAt, sessionWindowMs } from './leaguesLock'

/**
 * Safety-net check interval. ARBITRARY, hand-set: 30 s. The real work is done by the activity handler (renews)
 * and the expiry timer (locks); the tick only catches what those miss, such as another tab having renewed the
 * session, or a timer a background tab throttled. Must stay well below ACTIVE_GRACE_MS (120 s).
 */
export const IDLE_TICK_MS = 30_000

/**
 * Smallest gap between two renewal requests the watcher sends. ARBITRARY, hand-set: 15 s. Scrolling fires
 * dozens of events a second; without this each one would call the server.
 */
export const RENEW_SPACING_MS = 15_000

/**
 * Fired on `window` by useWatching() while a page that is watched rather than operated is on screen. It counts
 * as activity. Allan's decision, 2026-11-16 (lock review CR-06): a live draft room that is open and visible
 * must not lock mid-draft just because nobody touched the page for five minutes.
 */
export const WATCHING_EVENT = 'bk:watching'

/** How often a watched page reports itself. ARBITRARY, hand-set: 20 s, well inside ACTIVE_GRACE_MS (120 s). */
export const WATCHING_BEAT_MS = 20_000

const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'wheel', 'scroll', 'touchstart', WATCHING_EVENT] as const

/**
 * Mount on a page people watch without touching (the live draft room). While it is mounted AND the tab is
 * visible, the page counts as active, so the league session keeps renewing. A hidden tab reports nothing and
 * expires on the normal schedule; leaving the page stops the beat. Harmless where no idle lock is mounted
 * (the combined and Time Machine builds): nothing listens.
 *
 * What this costs: a draft room left open and visible on an unattended screen stays unlocked until the 12-hour
 * session cap. That is the trade Allan chose.
 */
export function useWatching(watching = true) {
  useEffect(() => {
    if (!watching) return
    const beat = () => {
      if (document.visibilityState === 'visible') window.dispatchEvent(new Event(WATCHING_EVENT))
    }
    beat()
    const timer = window.setInterval(beat, WATCHING_BEAT_MS)
    document.addEventListener('visibilitychange', beat)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', beat)
    }
  }, [watching])
}

/**
 * Keeps the league session alive while the user is active and locks the site when they are not. Mount it
 * only while unlocked on the `leagues` build (App.tsx does).
 *
 * RENEWING: from the activity handler itself. On input, if the session is past half its window, no renewal is in
 * flight and none was sent in the last RENEW_SPACING_MS, ask GET /api/league-session; its response carries the
 * renewed token (api.ts adopts it, see leaguesLock.renewSession). So input in the last seconds before expiry
 * renews immediately instead of waiting for a tick that would find the session expired (CR-01). The tick is a
 * safety net that does the same for input the handler ran too early to act on.
 *
 * LOCKING: a setTimeout armed for the exact stored expiry calls lock('idle'), so league data leaves the screen
 * at expiry, not at the next tick (CR-05). It is re-armed whenever the expiry changes. visibilitychange, focus and
 * pageshow re-check at once (sleep/wake, a clock jump, a tab brought back from the background). A hidden tab never
 * renews (it has no input), so it expires on its own.
 *
 * A single failed renewal (a network blip) does nothing: only the expiry, or the server's 401 inside apiFetch,
 * ends the session.
 *
 * WORST CASE from the last input to the lock screen is the window itself (300 s by default), plus the timer's own
 * slop of about a second, and it is shorter than that, between half the window and the whole window, when the
 * last input came before the session was past half (nothing renewed it, so it ends on its original schedule).
 * A throttled background tab can fire its timer up to about a minute late, but its data is not on screen then and
 * the visibilitychange check locks it the moment it is shown again. The 401 path (a stale session sent after
 * expiry) locks too.
 */
export function useIdleLock(enabled: boolean) {
  useEffect(() => {
    if (!enabled) return
    let activeSinceTick = false
    let inFlight = false
    let lastRenewSentAt = 0
    let expiryTimer: number | undefined
    let disposed = false

    const arm = () => {
      if (expiryTimer !== undefined) window.clearTimeout(expiryTimer)
      expiryTimer = undefined
      const at = sessionExpiresAt()
      if (at === null || disposed) return
      expiryTimer = window.setTimeout(onExpiryTimer, Math.max(0, at - Date.now()))
    }
    const onExpiryTimer = () => {
      expiryTimer = undefined
      if (disposed) return
      // The expiry moved since this was armed (a renewal, or another tab): re-arm for the new one instead.
      if (sessionExpired()) lock('idle')
      else arm()
    }

    const pastHalf = () => {
      const at = sessionExpiresAt()
      const windowMs = sessionWindowMs()
      return at !== null && windowMs !== null && at - Date.now() < windowMs / 2
    }

    const renew = () => {
      if (inFlight || Date.now() - lastRenewSentAt < RENEW_SPACING_MS) return
      inFlight = true
      lastRenewSentAt = Date.now()
      renewLeagueSession()
        .catch(() => {
          // A network blip: the session is still good until its expiry; the next input tries again.
        })
        .finally(() => {
          inFlight = false
          arm()
        })
    }

    const maybeRenew = () => {
      if (document.visibilityState !== 'visible' || sessionExpired()) return
      if (pastHalf()) renew()
    }

    const onActivity = () => {
      activeSinceTick = true
      noteActivity()
      maybeRenew()
    }
    for (const type of ACTIVITY_EVENTS) window.addEventListener(type, onActivity, { passive: true, capture: true })

    const check = () => {
      if (sessionExpired()) {
        lock('idle')
        return
      }
      if (activeSinceTick && Date.now() - lastActivity() <= IDLE_TICK_MS * 2) {
        activeSinceTick = false
        maybeRenew()
      }
      arm()
    }
    const timer = window.setInterval(check, IDLE_TICK_MS)

    const recheck = () => {
      if (sessionExpired()) lock('idle')
      else arm()
    }
    const onVisible = () => {
      if (document.visibilityState === 'visible') recheck()
    }
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('focus', recheck)
    window.addEventListener('pageshow', recheck)

    arm()

    return () => {
      disposed = true
      window.clearInterval(timer)
      if (expiryTimer !== undefined) window.clearTimeout(expiryTimer)
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('focus', recheck)
      window.removeEventListener('pageshow', recheck)
      for (const type of ACTIVITY_EVENTS) window.removeEventListener(type, onActivity, { capture: true })
    }
  }, [enabled])
}
