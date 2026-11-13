import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { crestLetter, hueForName } from '../hue'
import {
  LEAGUE_TABS,
  destinationFromPath,
  destinationsFor,
  labelOf,
  railKeyFor,
  subTabsFor,
  tabFromPath,
  tabsFor,
  type DestinationKey,
  type LeagueTab,
} from '../destinations'
import type { LeagueLineage } from '../leagueLineage'
import type { RailLeague } from '../railLeague'
import PlatformBadge from './PlatformBadge'
import { refreshLine, useLeagueRefresh } from '../leagueRefresh'
import { switchTarget } from '../switchTarget'
import { laneOverflow, type LaneOverflow } from '../railLane'
import { usePopup } from '../popupFocus'
import type { DraftSummary } from '../api'

type Props = {
  league: RailLeague
  pathname: string
  /** The location's query string (with or without the leading `?`); only the stat calculator's season links read it. */
  search?: string
  /** A draft room or mock on a desktop: identity, switcher and tabs ride in the top bar (portaled
   *  into `topbarSlot`), the Draft sub-tabs stay as a thin row, and the refresh line is not shown. */
  compact: boolean
  /** Phone width: the tabs live in the bottom bar, so this header renders none. */
  narrow: boolean
  /** Seeded with this league, the same way the home card's "Mock it" is. */
  onMockIt: (leagueId: string, sport: DraftSummary['sport']) => void
  /** Every league the signed-in user can see, for the switcher. Empty until the draft list
   *  resolves, which just means no switcher yet. */
  allLeagues?: LeagueLineage[]
  /** The compact top bar's mount point; null until it renders. */
  topbarSlot: HTMLElement | null
}

function Caret() {
  return (
    <svg viewBox="0 0 10 6" width="10" height="6" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <path d="M1 1l4 4 4-4" />
    </svg>
  )
}

/** The season dropdown. Every season is its own Sleeper league, so a choice is a link, and it goes
 *  through switchTarget: pick 2025 on Superlatives and you get 2025's Superlatives (spec 011). */
function SeasonMenu({
  league,
  pathname,
  search,
  currentKey,
}: {
  league: RailLeague
  pathname: string
  search?: string
  currentKey: DestinationKey | null
}) {
  const { lineage, season } = league
  const [open, setOpen] = useState(false)
  const menu = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  useEffect(() => setOpen(false), [pathname])
  usePopup(open, () => setOpen(false), menu, trigger)

  // Whole-chain pages (League home, History, Power rankings) walk every season themselves, so each
  // year's target is the URL you are already on. A dropdown whose every item does nothing reads as
  // broken; show the season as a label there instead.
  const here = pathname + (search ?? '')
  const targets = lineage.seasons.map((s) => switchTarget(currentKey, { lineage, season: s }, pathname, search))
  const goesNowhere = targets.every((t) => t === here || t === pathname)
  if (lineage.seasons.length <= 1 || goesNowhere) {
    return <span className="league-season-static">{season.season}</span>
  }

  return (
    <div className="league-popup-wrap">
      <button
        type="button"
        ref={trigger}
        className="league-menu-button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Season: ${season.season}`}
      >
        {season.season}
        <Caret />
      </button>
      {open && (
        <div className="league-menu" role="menu" ref={menu}>
          {lineage.seasons.map((s) => (
            <Link
              key={s.sleeperLeagueId}
              to={switchTarget(currentKey, { lineage, season: s }, pathname, search)}
              role="menuitem"
              onClick={() => setOpen(false)}
              className={`league-menu-item${s.sleeperDraftId === season.sleeperDraftId ? ' on' : ''}`}
              title={`${s.season} · ${s.teams} managers`}
            >
              {s.season}
              <span className="muted small">{s.teams} managers</span>
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}

/** The league switcher: the user's other leagues, each landing on the same page when that league has
 *  it (switchTarget). Absent when there is nothing to switch to. */
function LeagueSwitcher({
  others,
  pathname,
  currentKey,
}: {
  others: LeagueLineage[]
  pathname: string
  currentKey: DestinationKey | null
}) {
  const [open, setOpen] = useState(false)
  const menu = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  useEffect(() => setOpen(false), [pathname])
  usePopup(open, () => setOpen(false), menu, trigger)

  if (others.length === 0) return null
  return (
    <div className="league-popup-wrap">
      <button
        type="button"
        ref={trigger}
        className="league-menu-button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        title="Switch league"
      >
        Switch league
        <Caret />
      </button>
      {open && (
        <div className="league-menu" role="menu" ref={menu}>
          <span className="league-menu-label">Leagues</span>
          {others.map((l) => (
            <Link
              key={l.current.sleeperLeagueId}
              to={switchTarget(currentKey, { lineage: l, season: l.current }, pathname)}
              role="menuitem"
              onClick={() => setOpen(false)}
              className="league-menu-item"
            >
              {l.current.leagueName}
              <span className={`sport-pill ${l.current.sport}`}>{l.current.sport.toUpperCase()}</span>
              <PlatformBadge platform={l.current.platform} />
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * The league header: whose league you are inside, the tabs for what it offers, and under the active
 * tab its sub-tabs. It replaces LeagueRailSection (claude/site-redesign.md Phase 2). Rendered by
 * AppShell rather than portaled by each page, because several routes share two URL shapes and the
 * league resolves from the path alone (railLeague.ts).
 *
 * Every row comes from `destinationsFor(league)` in destinations.ts -- the same table the URL matcher
 * and the jump-to palette read -- so a link this renders and a URL the app recognises cannot disagree
 * (the Analysis page once did: linked from the old rail, missing from the matcher, so using it
 * deleted the section that linked you).
 *
 * `useLeagueRefresh` runs here, exactly once per league, in every variant: compact only hides the
 * line it feeds, so a league still refreshes from a draft room (plan review PR-16).
 */
export default function LeagueHeader({ league, pathname, search, compact, narrow, onMockIt, allLeagues = [], topbarSlot }: Props) {
  const { lineage, season } = league
  const d = lineage.current
  const refresh = useLeagueRefresh(
    d.sleeperLeagueId,
    lineage.seasons.map((s) => s.sleeperLeagueId),
  )
  const refreshText = refreshLine(refresh)

  const offered = destinationsFor(league)
  // railKeyFor: on an auction league's ADP page the row that is "here" is the board row.
  const currentKey: DestinationKey | null = railKeyFor(destinationFromPath(pathname), offered)
  const currentTab: LeagueTab | null = tabFromPath(pathname, offered)
  const tabs = tabsFor(league)
  const others = allLeagues.filter((l) => l.current.sleeperLeagueId !== d.sleeperLeagueId)
  const hue = hueForName(d.leagueName)
  const homeHref = tabs.find((t) => t.tab === 'home')?.href ?? `/leagues/${d.sleeperLeagueId}`

  // A draft room or mock has no tab of its own to light, but the Draft sub-tabs are exactly what is
  // wanted there (Follow live, ADP, Mock it -- plan review PR-02).
  const subTab: LeagueTab | null = compact ? 'draft' : currentTab
  const subRows = subTab ? subTabsFor(league, subTab) : []
  const subLinks = subRows.filter((r) => !r.isAction)
  const subActions = subRows.filter((r) => r.isAction)
  const showSubTabs = subTab != null && (subRows.length > 1 || subActions.length > 0)

  // Which edges of the horizontally scrolling sub-tab strip still have pages behind them, and keep
  // the current page on screen. No-ops wherever the strip does not scroll (every desktop layout).
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
  }, [pathname, showSubTabs])
  // `block: 'nearest'` matters: the default ('start') would also scroll the page vertically.
  useEffect(() => {
    const lane = laneRef.current
    if (!lane || lane.scrollWidth <= lane.clientWidth) return
    lane.querySelector<HTMLElement>('[aria-current="page"]')?.scrollIntoView?.({ inline: 'nearest', block: 'nearest' })
  }, [pathname])

  const tabsNav = (
    <nav className="league-tabs" aria-label="League">
      {tabs.map((t) => {
        const on = t.tab === currentTab
        return (
          <Link key={t.tab} to={t.href} className={`league-tab${on ? ' on' : ''}`} aria-current={on ? 'page' : undefined}>
            {t.label}
          </Link>
        )
      })}
    </nav>
  )

  const identity = (
    <Link
      to={homeHref}
      className="league-identity"
      title={`${d.leagueName} · ${d.sport.toUpperCase()} · ${season.teams} managers`}
      aria-label={d.leagueName}
    >
      <span
        className="avatar league-crest league-identity-crest"
        style={{ background: `oklch(30% 0.05 ${hue})`, color: `oklch(84% 0.12 ${hue})` }}
        aria-hidden="true"
      >
        {crestLetter(d.leagueName)}
      </span>
      <span className="league-identity-text">
        <span className="league-identity-name">{d.leagueName}</span>
        {!compact && (
          <span className="league-identity-sub">
            <span className={`sport-pill ${d.sport}`}>{d.sport.toUpperCase()}</span>
            <PlatformBadge platform={d.platform} />
            {season.teams} managers
          </span>
        )}
      </span>
    </Link>
  )

  const controls = (
    <>
      <SeasonMenu league={league} pathname={pathname} search={search} currentKey={currentKey} />
      <LeagueSwitcher others={others} pathname={pathname} currentKey={currentKey} />
    </>
  )

  const tabName = LEAGUE_TABS.find((t) => t.key === subTab)?.label ?? ''
  const subNav = showSubTabs && (
    <div className="league-subtabs-wrap">
      <nav
        className="league-subtabs"
        aria-label={`${tabName} pages`}
        ref={laneRef}
        data-overflow-start={overflow.start ? 'true' : undefined}
        data-overflow-end={overflow.end ? 'true' : undefined}
      >
        {subLinks.map((row) => {
          const on = row.key === currentKey
          return (
            <Link
              key={row.key}
              to={row.href(league)}
              className={`league-subtab${row.key === 'live' ? ' live' : ''}${on ? ' on' : ''}`}
              aria-current={on ? 'page' : undefined}
            >
              {/* Live is the one destination whose mark carries state, so it keeps its pulsing dot. */}
              {row.key === 'live' && <span className="league-live-dot" aria-hidden="true" />}
              {labelOf(row, season)}
            </Link>
          )
        })}
      </nav>
      {subActions.length > 0 && (
        <div className="league-subtabs-actions">
          {subActions.map((row) => (
            <button key={row.key} type="button" className="league-subtab action" onClick={() => onMockIt(d.sleeperLeagueId, d.sport)}>
              {labelOf(row, season)}
            </button>
          ))}
        </div>
      )}
    </div>
  )

  if (compact) {
    return (
      <>
        {topbarSlot &&
          createPortal(
            <div className="league-compact">
              {identity}
              {controls}
              {tabsNav}
            </div>,
            topbarSlot,
          )}
        {subNav && <div className="league-header compact">{subNav}</div>}
      </>
    )
  }

  return (
    <div className="league-header">
      <div className="league-header-row">
        {identity}
        {controls}
        {!narrow && tabsNav}
        {refreshText && (
          <span className="league-header-refresh" role="status">
            {refreshText}
          </span>
        )}
      </div>
      {subNav}
    </div>
  )
}
