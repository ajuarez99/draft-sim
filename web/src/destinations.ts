import type { DraftSummary, Sport } from './api'

/**
 * The one declaration of what pages a league has.
 *
 * Before this module the same knowledge lived in three places -- App.tsx's
 * route table, a literal alternation in railLeague.ts, and the hrefs and sport
 * gate hand-written into LeagueRailSection's JSX -- and they had already
 * drifted apart. `/leagues/:id/analysis` was added to two of the three, so the
 * rail linked you to Analysis and then, because the matcher did not recognise
 * the route it had just sent you to, deleted the League section that linked
 * you. Four of a league's five destinations vanished at the moment you used
 * the fifth. Same class as the multi-sport landmines: two implementations of
 * one rule, disagreeing quietly.
 *
 * So the rule is stated once, here, and every consumer reads it:
 *
 *   - railLeague.ts     -- which league a pathname is inside (`match`/`idKind`)
 *   - LeagueRailSection -- what to render and where it links (`label`/`href`)
 *   - searchIndex.ts    -- what the palette can offer (`destinationsFor`)
 *   - the league switcher -- what survives a switch between leagues (`key`)
 *
 * Adding a league-scoped route means adding a row here. destinations.test.ts
 * fails if App.tsx grows one without it, which is the part that stops this
 * from rotting again.
 */

/** The minimum a destination needs to build its own URL. Structurally what
 *  railLeague's `RailLeague` is -- declared here rather than imported from
 *  there because railLeague imports *this*, and the cycle has to break
 *  somewhere. railLeague re-exports it under its own name. */
export type LeagueContext = {
  /** Seasons of one league, newest first, `current` included. */
  lineage: { current: DraftSummary; seasons: DraftSummary[] }
  /** The season actually being viewed -- not always `lineage.current`, since
   *  an older season's board is its own route. */
  season: DraftSummary
}

export type DestinationKey =
  | 'board' | 'live' | 'history' | 'power' | 'analysis'
  | 'rosterManagement' | 'expectedWins' | 'forecast' | 'mock'

export type LeagueDestination = {
  key: DestinationKey
  /** Rail glyph. Empty for `live`, which renders a pulsing dot instead of a
   *  character -- the one destination whose mark carries state. */
  glyph: string
  /** Which sports offer this page. Required and non-empty by construction: a
   *  defaulted sport list asserts a rule rather than a value, which is exactly
   *  how a football-only page gets offered to a basketball league. */
  sports: Sport[]
  /** A function only where the label genuinely depends on the season -- the
   *  board is "Draft room" before and during a draft, "Draft board" after. */
  label: string | ((season: DraftSummary) => string)
  href: (ctx: LeagueContext) => string
  /** Recognises this destination in a pathname, capturing the id in group 1.
   *  Null for actions, which have no URL to recognise. */
  match: RegExp | null
  /** What group 1 of `match` is. The matcher needs this to know whether it
   *  captured a draft id or a league id -- they are different keys and both
   *  appear in these URLs. */
  idKind: 'draft' | 'league' | null
  /** Sleeper draft statuses this destination exists for; null means always.
   *  `status` is nullable on DraftSummary and an unknown status is treated as
   *  not matching, never as a wildcard. */
  requiresStatus: string[] | null
  /** True for a row that runs something instead of navigating -- "Mock it"
   *  opens the new-mock modal and has no route of its own. */
  isAction: boolean
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

const ALL_SPORTS: Sport[] = ['nfl', 'nba']

/**
 * The declaration. Order is rail render order.
 *
 * League-scoped pages key off `lineage.current.sleeperLeagueId` rather than the
 * season being viewed: history, power rankings and analysis each walk the whole
 * season chain themselves, so they live on the league, not on one of its years.
 * The board is the exception and takes the viewed season, because every season
 * genuinely has its own board.
 */
export const LEAGUE_DESTINATIONS: readonly LeagueDestination[] = [
  {
    key: 'board',
    glyph: '▦',
    sports: ALL_SPORTS,
    label: draftLabel,
    href: (ctx) => draftRoute(ctx.season),
    // `/drafts/:id` and `/drafts/:id/board` are the same destination: which one
    // a league gets is draftRoute()'s decision, not the user's.
    match: /^\/drafts\/([^/]+)(?:\/board)?\/?$/,
    idKind: 'draft',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'live',
    glyph: '',
    sports: ALL_SPORTS,
    label: 'Follow live',
    href: (ctx) => `/drafts/${ctx.season.sleeperDraftId}/live`,
    match: /^\/drafts\/([^/]+)\/live\/?$/,
    idKind: 'draft',
    requiresStatus: ['pre_draft', 'drafting'],
    isAction: false,
  },
  {
    key: 'history',
    glyph: '◷',
    sports: ALL_SPORTS,
    label: 'History',
    href: (ctx) => `/leagues/${ctx.lineage.current.sleeperLeagueId}/history`,
    match: /^\/leagues\/([^/]+)\/history\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'power',
    glyph: '▲',
    sports: ALL_SPORTS,
    label: 'Power rankings',
    href: (ctx) => `/leagues/${ctx.lineage.current.sleeperLeagueId}/power`,
    // `/verify` is the dev-only self-check harness (power-rankings-reskin.md
    // §7). It is not a destination of its own, but it is inside the league and
    // the rail should not blank out on it.
    match: /^\/leagues\/([^/]+)\/power(?:\/verify)?\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'analysis',
    glyph: '◫',
    // Football-only on its own terms rather than by a shared sport gate: two of
    // this page's three blocks are rest-of-season projections, and the only
    // projection source wired up (Sleeper's pts_ppr and friends) has no
    // basketball equivalent. A basketball league reaching it would get one
    // working block and two explaining themselves. See claude/league-analysis.md.
    sports: ['nfl'],
    label: 'Analysis',
    href: (ctx) => `/leagues/${ctx.lineage.current.sleeperLeagueId}/analysis`,
    match: /^\/leagues\/([^/]+)\/analysis\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'rosterManagement',
    glyph: '◱',
    // Both sports, and written out rather than inherited from ALL_SPORTS by
    // habit: this page earns it. Unlike Analysis above, nothing here is a
    // projection -- total, potential and efficiency are all computed from
    // points already scored, which Sleeper reports for basketball exactly as
    // for football. See specs/004-ffwrapped-feature-parity research R2/R4.
    sports: ['nfl', 'nba'],
    label: 'Roster management',
    href: (ctx) => `/leagues/${ctx.lineage.current.sleeperLeagueId}/roster-management`,
    match: /^\/leagues\/([^/]+)\/roster-management\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'expectedWins',
    glyph: '◑',
    // Both sports, explicitly. Expected wins touches no position, no lineup and
    // no projection -- it is weekly scores and pairings, which mean the same
    // thing in basketball.
    sports: ['nfl', 'nba'],
    label: 'Expected wins',
    href: (ctx) => `/leagues/${ctx.lineage.current.sleeperLeagueId}/expected-wins`,
    match: /^\/leagues\/([^/]+)\/expected-wins\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'forecast',
    glyph: '◔',
    // Both sports. The simulator is driven by weekly scores and pairings, which
    // basketball has; the only gate is whether this app models the league's
    // seeding, and that is a league property rather than a sport one.
    sports: ['nfl', 'nba'],
    label: 'Season forecast',
    href: (ctx) => `/leagues/${ctx.lineage.current.sleeperLeagueId}/forecast`,
    match: /^\/leagues\/([^/]+)\/forecast\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'mock',
    glyph: '▶',
    sports: ALL_SPORTS,
    label: 'Mock it',
    // Seeded with this league, the same way the home card's "Mock it" is. The
    // modal lives on Home, so this is a request carried by navigation rather
    // than a route -- see AppShell.
    href: () => '/',
    match: null,
    idKind: null,
    requiresStatus: null,
    isAction: true,
  },
]

/** Resolves a label that may depend on the season being viewed. */
export function labelOf(d: LeagueDestination, season: DraftSummary): string {
  return typeof d.label === 'function' ? d.label(season) : d.label
}

/**
 * The destinations one league actually offers, sport and status applied.
 *
 * Both gates are one-way: a basketball league never gets Analysis, and
 * "Follow live" never appears for a draft that is over. An unknown or missing
 * status fails `requiresStatus` rather than passing it -- `status` is nullable
 * and a null must not become a wildcard that shows a live link for a draft
 * nobody is running.
 */
export function destinationsFor(ctx: LeagueContext): LeagueDestination[] {
  const sport = ctx.lineage.current.sport
  const status = ctx.season.status ?? 'unknown'
  return LEAGUE_DESTINATIONS.filter(
    (d) => d.sports.includes(sport) && (d.requiresStatus == null || d.requiresStatus.includes(status)),
  )
}

/** Which destination a pathname is, if any. Table order decides ties; there
 *  are none today, since `live` is matched before the board's optional-suffix
 *  pattern could be tempted by it (`/live` is not `/board` and not bare). */
export function destinationFromPath(pathname: string): DestinationKey | null {
  for (const d of LEAGUE_DESTINATIONS) {
    if (d.match && d.match.test(pathname)) return d.key
  }
  return null
}

/** The id a league-scoped pathname carries, and which key it is. Null when the
 *  path belongs to no league destination. */
export function leagueIdFromPath(
  pathname: string,
): { idKind: 'draft' | 'league'; id: string } | null {
  for (const d of LEAGUE_DESTINATIONS) {
    if (!d.match || !d.idKind) continue
    const m = pathname.match(d.match)
    if (m) return { idKind: d.idKind, id: m[1] }
  }
  return null
}
