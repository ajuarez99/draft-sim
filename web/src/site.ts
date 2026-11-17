// Spec 073: which site is this build. PURE on purpose -- no import.meta, no process, no
// window -- because vite.config.ts imports it in Node (through landingHtml.ts and
// landingCopy.ts) and the browser reads it too. Everything takes an explicit SiteConfig;
// siteEnv.ts builds one in the browser, vite.config.ts builds one from loadEnv().

export type Site = 'combined' | 'timemachine' | 'leagues'

export type SiteConfig = { site: Site; tmOrigin: string; leaguesOrigin: string }

export const DEFAULT_TM_ORIGIN = 'https://www.ballknowers.co'
export const DEFAULT_LEAGUES_ORIGIN = 'https://leagues.ballknowers.co'

/**
 * Unset or blank is `combined`. Trims first: a Windows `set X=value && cmd` delivers "value ".
 * Anything else throws, so a typo in a Railway variable fails the build rather than quietly
 * shipping the wrong site.
 */
export function parseSite(raw: string | undefined | null): Site {
  const v = (raw ?? '').trim()
  if (v === '') return 'combined'
  if (v === 'timemachine' || v === 'leagues') return v
  throw new Error(`VITE_SITE: unknown value "${v}". Use "timemachine", "leagues", or leave it unset (combined).`)
}

/**
 * Empty (after trim) is the default. Anything else must parse as an http(s) URL; the stored value is
 * `.origin`, so the host is lowercased and a path or trailing slash is dropped. Throws naming the variable,
 * so a scheme-less Railway value fails the build rather than redirecting to a relative path.
 */
function origin(name: string, raw: string | undefined | null, fallback: string): string {
  const v = (raw ?? '').trim()
  if (v === '') return fallback
  let url: URL | null = null
  try {
    url = new URL(v)
  } catch {
    url = null
  }
  if (!url || (url.protocol !== 'http:' && url.protocol !== 'https:')) {
    throw new Error(`${name}: "${v}" is not an http(s) origin. Use something like "https://leagues.ballknowers.co", or leave it unset.`)
  }
  return url.origin
}

export function makeConfig(env: {
  VITE_SITE?: string
  VITE_TM_ORIGIN?: string
  VITE_LEAGUES_ORIGIN?: string
}): SiteConfig {
  return {
    site: parseSite(env.VITE_SITE),
    tmOrigin: origin('VITE_TM_ORIGIN', env.VITE_TM_ORIGIN, DEFAULT_TM_ORIGIN),
    leaguesOrigin: origin('VITE_LEAGUES_ORIGIN', env.VITE_LEAGUES_ORIGIN, DEFAULT_LEAGUES_ORIGIN),
  }
}

/** The canonical origin of the site being built. */
export function siteOrigin(cfg: SiteConfig): string {
  return cfg.site === 'leagues' ? cfg.leaguesOrigin : cfg.tmOrigin
}

/** The Time Machine home path. The league site has none. */
export function tmHome(cfg: SiteConfig): string {
  if (cfg.site === 'leagues') throw new Error('tmHome: the league site has no Redraft game')
  return cfg.site === 'timemachine' ? '/' : '/history'
}

export function tmGame(cfg: SiteConfig, gameId: string | number): string {
  if (cfg.site === 'leagues') throw new Error('tmGame: the league site has no Redraft game')
  return cfg.site === 'timemachine' ? `/game/${gameId}` : `/history/${gameId}`
}

// Spec 074 CR-04: the Redraft pages (/daily, /nba, /nfl, a season) are routes of the Redraft site ONLY. A component
// shared with the combined build (the home, the game room, a shared result) takes these paths from here, and a null
// means "no such route on this site": the component does not render the link. A path literal would assert that the
// route exists on every site. (redraftPages.ts keeps its own literals for the static build, which is Redraft-only.)

export type SportCode = 'NBA' | 'NFL'

const onRedraft = (cfg: SiteConfig) => cfg.site === 'timemachine'

/** The game-of-the-day page, or null where the site has none (combined, leagues). */
export function tmDaily(cfg: SiteConfig): string | null {
  return onRedraft(cfg) ? '/daily' : null
}

/** A sport's season index (`/nba`), or null where the site has none. */
export function tmSportIndex(cfg: SiteConfig, sport: SportCode): string | null {
  return onRedraft(cfg) ? `/${sport.toLowerCase()}` : null
}

/** One season's page (`/nba/2016-17`), or null where the site has none. */
export function tmSeason(cfg: SiteConfig, sport: SportCode, slug: string): string | null {
  return onRedraft(cfg) ? `/${sport.toLowerCase()}/${slug}` : null
}

/** Old league-site paths the Time Machine site forwards to the league site. */
export const LEAGUE_PREFIXES = ['/leagues', '/drafts', '/mock', '/managers', '/admin'] as const

/** `origin` has no trailing slash; `pathname` starts with one. Query and hash are kept as given. */
export function crossSiteUrl(targetOrigin: string, pathname: string, search = '', hash = ''): string {
  return `${targetOrigin.replace(/\/+$/, '')}${pathname}${search}${hash}`
}
