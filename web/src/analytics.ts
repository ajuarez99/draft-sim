import { matchRoutes, type RouteObject } from 'react-router-dom'
import { API_TOKEN, apiUrl, type Sport } from './api'
import type { Site } from './site'
import { currentSite } from './siteEnv'

/*
 * Spec 052 + spec 076: cookie-free usage counts, two sinks. The ONLY module that knows either sink exists --
 * nothing else touches window.umami, the service's URL or POST /api/usage.
 *
 * Sink 1, Umami Cloud (spec 052, optional): what may leave the browser is exactly
 * specs/052-site-analytics/data-model.md: a route TEMPLATE (never the pathname), the template's label (never
 * document.title), an outside referrer's origin on the first view, and for league views a sport and a league
 * id. Every payload is built here and handed to `umami.track(object)`. The string form `track(name, data)` is
 * never used: in Umami's own script it merges in the raw URL and document.title, which carry draft, player and
 * manager ids (plan F4). On only when ALL hold: a build-time website id, a production hostname, and no
 * `localStorage['umami.disabled']`.
 *
 * Sink 2, our own counters (spec 076): a bare `fetch` to POST /api/usage with the vocabulary fields and nothing
 * else (specs/076-usage-analytics/data-model.md). Never apiFetch: that adds the signed-in user, the guest id
 * and the league session, and the whole point is that the send carries no identity. The own body is built by
 * its own function, never from the Umami payload. On when not opted out (the same `umami.disabled` switch)
 * AND the host is one of ours, or this is a dev build with VITE_USAGE_COLLECT=1. It needs no website id.
 *
 * The dedupe latches are shared, so both sinks see the same events. Every call is wrapped: a failure in either
 * sink must never reach React (FR-007).
 */

type Payload = Record<string, unknown>
type Umami = { track: (payload: Payload) => unknown }

declare global {
  interface Window {
    umami?: Umami
  }
}

export type KeyAction = 'sign-in' | 'league-added' | 'mock-started' | 'result-shared'

const SCRIPT_SRC = 'https://cloud.umami.is/script.js'
// Spec 073: the league site reports too. Each site sends its own hostname (basePayload), which is what keeps
// the two streams apart (FR-016).
const OWN_HOSTS = ['www.ballknowers.co', 'ballknowers.co', 'leagues.ballknowers.co']
const QUEUE_CAP = 20

// ---- Route templates ------------------------------------------------------------------------
//
// One entry per <Route path> in App.tsx, plus the template's label. `path` is App.tsx's own
// string (so analytics.test.ts can check the two lists agree); `url` is the same path with the
// params renamed to neutral names. matchRoutes ranks, so /mock/new beats /mock/:sessionId and
// /stats/calculator beats /stats.
type RouteEntry = { path: string; url: string; title: string }

// Spec 073: one table per site. A path appears in a table only if that site renders a PAGE there; the
// redirect paths (/history on the Time Machine and league sites, /game/:gameId on the combined one, the
// forwarded league prefixes) are in no table and send no page view -- they sit outside the shells' tracking
// in App.tsx. analytics.test.ts checks each tree's <Route path> values against its table.
const LEAGUE_ROUTES: RouteEntry[] = [
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
  { path: '/admin/usage', url: '/admin/usage', title: 'Admin usage' },
]

const HISTORY_ROUTES: RouteEntry[] = [
  { path: '/history', url: '/history', title: 'Redraft' },
  { path: '/history/:gameId', url: '/history/:game', title: 'Redraft game' },
]

// App.tsx's catch-all. Never the real path: a mistyped URL could contain anything.
const NOT_FOUND: RouteEntry = { path: '*', url: '/(not-found)', title: 'Not found' }

const TIME_MACHINE_ROUTES: RouteEntry[] = [
  { path: '/', url: '/', title: 'Redraft' },
  { path: '/daily', url: '/daily', title: 'Game of the day' },
  { path: '/nba', url: '/nba', title: 'NBA seasons' },
  { path: '/nfl', url: '/nfl', title: 'NFL seasons' },
  // One template per sport, never the slug: the owed check is "visits to the season-page route as a whole" (PR-28).
  { path: '/nba/:season', url: '/nba/:season', title: 'NBA season page' },
  { path: '/nfl/:season', url: '/nfl/:season', title: 'NFL season page' },
  { path: '/game/:gameId', url: '/game/:game', title: 'Redraft game' },
]

const TABLES: Record<Site, RouteEntry[]> = {
  combined: [...LEAGUE_ROUTES, ...HISTORY_ROUTES, NOT_FOUND],
  leagues: [...LEAGUE_ROUTES, NOT_FOUND],
  timemachine: [...TIME_MACHINE_ROUTES, NOT_FOUND],
}

/** Exported so the coverage test can compare each site's table with its tree's <Route path> values. */
export function routesFor(site: Site): { path: string; url: string; title: string }[] {
  return TABLES[site]
}

/**
 * Spec 076 (F2): every string `routeTemplate()` can RETURN for a site, sorted. This, not the table, is what the
 * server accepts as a `route`: signed out on the league and combined sites every entry also has a landing form
 * (`/(landing)` for the home page, `/(landing)<url>` for the rest). The Redraft site has real pages for a guest,
 * so it has no landing forms. usageVocabulary.test.ts walks every entry through routeTemplate to keep this honest.
 */
export function routeVocabulary(site: Site): string[] {
  const out = new Set<string>()
  for (const r of TABLES[site]) {
    out.add(r.url)
    if (site !== 'timemachine') out.add(r.url === '/' ? '/(landing)' : `/(landing)${r.url}`)
  }
  return [...out].sort()
}

/** Spec 076: the key actions the server accepts, and for each the closed set of values of each prop. */
export const EVENT_VOCABULARY: Record<KeyAction, Record<string, string[]>> = {
  'sign-in': {},
  'league-added': { via: ['espn', 'id', 'sleeper'] },
  'mock-started': { from: ['live', 'setup'] },
  'result-shared': { daily: ['no', 'yes'], sport: ['nba', 'nfl'], who: ['guest', 'member'] },
}

const OBJECTS: Record<Site, RouteObject[]> = {
  combined: TABLES.combined.map((r) => ({ path: r.path, handle: r })),
  leagues: TABLES.leagues.map((r) => ({ path: r.path, handle: r })),
  timemachine: TABLES.timemachine.map((r) => ({ path: r.path, handle: r })),
}

/**
 * The route template and label for a pathname. Pure. Signed out, the app renders SignIn at
 * every path (App.tsx), so the visit is a landing visit and is recorded as `/(landing)` plus
 * the template -- a shared link opened signed out stays distinguishable from a league page
 * view (plan F1).
 */
export function routeTemplate(pathname: string, signedIn: boolean): { url: string; title: string } {
  const hit = matchRoutes(OBJECTS[currentSite().site], pathname)
  const entry = (hit?.[hit.length - 1]?.route.handle as RouteEntry | undefined) ?? NOT_FOUND
  // Spec 074 (PR-09): the Redraft site has real pages for a guest, so a guest's view is recorded under its route like
  // anyone's. The "landing" record is for the other sites, where signed out means one landing page at every URL.
  if (signedIn || currentSite().site === 'timemachine') return { url: entry.url, title: entry.title }
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

function umamiEnabled(): boolean {
  if (ownerOptedOut()) return false
  if (debugMode()) return true
  return websiteId() !== '' && OWN_HOSTS.includes(location.hostname)
}

function ownEnabled(): boolean {
  if (ownerOptedOut()) return false
  if (debugMode()) return true
  return OWN_HOSTS.includes(location.hostname) || (import.meta.env.DEV && import.meta.env.VITE_USAGE_COLLECT === '1')
}

/** Neither sink on: every public function returns before touching a latch. */
function anySinkEnabled(): boolean {
  return umamiEnabled() || ownEnabled()
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
    if (!umamiEnabled()) return
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

// The own sink's body: exactly the vocabulary fields (data-model.md "What the browser sends"). Built from the
// site and the route template or the event, never from the Umami payload, so a field added there cannot leak here.
type OwnBody =
  | { kind: 'view'; site: Site; route: string }
  | { kind: 'league-view'; site: Site; route: string; league: string }
  | { kind: 'event'; site: Site; name: string; props: Record<string, string> }

function sendOwn(body: OwnBody) {
  try {
    if (!ownEnabled()) return
    if (debugMode()) {
      console.debug('[analytics:own]', body)
      return
    }
    // Bare fetch, on purpose: no X-Sleeper-User, X-Guest-Id, X-League-Session or X-Admin-Token. The bearer token
    // is the one header allowed, and only when the build has one (the same gate every /api call is behind).
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (API_TOKEN) headers.Authorization = `Bearer ${API_TOKEN}`
    void fetch(apiUrl('/api/usage'), {
      method: 'POST',
      keepalive: true,
      referrerPolicy: 'no-referrer',
      headers,
      body: JSON.stringify(body),
    }).catch(() => {
      // A refused or failed send is nothing the visitor needs to hear about.
    })
  } catch {
    // Same: nothing in here may reach React.
  }
}

/** Once, from main.tsx. Adds the tracker script only when analytics is on for this build and host. */
export function initAnalytics(): void {
  try {
    if (status !== 'idle' || !umamiEnabled() || debugMode()) return
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
    if (!anySinkEnabled()) return
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
    sendOwn({ kind: 'view', site: currentSite().site, route: url })
  } catch {
    // never reaches React
  }
}

/** A `league-view` event: which sport and league a league-scoped page was for. Once per signedIn+pathname. */
export function trackLeagueView(pathname: string, signedIn: boolean, sport: Sport, leagueId: string): void {
  try {
    if (!anySinkEnabled()) return
    const key = `${signedIn}|${pathname}`
    if (key === lastLeagueKey) return
    lastLeagueKey = key
    const { url, title } = routeTemplate(pathname, signedIn)
    send({ ...basePayload(url, title), name: 'league-view', data: { route: url, sport, league: leagueId } })
    // The sport is the server's to look up from the league table; the browser sends the league id only.
    sendOwn({ kind: 'league-view', site: currentSite().site, route: url, league: leagueId })
  } catch {
    // never reaches React
  }
}

/** A named key action, fired by the caller only after the action succeeded. */
export function trackAction(name: KeyAction, data?: Record<string, string>): void {
  try {
    if (!anySinkEnabled()) return
    // An action carries the page it happened on, as a template like everything else. Sign-in
    // happens signed out, so its page is the landing form.
    const { url, title } = routeTemplate(location.pathname, name !== 'sign-in')
    send({ ...basePayload(url, title), name, ...(data ? { data } : {}) })
    sendOwn({ kind: 'event', site: currentSite().site, name, props: data ?? {} })
  } catch {
    // never reaches React
  }
}
