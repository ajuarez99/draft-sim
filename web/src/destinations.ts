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
  | 'home' | 'board' | 'live' | 'history' | 'power' | 'analysis'
  | 'rosterManagement' | 'expectedWins' | 'forecast' | 'weeklyReport' | 'superlatives' | 'mock'

/** What a fan is doing when they reach for the page (specs/013 US3). `home` is
 *  the one group without a heading: it is a single row, "League home". */
export type DestinationGroup = 'home' | 'thisWeek' | 'season' | 'draft' | 'history'

/** The groups in rail order, with the heading each renders. Declared beside the
 *  table so the order and the names are not a second copy in the rail. */
export const DESTINATION_GROUPS: readonly { key: DestinationGroup; heading: string | null }[] = [
  { key: 'home', heading: null },
  { key: 'thisWeek', heading: 'This week' },
  { key: 'season', heading: 'The season' },
  { key: 'draft', heading: 'Draft' },
  { key: 'history', heading: 'History' },
]

export type LeagueDestination = {
  key: DestinationKey
  /** Which rail group the row sits in. Required: a row with no group would be
   *  rendered nowhere. */
  group: DestinationGroup
  /** The label this page had before the fan-first rename. Jump-to still
   *  matches it, so nobody who knows "Expected wins" loses the page. Always
   *  different from `label`; absent where the name did not change. */
  formerLabel?: string
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
 * The declaration. Order is render order within each group; the groups
 * themselves render in DESTINATION_GROUPS order.
 *
 * Two rules, and each row states which one it follows:
 *
 * - Whole chain: League home, History (and, pending a decision, Power rankings) walk the
 *   season chain themselves, so they live on the league and use
 *   `lineage.current`. Every season's link lands on the same page.
 * - One season: the board, and every other league page (Analysis, Roster
 *   management, Expected wins, Season forecast, Weekly report, Superlatives),
 *   is a view of one season and uses the season being viewed, `ctx.season`.
 *   That is what lets the rail's year links keep you on the page you are on.
 */
export const LEAGUE_DESTINATIONS: readonly LeagueDestination[] = [
  {
    key: 'home',
    group: 'home',
    glyph: '⌂',
    // Both sports, written out: home is composed from endpoints that exist for
    // both, and the football-only block inside it gates itself on the sport.
    sports: ['nfl', 'nba'],
    label: 'League home',
    // whole chain: it reads the newest season's standings, like History
    href: (ctx) => `/leagues/${ctx.lineage.current.sleeperLeagueId}`,
    match: /^\/leagues\/([^/]+)\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'weeklyReport',
    group: 'thisWeek',
    formerLabel: 'Weekly report',
    glyph: '◨',
    // Both sports. Matchups, scores and the optimal-lineup awards all read
    // points already scored; none of it is a projection.
    sports: ['nfl', 'nba'],
    label: 'Matchups & awards',
    // one season: the page reads the season it is opened on
    href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/weekly-report`,
    match: /^\/leagues\/([^/]+)\/weekly-report\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'power',
    group: 'thisWeek',
    glyph: '▲',
    sports: ALL_SPORTS,
    label: 'Power rankings',
    // whole chain, for now: pending a decision on whether Power rankings should follow the season
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
    group: 'season',
    formerLabel: 'Analysis',
    glyph: '◫',
    // Football-only on its own terms rather than by a shared sport gate: two of
    // this page's three blocks are rest-of-season projections, and the only
    // projection source wired up (Sleeper's pts_ppr and friends) has no
    // basketball equivalent. A basketball league reaching it would get one
    // working block and two explaining themselves. See claude/league-analysis.md.
    sports: ['nfl'],
    label: 'Team strength',
    // one season: the page reads the season it is opened on
    href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/analysis`,
    match: /^\/leagues\/([^/]+)\/analysis\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'expectedWins',
    group: 'season',
    formerLabel: 'Expected wins',
    glyph: '◑',
    // Both sports, explicitly. Expected wins touches no position, no lineup and
    // no projection -- it is weekly scores and pairings, which mean the same
    // thing in basketball.
    sports: ['nfl', 'nba'],
    label: 'Luck',
    // one season: the page reads the season it is opened on
    href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/expected-wins`,
    match: /^\/leagues\/([^/]+)\/expected-wins\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'rosterManagement',
    group: 'season',
    formerLabel: 'Roster management',
    glyph: '◱',
    // Both sports, and written out rather than inherited from ALL_SPORTS by
    // habit: this page earns it. Unlike Analysis above, nothing here is a
    // projection -- total, potential and efficiency are all computed from
    // points already scored, which Sleeper reports for basketball exactly as
    // for football. See specs/004-ffwrapped-feature-parity research R2/R4.
    sports: ['nfl', 'nba'],
    label: 'Bench points',
    // one season: the page reads the season it is opened on
    href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/roster-management`,
    match: /^\/leagues\/([^/]+)\/roster-management\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'forecast',
    group: 'season',
    formerLabel: 'Season forecast',
    glyph: '◔',
    // Both sports. The simulator is driven by weekly scores and pairings, which
    // basketball has; the only gate is whether this app models the league's
    // seeding, and that is a league property rather than a sport one.
    sports: ['nfl', 'nba'],
    label: 'Playoff odds',
    // one season: the page reads the season it is opened on
    href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/forecast`,
    match: /^\/leagues\/([^/]+)\/forecast\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'superlatives',
    group: 'season',
    formerLabel: 'Superlatives',
    glyph: '◈',
    // Both sports. Every superlative reads scores, pairings and transactions
    // already stored -- none of it is a projection, so basketball gets it too.
    sports: ['nfl', 'nba'],
    label: 'Awards',
    // one season: the page reads the season it is opened on
    href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/superlatives`,
    match: /^\/leagues\/([^/]+)\/superlatives\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
  {
    key: 'board',
    group: 'draft',
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
    group: 'draft',
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
    key: 'mock',
    group: 'draft',
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
  {
    key: 'history',
    group: 'history',
    formerLabel: 'History',
    glyph: '◷',
    sports: ALL_SPORTS,
    label: 'Standings',
    // whole chain: History walks every season itself
    href: (ctx) => `/leagues/${ctx.lineage.current.sleeperLeagueId}/history`,
    match: /^\/leagues\/([^/]+)\/history\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
  },
]

/** The rows of one group, in table order. */
export function destinationsInGroup(rows: LeagueDestination[], group: DestinationGroup): LeagueDestination[] {
  return rows.filter((d) => d.group === group)
}

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
