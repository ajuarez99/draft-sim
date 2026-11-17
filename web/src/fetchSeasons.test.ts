import { describe, expect, it, vi } from 'vitest'
import fixtureJson from './__fixtures__/redraft-seasons.json'
import { loadSeasonData, SeasonDataError, validateSeasonList, validateSeasonPage } from '../scripts/fetchSeasons'

// Spec 074 T024: the build's season-data fetch. No test here reaches a network: fetch is injected.
const BASE = 'https://api.example.test'
const clone = <T,>(x: T): T => JSON.parse(JSON.stringify(x))
const respond = (body: unknown, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body }) as Response

/** A fetch that answers from the fixture, like the backend would. */
function fixtureFetch(mutate?: (url: string, body: unknown) => unknown) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    let body: unknown
    const list = url.match(/seasons\?sport=(NBA|NFL)$/)
    if (list) body = (fixtureJson as Record<string, { list: unknown }>)[list[1]].list
    else {
      const m = url.match(/seasons\/(NBA|NFL)\/(.+)$/)!
      body = (fixtureJson as Record<string, { pages: { slug: string }[] }>)[m[1]].pages.find((p) => p.slug === decodeURIComponent(m[2]))
    }
    return respond(clone(mutate ? mutate(url, clone(body)) : body))
  }) as unknown as typeof fetch
}

const quick = { retryBudgetMs: 50, retryDelayMs: 10, sleep: async () => {} }

describe('loadSeasonData from the backend', () => {
  it('fetches the list then every season, sequentially, and returns what the pages are made from', async () => {
    const fetchImpl = fixtureFetch()
    const log = vi.fn()
    const data = await loadSeasonData({ apiBase: `${BASE}/`, fetchImpl, log, ...quick })
    expect(data.NBA.pages.map((p) => p.slug)).toEqual(['1976-77', '2016-17'])
    expect(data.NFL.pages.map((p) => p.slug)).toEqual(['2006', '2007'])
    const urls = (fetchImpl as unknown as { mock: { calls: unknown[][] } }).mock.calls.map((c) => String(c[0]))
    expect(urls[0]).toBe(`${BASE}/api/history/public/seasons?sport=NBA`)
    expect(urls[1]).toBe(`${BASE}/api/history/public/seasons/NBA/1976-77`)
    expect(urls).toHaveLength(2 + 2 + 2)
    expect(log).toHaveBeenCalledWith(expect.stringContaining(`backend ${BASE}`))
  })

  it('an empty season list throws, naming the URL and the reason', async () => {
    const fetchImpl = fixtureFetch((url, body) => (url.endsWith('sport=NBA') ? { ...(body as object), seasons: [] } : body))
    await expect(loadSeasonData({ apiBase: BASE, fetchImpl, ...quick })).rejects.toThrow(/seasons\?sport=NBA: the NBA season list is empty/)
  })

  it('a wrong or missing schema throws, naming the URL and saying older or newer backend', async () => {
    for (const schema of [2, undefined]) {
      const fetchImpl = fixtureFetch((url, body) => (url.endsWith('sport=NBA') ? { ...(body as object), schema } : body))
      await expect(loadSeasonData({ apiBase: BASE, fetchImpl, ...quick })).rejects.toThrow(/seasons\?sport=NBA: schema is .*expected 1/)
    }
  })

  it('a missing field in a season page throws, naming the URL and the field', async () => {
    const fetchImpl = fixtureFetch((url, body) => {
      if (!url.endsWith('/NBA/2016-17')) return body
      const b = body as Record<string, unknown>
      delete b.pointsNote
      return b
    })
    await expect(loadSeasonData({ apiBase: BASE, fetchImpl, ...quick })).rejects.toThrow(/NBA\/2016-17: page\.pointsNote is missing/)
  })

  it('a mistyped nested field throws with its path', async () => {
    const fetchImpl = fixtureFetch((url, body) => {
      if (!url.endsWith('/NFL/2007')) return body
      const b = body as { busts: { weeksPlayed: unknown }[] }
      b.busts[1].weeksPlayed = '12'
      return b
    })
    await expect(loadSeasonData({ apiBase: BASE, fetchImpl, ...quick })).rejects.toThrow(/NFL\/2007: busts\[1\]\.weeksPlayed is not a number/)
  })

  it('a page that answers with the wrong slug throws', async () => {
    const fetchImpl = fixtureFetch((url, body) => (url.endsWith('/NFL/2006') ? { ...(body as object), slug: '2007' } : body))
    await expect(loadSeasonData({ apiBase: BASE, fetchImpl, ...quick })).rejects.toThrow(/page\.slug is "2007", expected 2006/)
  })

  it('retries a failing request within its budget, then fails naming the URL and the status', async () => {
    const fetchImpl = vi.fn(async () => respond({}, 503)) as unknown as typeof fetch
    const log = vi.fn()
    await expect(
      loadSeasonData({ apiBase: BASE, fetchImpl, log, retryBudgetMs: 35, retryDelayMs: 10, sleep: async () => {} }),
    ).rejects.toThrow(/seasons\?sport=NBA: HTTP 503 \(gave up after \d+ tries/)
    expect((fetchImpl as unknown as { mock: { calls: unknown[] } }).mock.calls.length).toBeGreaterThan(1)
    expect(log).toHaveBeenCalledWith(expect.stringContaining('retrying'))
  })

  it('recovers when a request succeeds on a later try (a backend finishing its deploy)', async () => {
    let n = 0
    const inner = fixtureFetch()
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => (n++ < 2 ? respond({}, 502) : inner(input, init))) as unknown as typeof fetch
    const data = await loadSeasonData({ apiBase: BASE, fetchImpl, retryBudgetMs: 10_000, retryDelayMs: 1, sleep: async () => {} })
    expect(data.NBA.pages).toHaveLength(2)
  })

  it('with no VITE_API_BASE and no fixture it fails and says what to set', async () => {
    await expect(loadSeasonData({ apiBase: '  ' })).rejects.toThrow(/VITE_API_BASE: is not set, and REDRAFT_SEASON_DATA is not given/)
  })
})

describe('loadSeasonData from a fixture file', () => {
  it('reads the file, makes no request, and says which source it used', async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch
    const log = vi.fn()
    const data = await loadSeasonData({ apiBase: BASE, fixturePath: 'src/__fixtures__/redraft-seasons.json', fetchImpl, log })
    expect(data.NBA.pages).toHaveLength(2)
    expect(fetchImpl).not.toHaveBeenCalled()
    expect(log).toHaveBeenCalledWith(expect.stringContaining('fixture src/__fixtures__/redraft-seasons.json'))
  })

  it('an unreadable fixture fails naming the path', async () => {
    await expect(loadSeasonData({ apiBase: '', fixturePath: 'src/__fixtures__/nope.json' })).rejects.toThrow(SeasonDataError)
    await expect(loadSeasonData({ apiBase: '', fixturePath: 'src/__fixtures__/nope.json' })).rejects.toThrow(/nope\.json: cannot read the fixture/)
  })
})

describe('validators', () => {
  const page = (fixtureJson as unknown as { NBA: { pages: Record<string, unknown>[] } }).NBA.pages[0]
  it('accept the fixture', () => {
    expect(validateSeasonPage('t', clone(page), 'NBA', '1976-77').label).toBe('1976-77')
    expect(validateSeasonList('t', clone(fixtureJson.NBA.list), 'NBA').schema).toBe(1)
  })
  it('a null team and a null goingInRank are valid; a missing team is not', () => {
    const p = clone(page) as { topOverall: Record<string, unknown>[] }
    p.topOverall[0].team = null
    p.topOverall[0].goingInRank = null
    expect(() => validateSeasonPage('t', p, 'NBA', '1976-77')).not.toThrow()
    delete p.topOverall[1].team
    expect(() => validateSeasonPage('t', p, 'NBA', '1976-77')).toThrow(/topOverall\[1\]\.team is missing/)
  })
  it('an empty pointsNote is refused: the page must say what points are', () => {
    expect(() => validateSeasonPage('t', { ...clone(page), pointsNote: ' ' }, 'NBA', '1976-77')).toThrow(/pointsNote is empty/)
  })
})

// Review-fixes B1: a season's slug and label become a file name, a canonical URL and a sitemap entry.
describe('slug and label are validated as seasons (B1)', () => {
  const list = () => clone(fixtureJson.NBA.list) as { seasons: { slug: string; label: string }[] }
  const page = () => clone((fixtureJson as unknown as { NBA: { pages: Record<string, unknown>[] } }).NBA.pages[0])

  it.each(['../x', 'a"b', 'a&b', '2016/17', '2016-7', '', '2016-17 ', '<b>'])('a list entry with the slug %j throws, naming it', (bad) => {
    const l = list()
    l.seasons[0].slug = bad
    expect(() => validateSeasonList('t', l, 'NBA')).toThrow(/seasons\[0\]\.slug is .*expected a season like/)
  })

  it.each(['../x', 'a"b', 'a&b'])('a list entry with the label %j throws', (bad) => {
    const l = list()
    l.seasons[0].label = bad
    expect(() => validateSeasonList('t', l, 'NBA')).toThrow(/seasons\[0\]\.label is/)
  })

  it('a page whose slug or label is not a season throws, before it is compared with the one asked for', () => {
    expect(() => validateSeasonPage('t', { ...page(), slug: '../x' }, 'NBA', '../x')).toThrow(/page\.slug is "\.\.\/x", expected a season/)
    expect(() => validateSeasonPage('t', { ...page(), label: 'a&b' }, 'NBA', '1976-77')).toThrow(/page\.label is "a&b"/)
  })

  it('both real forms pass: 2016-17 and 2007', () => {
    expect(() => validateSeasonList('t', clone(fixtureJson.NFL.list), 'NFL')).not.toThrow()
    expect(() => validateSeasonList('t', clone(fixtureJson.NBA.list), 'NBA')).not.toThrow()
  })
})

// CR-08: one empty sport fails the build by default, loudly; REDRAFT_ALLOW_EMPTY_SPORT=1 builds the other sport alone.
describe('a sport with no seasons (CR-08)', () => {
  const emptyNfl = () => fixtureFetch((url, body) => (url.endsWith('sport=NFL') ? { ...(body as object), seasons: [] } : body))

  it('fails by default, and the message says how to opt in', async () => {
    await expect(loadSeasonData({ apiBase: BASE, fetchImpl: emptyNfl(), ...quick })).rejects.toThrow(/the NFL season list is empty/)
    await expect(loadSeasonData({ apiBase: BASE, fetchImpl: emptyNfl(), ...quick })).rejects.toThrow(/REDRAFT_ALLOW_EMPTY_SPORT=1/)
  })

  it('with the opt-in builds the other sport, logs a loud warning, and has no pages for the empty one', async () => {
    const log = vi.fn()
    const data = await loadSeasonData({ apiBase: BASE, fetchImpl: emptyNfl(), allowEmptySport: true, log, ...quick })
    expect(data.NFL.list.seasons).toEqual([])
    expect(data.NFL.pages).toEqual([])
    expect(data.NBA.pages.map((p) => p.slug)).toEqual(['1976-77', '2016-17'])
    const said = log.mock.calls.map((c) => String(c[0])).join('\n')
    expect(said).toMatch(/WARNING: REDRAFT_ALLOW_EMPTY_SPORT=1 and NFL has NO seasons/)
    expect(said).toMatch(/publishes NO NFL pages/)
  })

  it('with the opt-in, BOTH sports empty is still a failure (nothing to publish)', async () => {
    const fetchImpl = fixtureFetch((_url, body) => ({ ...(body as object), seasons: [] }))
    await expect(loadSeasonData({ apiBase: BASE, fetchImpl, allowEmptySport: true, ...quick })).rejects.toThrow(/nothing to publish for either sport/)
  })

  it('with both sports present the opt-in changes nothing and logs no warning', async () => {
    const log = vi.fn()
    await loadSeasonData({ apiBase: BASE, fetchImpl: fixtureFetch(), allowEmptySport: true, log, ...quick })
    expect(log.mock.calls.map((c) => String(c[0])).join('\n')).not.toMatch(/WARNING/)
  })
})
