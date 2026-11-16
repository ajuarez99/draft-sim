import { Link } from 'react-router-dom'
import { siteCopy } from '../siteCopy'
import { currentSite } from '../siteEnv'

const COPY: Record<'league' | 'mock' | 'draft' | 'page', string> = {
  league: "No league here, or it isn't one of yours.",
  mock: 'No mock draft at this address.',
  draft: 'No draft at this address.',
  page: 'No draft, mock or page at this address.',
}

/** The one not-found state: every route with an unknown id renders this. */
export default function NotFound({ what }: { what: keyof typeof COPY }) {
  // Spec 073: the Time Machine site has no leagues, drafts or mocks to name.
  const copy = siteCopy(currentSite())
  return (
    <div className="content">
      <section className="panel">
        <h2>Nothing here</h2>
        <p className="muted">
          {copy.notFoundBody ?? COPY[what]} <Link to="/">Back to {copy.homeLabel}</Link>.
        </p>
      </section>
    </div>
  )
}
