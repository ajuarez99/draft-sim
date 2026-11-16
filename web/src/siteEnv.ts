import { makeConfig, type SiteConfig } from './site'

/**
 * The browser-side reader of the build variables (the only one: site.ts is pure so Node can
 * import it). A function, read at call time, so a test can vi.stubEnv per case.
 */
export function currentSite(): SiteConfig {
  return makeConfig(import.meta.env)
}

/**
 * True on a dev server (never a production build) when the named site's origin variable was not set, so
 * its origin is only the production default. CrossSiteRedirect uses it to avoid forwarding a local run to
 * the real site. A function for the same reason as currentSite(): tests stub the env per case.
 */
export function otherOriginUnsetInDev(target: 'tm' | 'leagues'): boolean {
  if (!import.meta.env.DEV) return false
  const raw = target === 'tm' ? import.meta.env.VITE_TM_ORIGIN : import.meta.env.VITE_LEAGUES_ORIGIN
  return (raw ?? '').trim() === ''
}
