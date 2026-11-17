import { describe, expect, it } from 'vitest'
import type { RedraftSeasonData } from './api'
import fixtureJson from './__fixtures__/redraft-seasons.json'
import { appShellPage, indexablePages, notFoundPage, pageForPath } from './redraftPages'
import { makeConfig } from './site'

// Spec 074 T022: the page list the sitemap, the static files and the canonicals are all made from.
const data = fixtureJson as unknown as RedraftSeasonData
const cfg = makeConfig({ VITE_SITE: 'timemachine' })
const pages = indexablePages(cfg, data)
const TM = 'https://www.ballknowers.co'

describe('indexablePages', () => {
  it("is home, daily, both sport indexes, then each sport's seasons in list order", () => {
    expect(pages.map((p) => p.path)).toEqual([
      '/', '/daily', '/nba', '/nfl', '/nba/1976-77', '/nba/2016-17', '/nfl/2006', '/nfl/2007',
    ])
  })

  it('every page has a distinct title, description, canonical and file (FR-009)', () => {
    for (const key of ['title', 'description', 'canonical', 'file'] as const) {
      const values = pages.map((p) => p[key])
      expect(new Set(values).size, key).toBe(values.length)
    }
  })

  it('canonicals are absolute and slashless, except the home page', () => {
    for (const p of pages) {
      expect(p.canonical).toBe(p.path === '/' ? `${TM}/` : `${TM}${p.path}`)
      if (p.path !== '/') expect(p.canonical.endsWith('/')).toBe(false)
    }
  })

  it('files match the addresses cleanUrls serves: nba/index.html for /nba, nba/<label>.html for a season', () => {
    const byPath = Object.fromEntries(pages.map((p) => [p.path, p.file]))
    expect(byPath).toMatchObject({
      '/': 'index.html', '/daily': 'daily.html', '/nba': 'nba/index.html', '/nfl': 'nfl/index.html',
      '/nba/2016-17': 'nba/2016-17.html', '/nfl/2007': 'nfl/2007.html',
    })
  })

  it('every indexable title, description and H1 carries a year or "past" (FR-002)', () => {
    for (const p of pages) {
      for (const text of [p.title, p.description, p.h1]) expect(text, `${p.path}: ${text}`).toMatch(/\b(1[89]|20)\d\d\b|\bpast\b/i)
    }
    expect(pages.find((p) => p.path === '/nba/2016-17')!.h1).toBe('2016-17 NBA Redraft')
  })

  it("a season description names the season and NO player: the outcome is folded as a spoiler, the snippet must not give it away", () => {
    const page = data.NBA.pages.find((x) => x.slug === '2016-17')!
    const d = pages.find((p) => p.path === '/nba/2016-17')!.description
    expect(d).toContain('2016-17')
    expect(d).toContain('NBA')
    for (const who of [page.topOverall[0].name, page.steals[0].name, page.busts[0]?.name].filter(Boolean) as string[]) {
      expect(d).not.toContain(who)
    }
    // still unique per page, because the label is in each
    const all = pages.filter((p) => p.kind === 'season').map((p) => p.description)
    expect(new Set(all).size).toBe(all.length)
  })

  it('JSON-LD: a WebPage plus a breadcrumb per inner page, no rating or review', () => {
    const season = pages.find((p) => p.path === '/nfl/2007')!
    expect(season.jsonLd).toHaveLength(2)
    expect(JSON.stringify(season.jsonLd)).not.toMatch(/aggregateRating|"review"|FAQPage|"Rating"/)
    const crumbs = season.jsonLd.find((b) => (b as { '@type': string })['@type'] === 'BreadcrumbList') as {
      itemListElement: { item: string }[]
    }
    expect(crumbs.itemListElement.map((c) => c.item)).toEqual([`${TM}/`, `${TM}/nfl`, `${TM}/nfl/2007`])
  })

  it('the leagues site has no Redraft pages to list (tmHome throws there)', () => {
    expect(() => indexablePages(makeConfig({ VITE_SITE: 'leagues' }), data)).toThrow()
  })

  it('a sport with no seasons gets no index page', () => {
    const empty = { ...data, NFL: { list: { ...data.NFL.list, seasons: [] }, pages: [] } } as RedraftSeasonData
    expect(indexablePages(cfg, empty).map((p) => p.path)).not.toContain('/nfl')
  })
})

describe('the two private files', () => {
  it('404.html and app-shell.html are noindex and not in the indexable list', () => {
    for (const p of [notFoundPage(cfg), appShellPage(cfg)]) {
      expect(p.noindex).toBe(true)
      expect(pages.map((x) => x.file)).not.toContain(p.file)
    }
    expect(notFoundPage(cfg).file).toBe('404.html')
    expect(appShellPage(cfg).file).toBe('app-shell.html')
  })
})

describe('pageForPath', () => {
  it('finds a page by slashless or trailing-slash path, and nothing for an unknown one', () => {
    expect(pageForPath(pages, '/nba/2016-17')?.slug).toBe('2016-17')
    expect(pageForPath(pages, '/nba/')?.kind).toBe('sport')
    expect(pageForPath(pages, '/')?.kind).toBe('home')
    expect(pageForPath(pages, '/nba/1900')).toBeNull()
  })
})
