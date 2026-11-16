import { useState } from 'react'
import { useUser } from '../user'
import { currentSite } from '../siteEnv'
import { withCarry } from '../siteCarry'
import { dismissMovedNotice, movedNoticeDismissed, signedInHere } from '../tmSignedInHere'

/**
 * Spec 073 FR-019 (Allan's decision): a signed-in browser that was signed in at this address before it became
 * the Time Machine site is told, once, where the league pages went. Shown only when the site is `timemachine`,
 * a user is signed in, the "signed in here" marker is absent and the notice was never dismissed. Dismissing it
 * or following its link sets the dismissed key for good; with storage blocked it stays dismissed for this page
 * load only. The link carries saved stat-calculator formulas (FR-020) through the same helper the redirects use.
 *
 * This is the one place besides CrossSiteRedirect that links to the other site (FR-015's exception).
 */
export default function LeaguesMovedNotice() {
  const user = useUser()
  const cfg = currentSite()
  const [hidden, setHidden] = useState(false)

  if (cfg.site !== 'timemachine' || !user || hidden || signedInHere() || movedNoticeDismissed()) return null

  let href = `${cfg.leaguesOrigin}/`
  try {
    href = withCarry(href, localStorage)
  } catch {
    // Blocked storage: no formulas to carry.
  }
  const host = cfg.leaguesOrigin.replace(/^https?:\/\//, '')

  return (
    <aside className="tm-moved" aria-label="Your leagues moved">
      <p>
        Looking for your leagues? They moved to{' '}
        <a href={href} onClick={dismissMovedNotice} onAuxClick={dismissMovedNotice}>{host}</a>.
      </p>
      <button
        type="button"
        className="tm-btn quiet"
        onClick={() => {
          dismissMovedNotice()
          setHidden(true)
        }}
      >
        Dismiss
      </button>
    </aside>
  )
}
