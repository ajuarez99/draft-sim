import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  abandonHistoricalGame,
  historicalSeasons,
  listHistoricalGames,
  startHistoricalGame,
  type HistoricalGameSummary,
  type HistoricalSport,
  type HistoricalSeasonSummary,
} from '../api'
import { useFailure } from '../useFailure'
import PageHeader from '../components/PageHeader'
import HowThisWorks from '../components/HowThisWorks'
import { ordinal, sportOf } from '../historicalCopy'

function when(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

const SPORT_LABEL: Record<HistoricalSport, string> = { NBA: 'Basketball', NFL: 'Football' }

/** The offered football range, from the seasons the server actually offers (never a literal): "2000 to 2025". */
function nflRange(rows: HistoricalSeasonSummary[]): string {
  if (rows.length === 0) return ''
  const starts = rows.map((r) => r.seasonStart)
  return `${Math.min(...starts)} to ${Math.max(...starts)}`
}

/** Per-sport wording. Football never mentions the NBA, 1970-71 or a draft-position feed. */
const COPY: Record<HistoricalSport, (range: string) => { sub: string; scoring: string; how: string }> = {
  NBA: () => ({
    sub: 'Pick a team out of a real NBA season, then watch that season play out.',
    scoring: "It runs as lock-in best ball: each starter's best game of the week counts, and your best legal lineup is set for you.",
    how: "Seasons run from 1970-71 to the latest complete one. Only stats the league tracked for essentially the whole season count, and each season says which. Opponents draft from last season's points, not from a real draft-position feed.",
  }),
  NFL: (range) => ({
    sub: `Pick a team out of a real NFL season${range ? `, ${range},` : ''} then watch that season play out.`,
    scoring:
      'It runs as weekly best ball on real full-PPR scores: your best legal lineup is set for you each week. Quarterbacks, running backs, wide receivers and tight ends only; no kickers or defenses.',
    how: `Seasons run${range ? ` from ${range}` : ' over the years on offer'}. Scores come from the real weekly stat lines. Opponents draft from last season's points over replacement, not from a real draft-position feed.`,
  }),
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
  const [nbaRows, setNbaRows] = useState<HistoricalSeasonSummary[] | null>(null)
  const [nflRows, setNflRows] = useState<HistoricalSeasonSummary[] | null>(null)
  const [seasonsFailed, setSeasonsFailed] = useState(false)
  const [starting, setStarting] = useState(false)
  // Basketball is only the preselected tab; every request still carries the sport explicitly.
  const [chosen, setSport] = useState<HistoricalSport>('NBA')

  useEffect(() => {
    listHistoricalGames()
      .then(setGames)
      .catch((e) => fail(e))
  }, [])

  // The settings line is the same for every season of a sport; a failure here only loses that line.
  // A 053 backend ignores ?sport= and returns basketball rows with no `sport` field, so football rows are kept only
  // when they say NFL themselves: that is what decides whether the Football choice is offered at all (it also stays
  // hidden until the post-deploy NFLVERSE import has put at least one season in).
  useEffect(() => {
    let current = true
    historicalSeasons('NBA')
      .then((x) => current && setNbaRows(x.filter((r) => sportOf(r) === 'NBA')))
      .catch(() => {
        if (!current) return
        setSeasonsFailed(true)
        setNbaRows([])
      })
    historicalSeasons('NFL')
      .then((x) => current && setNflRows(x.filter((r) => r.sport === 'NFL')))
      .catch(() => {
        if (!current) return
        setSeasonsFailed(true)
        setNflRows([])
      })
    return () => {
      current = false
    }
  }, [])

  // A sport is offered only once the server lists at least one of its seasons: prod ran with football imported
  // and basketball not (2026-11-08), and the Basketball tab then failed every start. If a seasons request failed we
  // can't tell, so the old behaviour stands (basketball preselected, start allowed) rather than locking the page.
  const loaded = nbaRows !== null && nflRows !== null
  const offered = (['NBA', 'NFL'] as const).filter((sp) => ((sp === 'NFL' ? nflRows : nbaRows) ?? []).length > 0)
  const sport: HistoricalSport = offered.includes(chosen) || seasonsFailed ? chosen : (offered[0] ?? chosen)
  const nothingOffered = loaded && !seasonsFailed && offered.length === 0
  const seasons = sport === 'NFL' ? nflRows : nbaRows
  const copy = COPY[sport](nflRange(nflRows ?? []))

  async function start() {
    if (starting) return
    setStarting(true)
    setError(null)
    try {
      const g = await startHistoricalGame(sport)
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
        sub={copy.sub}
        actions={
          <button
            type="button"
            className="chip on hist-start"
            onClick={() => void start()}
            disabled={starting || !loaded || nothingOffered}
          >
            {starting ? 'Starting…' : 'Draft a random year'}
          </button>
        }
      />
      {error && <div className="error">{error}</div>}
      {nothingOffered && <div className="muted">No seasons are loaded yet, so there is nothing to draft.</div>}

      {offered.length > 1 && (
        <div className="hist-sports" role="group" aria-label="Sport">
          {offered.map((sp) => (
            <button
              key={sp}
              type="button"
              className={`chip${sp === sport ? ' on' : ''}`}
              aria-pressed={sp === sport}
              disabled={starting}
              onClick={() => setSport(sp)}
            >
              {SPORT_LABEL[sp]}
            </button>
          ))}
        </div>
      )}

      <section className="panel hist-hero">
        <h2>How it works</h2>
        <ol className="hist-steps">
          <li>
            <strong>Draft with last season&apos;s stats.</strong> You see what every player did the year before, like a real drafter would have.
          </li>
          <li>
            <strong>The year plays out.</strong> {copy.scoring}
          </li>
          <li>
            <strong>See how you did.</strong> Standings, your weekly lineups, and how you stack up against hindsight.
          </li>
        </ol>
        {settingsSource && <p className="muted small">{settingsSource}</p>}
        <HowThisWorks>
          <p>{copy.how}</p>
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
                  <span className={`chip hist-sport-tag sport-${sportOf(g).toLowerCase()}`}>{SPORT_LABEL[sportOf(g)]}</span>{' '}
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
