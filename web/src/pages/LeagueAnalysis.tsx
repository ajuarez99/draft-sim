import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import { getLeagueAnalysis, type LeagueAnalysis as LeagueAnalysisData } from '../api'
import { useFailure } from '../useFailure'
import NotFound from '../components/NotFound'
import HowThisWorks from '../components/HowThisWorks'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { HeadToHead } from './../components/leagueAnalysis/headToHead'
import { MatchupsBlock } from './../components/leagueAnalysis/matchups'
import { PositionGroupsBlock } from './../components/leagueAnalysis/positionGroups'
import { ProjectedBumpBlock, ProjectionsBlock } from './../components/leagueAnalysis/projections'
import { RankingScoresBlock } from './../components/leagueAnalysis/rankingScores'
import { ScoresBlock } from './../components/leagueAnalysis/scores'
import { NotYet, SCORING_LABEL, TeamLabelsContext } from './../components/leagueAnalysis/shared'

/**
 * claude/league-analysis.md: what each roster is MADE OF, as opposed to Power
 * rankings' question of who is best. Its second pass
 * (claude/league-analysis-lineups-and-matchups.md) adds the three things that
 * answer "made of what, exactly": the lineup behind a bar, two lineups against
 * each other, and the lineup you play next week.
 *
 * The bump chart ffwrapped shows alongside these blocks is deliberately absent:
 * Power rankings already draws one, and this page links to it instead of
 * drawing a second.
 */

export default function LeagueAnalysis() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [data, setData] = useState<LeagueAnalysisData | null>(null)
  const { error, notFound, setError, fail } = useFailure()
  // Only the matchup block moves with the chosen week, so only it is refetched
  // and swapped in. Replacing the whole response would reset every open lineup
  // drawer to answer a question that did not touch them.
  const [week, setWeek] = useState<number | null>(null)
  const [weekLoading, setWeekLoading] = useState(false)
  // Bumped by the rail when this league's background refresh finishes
  // (specs/009-auto-data-refresh). A refetch must not close open lineup
  // drawers or drop the chosen week, so the resets below run per league only.
  const dataVersion = useLeagueDataVersion(sleeperLeagueId)
  const loadedFor = useRef<string | null>(null)
  // The chosen week, readable inside the effect without making the effect
  // re-run (and refetch) every time the user steps the week.
  const weekRef = useRef<number | null>(null)

  useEffect(() => {
    if (!sleeperLeagueId) return
    if (loadedFor.current !== sleeperLeagueId) {
      loadedFor.current = sleeperLeagueId
      setData(null)
      setWeek(null)
      weekRef.current = null
    }
    setError(null)
    // A data-version bump keeps the chosen week, so the refetch must ask for it:
    // otherwise the stepper highlights week 5 over the default week's matchups.
    const chosen = weekRef.current
    ;(chosen == null ? getLeagueAnalysis(sleeperLeagueId) : getLeagueAnalysis(sleeperLeagueId, chosen))
      .then(setData)
      .catch((e) => fail(e))
  }, [sleeperLeagueId, dataVersion])

  async function pickWeek(next: number) {
    if (!sleeperLeagueId) return
    setWeek(next)
    weekRef.current = next
    setWeekLoading(true)
    try {
      const fresh = await getLeagueAnalysis(sleeperLeagueId, next)
      setData((prev) => (prev == null ? fresh : { ...prev, matchups: fresh.matchups }))
    } catch (e) {
      fail(e)
    } finally {
      setWeekLoading(false)
    }
  }

  const window = data?.window
  const labels = useMemo(
    () => new Map((data?.teams ?? []).map((t) => [t.rosterId, t] as const)),
    [data],
  )

  if (notFound) return <NotFound what="league" />

  return (
    <TeamLabelsContext.Provider value={labels}>
    <div className="content">
      <PageHeader
        eyebrow={data ? `League · ${data.season}` : 'League'}
        title="Team strength"
        sub="How strong each roster is so far, and who is projected ahead in next week's games."
      />

      {error && (
        <div className="error history-error">
          <span>{error}</span>
        </div>
      )}

      {!data && !error && (
        <p className="muted small" role="status" aria-busy="true">
          Loading league analysis…
        </p>
      )}

      {data && (
        <>
          <section className="section analysis-section">
            <div className="panel-head">
              <h2 className="section-title">Ranking score</h2>
              {sleeperLeagueId && (
                <Link className="chip" to={`/leagues/${sleeperLeagueId}/power`}>
                  Power rankings →
                </Link>
              )}
            </div>
            <RankingScoresBlock block={data.rankingScores} />
          </section>

          <section className="section analysis-section">
            <div className="panel-head">
              <h2 className="section-title">Roster projections</h2>
              <span className="muted small">
                {window && window.weeks > 0
                  ? `weeks ${window.fromWeek}–${window.toWeek} · ${SCORING_LABEL[data.scoringKey] ?? data.scoringKey}`
                  : SCORING_LABEL[data.scoringKey] ?? data.scoringKey}
              </span>
            </div>
            <ProjectionsBlock block={data.projections} />
            <HowThisWorks>
              <p>
                The rest of the regular season is projected onto the lineup each manager would
                actually start. These are rest-of-season points for that starting lineup, split by
                the position the starter actually plays — a running back filling a flex slot counts
                under RB. Open a lineup to see the players the number is made of.
              </p>
            </HowThisWorks>
          </section>

          <section className="section analysis-section">
            <div className="panel-head">
              <h2 className="section-title">Projected week by week</h2>
              <span className="muted small">
                {window && window.weeks > 0 ? `weeks ${window.fromWeek}–${window.toWeek}` : null}
              </span>
            </div>
            <p className="muted small">
              This is the projection; what was actually scored is further down.
            </p>
            <ProjectedBumpBlock block={data.projections} />
            <HowThisWorks>
              <p>
                Where each roster is projected to rank in each remaining week — the bye weeks are
                the drops. Click a line to follow it. This is the projection; what was actually
                scored is further down.
              </p>
            </HowThisWorks>
          </section>

          <section className="section analysis-section">
            <div className="panel-head">
              <h2 className="section-title">Position group rankings</h2>
            </div>
            <PositionGroupsBlock block={data.projections} />
            <HowThisWorks>
              <p>
                The same projections read down the columns: where each roster stands at each
                position. Big number is the rank, small number is the projected points behind it.
              </p>
            </HowThisWorks>
          </section>

          <section className="section analysis-section">
            <div className="panel-head">
              {/* The week is named only when there IS one. A finished season
                  refuses this block because its regular season ended at week
                  14, and heading that refusal "Week 18 matchups" would assert
                  a week the reason underneath denies. */}
              <h2 className="section-title">
                {data.matchups.available ? `Week ${data.matchups.week} matchups` : 'Upcoming matchups'}
              </h2>
              {window && window.toWeek >= window.fromWeek && (
                <div className="analysis-weekpick" role="group" aria-label="Choose a week">
                  {Array.from(
                    { length: window.toWeek - window.fromWeek + 1 },
                    (_, i) => window.fromWeek + i,
                  ).map((w) => {
                    const on = (week ?? data.matchups.week) === w
                    return (
                      <button
                        key={w}
                        type="button"
                        className={`chip analysis-weekchip${on ? ' on' : ''}`}
                        aria-pressed={on}
                        disabled={weekLoading}
                        onClick={() => pickWeek(w)}
                      >
                        {w}
                      </button>
                    )
                  })}
                </div>
              )}
            </div>
            <p className="muted small">
              The margin is two projections subtracted, not a win probability.
            </p>
            <div aria-busy={weekLoading}>
              <MatchupsBlock block={data.matchups} />
            </div>
            <HowThisWorks>
              <p>
                The league's real pairings, each side's lineup rebuilt for the chosen week rather
                than sliced out of the rest-of-season total — a bye or a one-week injury moves who
                starts. The margin is two projections subtracted, not a win probability.
              </p>
            </HowThisWorks>
          </section>

          <section className="section analysis-section">
            <div className="panel-head">
              <h2 className="section-title">Week by week</h2>
              <span className="muted small">
                {data.scores.weeks.length} week{data.scores.weeks.length === 1 ? '' : 's'} scored
              </span>
            </div>
            <ScoresBlock block={data.scores} />
            <HowThisWorks>
              <p>
                What every roster actually scored, week by week — played games, not projections, so
                nothing here moves once a week is in the books.
              </p>
            </HowThisWorks>
          </section>

          <section className="section analysis-section">
            <div className="panel-head">
              <h2 className="section-title">Head to head</h2>
              <span className="muted small">
                {window && window.weeks > 0 ? `weeks ${window.fromWeek}–${window.toWeek}` : null}
              </span>
            </div>
            {data.projections.available ? (
              <HeadToHead block={data.projections} />
            ) : (
              <NotYet reason={data.projections.reason} />
            )}
            <HowThisWorks>
              <p>Any two rosters, slot against slot, over the same rest-of-season window.</p>
            </HowThisWorks>
          </section>
        </>
      )}
    </div>
    </TeamLabelsContext.Provider>
  )
}
