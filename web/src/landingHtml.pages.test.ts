import { describe, expect, it } from 'vitest'
import type { RedraftSeasonData } from './api'
import fixtureJson from './__fixtures__/redraft-seasons.json'
import source from '../index.html?raw'
import { pageParts, transform } from './landingHtml'
import { appShellPage, indexablePages, notFoundPage } from './redraftPages'
import { makeConfig } from './site'

// Spec 074 T025: filling one template once per page. The template here is index.html as the build captures it:
// the unfilled page (the real build adds hashed asset tags to it, which this test does not need).
const data = fixtureJson as unknown as RedraftSeasonData
const cfg = makeConfig({ VITE_SITE: 'timemachine' })
const pages = indexablePages(cfg, data)
const fill = (path: string) => {
  const page = pages.find((p) => p.path === path)!
  return transform(source, cfg, pageParts(page, cfg, data))
}
const headOf = (html: string) => html.slice(0, html.indexOf('</head>'))

describe('transform with a page', () => {
  it('writes the page head: its own title, description, canonical, og:url and JSON-LD blocks, and boot.js', () => {
    const out = fill('/nba/2016-17')
    const head = headOf(out)
    expect(head).toContain('<title>2016-17 Fantasy Basketball Redraft | Ball Knowers</title>')
    expect(head).toContain('<link rel="canonical" href="https://www.ballknowers.co/nba/2016-17" />')
    expect(head).toContain('property="og:url" content="https://www.ballknowers.co/nba/2016-17"')
    expect(head).toContain('property="og:image" content="https://www.ballknowers.co/og-image-tm.png"')
    expect(head.match(/application\/ld\+json/g)).toHaveLength(2)
    expect(head).toContain('<script src="/boot.js"></script>')
    expect(head).not.toContain('robots')
    expect(out.match(/<title>/g)).toHaveLength(1)
  })

  it('every JSON-LD block parses and carries no rating or review', () => {
    for (const m of fill('/nfl/2007').matchAll(/<script type="application\/ld\+json">(.*?)<\/script>/gs)) {
      const data = JSON.parse(m[1])
      expect(data['@context']).toBe('https://schema.org')
      expect(m[1]).not.toMatch(/aggregateRating|"review"/)
    }
  })

  it('embeds the season JSON after the root, with "<" escaped, and it parses back to the same page', () => {
    const evil = JSON.parse(JSON.stringify(data)) as RedraftSeasonData
    evil.NBA.pages[1].topOverall[0].name = 'A </script><b>x</b>'
    const page = indexablePages(cfg, evil).find((p) => p.path === '/nba/2016-17')!
    const out = transform(source, cfg, pageParts(page, cfg, evil))
    const m = out.match(/<script type="application\/json" id="redraft-data">(.*?)<\/script>/s)!
    expect(m[1]).not.toContain('<')
    expect(JSON.parse(m[1]).topOverall[0].name).toBe('A </script><b>x</b>')
    expect(out.indexOf('id="redraft-data"')).toBeGreaterThan(out.indexOf('id="root"'))
    expect(out.match(/id="redraft-data"/g)).toHaveLength(1)
  })

  it('a sport index embeds its season list, the home page both lists; daily embeds nothing', () => {
    expect(JSON.parse(fill('/nba').match(/id="redraft-data">(.*?)<\/script>/s)![1]).schema).toBe(1)
    expect(fill('/daily')).not.toContain('redraft-data')
    // Spec 074: the home page's sport tiles read their range and count from the embedded pair (no request on first paint).
    const home = JSON.parse(fill('/').match(/id="redraft-data">(.*?)<\/script>/s)![1])
    expect(home.NBA.seasons.length).toBeGreaterThan(0)
    expect(home.NFL.sport).toBe('NFL')
  })

  it('404.html and the app shell carry the exact static noindex meta, no canonical, and the shell has an empty root', () => {
    const notFound = transform(source, cfg, pageParts(notFoundPage(cfg), cfg, data))
    const shell = transform(source, cfg, pageParts(appShellPage(cfg), cfg, data))
    for (const out of [notFound, shell]) {
      expect(headOf(out)).toContain('<meta name="robots" content="noindex">')
      expect(out).not.toContain('rel="canonical"')
      expect(out).not.toContain('ld+json')
    }
    expect(headOf(notFound)).toContain('<title>Page not found | Ball Knowers Redraft</title>')
    expect(shell).toContain('<div id="root"></div>')
    expect(notFound).not.toContain('<div id="root"></div>')
  })

  it('an indexable page is not noindex', () => {
    for (const p of pages) expect(headOf(fill(p.path)), p.path).not.toMatch(/noindex/)
  })

  it('refuses a template that is already filled, so filling twice cannot pass quietly', () => {
    const once = fill('/daily')
    expect(() => transform(once, cfg, pageParts(pages[1], cfg, data))).toThrow(/empty <div id="root">/)
  })

  // PINNED RULE REWRITTEN (T041): "without a page it is the spec 073 index.html (the front door)". index.html is now the home
  // page: with no season data (a dev server) it is the home page with no tiles, and with data it is the same file the page
  // list writes, byte for byte.
  it('without a page, index.html is the home page (no tiles without season data); with the home parts it is the built one', () => {
    const bare = transform(source, cfg)
    expect(bare).toContain('<title>Ball Knowers Redraft: Draft a Past NBA or NFL Season</title>')
    expect(bare).not.toContain('landing-prerender')
    expect(bare).toContain('class="tm-signin" id="sign-in"')
    expect(bare).not.toContain('class="tm-sport-tile ')
    expect(fill('/')).toContain('class="tm-sport-tile ')
  })
})
