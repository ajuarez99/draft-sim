import { Link } from 'react-router-dom'

const COPY: Record<'league' | 'mock' | 'draft' | 'page', string> = {
  league: "No league here, or it isn't one of yours.",
  mock: 'No mock draft at this address.',
  draft: 'No draft at this address.',
  page: 'No draft, mock or page at this address.',
}

/** The one not-found state: every route with an unknown id renders this. */
export default function NotFound({ what }: { what: keyof typeof COPY }) {
  return (
    <div className="content">
      <section className="panel">
        <h2>Nothing here</h2>
        <p className="muted">
          {COPY[what]} <Link to="/">Back to your leagues</Link>.
        </p>
      </section>
    </div>
  )
}
