import { Fragment, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { crestLetter, hueForName } from '../hue'
import {
  DESTINATION_GROUPS,
  destinationFromPath,
  destinationsFor,
  destinationsInGroup,
  playerIdFromPath,
  type DestinationGroup,
  LEAGUE_DESTINATIONS,
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
import { useNarrow } from '../useNarrow'
import { laneOverflow, type LaneOverflow } from '../railLane'

type Props = {
  league: RailLeague
  pathname: string
  collapsed: boolean
  /** Seeded with this league, the same way the home card's "Mock it" is. */
  onMockIt: (leagueId: string, sport: DraftSummary['sport']) => void
  /** Every league the signed-in user can see, for the switcher. Empty until
   *  the draft list resolves, which just means no switcher yet. */
  allLeagues?: LeagueLineage[]
  /** Opens the jump-to palette. The phone lane's trailing "All pages" chip is
   *  the guaranteed way to a page the swipe lane has scrolled out of view. */
  onOpenJumpTo?: () => void
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

const GROUP_STATE_KEY = 'bk.rail.groups.v1'
type GroupState = Partial<Record<DestinationGroup, boolean>>

/** Per-viewer open/closed state of the rail groups. Missing or unreadable
 *  storage means "everything expanded", which is the default anyway. */
function readGroupState(): GroupState {
  try {
    const raw = window.localStorage.getItem(GROUP_STATE_KEY)
    const parsed = raw ? JSON.parse(raw) : null
    return parsed && typeof parsed === 'object' ? (parsed as GroupState) : {}
  } catch {
    return {}
  }
}

function writeGroupState(state: GroupState) {
  try {
    window.localStorage.setItem(GROUP_STATE_KEY, JSON.stringify(state))
  } catch {
    // private window or blocked storage: the toggle still works for this visit
  }
}

/**
 * Where switching to `target` should land you, given where you are now.
 *
 * Keeps the page you were on when the target has it -- another year of the same
 * league, or another league. When it does not (Analysis is football-only; Follow
 * live exists only while a draft is running), the fallback depends on what kind
 * of page you were on: a league page lands on League home (specs/013), and
 * anything else on '/'. A draft page lands on the target's board, and so does no page at all
 * (`current === null`): a hinted room (a mock, a manager's history) is inside a
 * league without being on one of its pages, and falling back to History there
 * pointed every year in the flyout at the same URL. The board is the page that
 * differs per year. The Leagues flyout group calls this with the same `current`,
 * so from a mock room it now lands on the other league's board rather than its
 * History -- intended, for the same reason. The
 * availability rule is read from `destinationsFor`, never restated here: a
 * second copy of "which sports have Analysis" is how the rail and the palette
 * would eventually disagree.
 */
export function switchTarget(current: DestinationKey | null, target: LeagueContext, pathname?: string): string {
  const offered = destinationsFor(target).filter((d) => !d.isAction)
  const same = current ? offered.find((d) => d.key === current) : undefined
  if (same) {
    // The player page is addressed by a league AND a player; the league context
    // alone cannot say which player, so it is read back off the current path.
    // Without one (no pathname given) the row's href falls back to league home.
    const playerId = pathname ? playerIdFromPath(pathname) : null
    return same.href(playerId ? { ...target, playerId } : target)
  }
  const wasDraftPage = current
    ? LEAGUE_DESTINATIONS.find((d) => d.key === current)?.idKind === 'draft'
    : true
  const fallback = offered.find((d) => d.key === (wasDraftPage ? 'board' : 'home'))
  return fallback ? fallback.href(target) : '/'
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
  onOpenJumpTo,
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

  // Phone lane: which edges still have pages behind them, and keep the current
  // page on screen. Both are no-ops wherever the lane is not horizontally
  // scrollable (every desktop layout), so they cost nothing there.
  const laneRef = useRef<HTMLDivElement>(null)
  const [overflow, setOverflow] = useState<LaneOverflow>({ start: false, end: false })

  useEffect(() => {
    const lane = laneRef.current
    if (!lane) return
    function measure() {
      const el = laneRef.current
      if (!el) return
      const next = laneOverflow(el.scrollLeft, el.clientWidth, el.scrollWidth)
      setOverflow((prev) => (prev.start === next.start && prev.end === next.end ? prev : next))
    }
    measure()
    lane.addEventListener('scroll', measure, { passive: true })
    window.addEventListener('resize', measure)
    return () => {
      lane.removeEventListener('scroll', measure)
      window.removeEventListener('resize', measure)
    }
  }, [pathname, collapsed])

  // `block: 'nearest'` matters: the default ('start') would also scroll the
  // page vertically to the lane, which is a jump nobody asked for.
  useEffect(() => {
    const lane = laneRef.current
    if (!lane || lane.scrollWidth <= lane.clientWidth) return
    lane.querySelector<HTMLElement>('.app-rail-row.on')?.scrollIntoView?.({ inline: 'nearest', block: 'nearest' })
  }, [pathname])

  const currentKey: DestinationKey | null = destinationFromPath(pathname)
  const others = allLeagues.filter((l) => l.current.sleeperLeagueId !== d.sleeperLeagueId)
  const multiSeason = lineage.seasons.length > 1
  // The flyout earns its place only if it has something to offer.
  const hasSwitcher = others.length > 0 || multiSeason
  const hue = hueForName(d.leagueName)
  const crest = crestLetter(d.leagueName)

  // inRail: false rows (the player page) are reached from a name, never listed here.
  const destinations = destinationsFor(league).filter((x) => x.inRail)
  const phone = useNarrow() // the phone lane: every group expanded (spec 013)
  const [groupState, setGroupState] = useState<GroupState>(readGroupState)
  const currentGroup = destinations.find((x) => x.key === currentKey)?.group ?? null

  // Landing on a page opens the group it lives in, so the current row is never
  // hidden behind a heading the viewer collapsed earlier.
  useEffect(() => {
    if (!currentGroup) return
    setGroupState((prev) => {
      if (prev[currentGroup] !== false) return prev
      const next = { ...prev, [currentGroup]: true }
      writeGroupState(next)
      return next
    })
  }, [currentGroup, pathname])

  function toggleGroup(g: DestinationGroup) {
    setGroupState((prev) => {
      const next = { ...prev, [g]: prev[g] === false }
      writeGroupState(next)
      return next
    })
  }

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

      {/* Every season is its own Sleeper league, so the years are links rather
          than a label, and the rail marks the season you are actually looking
          at instead of always the newest. A year link keeps the page you are
          on (switchTarget): pick 2025 on Superlatives and you get 2025's
          Superlatives; on the board, 2025's board; on History, History.
          Hidden while collapsed, where the flyout below carries them instead:
          a 56px rail has no room for a row of years, and draft rooms (which
          collapse by default) are exactly where comparing seasons is wanted. */}
      {!collapsed && multiSeason && (
        <div className="rail-league-seasons">
          {lineage.seasons.map((s) => (
            <Link
              key={s.sleeperLeagueId}
              to={switchTarget(currentKey, { lineage, season: s }, pathname)}
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
                      to={switchTarget(currentKey, { lineage, season: s }, pathname)}
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
                      to={switchTarget(currentKey, { lineage: l, season: l.current }, pathname)}
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
      <div className="app-rail-pages-wrap">
        <div
          className="app-rail-pages"
          ref={laneRef}
          data-overflow-start={overflow.start ? 'true' : undefined}
          data-overflow-end={overflow.end ? 'true' : undefined}
        >
          {DESTINATION_GROUPS.map((g) => {
            const rows = destinationsInGroup(destinations, g.key)
            if (rows.length === 0) return null
            // League home is one row and needs no heading.
            if (g.heading === null) return <Fragment key={g.key}>{rows.map(rowFor)}</Fragment>
            // A phone has no room for a second tap, and a collapsed desktop rail
            // has no room for a heading: both show every row.
            const alwaysOpen = phone || collapsed
            const open = alwaysOpen || groupState[g.key] !== false
            return (
              <div key={g.key} className="app-rail-group" role="group" aria-label={g.heading} data-open={open}>
                {alwaysOpen ? (
                  <span className="app-rail-group-head static" aria-hidden="true">
                    {g.heading}
                  </span>
                ) : (
                  <button
                    type="button"
                    className="app-rail-group-head"
                    aria-expanded={open}
                    onClick={() => toggleGroup(g.key)}
                  >
                    <span>{g.heading}</span>
                    <span className="app-rail-group-caret" aria-hidden="true">
                      {open ? '▾' : '▸'}
                    </span>
                  </button>
                )}
                {open && rows.map(rowFor)}
              </div>
            )
          })}
        </div>
        {/* Phone only (CSS). The lane hides pages off its right edge; this is
            the chip that is always on screen and lists all of them. */}
        {onOpenJumpTo && (
          <button type="button" className="app-rail-allpages" onClick={onOpenJumpTo} aria-label="All pages">
            All pages <span aria-hidden="true">▾</span>
          </button>
        )}
      </div>
    </div>
  )
}
