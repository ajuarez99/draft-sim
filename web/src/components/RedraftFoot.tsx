import { Link } from 'react-router-dom'
import * as copy from '../redraftCopy'
import { offeredSports } from '../redraftData'
import { tmDaily, tmHome, tmSportIndex } from '../site'
import { currentSite } from '../siteEnv'

/**
 * The footer nav every Redraft page carries (mirrors footNav() in seasonPageHtml.ts). Links go through site.ts, so a
 * site that does not have the routes (the combined build) does not show them (CR-04), and a sport the build made no
 * pages for is left out (CR-08).
 */
export default function RedraftFoot() {
  const cfg = currentSite()
  const daily = tmDaily(cfg)
  return (
    <footer>
      <nav className="tm-foot" aria-label={copy.footNavLabel}>
        <Link to={tmHome(cfg)}>{copy.footHome}</Link>
        {daily ? <Link to={daily}>{copy.footDaily}</Link> : null}
        {offeredSports().map((s) => {
          const to = tmSportIndex(cfg, s)
          return to ? (
            <Link key={s} to={to}>
              {copy.footSeasons(s)}
            </Link>
          ) : null
        })}
        <span className="muted small">{copy.footNote}</span>
      </nav>
    </footer>
  )
}
