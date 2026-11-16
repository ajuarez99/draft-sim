import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import NotFound from './NotFound'
import { goTo } from '../goTo'
import { crossSiteUrl } from '../site'
import { currentSite, otherOriginUnsetInDev } from '../siteEnv'
import { withCarry } from '../siteCarry'

type Props = {
  /** Which site the old path now lives on. The path, query and hash are kept as they are. */
  target: 'tm' | 'leagues'
}

/**
 * Spec 073: an old address that belongs to the other site (FR-011, FR-012). Replaces the current history
 * entry with the same path, query and hash on the other origin, and shows a one-line fallback with a plain
 * link in case the script is slow. That anchor is the only cross-site link either site renders, and only
 * on a redirect path (FR-015).
 *
 * Three guards:
 *  - the target origin equals this origin (a misconfigured build): not-found, never a redirect loop;
 *  - a dev server with the other site's origin variable unset would forward to production: it shows the
 *    target as text and does not navigate;
 *  - the Time Machine -> league direction carries saved stat-calculator formulas in the fragment (FR-020).
 */
export default function CrossSiteRedirect({ target }: Props) {
  const cfg = currentSite()
  const { pathname, search, hash } = useLocation()
  const targetOrigin = target === 'tm' ? cfg.tmOrigin : cfg.leaguesOrigin

  let url = crossSiteUrl(targetOrigin, pathname, search, hash)
  if (target === 'leagues') {
    try {
      url = withCarry(url, localStorage)
    } catch {
      // Blocked storage: forward without formulas.
    }
  }

  const sameOrigin = targetOrigin === window.location.origin
  const unsetInDev = otherOriginUnsetInDev(target)
  const navigates = !sameOrigin && !unsetInDev

  useEffect(() => {
    if (navigates) goTo(url)
  }, [navigates, url])

  if (sameOrigin) return <NotFound what="page" />

  const host = targetOrigin.replace(/^https?:\/\//, '')
  return (
    <div className="app">
      <div className="content">
        <p className="muted">
          {navigates ? (
            <>
              Taking you to {host}. Not moving? <a href={url}>Continue</a>.
            </>
          ) : (
            <>Local build: this address lives at {url}, which is not set here.</>
          )}
        </p>
      </div>
    </div>
  )
}
