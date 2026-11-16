import { describe, expect, it } from 'vitest'
import { makeConfig } from './site'
import { robotsTxt, sitemapXml } from './siteFiles'

// Spec 073 SC-007 / T005: each site's robots.txt and sitemap.xml name only its own address.
const TM = 'https://www.ballknowers.co'
const LG = 'https://leagues.ballknowers.co'

describe('robots.txt and sitemap.xml per site', () => {
  it('combined (unset) and timemachine name the main address only', () => {
    for (const v of [undefined, 'timemachine']) {
      const cfg = makeConfig({ VITE_SITE: v })
      expect(robotsTxt(cfg)).toBe(`User-agent: *\nAllow: /\n\nSitemap: ${TM}/sitemap.xml\n`)
      expect(sitemapXml(cfg)).toContain(`<loc>${TM}/</loc>`)
      expect(robotsTxt(cfg) + sitemapXml(cfg)).not.toContain('leagues.ballknowers.co')
    }
  })

  it('leagues names the league address only', () => {
    const cfg = makeConfig({ VITE_SITE: 'leagues' })
    expect(robotsTxt(cfg)).toContain(`Sitemap: ${LG}/sitemap.xml`)
    expect(sitemapXml(cfg)).toContain(`<loc>${LG}/</loc>`)
    expect(robotsTxt(cfg) + sitemapXml(cfg)).not.toContain('www.ballknowers.co')
  })

  it('combined matches the files that used to live in public/ byte for byte', () => {
    const cfg = makeConfig({})
    expect(robotsTxt(cfg)).toBe('User-agent: *\nAllow: /\n\nSitemap: https://www.ballknowers.co/sitemap.xml\n')
    expect(sitemapXml(cfg)).toBe(
      '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n  <url><loc>https://www.ballknowers.co/</loc></url>\n</urlset>\n',
    )
  })

  it('follows a configured origin', () => {
    const cfg = makeConfig({ VITE_SITE: 'leagues', VITE_LEAGUES_ORIGIN: 'http://localhost:5185/' })
    expect(robotsTxt(cfg)).toContain('Sitemap: http://localhost:5185/sitemap.xml')
  })
})
