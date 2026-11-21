import { esc } from './htmlEscape'
import { LEAGUE_PREFIXES, siteOrigin, type SiteConfig } from './site'

// Spec 073: robots.txt and sitemap.xml, one per site, each naming only its own address. Emitted by the
// Vite plugin in vite.config.ts (they used to be hand-written files in public/, which can only name one
// origin). Pure strings so a test can read all three modes.
//
// Spec 074: the Time Machine site's sitemap is built from the indexable page list and nothing else (FR-014),
// and each site gets a generated serve config (serveConfig below).

export function robotsTxt(cfg: SiteConfig): string {
  // The locked league site asks every crawler to stay out and names no sitemap. A request, not protection.
  if (cfg.site === 'leagues') return `User-agent: *\nDisallow: /\n`
  return `User-agent: *\nAllow: /\n\nSitemap: ${siteOrigin(cfg)}/sitemap.xml\n`
}

/**
 * A generated page's file name, relative to the output directory ("nba/2016-17.html"): plain path segments only, never
 * absolute, never a dot-dot, no quote or ampersand. The build refuses to write anything else (review-fixes B1).
 */
export function isSafePageFile(file: string): boolean {
  return /^[A-Za-z0-9][A-Za-z0-9._-]*(\/[A-Za-z0-9][A-Za-z0-9._-]*)*$/.test(file) && !file.includes('..')
}

/**
 * null for the league site: it is private, so it publishes no sitemap at all.
 *
 * `pages` (timemachine only) is the indexable page list: one `<url>` per page, the home page as `origin/` and
 * every other as `origin/path` with no trailing slash, the form the server answers 200 on. Without it (the
 * combined build, or a caller with no page list) the sitemap names the home page only, as before.
 */
export function sitemapXml(cfg: SiteConfig, pages?: readonly { path: string }[]): string | null {
  if (cfg.site === 'leagues') return null
  const origin = siteOrigin(cfg)
  const paths = cfg.site === 'timemachine' && pages ? pages.map((p) => p.path) : ['/']
  // XML-escaped: a path is built from a season slug (B1 also validates the slug, so this is the second lock).
  const urls = paths.map((p) => `  <url><loc>${esc(`${origin}${p}`)}</loc></url>\n`).join('')
  return (
    `<?xml version="1.0" encoding="UTF-8"?>\n` +
    `<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n` +
    urls +
    `</urlset>\n`
  )
}

/**
 * The paths on the Time Machine site that are the React app and nothing static: a shared game and the old
 * league-site paths the app forwards. They are rewritten to app-shell.html (a copy of the app page with a
 * static noindex), because a `headers` rule in serve matches the REWRITTEN file, so a header on /game/**
 * would never be sent (research R4, measured). Each league prefix is listed bare and with `/**`, so the
 * bare path is covered whether or not the glob matches it. Every other unknown path is a real 404.
 */
export const APP_ONLY_SOURCES: readonly string[] = [
  '/game/**',
  '/history',
  '/history/**',
  ...LEAGUE_PREFIXES.flatMap((p) => [p, `${p}/**`]),
]

/**
 * The serve config written next to dist (never inside it) for this site. `headers` is web/serve.json's
 * block, carried unchanged. The leagues and combined sites keep what `serve -s` did: every path answers
 * index.html with 200 (a lone `**` rewrite, measured equal to -s). The Time Machine site serves its static
 * pages as files and 404s the rest.
 */
export function serveConfig(cfg: SiteConfig, headers: unknown): Record<string, unknown> {
  if (cfg.site !== 'timemachine') {
    return { rewrites: [{ source: '**', destination: '/index.html' }], headers }
  }
  return {
    cleanUrls: true,
    trailingSlash: false,
    directoryListing: false,
    rewrites: APP_ONLY_SOURCES.map((source) => ({ source, destination: '/app-shell.html' })),
    headers,
  }
}

/**
 * Spec 079: what web/server/server.mjs reads at start (written next to serve.site.json, never inside dist). `apiBase` is the
 * backend the share-preview words and picture are fetched from (VITE_API_BASE, '' when unset), `origin` this site's own address.
 */
export function serverConfig(cfg: SiteConfig, env: { VITE_API_BASE?: string }): { site: string; apiBase: string; origin: string } {
  return { site: cfg.site, apiBase: (env.VITE_API_BASE ?? '').trim().replace(/\/+$/, ''), origin: siteOrigin(cfg) }
}
