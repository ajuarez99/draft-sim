import { siteOrigin, type SiteConfig } from './site'

// Spec 073: robots.txt and sitemap.xml, one per site, each naming only its own address. Emitted by the
// Vite plugin in vite.config.ts (they used to be hand-written files in public/, which can only name one
// origin). Pure strings so a test can read all three modes.

export function robotsTxt(cfg: SiteConfig): string {
  // The locked league site asks every crawler to stay out and names no sitemap. A request, not protection.
  if (cfg.site === 'leagues') return `User-agent: *\nDisallow: /\n`
  return `User-agent: *\nAllow: /\n\nSitemap: ${siteOrigin(cfg)}/sitemap.xml\n`
}

/** null for the league site: it is private, so it publishes no sitemap at all. */
export function sitemapXml(cfg: SiteConfig): string | null {
  if (cfg.site === 'leagues') return null
  return (
    `<?xml version="1.0" encoding="UTF-8"?>\n` +
    `<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n` +
    `  <url><loc>${siteOrigin(cfg)}/</loc></url>\n` +
    `</urlset>\n`
  )
}
