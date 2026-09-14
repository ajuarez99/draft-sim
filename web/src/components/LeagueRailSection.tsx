import { Link } from 'react-router-dom'
import { hueFor } from '../hue'
import type { RailLeague } from '../railLeague'
import type { DraftSummary } from '../api'

type Props = {
  league: RailLeague
  pathname: string
  collapsed: boolean
  /** Seeded with this league, the same way the home card's "Mock it" is. */
  onMockIt: (leagueId: string, sport: DraftSummary['sport']) => void
}

/** The same rule the home card uses: a complete draft has real picks to show,
 *  anything else opens the simulator instead of an empty "come back later". */
function draftRoute(d: DraftSummary): string {
  return (d.status ?? 'unknown') === 'complete'
    ? `/drafts/${d.sleeperDraftId}/board`
    : `/drafts/${d.sleeperDraftId}`
}

function draftLabel(d: DraftSummary): string {
  const status = d.status ?? 'unknown'
  if (status === 'pre_draft' || status === 'drafting') return 'Draft room'
  return status === 'complete' ? 'Draft board' : 'Mock draft'
}

/**
 * League context in the rail: whose league you are inside, and the same four
 * places the home league card offers -- draft, history, power rankings, mock.
 *
 * The problem it fixes (claude/site-wide-shell-propagation.md Phase 3): that
 * menu lives on the home card and vanishes the moment you use it. Before the
 * rail, `/managers`, `/mock/:id` and `/drafts/:id/board` had no way back at
 * all except the wordmark, and only PowerRankings carried a hand-rolled
 * `← League history` link. Those one-off links come out as this goes in --
 * the same navigation in two places is how they drift.
 *
 * Rendered by AppShell rather than portaled by each page, because six routes
 * share these two URL shapes and the rail can resolve the league from the
 * path alone (railLeague.ts).
 */
export default function LeagueRailSection({ league, pathname, collapsed, onMockIt }: Props) {
  const { lineage, season } = league
  const d = lineage.current
  const hue = hueFor(d.leagueName)
  // Leading punctuation is common in league names ("(Foot) Ball Knowers" would
  // crest as "("), so take the first character that actually carries identity
  // -- same rule as the home card, so one league crests identically in both.
  const crest = (d.leagueName.match(/[\p{L}\p{N}]/u)?.[0] ?? '?').toUpperCase()

  const live = season.status === 'pre_draft' || season.status === 'drafting'
  const boardHref = draftRoute(season)
  const historyHref = `/leagues/${d.sleeperLeagueId}/history`
  const powerHref = `/leagues/${d.sleeperLeagueId}/power`
  // Football only, and absent rather than greyed -- power rankings' wire
  // format is football-shaped down to the NFL week/season fields it returns.
  // Same call the home card makes: a greyed-out link invites a click and then
  // explains itself; an absent one just isn't a promise.
  const nfl = d.sport === 'nfl'

  return (
    <div className="app-rail-section app-rail-league">
      <span className="app-rail-label">League</span>

      <Link
        to={boardHref}
        className="rail-league-id"
        title={`${d.leagueName} · ${d.sport.toUpperCase()} · ${season.teams} managers`}
        aria-label={d.leagueName}
      >
        <span
          className="avatar league-crest rail-league-crest"
          style={{ background: `oklch(30% 0.05 ${hue})`, color: `oklch(84% 0.12 ${hue})` }}
          aria-hidden="true"
        >
          {crest}
        </span>
        <span className="rail-league-text app-rail-row-label">
          <span className="rail-league-name">{d.leagueName}</span>
          <span className="rail-league-sub">
            <span className={`sport-pill ${d.sport}`}>{d.sport.toUpperCase()}</span>
            {season.teams} managers
          </span>
        </span>
      </Link>

      {/* Every season is its own Sleeper league with its own board, so the
          older ones are links rather than a label -- the home card's own
          treatment, and the reason the rail marks the season you are actually
          looking at instead of always the newest. */}
      {!collapsed && lineage.seasons.length > 1 && (
        <div className="rail-league-seasons">
          {lineage.seasons.map((s) => (
            <Link
              key={s.sleeperLeagueId}
              to={draftRoute(s)}
              className={`league-season-link${s.sleeperDraftId === season.sleeperDraftId ? ' on' : ''}`}
              title={`${s.season} · ${s.teams} managers`}
            >
              {s.season}
            </Link>
          ))}
        </div>
      )}

      <Link
        to={boardHref}
        className={`app-rail-row${pathname === boardHref ? ' on' : ''}`}
        title={draftLabel(season)}
        aria-label={draftLabel(season)}
      >
        <span className="app-rail-glyph" aria-hidden="true">
          ▦
        </span>
        <span className="app-rail-row-label">{draftLabel(season)}</span>
      </Link>

      {live && (
        <Link
          to={`/drafts/${season.sleeperDraftId}/live`}
          className={`app-rail-row live${pathname.endsWith('/live') ? ' on' : ''}`}
          title="Follow live"
          aria-label="Follow live"
        >
          <span className="league-live-dot" aria-hidden="true" />
          <span className="app-rail-row-label">Follow live</span>
        </Link>
      )}

      {nfl && (
        <>
          <Link
            to={historyHref}
            className={`app-rail-row${pathname === historyHref ? ' on' : ''}`}
            title="History"
            aria-label="History"
          >
            <span className="app-rail-glyph" aria-hidden="true">
              ◷
            </span>
            <span className="app-rail-row-label">History</span>
          </Link>
          <Link
            to={powerHref}
            className={`app-rail-row${pathname === powerHref ? ' on' : ''}`}
            title="Power rankings"
            aria-label="Power rankings"
          >
            <span className="app-rail-glyph" aria-hidden="true">
              ▲
            </span>
            <span className="app-rail-row-label">Power rankings</span>
          </Link>
          <button
            type="button"
            className="app-rail-row"
            onClick={() => onMockIt(d.sleeperLeagueId, d.sport)}
            title="Mock it"
            aria-label="Mock it"
          >
            <span className="app-rail-glyph" aria-hidden="true">
              ▶
            </span>
            <span className="app-rail-row-label">Mock it</span>
          </button>
        </>
      )}
    </div>
  )
}
