import { matchRoutes, type RouteObject } from 'react-router-dom'
import type { Sport } from './api'

/*
 * Spec 052: cookie-free usage counts, through Umami Cloud. The ONLY module that knows the
 * service exists -- nothing else touches window.umami or the service's URL.
 *
 * What may leave the browser is exactly specs/052-site-analytics/data-model.md: a route
 * TEMPLATE (never the pathname), the template's label (never document.title), an outside
 * referrer's origin on the first view, and for league views a sport and a league id. Every
 * payload is built here and handed to `umami.track(object)`. The string form
 * `track(name, data)` is never used: in Umami's own script it merges in the raw URL and
 * document.title, which carry draft, player and manager ids (plan F4).
 *
 * Off unless ALL hold: a build-time website id, a production hostname, and no
 * `localStorage['umami.disabled']` (the same switch the script honours itself, so the owner's
 * opt-out works in both layers). Every call is wrapped: a failure in the tracker must never
 * reach React (FR-007).
 */

type Payload = Record<string, unknown>
type Umami = { track: (payload: Payload) => unknown }

declare global {
  interface Window {
    umami?: Umami
  }
}

export type KeyAction = 'sign-in' | 'league-added' | 'mock-started'

const SCRIPT_SRC = 'https://cloud.umami.is/script.js'
const OWN_HOSTS = ['www.ballknowers.co', 'ballknowers.co']
const QUEUE_CAP = 20

// ---- Route templates ------------------------------------------------------------------------
//
// One entry per <Route path> in App.tsx, plus the template's label. `path` is App.tsx's own
// string (so analytics.test.ts can check the two lists agree); `url` is the same path with the
// params renamed to neutral names. matchRoutes ranks, so /mock/new beats /mock/:sessionId and
// /stats/calculator beats /stats.
type RouteEntry = { path: string; url: string; title: string }

const ROUTES: RouteEntry[] = [
  { path: '/', url: '/', title: 'Home' },
  { path: '/drafts/:draftId', url: '/drafts/:draft', title: 'Draft room' },
  { path: '/drafts/:draftId/board', url: '/drafts/:draft/board', title: 'Completed draft board' },
  { path: '/drafts/:draftId/live', url: '/drafts/:draft/live', title: 'Live draft' },
  { path: '/mock/new', url: '/mock/new', title: 'Mock draft setup' },
  { path: '/mock/:sessionId', url: '/mock/:session', title: 'Mock draft' },
  { path: '/managers', url: '/managers', title: 'Managers' },
  { path: '/managers/:managerId/history', url: '/managers/:manager/history', title: 'Manager history' },
  { path: '/managers/:aId/versus/:bId', url: '/managers/:a/versus/:b', title: 'Manager comparison' },
  { path: '/leagues/:sleeperLeagueId', url: '/leagues/:league', title: 'League home' },
  { path: '/leagues/:sleeperLeagueId/history', url: '/leagues/:league/history', title: 'League history' },
  { path: '/leagues/:sleeperLeagueId/power', url: '/leagues/:league/power', title: 'League power rankings' },
  { path: '/leagues/:sleeperLeagueId/analysis', url: '/leagues/:league/analysis', title: 'League analysis' },
  { path: '/leagues/:sleeperLeagueId/schedule', url: '/leagues/:league/schedule', title: 'Schedule grid' },
  { path: '/leagues/:sleeperLeagueId/rivalries', url: '/leagues/:league/rivalries', title: 'Rivalries' },
  { path: '/leagues/:sleeperLeagueId/adp', url: '/leagues/:league/adp', title: 'ADP compare' },
  { path: '/leagues/:sleeperLeagueId/trends', url: '/leagues/:league/trends', title: 'Player trends' },
  { path: '/leagues/:sleeperLeagueId/stats', url: '/leagues/:league/stats', title: 'Player stats' },
  { path: '/leagues/:sleeperLeagueId/stats/calculator', url: '/leagues/:league/stats/calculator', title: 'Stat calculator' },
  { path: '/leagues/:sleeperLeagueId/players/:sleeperPlayerId', url: '/leagues/:league/players/:player', title: 'Player page' },
  { path: '/leagues/:sleeperLeagueId/compare', url: '/leagues/:league/compare', title: 'Compare players' },
  { path: '/leagues/:sleeperLeagueId/roster-management', url: '/leagues/:league/roster-management', title: 'Roster management' },
  { path: '/leagues/:sleeperLeagueId/expected-wins', url: '/leagues/:league/expected-wins', title: 'Expected wins' },
  { path: '/leagues/:sleeperLeagueId/forecast', url: '/leagues/:league/forecast', title: 'Season forecast' },
  { path: '/leagues/:sleeperLeagueId/weekly-report', url: '/leagues/:league/weekly-report', title: 'Weekly report' },
  { path: '/leagues/:sleeperLeagueId/superlatives', url: '/leagues/:league/superlatives', title: 'Season superlatives' },
  { path: '/leagues/:sleeperLeagueId/weekly-pot', url: '/leagues/:league/weekly-pot', title: 'Weekly pot' },
  { path: '/admin/digest', url: '/admin/digest', title: 'Admin digest' },
  // App.tsx's catch-all. Never the real path: a mistyped URL could contain anything.
  { path: '*', url: '/(not-found)', title: 'Not found' },
]

/** Exported so the coverage test can compare it with App.tsx's <Route path> values. */
export const ROUTE_TEMPLATE_PATHS: string[] = ROUTES.map((r) => r.path)

const ROUTE_OBJECTS: RouteObject[] = ROUTES.map((r) => ({ path: r.path, handle: r }))

/**
 * The route template and label for a pathname. Pure. Signed out, the app renders SignIn at
 * every path (App.tsx), so the visit is a landing visit and is recorded as `/(landing)` plus
 * the template -- a shared link opened signed out stays distinguishable from a league page
 * view (plan F1).
 */
export function routeTemplate(pathname: string, signedIn: boolean): { url: string; title: string } {
  const hit = matchRoutes(ROUTE_OBJECTS, pathname)
  const entry = (hit?.[hit.length - 1]?.route.handle as RouteEntry | undefined) ?? ROUTES[ROUTES.length - 1]
  if (signedIn) return { url: entry.url, title: entry.title }
  if (entry.url === '/') return { url: '/(landing)', title: 'Landing' }
  return { url: `/(landing)${entry.url}`, title: `Landing (from ${entry.title})` }
}

// ---- State ----------------------------------------------------------------------------------

type Status = 'idle' | 'loading' | 'loaded' | 'failed'

let status: Status = 'idle'
let queue: Payload[] = []
let firstViewSent = false
let lastPageKey: string | null = null
let lastLeagueKey: string | null = null

/** Test-only: back to a fresh document. */
export function __resetAnalyticsForTests() {
  status = 'idle'
  queue = []
  firstViewSent = false
  lastPageKey = null
  lastLeagueKey = null
}

// Read lazily (not at import) so a test can set the env and the hostname per case.
function websiteId(): string {
  return (import.meta.env.VITE_UMAMI_WEBSITE_ID ?? '').trim()
}

function debugMode(): boolean {
  return import.meta.env.DEV && import.meta.env.VITE_ANALYTICS_DEBUG === '1'
}

function ownerOptedOut(): boolean {
  try {
    return localStorage.getItem('umami.disabled') != null
  } catch {
    return false // blocked storage counts as unset
  }
}

function enabled(): boolean {
  if (ownerOptedOut()) return false
  if (debugMode()) return true
  return websiteId() !== '' && OWN_HOSTS.includes(location.hostname)
}

// ---- Sending --------------------------------------------------------------------------------

function basePayload(url: string, title: string): Payload {
  return {
    website: websiteId(),
    url,
    title,
    hostname: location.hostname,
    language: navigator.language,
    screen: `${screen.width}x${screen.height}`,
  }
}

function deliver(payload: Payload) {
  try {
    window.umami?.track(payload)
  } catch {
    // The tracker failing is never the visitor's problem.
  }
}

function send(payload: Payload) {
  try {
    if (!enabled()) return
    if (debugMode()) {
      console.debug('[analytics]', payload)
      return
    }
    if (status === 'loaded') deliver(payload)
    else if (status === 'loading' && queue.length < QUEUE_CAP) queue.push(payload)
    // idle (never initialised) and failed (blocked or offline): a no-op.
  } catch {
    // Same: nothing in here may reach React.
  }
}

/** Once, from main.tsx. Adds the tracker script only when analytics is on for this build and host. */
export function initAnalytics(): void {
  try {
    if (status !== 'idle' || !enabled() || debugMode()) return
    status = 'loading'
    const el = document.createElement('script')
    el.async = true
    el.src = SCRIPT_SRC
    el.setAttribute('data-website-id', websiteId())
    // We send every view ourselves, with templates. Never data-errors (its reporter sends stack
    // traces and the raw URL), data-performance, data-distinct-id or data-host-url.
    el.setAttribute('data-auto-track', 'false')
    el.setAttribute('data-domains', OWN_HOSTS.join(','))
    el.addEventListener('load', () => {
      status = 'loaded'
      const pending = queue
      queue = []
      for (const p of pending) deliver(p)
    })
    el.addEventListener('error', () => {
      // Blocked by an extension, or offline: drop what is queued and stay quiet.
      status = 'failed'
      queue = []
    })
    document.head.appendChild(el)
  } catch {
    status = 'failed'
    queue = []
  }
}

/** The outside referrer's origin, or undefined for none / our own site / something unparsable. */
function outsideReferrer(): string | undefined {
  try {
    if (!document.referrer) return undefined
    const u = new URL(document.referrer)
    // Only web referrers. A non-special scheme (Android's android-app://com.slack/) has an opaque
    // origin that serializes as the string "null" (Spec 052 code review #5).
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return undefined
    const h = u.hostname
    if (h === 'ballknowers.co' || h.endsWith('.ballknowers.co')) return undefined
    return u.origin
  } catch {
    return undefined
  }
}

/** One page view per signedIn+pathname change (AppShell's effect; also guards StrictMode doubles). */
export function trackPageView(pathname: string, signedIn: boolean): void {
  try {
    if (!enabled()) return
    const key = `${signedIn}|${pathname}`
    if (key === lastPageKey) return
    lastPageKey = key
    // A new page view re-arms the league-view latch (Spec 052): the same league page visited again
    // after another page is a new league view. Same-page repeats return above, so they never reset it.
    lastLeagueKey = null
    const { url, title } = routeTemplate(pathname, signedIn)
    const payload = basePayload(url, title)
    // The first view of the document only: later views are in-app navigation.
    if (!firstViewSent) {
      const ref = outsideReferrer()
      if (ref) payload.referrer = ref
    }
    firstViewSent = true
    send(payload)
  } catch {
    // never reaches React
  }
}

/** A `league-view` event: which sport and league a league-scoped page was for. Once per signedIn+pathname. */
export function trackLeagueView(pathname: string, signedIn: boolean, sport: Sport, leagueId: string): void {
  try {
    if (!enabled()) return
    const key = `${signedIn}|${pathname}`
    if (key === lastLeagueKey) return
    lastLeagueKey = key
    const { url, title } = routeTemplate(pathname, signedIn)
    send({ ...basePayload(url, title), name: 'league-view', data: { route: url, sport, league: leagueId } })
  } catch {
    // never reaches React
  }
}

/** A named key action, fired by the caller only after the action succeeded. */
export function trackAction(name: KeyAction, data?: Record<string, string>): void {
  try {
    if (!enabled()) return
    // An action carries the page it happened on, as a template like everything else. Sign-in
    // happens signed out, so its page is the landing form.
    const { url, title } = routeTemplate(location.pathname, name !== 'sign-in')
    send({ ...basePayload(url, title), name, ...(data ? { data } : {}) })
  } catch {
    // never reaches React
  }
}
