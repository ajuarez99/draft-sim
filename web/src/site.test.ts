import { describe, expect, it } from 'vitest'
import {
  LEAGUE_PREFIXES, crossSiteUrl, makeConfig, parseSite, siteOrigin, tmGame, tmHome,
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
