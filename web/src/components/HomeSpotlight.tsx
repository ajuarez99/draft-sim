import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { getPlayerSpotlight, getWeeklyReport, type Sport } from '../api'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { useBlock } from '../useBlock'
import { SkeletonRows } from './Skeleton'
import { SpotlightLists } from './PlayerSpotlight'

/*
 * specs/015: the player spotlight on the root home page, one tab per league.
 *
 * Each tab renders the league home's own lists (`SpotlightLists`) from the same two
 * endpoints, so the two pages cannot disagree (FR-004). Only the open tab is fetched;
 * a tab that has been opened stays mounted and is hidden when another is selected, so
 * switching back costs no request and shows no skeleton (research R5, FR-007/FR-008).
 *
 * Like PlayerSpotlight.tsx, this file never compares a sport. The sport reaches it only as
 * the pill's label. Whether football's weekly report is fetched follows the payload
 * (`topOfNight`, `period`), never `league.sport` (research R2, FR-014; pinned by a
 * source-scan test on the backend).
 */

export type HomeSpotlightLeague = { leagueId: string; name: string; sport: Sport }

export default function HomeSpotlight({ leagues }: { leagues: HomeSpotlightLeague[] }) {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [visited, setVisited] = useState<ReadonlySet<string>>(() => new Set())
  const tabRefs = useRef<Record<string, HTMLButtonElement | null>>({})

  if (leagues.length === 0) return null

  // The sport filter can remove the selected league; fall back to the first one shown.
  const effectiveId = leagues.some((l) => l.leagueId === selectedId) ? (selectedId as string) : leagues[0].leagueId
  // Grows to include whatever is selected and never shrinks during a visit. Set during render
  // (React re-renders immediately) so the selected panel mounts on the same pass.
  if (!visited.has(effectiveId)) setVisited(new Set(visited).add(effectiveId))

  const choose = (id: string, focus = false) => {
    setSelectedId(id)
    if (focus) tabRefs.current[id]?.focus()
  }
  const onKey = (e: React.KeyboardEvent, i: number) => {
    const last = leagues.length - 1
    const next =
      e.key === 'ArrowRight' ? (i === last ? 0 : i + 1) : e.key === 'ArrowLeft' ? (i === 0 ? last : i - 1) : e.key === 'Home' ? 0 : e.key === 'End' ? last : null
    if (next == null) return
    e.preventDefault()
    choose(leagues[next].leagueId, true)
  }

  return (
    <section className="section picker-section home-spotlight" aria-labelledby="home-spotlight-h">
      <div className="panel-head">
        <h2 id="home-spotlight-h" className="section-title">
          Player spotlight
        </h2>
        <Link to={`/leagues/${effectiveId}`} className="link-button">
          Open league
        </Link>
      </div>
      <div role="tablist" aria-label="Leagues" className="home-spot-leagues">
        {leagues.map((l, i) => {
          const selected = l.leagueId === effectiveId
          return (
            <button
              key={l.leagueId}
              type="button"
              role="tab"
              id={`home-spot-league-${l.leagueId}`}
              aria-selected={selected}
              aria-controls={`home-spot-panel-${l.leagueId}`}
              tabIndex={selected ? 0 : -1}
              ref={(el) => {
                tabRefs.current[l.leagueId] = el
              }}
              className="home-spot-league"
              onClick={() => choose(l.leagueId)}
              onKeyDown={(e) => onKey(e, i)}
            >
              {l.name} <span className={`sport-pill ${l.sport}`}>{l.sport.toUpperCase()}</span>
            </button>
          )
        })}
      </div>
      {/* Unvisited leagues mount nothing; a visited id no longer in `leagues` is not rendered. */}
      {leagues
        .filter((l) => visited.has(l.leagueId) || l.leagueId === effectiveId)
        .map((l) => (
          <div
            key={l.leagueId}
            role="tabpanel"
            id={`home-spot-panel-${l.leagueId}`}
            aria-labelledby={`home-spot-league-${l.leagueId}`}
            hidden={l.leagueId !== effectiveId}
          >
            <LeaguePanel league={l} idPrefix={`home-${l.leagueId}`} />
          </div>
        ))}
    </section>
  )
}

function LeaguePanel({ league, idPrefix }: { league: HomeSpotlightLeague; idPrefix: string }) {
  const version = useLeagueDataVersion(league.leagueId)
  const spotlight = useBlock(() => getPlayerSpotlight(league.leagueId), [league.leagueId, version])
  // Football's Top list is the weekly report's, fetched after the spotlight says it needs it
  // (research R2). Decided by payload fields only.
  const needsWeekly = spotlight.status === 'ok' && spotlight.data.applies && !spotlight.data.topOfNight && spotlight.data.period != null
  // No `version` here: a bump sends the spotlight through 'loading', which drops needsWeekly and
  // brings it back, and that alone refetches. With `version` listed, the bump's first render still
  // saw the old 'ok' spotlight and started a weekly request that was cancelled a render later.
  const weekly = useBlock(needsWeekly ? () => getWeeklyReport(league.leagueId, 0) : null, [league.leagueId, needsWeekly])

  if (spotlight.status === 'loading' || spotlight.status === 'idle') return <SkeletonRows count={3} label="Loading player spotlight" />
  if (spotlight.status === 'error') return <p className="muted small">Couldn't load the player spotlight for {league.name}.</p>
  if (!spotlight.data.applies) {
    // specs/015 research R4: the league home renders nothing here; a tab that opens blank is worse.
    return <p className="muted small">No player spotlight for {spotlight.data.season}: it covers the current season only.</p>
  }
  return <SpotlightLists spotlight={spotlight.data} weekly={weekly} idPrefix={idPrefix} />
}
