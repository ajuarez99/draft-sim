import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import appSource from './App.tsx?raw'
import {
  ROUTE_TEMPLATE_PATHS,
  __resetAnalyticsForTests,
  initAnalytics,
  routeTemplate,
  trackAction,
  trackLeagueView,
  trackPageView,
} from './analytics'

// Spec 052. The sink is a fake window.umami; the page's hostname is stubbed per case because
// analytics.ts refuses to run anywhere but the production hosts.

const track = vi.fn()
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
  })

  it('with umami.disabled set (the owner opt-out)', () => {
    onProduction()
    localStorage.setItem('umami.disabled', '1')
    initAnalytics()
    trackPageView('/managers', true)
    expect(scriptEl()).toBeNull()
    expect(track).not.toHaveBeenCalled()
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
    expect(el.getAttribute('data-domains')).toBe('www.ballknowers.co,ballknowers.co')
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

describe('route table vs App.tsx', () => {
  it('has a template for every <Route path> in App.tsx (two lists of one rule)', () => {
    // path="/x", path='/x', path={'/x'} or path={"/x"}. A path held in a variable or a template
    // literal matches none of these, so the count check below fails loudly instead of the route
    // silently falling through to /(not-found).
    const paths = [...appSource.matchAll(/\bpath=(?:\{\s*)?(?:"([^"]+)"|'([^']+)')(?:\s*\})?/g)].map((m) => m[1] ?? m[2])
    const routeCount = [...appSource.matchAll(/<Route\b/g)].length
    expect(paths.length).toBeGreaterThan(20)
    expect(paths.length, 'a <Route> whose path this test cannot parse').toBe(routeCount)
    for (const p of paths) expect(ROUTE_TEMPLATE_PATHS, `no template for ${p}`).toContain(p)
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
