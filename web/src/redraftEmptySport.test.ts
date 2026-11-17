import { describe, expect, it } from 'vitest'
import type { RedraftSeasonData } from './api'
import fixtureJson from './__fixtures__/redraft-seasons.json'
import { pageParts } from './landingHtml'
import { EMPTY_REDRAFT_DATA, indexablePages, offeredSportsOf } from './redraftPages'
import { dailyBody, homeBody, notFoundBody, seasonBody, sportIndexBody, staticBodyFor } from './seasonPageHtml'
import { makeConfig } from './site'
import { sitemapXml } from './siteFiles'

// CR-08: with REDRAFT_ALLOW_EMPTY_SPORT=1 a sport that has no seasons builds with no pages for it, and every place that
// would link to it leaves it out together: the page list, the sitemap, the home tiles, the footer, the 404, the head hint.
const full = fixtureJson as unknown as RedraftSeasonData
const noNfl: RedraftSeasonData = { ...full, NFL: { list: { ...full.NFL.list, seasons: [] }, pages: [] } }
const cfg = makeConfig({ VITE_SITE: 'timemachine' })
const hrefs = (html: string) =>
  [...new DOMParser().parseFromString(html, 'text/html').querySelectorAll('a[href]')].map((a) => a.getAttribute('href') ?? '')

describe('a build with no NFL seasons', () => {
  it('knows which sports it offers (both when it has nothing at all, so a dev server keeps its navigation)', () => {
    expect(offeredSportsOf(full)).toEqual(['NBA', 'NFL'])
    expect(offeredSportsOf(noNfl)).toEqual(['NBA'])
    expect(offeredSportsOf(EMPTY_REDRAFT_DATA)).toEqual(['NBA', 'NFL'])
  })

  it('the page list has no /nfl index and no football season page, and the sitemap names none of them', () => {
    const pages = indexablePages(cfg, noNfl)
    expect(pages.map((p) => p.path)).not.toContain('/nfl')
    expect(pages.filter((p) => p.path.startsWith('/nfl'))).toEqual([])
    expect(pages.map((p) => p.path)).toContain('/nba')
    const sm = sitemapXml(cfg, pages)!
    expect(sm).not.toMatch(/\/nfl/)
    expect(sm).toContain('/nba/2016-17')
  })

  it('no page of any kind links to /nfl, and each still links home', () => {
    for (const html of [
      homeBody(cfg, noNfl),
      dailyBody(cfg, ['NBA']),
      notFoundBody(cfg, ['NBA']),
      sportIndexBody(cfg, 'NBA', noNfl),
      seasonBody(cfg, noNfl.NBA.pages[0], ['NBA']),
    ]) {
      expect(hrefs(html).filter((h) => h.startsWith('/nfl'))).toEqual([])
      expect(hrefs(html)).toContain('/')
    }
    // and the same through the dispatcher the build uses
    for (const page of indexablePages(cfg, noNfl)) {
      expect(hrefs(staticBodyFor(page, cfg, noNfl)).filter((h) => h.startsWith('/nfl')), page.path).toEqual([])
    }
  })

  it('tells the mounted footer, 404 and shared result which sports exist, in the head, only when one is missing', () => {
    const home = indexablePages(cfg, noNfl)[0]
    expect(pageParts(home, cfg, noNfl).head).toContain('<meta name="redraft-sports" content="NBA" />')
    const fullHome = indexablePages(cfg, full)[0]
    expect(pageParts(fullHome, cfg, full).head).not.toContain('redraft-sports')
  })

  it('V-03: the static daily cards leave out the sport with no seasons (the head and h1 copy stay as written)', () => {
    const cards = (html: string) =>
      [...new DOMParser().parseFromString(html, 'text/html').querySelectorAll('.tm-daily-card')].map((c) => c.className)
    expect(cards(homeBody(cfg, noNfl))).toEqual(['tm-daily-card nba'])
    expect(cards(dailyBody(cfg, ['NBA']))).toEqual(['tm-daily-card nba'])
    expect(homeBody(cfg, noNfl)).not.toContain('Football')
    // a full build keeps both
    expect(cards(homeBody(cfg, full))).toEqual(['tm-daily-card nba', 'tm-daily-card nfl'])
    expect(cards(dailyBody(cfg))).toEqual(['tm-daily-card nba', 'tm-daily-card nfl'])
  })

  it('a full build is unchanged: both sports in the footer and the 404', () => {
    expect(hrefs(notFoundBody(cfg))).toEqual(expect.arrayContaining(['/nba', '/nfl']))
    expect(hrefs(dailyBody(cfg))).toEqual(expect.arrayContaining(['/nba', '/nfl']))
  })
})
