import { describe, expect, it } from 'vitest'
import {
  LEAGUE_PREFIXES, crossSiteUrl, makeConfig, parseSite, siteOrigin, tmDaily, tmGame, tmHome, tmSeason, tmSportIndex,
} from './site'

// Spec 073, contracts/site-routes.md: "Build variable" and "Time Machine path helpers".

describe('parseSite', () => {
  it.each([[undefined], [null], [''], ['   ']])('%j is combined', (raw) => {
    expect(parseSite(raw as string | undefined)).toBe('combined')
  })
  it('accepts the two names', () => {
    expect(parseSite('timemachine')).toBe('timemachine')
    expect(parseSite('leagues')).toBe('leagues')
  })
  it('trims: a Windows "set X=value && cmd" delivers a trailing space', () => {
    expect(parseSite('timemachine ')).toBe('timemachine')
    expect(parseSite(' leagues')).toBe('leagues')
  })
  it('throws on anything else, naming the value and the accepted ones', () => {
    expect(() => parseSite('leauges')).toThrow(/leauges/)
    expect(() => parseSite('leauges')).toThrow(/timemachine/)
    expect(() => parseSite('leauges')).toThrow(/leagues/)
    expect(() => parseSite('combined')).toThrow()
    expect(() => parseSite('TimeMachine')).toThrow()
  })
})

describe('makeConfig', () => {
  it('defaults the origins and strips trailing slashes', () => {
    expect(makeConfig({})).toEqual({
      site: 'combined', tmOrigin: 'https://www.ballknowers.co', leaguesOrigin: 'https://leagues.ballknowers.co',
    })
    const c = makeConfig({ VITE_SITE: 'leagues', VITE_TM_ORIGIN: 'http://localhost:5184/', VITE_LEAGUES_ORIGIN: 'http://localhost:5185//' })
    expect(c.tmOrigin).toBe('http://localhost:5184')
    expect(c.leaguesOrigin).toBe('http://localhost:5185')
  })
  it('stores the origin only: host lowercased, no path, no trailing slash (CR-02)', () => {
    expect(makeConfig({ VITE_TM_ORIGIN: 'http://LOCALHOST:3000' }).tmOrigin).toBe('http://localhost:3000')
    expect(makeConfig({ VITE_LEAGUES_ORIGIN: 'https://leagues.ballknowers.co/' }).leaguesOrigin).toBe('https://leagues.ballknowers.co')
    expect(makeConfig({ VITE_LEAGUES_ORIGIN: 'https://leagues.ballknowers.co/some/path' }).leaguesOrigin).toBe('https://leagues.ballknowers.co')
  })
  it('throws naming the variable for a value that is not an http(s) origin (CR-02)', () => {
    expect(() => makeConfig({ VITE_LEAGUES_ORIGIN: 'leagues.ballknowers.co' })).toThrow(/VITE_LEAGUES_ORIGIN.*leagues\.ballknowers\.co/)
    expect(() => makeConfig({ VITE_TM_ORIGIN: 'ftp://x.co' })).toThrow(/VITE_TM_ORIGIN/)
    expect(() => makeConfig({ VITE_TM_ORIGIN: 'javascript:alert(1)' })).toThrow(/VITE_TM_ORIGIN/)
    expect(() => makeConfig({ VITE_TM_ORIGIN: '::garbage::' })).toThrow(/VITE_TM_ORIGIN/)
  })
  it('an empty origin is the default (CR-02)', () => {
    expect(makeConfig({ VITE_LEAGUES_ORIGIN: '' }).leaguesOrigin).toBe('https://leagues.ballknowers.co')
    expect(makeConfig({ VITE_TM_ORIGIN: undefined }).tmOrigin).toBe('https://www.ballknowers.co')
  })
  it('a blank origin falls back to the default', () => {
    expect(makeConfig({ VITE_TM_ORIGIN: '  ' }).tmOrigin).toBe('https://www.ballknowers.co')
  })
})

describe('siteOrigin', () => {
  it('is the league origin only for the league site', () => {
    expect(siteOrigin(makeConfig({}))).toBe('https://www.ballknowers.co')
    expect(siteOrigin(makeConfig({ VITE_SITE: 'timemachine' }))).toBe('https://www.ballknowers.co')
    expect(siteOrigin(makeConfig({ VITE_SITE: 'leagues' }))).toBe('https://leagues.ballknowers.co')
  })
})

describe('Time Machine path helpers', () => {
  const combined = makeConfig({})
  const tm = makeConfig({ VITE_SITE: 'timemachine' })
  const lg = makeConfig({ VITE_SITE: 'leagues' })
  it('combined keeps /history', () => {
    expect(tmHome(combined)).toBe('/history')
    expect(tmGame(combined, 42)).toBe('/history/42')
  })
  it('timemachine uses / and /game', () => {
    expect(tmHome(tm)).toBe('/')
    expect(tmGame(tm, 42)).toBe('/game/42')
  })
  it('leagues throws', () => {
    expect(() => tmHome(lg)).toThrow()
    expect(() => tmGame(lg, 42)).toThrow()
  })
})

// CR-04: the Redraft pages are routes of the Redraft site only. A shared page takes its links from here, and a null
// is "no such route on this site", so the component renders no link rather than one that lands on "Nothing here".
describe('Redraft page paths (spec 074 CR-04)', () => {
  const combined = makeConfig({})
  const tm = makeConfig({ VITE_SITE: 'timemachine' })
  const lg = makeConfig({ VITE_SITE: 'leagues' })
  it('timemachine has /daily, the sport indexes and every season', () => {
    expect(tmDaily(tm)).toBe('/daily')
    expect(tmSportIndex(tm, 'NBA')).toBe('/nba')
    expect(tmSportIndex(tm, 'NFL')).toBe('/nfl')
    expect(tmSeason(tm, 'NBA', '2016-17')).toBe('/nba/2016-17')
    expect(tmSeason(tm, 'NFL', '2007')).toBe('/nfl/2007')
  })
  it.each([['combined', combined], ['leagues', lg]])('%s has none of them: each helper answers null, never a path that is not a route', (_n, cfg) => {
    expect(tmDaily(cfg)).toBeNull()
    expect(tmSportIndex(cfg, 'NBA')).toBeNull()
    expect(tmSportIndex(cfg, 'NFL')).toBeNull()
    expect(tmSeason(cfg, 'NBA', '2016-17')).toBeNull()
  })
})

describe('LEAGUE_PREFIXES and crossSiteUrl', () => {
  it('lists the five forwarded prefixes, /admin included', () => {
    expect([...LEAGUE_PREFIXES]).toEqual(['/leagues', '/drafts', '/mock', '/managers', '/admin'])
  })
  it('keeps path, query and hash', () => {
    expect(crossSiteUrl('https://leagues.ballknowers.co/', '/leagues/9/power', '?a=1', '#x')).toBe(
      'https://leagues.ballknowers.co/leagues/9/power?a=1#x',
    )
    expect(crossSiteUrl('https://x.co', '/')).toBe('https://x.co/')
  })
})
