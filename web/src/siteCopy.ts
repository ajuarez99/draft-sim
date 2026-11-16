import { analyticsLine as leagueAnalyticsLine, signInNote as leagueSignInNote } from './landingCopy'
import { analyticsLine as tmAnalyticsLine, signInNote as tmSignInNote } from './tmLandingCopy'
import type { SiteConfig } from './site'

// Spec 073 (plan review PR-02): the few shared screens that carried league wording (the not-found
// page, the error boundary, the sign-in note, the analytics disclosure) take their words from here,
// so the Time Machine site never says "leagues". `combined` and `leagues` read exactly as before.

export interface SiteCopy {
  /** "Back to ___" target name, sentence-cased by the caller. Both links point at "/". */
  homeLabel: string
  /** Replaces the not-found page's per-kind sentence when set. */
  notFoundBody: string | null
  signInNote: string
  analyticsLine: string
}

export function siteCopy(cfg: SiteConfig): SiteCopy {
  if (cfg.site === 'timemachine') {
    return {
      homeLabel: 'Time Machine',
      notFoundBody: 'No page at this address.',
      signInNote: tmSignInNote,
      analyticsLine: tmAnalyticsLine,
    }
  }
  return { homeLabel: 'your leagues', notFoundBody: null, signInNote: leagueSignInNote, analyticsLine: leagueAnalyticsLine }
}
