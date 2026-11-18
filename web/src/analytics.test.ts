import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import appSource from './App.tsx?raw'
import type { Site } from './site'
import {
  __resetAnalyticsForTests,
  initAnalytics,
  routeTemplate,
  routesFor,
  trackAction,
  trackLeagueView,
  trackPageView,
} from './analytics'

// Spec 052. The sink is a fake window.umami; the page's hostname is stubbed per case because
// analytics.ts refuses to run anywhere but the production hosts.

const track = vi.fn()
// Spec 076: the own sink's bare fetch. Every case stubs it, because the production-host cases now reach it.
const fetchMock = vi.fn()
const ownBodies = () => fetchMock.mock.calls.map((c) => JSON.parse((c[1] as RequestInit).body as string) as Record<string, unknown>)
const sentPayloads = () => track.mock.calls.map((c) => c[0] as Record<string, unknown>)

function scriptEl(): HTMLScriptElement | null {
  return document.head.querySelector<HTMLScriptElement>('script[src*="umami"]')
}

function onProduction(hostname = 'www.ballknowers.co') {
  vi.stubGlobal('location', { ...window.location, hostname, pathname: '/' })
  vi.stubEnv('VITE_UMAMI_WEBSITE_ID', 'site-123')
}

/** Initialise and let the script "load", so sends reach window.umami. */
function initAndLoad() {
  initAnalytics()
  scriptEl()!.dispatchEvent(new Event('load'))
}

function setReferrer(value: string) {
  Object.defineProperty(document, 'referrer', { configurable: true, value })
}

beforeEach(() => {
  fetchMock.mockReset()
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
  vi.stubGlobal('fetch', fetchMock)
  track.mockReset()
  ;(window as unknown as { umami?: unknown }).umami = { track }
  __resetAnalyticsForTests()
  localStorage.clear()
  setReferrer('')
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  scriptEl()?.remove()
  delete (window as unknown as { umami?: unknown }).umami
})

describe('when analytics is off', () => {
  it('without a website id: no script, window.umami untouched, no throw', () => {
    vi.stubGlobal('location', { ...window.location, hostname: 'www.ballknowers.co', pathname: '/' })
    vi.stubEnv('VITE_UMAMI_WEBSITE_ID', '')
    expect(() => {
      initAnalytics()
      trackPageView('/leagues/1/power', true)
      trackLeagueView('/leagues/1/power', true, 'nfl', '1')
      trackAction('sign-in')
    }).not.toThrow()
    expect(scriptEl()).toBeNull()
    expect(track).not.toHaveBeenCalled()
  })

  it('on localhost, even with an id', () => {
    vi.stubEnv('VITE_UMAMI_WEBSITE_ID', 'site-123')
    expect(window.location.hostname).toBe('localhost')
    initAnalytics()
    trackPageView('/managers', true)
    expect(scriptEl()).toBeNull()
    expect(track).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('with umami.disabled set (the owner opt-out)', () => {
    onProduction()
    localStorage.setItem('umami.disabled', '1')
    initAnalytics()
    trackPageView('/managers', true)
    expect(scriptEl()).toBeNull()
    expect(track).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('blocked storage counts as not opted out', () => {
    onProduction()
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    try {
      initAnalytics()
      expect(scriptEl()).not.toBeNull()
    } finally {
      spy.mockRestore()
    }
  })

  it('debug mode logs the payload and never loads the script', () => {
    vi.stubEnv('VITE_ANALYTICS_DEBUG', '1')
    const debug = vi.spyOn(console, 'debug').mockImplementation(() => {})
    initAnalytics()
    trackPageView('/managers', true)
    expect(scriptEl()).toBeNull()
    expect(track).not.toHaveBeenCalled()
    expect(debug).toHaveBeenCalledWith('[analytics]', expect.objectContaining({ url: '/managers' }))
    // The own sink logs its body too, and sends nothing.
    expect(debug).toHaveBeenCalledWith('[analytics:own]', { kind: 'view', site: 'combined', route: '/managers' })
    expect(fetchMock).not.toHaveBeenCalled()
    debug.mockRestore()
  })
})

describe('the script element', () => {
  it('is async, auto-track off, domain-limited, and never enables the error reporter', () => {
    onProduction()
    initAnalytics()
    const el = scriptEl()!
    expect(el.src).toBe('https://cloud.umami.is/script.js')
    expect(el.async).toBe(true)
    expect(el.getAttribute('data-website-id')).toBe('site-123')
    expect(el.getAttribute('data-auto-track')).toBe('false')
    // Spec 073: the league site reports too (its own hostname keeps the two streams apart).
    expect(el.getAttribute('data-domains')).toBe('www.ballknowers.co,ballknowers.co,leagues.ballknowers.co')
    for (const a of ['data-errors', 'data-performance', 'data-distinct-id', 'data-host-url']) {
      expect(el.hasAttribute(a), a).toBe(false)
    }
  })
})

describe('routeTemplate', () => {
  const cases: [string, string, string][] = [
    ['/', '/', 'Home'],
    ['/drafts/123456789', '/drafts/:draft', 'Draft room'],
    ['/drafts/123456789/board', '/drafts/:draft/board', 'Completed draft board'],
    ['/drafts/123456789/live', '/drafts/:draft/live', 'Live draft'],
    ['/mock/new', '/mock/new', 'Mock draft setup'],
    ['/mock/9f1c2d3e-aaaa-bbbb-cccc-1234567890ab', '/mock/:session', 'Mock draft'],
    ['/managers', '/managers', 'Managers'],
    ['/managers/987654321012/history', '/managers/:manager/history', 'Manager history'],
    ['/managers/111111111111/versus/222222222222', '/managers/:a/versus/:b', 'Manager comparison'],
    ['/leagues/1122386008709910528', '/leagues/:league', 'League home'],
    ['/leagues/1122386008709910528/history', '/leagues/:league/history', 'League history'],
    ['/leagues/1122386008709910528/power', '/leagues/:league/power', 'League power rankings'],
    ['/leagues/1122386008709910528/analysis', '/leagues/:league/analysis', 'League analysis'],
    ['/leagues/1122386008709910528/schedule', '/leagues/:league/schedule', 'Schedule grid'],
    ['/leagues/1122386008709910528/rivalries', '/leagues/:league/rivalries', 'Rivalries'],
    ['/leagues/1122386008709910528/adp', '/leagues/:league/adp', 'ADP compare'],
    ['/leagues/1122386008709910528/trends', '/leagues/:league/trends', 'Player trends'],
    ['/leagues/1122386008709910528/stats', '/leagues/:league/stats', 'Player stats'],
    ['/leagues/1122386008709910528/stats/calculator', '/leagues/:league/stats/calculator', 'Stat calculator'],
    ['/leagues/1122386008709910528/players/4046', '/leagues/:league/players/:player', 'Player page'],
    ['/leagues/1122386008709910528/compare', '/leagues/:league/compare', 'Compare players'],
    ['/leagues/1122386008709910528/roster-management', '/leagues/:league/roster-management', 'Roster management'],
    ['/leagues/1122386008709910528/expected-wins', '/leagues/:league/expected-wins', 'Expected wins'],
    ['/leagues/1122386008709910528/forecast', '/leagues/:league/forecast', 'Season forecast'],
    ['/leagues/1122386008709910528/weekly-report', '/leagues/:league/weekly-report', 'Weekly report'],
    ['/leagues/1122386008709910528/superlatives', '/leagues/:league/superlatives', 'Season superlatives'],
    ['/leagues/1122386008709910528/weekly-pot', '/leagues/:league/weekly-pot', 'Weekly pot'],
    ['/admin/digest', '/admin/digest', 'Admin digest'],
    ['/admin/usage', '/admin/usage', 'Admin usage'],
  ]

  it.each(cases)('%s -> %s', (path, url, title) => {
    expect(routeTemplate(path, true)).toEqual({ url, title })
  })

  it('does not confuse /mock/new with /mock/:sessionId, or /stats/calculator with /stats', () => {
    expect(routeTemplate('/mock/new', true).url).toBe('/mock/new')
    expect(routeTemplate('/mock/abc', true).url).toBe('/mock/:session')
    expect(routeTemplate('/leagues/1/stats/calculator', true).url).toBe('/leagues/:league/stats/calculator')
    expect(routeTemplate('/leagues/1/stats', true).url).toBe('/leagues/:league/stats')
  })

  it('maps an unmatched path to /(not-found), never echoing the path', () => {
    const t = routeTemplate('/tendencies/some-private-thing-1234567', true)
    expect(t).toEqual({ url: '/(not-found)', title: 'Not found' })
    expect(JSON.stringify(t)).not.toContain('private')
  })

  it('signed out is a landing visit carrying the template it came from', () => {
    expect(routeTemplate('/', false)).toEqual({ url: '/(landing)', title: 'Landing' })
    expect(routeTemplate('/leagues/123456789/power', false)).toEqual({
      url: '/(landing)/leagues/:league/power',
      title: 'Landing (from League power rankings)',
    })
    expect(routeTemplate('/nope/1234567', false).url).toBe('/(landing)/(not-found)')
  })

  it('no url or title ever contains a run of 6+ digits or the username', () => {
    for (const [path] of cases) {
      for (const signedIn of [true, false]) {
        const { url, title } = routeTemplate(`${path}?u=popsharky#frag`, signedIn)
        expect(url + title).not.toMatch(/\d{6,}/)
        expect(url + title).not.toContain('popsharky')
      }
    }
    expect(routeTemplate('/leagues/popsharky/power', true).url).not.toContain('popsharky')
  })
})

// Spec 073: App.tsx holds three route trees, each between @routes markers (the league pages once, shared).
function region(name: string): string {
  const begin = `// @routes:${name} begin`
  const end = `// @routes:${name} end`
  const from = appSource.indexOf(begin)
  const to = appSource.indexOf(end)
  if (from < 0 || to < from) throw new Error(`App.tsx has no @routes:${name} region`)
  return appSource.slice(from + begin.length, to)
}

// path="/x", path='/x', path={'/x'} or path={"/x"}. A path held in a variable or a template literal
// matches none of these, so the count check fails loudly instead of the route silently falling through
// to /(not-found).
function pathsIn(text: string): { paths: string[]; routeCount: number } {
  const paths = [...text.matchAll(/\bpath=(?:\{\s*)?(?:"([^"]+)"|'([^']+)')(?:\s*\})?/g)].map((m) => m[1] ?? m[2])
  return { paths, routeCount: [...text.matchAll(/<Route\b/g)].length }
}

// Redirect routes render no page, so they are in no table and send no page view. Listed here so a new
// redirect has to be named on purpose.
const REDIRECTS: Record<Site, string[]> = {
  combined: ['/game/:gameId'],
  leagues: ['/history', '/history/:gameId'],
  timemachine: ['/history', '/history/:gameId', '/leagues/*', '/drafts/*', '/mock/*', '/managers/*', '/admin/*'],
}

describe('route table vs App.tsx', () => {
  const leagueRegion = region('league-pages')
  const trees: Record<Site, string> = {
    combined: leagueRegion + region('combined'),
    leagues: leagueRegion + region('leagues'),
    timemachine: region('timemachine'),
  }

  it.each(['combined', 'leagues', 'timemachine'] as const)(
    '%s: every <Route path> is a template in the site table or a named redirect, and every template has a route',
    (site) => {
      const { paths, routeCount } = pathsIn(trees[site])
      expect(paths.length).toBeGreaterThan(site === 'timemachine' ? 5 : 20)
      expect(paths.length, 'a <Route> whose path this test cannot parse').toBe(routeCount)
      const table = routesFor(site).map((r) => r.path)
      for (const p of paths) {
        expect(table.includes(p) || REDIRECTS[site].includes(p), `${site}: no template or redirect for ${p}`).toBe(true)
      }
      for (const t of table) expect(paths, `${site}: table entry ${t} has no <Route>`).toContain(t)
      for (const r of REDIRECTS[site]) expect(paths, `${site}: redirect ${r} has no <Route>`).toContain(r)
    },
  )

  it('every <Route> in App.tsx is inside a marked region or listed here (CR-07)', () => {
    // Routes deliberately outside any @routes region. None today; add one here on purpose.
    const OUTSIDE_REGIONS: string[] = []
    const inRegions = ['league-pages', 'combined', 'leagues', 'timemachine']
      .map((name) => pathsIn(region(name)).routeCount)
      .reduce((a, b) => a + b, 0)
    // Comment lines name <Route> too, so they are left out of the whole-file count.
    const code = appSource.split('\n').filter((l) => !l.trim().startsWith('//')).join('\n')
    const whole = [...code.matchAll(/<Route\b/g)].length
    expect(whole, 'a <Route> outside every @routes region is never compared with a table').toBe(inRegions + OUTSIDE_REGIONS.length)
  })

  it('no redirect path is in any table (a redirecting path sends no page view)', () => {
    for (const site of ['combined', 'leagues', 'timemachine'] as const) {
      const table = routesFor(site).map((r) => r.path)
      for (const r of REDIRECTS[site]) expect(table, `${site} ${r}`).not.toContain(r)
    }
  })

  // PINNED RULE REWRITTEN (T041, spec 074): was "only the two Time Machine pages and the catch-all". The Redraft site now has
  // seven pages; the season pages are one template per sport (never the slug, PR-28).
  it('the Redraft table is its seven pages and the catch-all', () => {
    expect(routesFor('timemachine').map((r) => r.path)).toEqual([
      '/', '/daily', '/nba', '/nfl', '/nba/:season', '/nfl/:season', '/game/:gameId', '*',
    ])
  })

  it('the league table has no Time Machine route; the combined table keeps both /history routes', () => {
    expect(routesFor('leagues').map((r) => r.path)).not.toContain('/history')
    expect(routesFor('leagues').map((r) => r.path)).not.toContain('/history/:gameId')
    expect(routesFor('combined').map((r) => r.path)).toEqual(expect.arrayContaining(['/history', '/history/:gameId']))
  })
})

describe('routeTemplate per site', () => {
  afterEach(() => vi.unstubAllEnvs())

  // PINNED RULE REWRITTEN (T041): signed out used to be recorded as "/(landing)" plus the template, because the site showed one
  // landing page at every URL. A guest on the Redraft site now views real pages and is recorded under their route (PR-09).
  it('timemachine: each page has its template, a guest is recorded under it, anything else is not found', () => {
    vi.stubEnv('VITE_SITE', 'timemachine')
    for (const signedIn of [true, false]) {
      expect(routeTemplate('/', signedIn)).toEqual({ url: '/', title: 'Redraft' })
      expect(routeTemplate('/game/123456789', signedIn)).toEqual({ url: '/game/:game', title: 'Redraft game' })
      expect(routeTemplate('/daily', signedIn)).toEqual({ url: '/daily', title: 'Game of the day' })
      expect(routeTemplate('/nba', signedIn).url).toBe('/nba')
      // One template per sport, never the slug: a season is not a thing the analytics should be able to list.
      expect(routeTemplate('/nba/2016-17', signedIn)).toEqual({ url: '/nba/:season', title: 'NBA season page' })
      expect(routeTemplate('/nfl/2007', signedIn)).toEqual({ url: '/nfl/:season', title: 'NFL season page' })
      expect(routeTemplate('/leagues/123456789/power', signedIn).url).toBe('/(not-found)')
    }
    expect(JSON.stringify([routeTemplate('/nba/2016-17', false), routeTemplate('/nfl/2007', false)])).not.toMatch(/2016|2007/)
  })

  it('leagues: no Time Machine template; /history is not a page there', () => {
    vi.stubEnv('VITE_SITE', 'leagues')
    expect(routeTemplate('/history', true).url).toBe('/(not-found)')
    expect(routeTemplate('/leagues/1/power', true).url).toBe('/leagues/:league/power')
    expect(routeTemplate('/', true)).toEqual({ url: '/', title: 'Home' })
  })

  it('combined: /history and /history/:id keep their templates', () => {
    expect(routeTemplate('/history', true)).toEqual({ url: '/history', title: 'Redraft' })
    expect(routeTemplate('/history/42', true)).toEqual({ url: '/history/:game', title: 'Redraft game' })
  })
})

describe('the league site reports under its own host', () => {
  it('runs on leagues.ballknowers.co and sends that hostname', () => {
    onProduction('leagues.ballknowers.co')
    initAndLoad()
    trackPageView('/leagues/1122386008709910528/power', true)
    expect(sentPayloads()[0]).toMatchObject({ hostname: 'leagues.ballknowers.co', url: '/leagues/:league/power' })
  })
})

describe('sending', () => {
  beforeEach(() => onProduction())

  it('builds a full object payload with the documented fields only', () => {
    initAndLoad()
    trackPageView('/leagues/1122386008709910528/power', true)
    const [p] = sentPayloads()
    expect(Object.keys(p).sort()).toEqual(['hostname', 'language', 'screen', 'title', 'url', 'website'])
    expect(p).toMatchObject({
      website: 'site-123',
      url: '/leagues/:league/power',
      title: 'League power rankings',
      hostname: 'www.ballknowers.co',
    })
  })

  it('every track call passes an object, never the string form', () => {
    initAndLoad()
    trackPageView('/managers', true)
    trackLeagueView('/leagues/1/power', true, 'nba', '1')
    trackAction('league-added', { via: 'espn' })
    expect(track).toHaveBeenCalledTimes(3)
    for (const [first] of track.mock.calls) expect(typeof first).toBe('object')
  })

  it('league-view and actions carry only name plus the documented data', () => {
    initAndLoad()
    trackLeagueView('/leagues/1122386008709910528/power', true, 'nba', '1122386008709910528')
    trackAction('mock-started', { from: 'live' })
    const [league, action] = sentPayloads()
    expect(league).toMatchObject({
      name: 'league-view',
      url: '/leagues/:league/power',
      data: { route: '/leagues/:league/power', sport: 'nba', league: '1122386008709910528' },
    })
    expect(action).toMatchObject({ name: 'mock-started', data: { from: 'live' } })
  })

  it('the same pathname twice is one send; flipping signedIn is two', () => {
    initAndLoad()
    trackPageView('/managers', true)
    trackPageView('/managers', true)
    expect(track).toHaveBeenCalledTimes(1)
    trackPageView('/managers', false)
    expect(track).toHaveBeenCalledTimes(2)
    expect(sentPayloads()[1].url).toBe('/(landing)/managers')
  })

  it('a league view is latched per signedIn+pathname', () => {
    initAndLoad()
    trackLeagueView('/leagues/1/power', true, 'nfl', '1')
    trackLeagueView('/leagues/1/power', true, 'nfl', '1')
    trackLeagueView('/leagues/2/power', true, 'nfl', '2')
    expect(track).toHaveBeenCalledTimes(2)
  })

  it('a return visit to a league page is a new league view (the latch re-arms on a page change)', () => {
    initAndLoad()
    trackPageView('/leagues/1/power', true)
    trackLeagueView('/leagues/1/power', true, 'nfl', '1')
    trackPageView('/managers', true)
    trackPageView('/leagues/1/power', true)
    trackLeagueView('/leagues/1/power', true, 'nfl', '1')
    const sends = sentPayloads().map((p) => (p.name ? `${p.name}:${p.url}` : `pv:${p.url}`))
    expect(sends).toEqual([
      'pv:/leagues/:league/power',
      'league-view:/leagues/:league/power',
      'pv:/managers',
      'pv:/leagues/:league/power',
      'league-view:/leagues/:league/power',
    ])
  })

  it('a same-page repeat does not re-arm the latch', () => {
    initAndLoad()
    trackPageView('/leagues/1/power', true)
    trackLeagueView('/leagues/1/power', true, 'nfl', '1')
    trackPageView('/leagues/1/power', true)
    trackLeagueView('/leagues/1/power', true, 'nfl', '1')
    expect(sentPayloads().filter((p) => p.name === 'league-view')).toHaveLength(1)
  })

  it('queues before the script loads, flushes on load', () => {
    initAnalytics()
    trackPageView('/managers', true)
    trackAction('sign-in')
    expect(track).not.toHaveBeenCalled()
    scriptEl()!.dispatchEvent(new Event('load'))
    expect(track).toHaveBeenCalledTimes(2)
  })

  it('caps the queue at 20', () => {
    initAnalytics()
    for (let i = 0; i < 30; i++) trackAction('mock-started', { from: 'setup' })
    scriptEl()!.dispatchEvent(new Event('load'))
    expect(track).toHaveBeenCalledTimes(20)
  })

  it('drops the queue on a script error and stays silent afterwards', () => {
    initAnalytics()
    trackPageView('/managers', true)
    scriptEl()!.dispatchEvent(new Event('error'))
    trackPageView('/mock/new', true)
    trackAction('sign-in')
    expect(track).not.toHaveBeenCalled()
  })

  it('a throwing umami.track does not propagate', () => {
    initAndLoad()
    track.mockImplementation(() => {
      throw new Error('tracker bug')
    })
    expect(() => {
      trackPageView('/managers', true)
      trackLeagueView('/leagues/1/power', true, 'nfl', '1')
      trackAction('sign-in')
    }).not.toThrow()
  })

  it('never touches document.cookie (FR-005)', () => {
    const before = document.cookie
    initAndLoad()
    trackPageView('/managers', true)
    trackPageView('/mock/new', true)
    trackAction('sign-in')
    expect(document.cookie).toBe(before)
  })
})

describe('referrer', () => {
  beforeEach(() => onProduction())

  it('an outside referrer rides the first view only, reduced to its origin', () => {
    setReferrer('https://www.google.com/search?q=fantasy+league&secret=1')
    initAndLoad()
    trackPageView('/', false)
    trackPageView('/managers', false)
    const [first, second] = sentPayloads()
    expect(first.referrer).toBe('https://www.google.com')
    expect(second).not.toHaveProperty('referrer')
  })

  it('a non-web referrer (an Android app) is dropped, never sent as "null"', () => {
    setReferrer('android-app://com.slack/')
    initAndLoad()
    trackPageView('/', false)
    expect(sentPayloads()[0]).not.toHaveProperty('referrer')
  })

  it('our own origins are dropped', () => {
    for (const r of ['https://www.ballknowers.co/leagues/1', 'https://ballknowers.co/', 'https://app.ballknowers.co/x']) {
      __resetAnalyticsForTests()
      track.mockReset()
      setReferrer(r)
      initAndLoad()
      trackPageView('/', false)
      expect(sentPayloads()[0], r).not.toHaveProperty('referrer')
      scriptEl()?.remove()
    }
  })
})

// ---- Spec 076: the own sink ---------------------------------------------------------------------

const FORBIDDEN_HEADERS = ['x-sleeper-user', 'x-guest-id', 'x-league-session', 'x-admin-token']
const sentHeaders = (i = 0) => (fetchMock.mock.calls[i][1] as RequestInit).headers as Record<string, string>

describe('the own sink (spec 076)', () => {
  it('sends with no website id at all: Umami off, own counts on', () => {
    vi.stubGlobal('location', { ...window.location, hostname: 'www.ballknowers.co', pathname: '/' })
    vi.stubEnv('VITE_UMAMI_WEBSITE_ID', '')
    initAnalytics()
    trackPageView('/managers', true)
    expect(scriptEl()).toBeNull()
    expect(track).not.toHaveBeenCalled()
    expect(ownBodies()).toEqual([{ kind: 'view', site: 'combined', route: '/managers' }])
  })

  it('both sinks on: one view reaches both, from the same latch', () => {
    onProduction()
    initAndLoad()
    trackPageView('/leagues/1122386008709910528/power', true)
    trackPageView('/leagues/1122386008709910528/power', true)
    expect(track).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(ownBodies()[0]).toEqual({ kind: 'view', site: 'combined', route: '/leagues/:league/power' })
  })

  it('the request is a bare keepalive POST with no identity header and no referrer', () => {
    onProduction()
    trackPageView('/managers', true)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/usage')
    expect(init.method).toBe('POST')
    expect(init.keepalive).toBe(true)
    expect(init.referrerPolicy).toBe('no-referrer')
    expect(Object.keys(sentHeaders())).toEqual(['Content-Type'])
    for (const h of FORBIDDEN_HEADERS) expect(Object.keys(sentHeaders()).map((k) => k.toLowerCase())).not.toContain(h)
  })

  it('a signed-in user, a guest id and a league session never ride on it', () => {
    onProduction()
    localStorage.setItem('bk.user.v1', JSON.stringify({ sleeperUserId: '999', username: 'popsharky', displayName: 'P', avatar: null }))
    localStorage.setItem('bk.guestId', 'guest-abc')
    trackLeagueView('/leagues/1122386008709910528/power', true, 'nba', '1122386008709910528')
    const text = JSON.stringify(fetchMock.mock.calls[0])
    expect(text).not.toContain('999')
    expect(text).not.toContain('popsharky')
    expect(text).not.toContain('guest-abc')
  })

  it('the body holds only vocabulary fields for each of the three kinds', () => {
    onProduction()
    trackPageView('/managers', false)
    trackLeagueView('/leagues/1122386008709910528/power', true, 'nba', '1122386008709910528')
    trackAction('result-shared', { sport: 'nfl', who: 'guest', daily: 'no' })
    const [view, league, event] = ownBodies()
    expect(view).toEqual({ kind: 'view', site: 'combined', route: '/(landing)/managers' })
    // No sport on the wire for a league view: the server reads it from its own league table.
    expect(league).toEqual({ kind: 'league-view', site: 'combined', route: '/leagues/:league/power', league: '1122386008709910528' })
    expect(event).toEqual({ kind: 'event', site: 'combined', name: 'result-shared', props: { sport: 'nfl', who: 'guest', daily: 'no' } })
    for (const body of ownBodies()) {
      expect(JSON.stringify(body)).not.toMatch(/website|hostname|language|screen|title|referrer|pathname/)
    }
  })

  it('an event with no props sends an empty props object', () => {
    onProduction()
    trackAction('sign-in')
    expect(ownBodies()[0]).toMatchObject({ kind: 'event', name: 'sign-in', props: {} })
  })

  it('the site is the one this build is', () => {
    onProduction('leagues.ballknowers.co')
    vi.stubEnv('VITE_SITE', 'leagues')
    trackPageView('/', true)
    vi.stubEnv('VITE_SITE', 'timemachine')
    trackPageView('/daily', true)
    expect(ownBodies()).toEqual([
      { kind: 'view', site: 'leagues', route: '/' },
      { kind: 'view', site: 'timemachine', route: '/daily' },
    ])
  })

  it('the bearer token is the only extra header, and only when the build has one', async () => {
    vi.resetModules()
    vi.stubEnv('VITE_API_TOKEN', 'tok-123')
    const fresh = await import('./analytics')
    onProduction()
    fresh.trackPageView('/managers', true)
    expect(sentHeaders()).toEqual({ 'Content-Type': 'application/json', Authorization: 'Bearer tok-123' })
  })

  it('opting out stops both sinks', () => {
    onProduction()
    localStorage.setItem('umami.disabled', '1')
    initAnalytics()
    trackPageView('/managers', true)
    trackLeagueView('/leagues/1/power', true, 'nfl', '1')
    trackAction('sign-in')
    expect(track).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('localhost sends nothing without the dev switch, and sends with it', () => {
    expect(window.location.hostname).toBe('localhost')
    trackPageView('/managers', true)
    expect(fetchMock).not.toHaveBeenCalled()
    vi.stubEnv('VITE_USAGE_COLLECT', '1')
    trackPageView('/mock/new', true)
    expect(ownBodies()).toEqual([{ kind: 'view', site: 'combined', route: '/mock/new' }])
  })

  it('the dev switch is exactly "1"', () => {
    vi.stubEnv('VITE_USAGE_COLLECT', 'true')
    trackPageView('/managers', true)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('a rejecting fetch never throws into the caller', async () => {
    onProduction()
    fetchMock.mockRejectedValue(new Error('offline'))
    expect(() => {
      trackPageView('/managers', true)
      trackLeagueView('/leagues/1/power', true, 'nfl', '1')
      trackAction('sign-in')
    }).not.toThrow()
    await Promise.resolve() // the swallowed rejection settles without an unhandled one
  })

  it('a fetch that throws synchronously never reaches the caller either', () => {
    onProduction()
    fetchMock.mockImplementation(() => {
      throw new Error('sync boom')
    })
    expect(() => trackPageView('/managers', true)).not.toThrow()
  })

  it('a throwing Umami tracker does not stop the own send', () => {
    onProduction()
    initAndLoad()
    track.mockImplementation(() => {
      throw new Error('tracker bug')
    })
    trackPageView('/managers', true)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
