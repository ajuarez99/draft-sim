import { describe, expect, it } from 'vitest'
import { makeConfig } from './site'
import { isSafePageFile, robotsTxt, serveConfig, sitemapXml } from './siteFiles'

// Spec 073 SC-007 / T005: each site's robots.txt and sitemap.xml name only its own address.
const TM = 'https://www.ballknowers.co'

describe('robots.txt and sitemap.xml per site', () => {
  it('combined (unset) and timemachine name the main address only', () => {
    for (const v of [undefined, 'timemachine']) {
      const cfg = makeConfig({ VITE_SITE: v })
      expect(robotsTxt(cfg)).toBe(`User-agent: *\nAllow: /\n\nSitemap: ${TM}/sitemap.xml\n`)
      expect(sitemapXml(cfg)).toContain(`<loc>${TM}/</loc>`)
      expect(robotsTxt(cfg) + sitemapXml(cfg)).not.toContain('leagues.ballknowers.co')
    }
  })

  it('leagues is locked: robots says Disallow for everyone, names no sitemap, and there is no sitemap', () => {
    const cfg = makeConfig({ VITE_SITE: 'leagues' })
    expect(robotsTxt(cfg)).toBe('User-agent: *\nDisallow: /\n')
    expect(robotsTxt(cfg)).not.toMatch(/Sitemap|Allow:/)
    expect(sitemapXml(cfg)).toBeNull()
  })

  it('combined matches the files that used to live in public/ byte for byte', () => {
    const cfg = makeConfig({})
    expect(robotsTxt(cfg)).toBe('User-agent: *\nAllow: /\n\nSitemap: https://www.ballknowers.co/sitemap.xml\n')
    expect(sitemapXml(cfg)).toBe(
      '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n  <url><loc>https://www.ballknowers.co/</loc></url>\n</urlset>\n',
    )
  })

  it('follows a configured origin', () => {
    const cfg = makeConfig({ VITE_SITE: 'timemachine', VITE_TM_ORIGIN: 'http://localhost:5184/' })
    expect(robotsTxt(cfg)).toContain('Sitemap: http://localhost:5184/sitemap.xml')
    // The locked league site names no origin at all, configured or not.
    expect(robotsTxt(makeConfig({ VITE_SITE: 'leagues', VITE_LEAGUES_ORIGIN: 'http://localhost:5185/' }))).not.toContain('5185')
  })
})

// Spec 074 T026: the Time Machine sitemap is the page list and nothing else; the serve config per site.
describe('sitemap from the page list (spec 074)', () => {
  const tm = makeConfig({ VITE_SITE: 'timemachine' })
  const pages = [{ path: '/' }, { path: '/daily' }, { path: '/nba' }, { path: '/nba/2016-17' }]

  it('lists exactly the pages given, slashless, home as origin/', () => {
    const xml = sitemapXml(tm, pages)!
    const locs = [...xml.matchAll(/<loc>(.*?)<\/loc>/g)].map((m) => m[1])
    expect(locs).toEqual([`${TM}/`, `${TM}/daily`, `${TM}/nba`, `${TM}/nba/2016-17`])
  })

  it('leagues still has none, whatever it is given; combined ignores a page list', () => {
    expect(sitemapXml(makeConfig({ VITE_SITE: 'leagues' }), pages)).toBeNull()
    expect(sitemapXml(makeConfig({}), pages)).toBe(sitemapXml(makeConfig({})))
  })
})

describe('serve config per site (spec 074 T019)', () => {
  const headers = [{ source: '**', headers: [{ key: 'X-Test', value: '1' }] }]

  it('leagues and combined: one ** rewrite to index.html, the headers carried, nothing else', () => {
    for (const site of ['leagues', undefined]) {
      expect(serveConfig(makeConfig({ VITE_SITE: site }), headers)).toEqual({
        rewrites: [{ source: '**', destination: '/index.html' }],
        headers,
      })
    }
  })

  it('timemachine: clean slashless URLs, no listings, app-only paths to app-shell.html', () => {
    const c = serveConfig(makeConfig({ VITE_SITE: 'timemachine' }), headers) as {
      cleanUrls: boolean; trailingSlash: boolean; directoryListing: boolean
      rewrites: { source: string; destination: string }[]; headers: unknown
    }
    expect(c.cleanUrls).toBe(true)
    expect(c.trailingSlash).toBe(false)
    expect(c.directoryListing).toBe(false)
    expect(c.headers).toBe(headers)
    expect(c.rewrites.every((r) => r.destination === '/app-shell.html')).toBe(true)
    const sources = c.rewrites.map((r) => r.source)
    for (const s of ['/game/**', '/history', '/history/**', '/leagues/**', '/drafts/**', '/mock/**', '/managers/**', '/admin/**'])
      expect(sources).toContain(s)
    // The static pages are files, never rewritten.
    for (const s of sources) expect(s).not.toMatch(/^\/(nba|nfl|daily)/)
    expect(sources).not.toContain('**')
  })
})

describe('what the build writes (review-fixes B1)', () => {
  it('a sitemap location is XML-escaped', () => {
    const cfg = makeConfig({ VITE_SITE: 'timemachine' })
    const xml = sitemapXml(cfg, [{ path: '/a&b' }, { path: '/x"y<z' }])!
    expect(xml).toContain('<loc>https://www.ballknowers.co/a&amp;b</loc>')
    expect(xml).toContain('&quot;')
    expect(xml).not.toMatch(/<loc>[^<]*(&(?!amp;|quot;|lt;|gt;)|")[^<]*<\/loc>/)
  })

  it.each(['nba/2016-17.html', 'nfl/2007.html', 'daily.html', '404.html', 'app-shell.html', 'nba/index.html'])('%s is a plain page file', (f) => {
    expect(isSafePageFile(f)).toBe(true)
  })

  it.each(['../x.html', 'nba/../../x.html', '/etc/passwd', 'C:\\x.html', 'nba/a"b.html', 'nba/a&b.html', '', 'nba//x.html', 'nba/.hidden', 'a b.html'])(
    '%j is refused: the build writes nothing outside its output directory',
    (f) => {
      expect(isSafePageFile(f)).toBe(false)
    },
  )
})
