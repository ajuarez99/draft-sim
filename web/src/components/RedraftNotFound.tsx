import { Link } from 'react-router-dom'
import * as copy from '../redraftCopy'
import { usePageHead } from '../pageTitle'
import { offeredSports } from '../redraftData'
import { tmDaily, tmHome, tmSportIndex } from '../site'
import { currentSite } from '../siteEnv'

/** The pill links of the 404 and of "No finished game here": home, today's game, and each sport's seasons the site has. */
export function RedraftWhereTo() {
  const cfg = currentSite()
  const daily = tmDaily(cfg)
  return (
    <nav className="tm-links" aria-label={copy.notFoundNavLabel}>
      <Link className="tm-pill-link" to={tmHome(cfg)}>{copy.notFoundLinks.home}</Link>
      {daily ? <Link className="tm-pill-link" to={daily}>{copy.notFoundLinks.daily}</Link> : null}
      {offeredSports().map((s) => {
        const to = tmSportIndex(cfg, s)
        return to ? (
          <Link key={s} className="tm-pill-link" to={to}>
            {s === 'NBA' ? copy.notFoundLinks.nba : copy.notFoundLinks.nfl}
          </Link>
        ) : null
      })}
    </nav>
  )
}

/** Spec 074 §6: the in-app twin of the static 404.html (same words, same links), for an unoffered season or an unknown path. */
export default function RedraftNotFound() {
  usePageHead({ title: copy.notFoundTitle, description: null, noindex: true, jsonLd: [] }) // 404.html's head
  return (
    <div className="content tm tm-page">
      <header className="tm-hero">
        <h1 className="tm-title tm-title-sm">{copy.notFoundH1}</h1>
        <p className="tm-tagline">{copy.notFoundBody}</p>
        <RedraftWhereTo />
      </header>
    </div>
  )
}
