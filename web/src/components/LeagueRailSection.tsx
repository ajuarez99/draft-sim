import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { crestLetter, hueForName } from '../hue'
import {
  destinationFromPath,
  destinationsFor,
  labelOf,
  type DestinationKey,
  type LeagueContext,
  type LeagueDestination,
} from '../destinations'
import type { LeagueLineage } from '../leagueLineage'
import type { RailLeague } from '../railLeague'
import { getLeagueRefresh, refreshLeague, type DraftSummary, type RefreshStatus } from '../api'
import { useBumpLeagueDataVersion } from '../leagueDataVersion'
import { relativeTime } from '../relativeTime'

type Props = {
  league: RailLeague
  pathname: string
  collapsed: boolean
  /** Seeded with this league, the same way the home card's "Mock it" is. */
  onMockIt: (leagueId: string, sport: DraftSummary['sport']) => void
  /** Every league the signed-in user can see, for the switcher. Empty until
   *  the draft list resolves, which just means no switcher yet. */
  allLeagues?: LeagueLineage[]
}

/** Hand-set, arbitrary (specs/009 refresh contract): how often the rail asks how a running refresh is going. */
export const REFRESH_POLL_MS = 3000

/**
 * Starts the league's background refresh when the rail lands in it, follows it
 * while it runs, and tells the pages when it finished
 * (specs/009-auto-data-refresh T026, research R1).
 *
 * One POST per league change. While the answer is RUNNING it asks again every
 * {@link REFRESH_POLL_MS} until it is not, and on RUNNING -> FRESH/COMPLETE it
 * bumps the data version of every season in the lineage, which is what makes
 * the pages refetch. A network failure shows nothing and does not retry: a
 * refresh is an improvement to a page that already works, never a reason to
 * hammer the server.
 *
 * `seasonIds` rides in a ref so a lineage re-render cannot restart the POST.
 */
function useLeagueRefresh(leagueId: string, seasonIds: string[]): RefreshStatus | null {
  const bump = useBumpLeagueDataVersion()
  const [status, setStatus] = useState<RefreshStatus | null>(null)
  const seasonIdsRef = useRef(seasonIds)
  seasonIdsRef.current = seasonIds

  useEffect(() => {
    let live = true
    let wasRunning = false
    let timer: ReturnType<typeof setTimeout> | undefined
    setStatus(null)

    function apply(next: RefreshStatus) {
      if (!live) return
      setStatus(next)
      if (next.state === 'RUNNING') {
        wasRunning = true
        timer = setTimeout(poll, REFRESH_POLL_MS)
      } else if (wasRunning) {
        wasRunning = false
        if (next.state === 'FRESH' || next.state === 'COMPLETE') {
          for (const id of seasonIdsRef.current) bump(id)
        }
      }
    }

    function poll() {
      getLeagueRefresh(leagueId)
        .then(apply)
        .catch(() => {
          // Stop asking, and stop claiming "Updating…" about a run we can no longer see.
          if (live) setStatus(null)
        })
    }

    // Inside a wrapper so a synchronous throw from the fetch layer (it does
    // under a partial module mock) is a swallowed failure, not a crashed shell.
    Promise.resolve()
      .then(() => refreshLeague(leagueId))
      .then(apply)
      .catch(() => {})

    return () => {
      live = false
      if (timer) clearTimeout(timer)
    }
  }, [leagueId, bump])

  return status
}

/** "3 h", "12 min", "2 d": short enough for the rail, and never rounds a recent thing up to "0". */
function shortAge(iso: string, now: Date = new Date()): string {
  const min = Math.max(1, Math.round((now.getTime() - new Date(iso).getTime()) / 60000))
  if (min < 60) return `${min} min`
  const hours = Math.round(min / 60)
  if (hours < 24) return `${hours} h`
  return `${Math.round(hours / 24)} d`
}

/** The rail's one-line refresh state, per the contract's indicator table. Null shows nothing. */
export function refreshLine(status: RefreshStatus | null, now: Date = new Date()): string | null {
  if (!status) return null
  switch (status.state) {
    case 'RUNNING':
      return 'Updating…'
    case 'FAILED':
      return status.lastSuccessAt
        ? `Couldn't reach Sleeper — data from ${shortAge(status.lastSuccessAt, now)} ago`
        : "Couldn't reach Sleeper"
    default: {
      if (!status.lastSuccessAt) return null
      // "Updated Just now" reads wrong, and Today/Yesterday are not sentence-initial either.
      const age = relativeTime(status.lastSuccessAt, now).replace(/^(Just now|Today|Yesterday)$/, (w) =>
        w.toLowerCase(),
      )
      return `Updated ${age}`
    }
  }
}

/** The same rule the board destination uses: a finished season has picks to
 *  show, anything else opens the room. */
function seasonHref(s: DraftSummary): string {
  return (s.status ?? 'unknown') === 'complete'
    ? `/drafts/${s.sleeperDraftId}/board`
    : `/drafts/${s.sleeperDraftId}`
}

/**
 * Where switching to `target` should land you, given where you are now.
 *
 * Keeps the page you were on when the other league has it, and falls back to
 * that league's History when it does not -- switching from an NFL league's
 * Analysis to a basketball league has to go somewhere, and Analysis is
 * football-only. The availability rule is read from `destinationsFor`, never
 * restated here: a second copy of "which sports have Analysis" is how the rail
 * and the palette would eventually disagree.
 */
export function switchTarget(current: DestinationKey | null, target: LeagueContext): string {
  const offered = destinationsFor(target).filter((d) => !d.isAction)
  const same = current ? offered.find((d) => d.key === current) : undefined
  if (same) return same.href(target)
  const history = offered.find((d) => d.key === 'history')
  return history ? history.href(target) : '/'
}

/**
 * League context in the rail: whose league you are inside, and every place
 * that league offers -- draft, history, power rankings, analysis, mock.
 *
 * The problem it fixes (claude/site-wide-shell-propagation.md Phase 3): that
 * menu lives on the home card and vanishes the moment you use it. Before the
 * rail, `/managers`, `/mock/:id` and `/drafts/:id/board` had no way back at
 * all except the wordmark, and only PowerRankings carried a hand-rolled
 * `← League history` link. Those one-off links come out as this goes in --
 * the same navigation in two places is how they drift.
 *
 * The rows are no longer written out here. They are `destinationsFor(league)`
 * from destinations.ts, which is also what the matcher reads -- so the link
 * this renders and the URL the rail recognises cannot disagree. They did:
 * Analysis was linked from here and missing from the matcher, so using it
 * deleted this whole section.
 *
 * Rendered by AppShell rather than portaled by each page, because several
 * routes share two URL shapes and the rail can resolve the league from the
 * path alone (railLeague.ts).
 */
export default function LeagueRailSection({
  league,
  pathname,
  collapsed,
  onMockIt,
  allLeagues = [],
}: Props) {
  const { lineage, season } = league
  const d = lineage.current
  const refresh = useLeagueRefresh(
    d.sleeperLeagueId,
    lineage.seasons.map((s) => s.sleeperLeagueId),
  )
  const refreshText = refreshLine(refresh)
  const [switcherOpen, setSwitcherOpen] = useState(false)
  const switcherRef = useRef<HTMLDivElement>(null)
  const toggleRef = useRef<HTMLButtonElement>(null)
  const [menuPos, setMenuPos] = useState<{ left: number; top: number } | null>(null)

  // Any navigation closes it -- the flyout is a menu, not a mode, and leaving
  // it open across a route change would leave it hanging over the new page.
  useEffect(() => {
    setSwitcherOpen(false)
  }, [pathname])

  /*
   * Positioned against the viewport rather than the rail.
   *
   * The rail is `overflow-y: auto`, which makes it a clipping context, and
   * when collapsed it is 56px wide. An absolutely-positioned flyout inside it
   * was measured at 210px wide running to x=263 and being cut off at x=56 --
   * the seasons were in the DOM, and a user in a draft room saw a sliver. A
   * DOM-presence test passes that happily, which is why this needed a browser
   * to catch. `position: fixed` escapes the clip entirely.
   */
  useEffect(() => {
    if (!switcherOpen) {
      setMenuPos(null)
      return
    }

    function place() {
      const toggle = toggleRef.current
      const t = toggle?.getBoundingClientRect()
      if (!t) return
      // Collapsed, the rail has no room to drop a menu inside it, so it opens
      // beside the rail; expanded, it drops under the button as a menu should.
      //
      // Measured from the RAIL's right edge, not the button's: the button sits
      // inside the rail's padding, so clearing it still left the menu lapping
      // over the rail by the width of that padding.
      const railRight = toggle?.closest('nav.app-rail')?.getBoundingClientRect().right ?? t.right
      const left = collapsed ? railRight + 6 : t.left
      const top = collapsed ? t.top : t.bottom + 4
      // Keep it on screen: a league near the bottom of a long rail would
      // otherwise open a menu running off the viewport.
      const maxTop = Math.max(8, window.innerHeight - 260)
      setMenuPos({ left: Math.min(left, window.innerWidth - 226), top: Math.min(top, maxTop) })
    }

    place()
    window.addEventListener('resize', place)
    // The rail scrolls independently of the page, so both matter.
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [switcherOpen, collapsed])

  useEffect(() => {
    if (!switcherOpen) return
    function onDown(e: MouseEvent) {
      if (!switcherRef.current?.contains(e.target as Node)) setSwitcherOpen(false)
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setSwitcherOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [switcherOpen])

  const currentKey: DestinationKey | null = destinationFromPath(pathname)
  const others = allLeagues.filter((l) => l.current.sleeperLeagueId !== d.sleeperLeagueId)
  const multiSeason = lineage.seasons.length > 1
  // The flyout earns its place only if it has something to offer.
  const hasSwitcher = others.length > 0 || multiSeason
  const hue = hueForName(d.leagueName)
  const crest = crestLetter(d.leagueName)

  const destinations = destinationsFor(league)
  const boardHref = destinations.find((x) => x.key === 'board')?.href(league) ?? '/'

  function rowFor(dest: LeagueDestination) {
    const label = labelOf(dest, season)

    if (dest.isAction) {
      return (
        <button
          key={dest.key}
          type="button"
          className="app-rail-row"
          onClick={() => onMockIt(d.sleeperLeagueId, d.sport)}
          title={label}
          aria-label={label}
        >
          <span className="app-rail-glyph" aria-hidden="true">
            {dest.glyph}
          </span>
          <span className="app-rail-row-label">{label}</span>
        </button>
      )
    }

    const href = dest.href(league)
    const current = dest.match?.test(pathname) ?? false

    return (
      <Link
        key={dest.key}
        to={href}
        className={`app-rail-row${dest.key === 'live' ? ' live' : ''}${current ? ' on' : ''}`}
        title={label}
        aria-label={label}
      >
        {/* Live is the one destination whose mark carries state rather than
            identity, so it gets the pulsing dot instead of a glyph. */}
        {dest.key === 'live' ? (
          <span className="league-live-dot" aria-hidden="true" />
        ) : (
          <span className="app-rail-glyph" aria-hidden="true">
            {dest.glyph}
          </span>
        )}
        <span className="app-rail-row-label">{label}</span>
      </Link>
    )
  }

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

      {/* specs/009: what the background refresh is doing. Hidden collapsed, like every label. */}
      {refreshText && (
        <span className="rail-league-refresh app-rail-row-label" role="status">
          {refreshText}
        </span>
      )}

      {/* Every season is its own Sleeper league with its own board, so the
          older ones are links rather than a label -- the home card's own
          treatment, and the reason the rail marks the season you are actually
          looking at instead of always the newest.
          Hidden while collapsed, where the flyout below carries them instead:
          a 56px rail has no room for a row of years, and draft rooms (which
          collapse by default) are exactly where comparing seasons is wanted. */}
      {!collapsed && multiSeason && (
        <div className="rail-league-seasons">
          {lineage.seasons.map((s) => (
            <Link
              key={s.sleeperLeagueId}
              to={seasonHref(s)}
              className={`league-season-link${s.sleeperDraftId === season.sleeperDraftId ? ' on' : ''}`}
              title={`${s.season} · ${s.teams} managers`}
            >
              {s.season}
            </Link>
          ))}
        </div>
      )}

      {hasSwitcher && (
        <div className="rail-switcher" ref={switcherRef}>
          <button
            type="button"
            ref={toggleRef}
            className="rail-switcher-toggle"
            onClick={() => setSwitcherOpen((v) => !v)}
            aria-expanded={switcherOpen}
            aria-haspopup="menu"
            title="Switch league or season"
          >
            <span className="app-rail-row-label">Switch</span>
            <span className="rail-switcher-caret" aria-hidden="true">
              {collapsed ? '»' : '▾'}
            </span>
          </button>

          {switcherOpen && (
            <div
              className="rail-switcher-menu"
              role="menu"
              style={menuPos ? { left: menuPos.left, top: menuPos.top } : { visibility: 'hidden' }}
            >
              {/* Seasons come first and are always here, collapsed or not --
                  this flyout is the only route to them in a draft room. */}
              {multiSeason && (
                <>
                  <span className="rail-switcher-label cond">Seasons</span>
                  {lineage.seasons.map((s) => (
                    <Link
                      key={s.sleeperLeagueId}
                      to={seasonHref(s)}
                      role="menuitem"
                      className={`rail-switcher-item${s.sleeperDraftId === season.sleeperDraftId ? ' on' : ''}`}
                    >
                      {s.season}
                      <span className="muted small">{s.teams} managers</span>
                    </Link>
                  ))}
                </>
              )}

              {others.length > 0 && (
                <>
                  <span className="rail-switcher-label cond">Leagues</span>
                  {others.map((l) => (
                    <Link
                      key={l.current.sleeperLeagueId}
                      to={switchTarget(currentKey, { lineage: l, season: l.current })}
                      role="menuitem"
                      className="rail-switcher-item"
                    >
                      {l.current.leagueName}
                      <span className={`sport-pill ${l.current.sport}`}>
                        {l.current.sport.toUpperCase()}
                      </span>
                    </Link>
                  ))}
                </>
              )}
            </div>
          )}
        </div>
      )}

      {/* Wrapped, rather than left as bare siblings of the league identity,
          so the phone breakpoint can turn just these into one horizontally
          scrolling lane. As siblings they wrapped onto five stacked lines at
          375px -- about 200px of an 812px screen spent on page links alone.
          The wrapper reproduces the column layout it replaced on desktop. */}
      <div className="app-rail-pages">{destinations.map(rowFor)}</div>
    </div>
  )
}
