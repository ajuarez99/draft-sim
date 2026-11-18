import { Link } from 'react-router-dom'
import type { HistoricalSport, SeasonRef } from '../api'
import RedraftFoot from '../components/RedraftFoot'
import { usePageHead } from '../pageTitle'
import * as copy from '../redraftCopy'
import { useSeasonList } from '../redraftData'
import { canonicalUrl, sportIndexPath, sportPage } from '../redraftPages'
import { tmSeason } from '../site'
import { currentSite } from '../siteEnv'

// Spec 074 T036 / design.md §2. Mirrors sportIndexBody() in seasonPageHtml.ts: decade groups of year pills, newest
// decade first and years oldest to newest within one, and one tile for the newest season. No per-season fact: anything
// drawn from the outcome is a spoiler.

function decades(seasons: SeasonRef[]): { decade: number; rows: SeasonRef[] }[] {
  const by = new Map<number, SeasonRef[]>()
  for (const x of seasons) {
    const d = Math.floor(x.seasonStart / 10) * 10
    by.set(d, [...(by.get(d) ?? []), x])
  }
  return [...by.keys()]
    .sort((a, b) => b - a)
    .map((d) => ({ decade: d, rows: by.get(d)!.sort((a, b) => a.seasonStart - b.seasonStart) }))
}

export default function SportIndex({ sport }: { sport: HistoricalSport }) {
  const { list, status, retry } = useSeasonList(sport)
  const seasons = list?.seasons ?? []
  const first = seasons[0]?.label
  const last = seasons[seasons.length - 1]?.label
  const cfg = currentSite()
  // While the list loads the range is unknown, so the description and JSON-LD wait; the canonical is known from the route.
  usePageHead(
    list && first && last
      ? sportPage(cfg, sport, { first, last, count: seasons.length })
      : { title: copy.brand, canonical: canonicalUrl(cfg, sportIndexPath(sport)) },
  )
  const newest = seasons[seasons.length - 1]
  const newestTo = newest ? tmSeason(cfg, sport, newest.slug) : null
  return (
    <div className="content tm tm-page">
      <header className="tm-hero">
        <p className="tm-over">{copy.seasonOver(sport)}</p>
        <h1 className="tm-title tm-title-sm">{copy.sportIndexH1(sport)}</h1>
        {first && last ? <p className="tm-tagline">{copy.sportIndexTagline(first, last)}</p> : null}
        {newest && newestTo ? (
          <Link className={`tm-newest ${sport.toLowerCase()}`} to={newestTo}>
            <span className="muted small">{copy.newestSeason}</span> <strong>{newest.label}</strong>
          </Link>
        ) : null}
      </header>
      {status === 'loading' && (
        <p className="muted small" role="status">
          {copy.seasonsLoading}
        </p>
      )}
      {status === 'error' && (
        <div className="error" role="alert">
          {copy.seasonsError}{' '}
          <button type="button" className="link-button" onClick={retry}>
            {copy.tryAgain}
          </button>
        </div>
      )}
      {status === 'ready' && seasons.length === 0 && <p className="muted small">{copy.seasonsEmpty(sport)}</p>}
      {decades(seasons).map(({ decade, rows }) => (
        <section key={decade} className="tm-decade">
          <h2 className="tm-decade-head">
            {decade}s <span className="muted small">{copy.decadeSeasons(rows.length)}</span>
          </h2>
          <ul className="tm-years">
            {rows.map((x) => {
              const to = tmSeason(cfg, sport, x.slug)
              return to ? (
                <li key={x.slug}>
                  <Link className="tm-pill-link" to={to}>
                    {x.label}
                    <span className="hist-sr-only">{copy.yearPillSuffix(sport)}</span>
                  </Link>
                </li>
              ) : null
            })}
          </ul>
        </section>
      ))}
      <p className="muted small">{copy.namingRule(sport)}</p>
      <p className="muted small">{copy.eraNote(sport)}</p>
      <RedraftFoot />
    </div>
  )
}
