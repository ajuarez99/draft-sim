import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  abandonHistoricalGame,
  historicalSeasons,
  listHistoricalGames,
  startHistoricalGame,
  type HistoricalGameSummary,
  type HistoricalSeasonSummary,
} from '../api'
import { useFailure } from '../useFailure'
import PageHeader from '../components/PageHeader'
import HowThisWorks from '../components/HowThisWorks'
import { ordinal } from '../historicalCopy'

function when(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

function statusText(g: HistoricalGameSummary): string {
  if (g.status === 'COMPLETE') return g.finish != null ? `Finished ${ordinal(g.finish)}` : 'Complete'
  if (g.status === 'DRAFTING') return 'Drafting'
  return 'Abandoned'
}

export default function HistoricalDraft() {
  const navigate = useNavigate()
  const { error, fail, setError } = useFailure()
  const [games, setGames] = useState<HistoricalGameSummary[] | null>(null)
  const [seasons, setSeasons] = useState<HistoricalSeasonSummary[] | null>(null)
  const [starting, setStarting] = useState(false)

  useEffect(() => {
    listHistoricalGames()
      .then(setGames)
      .catch((e) => fail(e))
    // The settings line is the same for every season; a failure here only loses that line.
    historicalSeasons()
      .then(setSeasons)
      .catch(() => setSeasons([]))
  }, [])

  async function start() {
    if (starting) return
    setStarting(true)
    setError(null)
    try {
      const g = await startHistoricalGame()
      navigate(`/history/${g.id}`)
    } catch (e) {
      fail(e)
      setStarting(false)
    }
  }

  async function abandon(id: number) {
    setError(null)
    try {
      await abandonHistoricalGame(id)
      setGames((gs) => (gs ?? []).map((g) => (g.id === id ? { ...g, status: 'ABANDONED' } : g)))
    } catch (e) {
      fail(e)
    }
  }

  const settingsSource = seasons && seasons.length > 0 ? seasons[0].settingsSource : null
  const visible = (games ?? []).filter((g) => g.status !== 'ABANDONED')

  return (
    <div className="content">
      <PageHeader
        eyebrow="Time Machine"
        title="Draft a random year"
        sub="Pick a team out of a real NBA season, then watch that season play out."
        actions={
          <button type="button" className="chip on hist-start" onClick={() => void start()} disabled={starting}>
            {starting ? 'Starting…' : 'Draft a random year'}
          </button>
        }
      />
      {error && <div className="error">{error}</div>}

      <section className="panel hist-hero">
        <h2>How it works</h2>
        <ol className="hist-steps">
          <li>
            <strong>Draft with last season&apos;s stats.</strong> You see what every player did the year before, like a real drafter would have.
          </li>
          <li>
            <strong>The year plays out.</strong> It runs as lock-in best ball: each starter&apos;s best game of the week counts, and your best legal lineup is set for you.
          </li>
          <li>
            <strong>See how you did.</strong> Standings, your weekly lineups, and how you stack up against hindsight.
          </li>
        </ol>
        {settingsSource && <p className="muted small">{settingsSource}</p>}
        <HowThisWorks>
          <p>
            Seasons run from 1970-71 to the latest complete one. Only stats the league tracked for essentially the whole season
            count, and each season says which. Opponents draft from last season&apos;s points, not from a real draft-position feed.
          </p>
        </HowThisWorks>
      </section>

      <section className="panel" aria-label="Your games">
        <h2>Your games</h2>
        {games == null && !error ? (
          <p className="muted">Loading…</p>
        ) : visible.length === 0 ? (
          <p className="muted">No games yet. Start one above.</p>
        ) : (
          <ul className="hist-games row-list">
            {visible.map((g) => (
              <li key={g.id} className="hist-game">
                <div>
                  <strong className="cond">{g.seasonLabel}</strong>{' '}
                  <span className={`chip status-${g.status.toLowerCase()}`}>{statusText(g)}</span>
                  <span className="muted small"> · {when(g.createdAt)}</span>
                </div>
                <div className="hist-game-actions">
                  {g.status === 'DRAFTING' && (
                    <>
                      <Link className="chip on" to={`/history/${g.id}`}>
                        Resume
                      </Link>
                      <button type="button" className="chip" onClick={() => void abandon(g.id)}>
                        Abandon
                      </button>
                    </>
                  )}
                  {g.status === 'COMPLETE' && (
                    <Link className="chip" to={`/history/${g.id}`}>
                      View
                    </Link>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
