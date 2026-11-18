import type { HistoricalSport, RedraftSeasonData, SeasonPage } from './api'
import * as copy from './redraftCopy'
import { siteOrigin, tmHome, type SiteConfig } from './site'

// Spec 074 T022: the one list of indexable pages. The sitemap, the static HTML files, the canonicals and (in
// Phase 5) each mounted page's document.title are all made from it, so they cannot disagree. PURE and
// Node-safe like site.ts: vite.config.ts imports it.
//
// Paths are slashless (research R4, measured): `/nba` is served from nba/index.html, `/nba/2016-17` from
// nba/2016-17.html, and the trailing-slash form 301s to the slashless one, so canonical, sitemap and links
// all use the slashless form.

export type RedraftPageKind = 'home' | 'daily' | 'sport' | 'season' | 'notFound' | 'appShell'

export type RedraftPage = {
  kind: RedraftPageKind
  /** The address the page answers 200 on: "/", "/daily", "/nba", "/nba/2016-17"; "/404" and "/app-shell" for the two private files. */
  path: string
  /** Where the build writes it, relative to dist. */
  file: string
  title: string
  description: string
  /** Absolute and slashless (the home page keeps its single slash). */
  canonical: string
  /** The H1 text; a year or "past" in every indexable one (FR-002). */
  h1: string
  /** schema.org blocks, one script element each. */
  jsonLd: object[]
  /** true for the two files that must never be indexed: 404.html and app-shell.html. */
  noindex: boolean
  sport?: HistoricalSport
  /** The season label, for a season page. */
  slug?: string
}

export const SPORTS: HistoricalSport[] = ['NBA', 'NFL']
const sportPath = (s: HistoricalSport) => `/${s.toLowerCase()}`
export const seasonPath = (s: HistoricalSport, slug: string) => `${sportPath(s)}/${slug}`
export const sportIndexPath = sportPath
export const DAILY_PATH = '/daily'

/** The absolute, slashless URL of a path on this site (the home page keeps its single slash): what canonical and og:url carry. */
export const canonicalUrl = (cfg: SiteConfig, path: string) => {
  const origin = siteOrigin(cfg)
  return path === '/' ? `${origin}/` : `${origin}${path}`
}

function siteApplication(cfg: SiteConfig) {
  return {
    '@type': 'WebApplication',
    name: copy.brand,
    url: canonicalUrl(cfg, tmHome(cfg)),
    applicationCategory: 'GameApplication',
    operatingSystem: 'Web',
    offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
  }
}

function webPage(cfg: SiteConfig, name: string, description: string, canonical: string) {
  // No Review, Rating or FAQ markup (contracts/pages.md): the pages have none of those.
  return {
    '@context': 'https://schema.org',
    '@type': 'WebPage',
    name,
    description,
    url: canonical,
    isPartOf: siteApplication(cfg),
  }
}

function breadcrumb(cfg: SiteConfig, trail: { name: string; path: string }[]) {
  return {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: trail.map((t, i) => ({
      '@type': 'ListItem',
      position: i + 1,
      name: t.name,
      item: canonicalUrl(cfg, t.path),
    })),
  }
}

/** The title with the trailing " | Ball Knowers" dropped: what a JSON-LD name and an H1-ish label carry. */
const bare = (title: string) => title.replace(/ \| Ball Knowers$/, '')

export type SportRange = { first: string; last: string; count: number }

/**
 * The sports that have at least one season in the build. A sport with none (REDRAFT_ALLOW_EMPTY_SPORT, CR-08) gets no
 * index page, no tile and no footer link; when neither has any (a dev server, a test with no data) both are kept, so
 * the dev site's navigation still works.
 */
export function offeredSportsOf(data: RedraftSeasonData): HistoricalSport[] {
  const some = SPORTS.filter((s) => data[s].list.seasons.length > 0)
  return some.length > 0 ? some : [...SPORTS]
}

export function sportRange(data: RedraftSeasonData, s: HistoricalSport): SportRange | null {
  const seasons = data[s].list.seasons
  if (seasons.length === 0) return null
  return { first: seasons[0].label, last: seasons[seasons.length - 1].label, count: seasons.length }
}

/** The home page's entry, for the index.html a dev server or a test writes with no season data. */
export const homePageEntry = (cfg: SiteConfig): RedraftPage => homePage(cfg)

/** No seasons at all: the shape a page is rendered from when nothing was fetched (the home page then has no tiles). */
export const EMPTY_REDRAFT_DATA: RedraftSeasonData = {
  NBA: { list: { schema: 1, sport: 'NBA', seasons: [] }, pages: [] },
  NFL: { list: { schema: 1, sport: 'NFL', seasons: [] }, pages: [] },
}

function homePage(cfg: SiteConfig): RedraftPage {
  const canonical = canonicalUrl(cfg, '/')
  return {
    kind: 'home',
    path: '/',
    file: 'index.html',
    title: copy.homeTitle,
    description: copy.homeDescription,
    canonical,
    h1: copy.homeH1,
    jsonLd: [{ '@context': 'https://schema.org', ...siteApplication(cfg), description: copy.homeDescription }],
    noindex: false,
  }
}

export function dailyPage(cfg: SiteConfig): RedraftPage {
  const canonical = canonicalUrl(cfg, DAILY_PATH)
  return {
    kind: 'daily',
    path: DAILY_PATH,
    file: 'daily.html',
    title: copy.dailyTitle,
    description: copy.dailyDescription,
    canonical,
    h1: copy.dailyH1,
    jsonLd: [
      webPage(cfg, bare(copy.dailyTitle), copy.dailyDescription, canonical),
      breadcrumb(cfg, [{ name: 'Home', path: '/' }, { name: 'Game of the day', path: DAILY_PATH }]),
    ],
    noindex: false,
  }
}

export function sportPage(cfg: SiteConfig, s: HistoricalSport, r: SportRange): RedraftPage {
  const path = sportPath(s)
  const canonical = canonicalUrl(cfg, path)
  const title = copy.sportIndexTitle(s, r.first, r.last)
  const description = copy.sportIndexDescription(s, r.first, r.last, r.count)
  return {
    kind: 'sport',
    path,
    file: `${s.toLowerCase()}/index.html`,
    title,
    description,
    canonical,
    h1: copy.sportIndexH1(s),
    jsonLd: [
      webPage(cfg, bare(title), description, canonical),
      breadcrumb(cfg, [{ name: 'Home', path: '/' }, { name: copy.footSeasons(s), path }]),
    ],
    noindex: false,
    sport: s,
  }
}

/** A season's entry. Takes only what it reads, so a mounted page can build it from the slug before its data loads. */
export function seasonPageEntry(cfg: SiteConfig, p: Pick<SeasonPage, 'sport' | 'slug' | 'label'>): RedraftPage {
  const path = seasonPath(p.sport, p.slug)
  const canonical = canonicalUrl(cfg, path)
  const title = copy.seasonTitle(p.label, p.sport)
  const description = copy.seasonDescription(p.label, p.sport)
  return {
    kind: 'season',
    path,
    file: `${p.sport.toLowerCase()}/${p.slug}.html`,
    title,
    description,
    canonical,
    h1: copy.seasonH1(p.label, p.sport),
    jsonLd: [
      webPage(cfg, bare(title), description, canonical),
      breadcrumb(cfg, [
        { name: 'Home', path: '/' },
        { name: copy.footSeasons(p.sport), path: sportPath(p.sport) },
        { name: `${p.label} ${p.sport} Redraft`, path },
      ]),
    ],
    noindex: false,
    sport: p.sport,
    slug: p.slug,
  }
}

/** `/`, `/daily`, `/nba`, `/nfl`, then each sport's seasons in list order. A sport with no seasons gets no index page. */
export function indexablePages(cfg: SiteConfig, data: RedraftSeasonData): RedraftPage[] {
  const pages: RedraftPage[] = [homePage(cfg), dailyPage(cfg)]
  for (const s of SPORTS) {
    const r = sportRange(data, s)
    if (r) pages.push(sportPage(cfg, s, r))
  }
  for (const s of SPORTS) for (const p of data[s].pages) pages.push(seasonPageEntry(cfg, p))
  return pages
}

/** 404.html: answers unknown paths with status 404 and must not be indexed (it also answers 200 at /404). */
export function notFoundPage(cfg: SiteConfig): RedraftPage {
  return {
    kind: 'notFound',
    path: '/404',
    file: '404.html',
    title: copy.notFoundTitle,
    description: copy.notFoundBody,
    canonical: canonicalUrl(cfg, '/404'),
    h1: copy.notFoundH1,
    jsonLd: [],
    noindex: true,
  }
}

/** app-shell.html: what /game/** and the old forwarded paths are rewritten to. Empty root, static noindex. */
export function appShellPage(cfg: SiteConfig): RedraftPage {
  return {
    kind: 'appShell',
    path: '/app-shell',
    file: 'app-shell.html',
    title: copy.appShellTitle,
    description: copy.appShellDescription,
    canonical: canonicalUrl(cfg, '/app-shell'),
    h1: copy.appShellTitle,
    jsonLd: [],
    noindex: true,
  }
}

/** The page the browser should be showing at `pathname` (slashless), or null. Phase 5's document.title reads this. */
export function pageForPath(pages: RedraftPage[], pathname: string): RedraftPage | null {
  const p = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname
  return pages.find((x) => x.path === p) ?? null
}
