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
  /** The player a player page is about. Only the `players` row reads it: that
   *  page is addressed by a league AND a player, and a league context alone
   *  cannot build its URL. Everything else ignores it. */
  playerId?: string
}

export type DestinationKey =
  | 'home' | 'board' | 'live' | 'history' | 'power' | 'analysis'
  | 'rosterManagement' | 'expectedWins' | 'forecast' | 'weeklyReport' | 'superlatives' | 'mock' | 'schedule' | 'trends' | 'players' | 'compare' | 'stats' | 'rivalries' | 'adp'

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
  /** False for a page that is reached from a link rather than from the rail.
   *  Required, never defaulted: a defaulted flag would offer a page that needs
   *  an id nobody has to every league. The rail and the jump-to palette skip
   *  rows where this is false; `destinationFromPath` and the sport gate still
   *  see them. */
  inRail: boolean
}

/** The ADP & prices page of a league (spec 046). Also where an auction draft lives. */
export function adpPath(sleeperLeagueId: string): string {
  return `/leagues/${sleeperLeagueId}/adp`
}

/**
 * Spec 046 FR-015: where an auction draft goes instead of the snake simulator, or null for a draft that
 * isn't one. The ADP page is basketball-only (football has no compare sources and answers 404), so a football
 * auction keeps its old routes, where the simulator refuses it with a clear message (FR-016). This is the ONE
 * place the rule is written; every entry point (rail, league home, picker, the old /drafts URLs) asks it.
 */
export function auctionAdpRoute(
  draftType: string | null | undefined,
  sport: Sport | null | undefined,
  sleeperLeagueId: string | null | undefined,
): string | null {
  if (draftType !== 'auction' || sport !== 'nba' || !sleeperLeagueId) return null
  return adpPath(sleeperLeagueId)
}

/** The same rule the home card uses: a complete draft has real picks to show,
 *  anything else opens the simulator instead of an empty "come back later".
 *  An auction has no pick order to simulate or show, so it goes to the ADP page (FR-015). */
function draftRoute(d: DraftSummary): string {
  const auction = auctionAdpRoute(d.draftType, d.sport, d.sleeperLeagueId)
  if (auction) return auction
  return (d.status ?? 'unknown') === 'complete'
    ? `/drafts/${d.sleeperDraftId}/board`
    : `/drafts/${d.sleeperDraftId}`
}

function draftLabel(d: DraftSummary): string {
  if (auctionAdpRoute(d.draftType, d.sport, d.sleeperLeagueId)) return 'Auction board'
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
    inRail: true,
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
    inRail: true,
  },
  {
    key: 'schedule',
    group: 'thisWeek',
    glyph: '▤',
    // Basketball only: an NFL team plays once a week, so the grid would be a
    // column of ones. See specs/017-nba-schedule-grid.
    sports: ['nba'],
    label: 'Schedule grid',
    // one season: the page reads the season it is opened on (spec 011's rule)
    href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/schedule`,
    match: /^\/leagues\/([^/]+)\/schedule\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
    inRail: true,
  },
  {
    key: 'trends',
    group: 'thisWeek',
    glyph: '↕',
    // Basketball only: minutes, usage and games-per-week are NBA notions.
    // See specs/019-minutes-streaming.
    sports: ['nba'],
    label: 'Trends',
    // one season: the page reads the season it is opened on (spec 011's rule)
    href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/trends`,
    match: /^\/leagues\/([^/]+)\/trends\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
    inRail: true,
  },
  {
    key: 'players',
    group: 'thisWeek',
    glyph: '◉',
    // Basketball only: the stat lines this page reads exist for the NBA alone.
    // This is the ONE place that says so -- PlayerLink asks playerPagesFor().
    sports: ['nba'],
    label: 'Player',
    // one season: the page reads the season it is opened on, so the rail's year
    // links keep you on the player. The id rides on the context; without one
    // there is no player page to link to, so fall back to the league's home.
    href: (ctx) =>
      ctx.playerId
        ? `/leagues/${ctx.season.sleeperLeagueId}/players/${encodeURIComponent(ctx.playerId)}`
        : `/leagues/${ctx.season.sleeperLeagueId}`,
    match: /^\/leagues\/([^/]+)\/players\/[^/]+\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
    // Reached from a player's name, not from the rail or the palette.
    inRail: false,
  },
  {
    key: 'compare',
    group: 'thisWeek',
    glyph: '⇄',
    // Basketball only, like the player pages it composes (specs/049). This is the ONE place that says so --
    // comparePagesFor() reads it, for both the page's football line and the player page's Compare link.
    sports: ['nba'],
    label: 'Compare players',
    // one season: addressed by a league id, so the rail's year links keep you on the page. Accepted (plan A8):
    // a season switch from the rail drops ?a=&b=, because another season's league is a different comparison.
    href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/compare`,
    match: /^\/leagues\/([^/]+)\/compare\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
    // Reached from the Compare link on a player page, not from the rail or the palette.
    inRail: false,
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
    inRail: true,
  },
  {
    key: 'analysis',
    group: 'season',
    formerLabel: 'Analysis',
    glyph: '◫',
    // Both sports since spec 030. Basketball's projections come from per-game lines
    // (one average game per starter), so the page words its numbers by the payload's
    // `unit` rather than by sport; see claude/league-analysis.md and specs/030.
    sports: ['nfl', 'nba'],
    label: 'Team strength',
    // one season: the page reads the season it is opened on
    href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/analysis`,
    match: /^\/leagues\/([^/]+)\/analysis\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
    inRail: true,
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
    inRail: true,
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
    inRail: true,
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
    inRail: true,
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
    inRail: true,
  },
  {
    key: 'stats',
    group: 'season',
    glyph: '▥',
    // Basketball only, like the player page it links into (specs/022 US4).
    sports: ['nba'],
    label: 'Player stats',
    // one season: the page reads the season it is opened on (spec 011's rule)
    href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/stats`,
    // The calculator is part of Player stats, so the rail keeps this row lit on it (specs/032 R12).
    match: /^\/leagues\/([^/]+)\/stats(?:\/calculator)?\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
    inRail: true,
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
    inRail: true,
  },
  {
    key: 'live',
    group: 'draft',
    glyph: '',
    sports: ALL_SPORTS,
    label: 'Follow live',
    // An auction has no live simulation; its live view is the ADP page (spec 046 FR-015).
    href: (ctx) => auctionAdpRoute(ctx.season.draftType, ctx.season.sport, ctx.season.sleeperLeagueId)
      ?? `/drafts/${ctx.season.sleeperDraftId}/live`,
    match: /^\/drafts\/([^/]+)\/live\/?$/,
    idKind: 'draft',
    requiresStatus: ['pre_draft', 'drafting'],
    isAction: false,
    inRail: true,
  },
  {
    key: 'adp',
    group: 'draft',
    glyph: '≈',
    // Basketball only (spec 046): the Sleeper dynasty/redraft captures and Yahoo's feed are NBA sources, and
    // the compare page has no football counterpart. This is the ONE place that says so.
    sports: ['nba'],
    label: 'ADP & prices',
    // one season: the page is addressed by a league id, so the rail's year links keep you on it
    href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/adp`,
    match: /^\/leagues\/([^/]+)\/adp\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
    inRail: true,
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
    inRail: true,
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
    inRail: true,
  },
  {
    key: 'rivalries',
    group: 'history',
    glyph: '⚔',
    // Both sports: it reads scores and pairings already stored (specs/034).
    sports: ['nfl', 'nba'],
    label: 'Rivalries',
    // one season, unlike History above: the page shows the head-to-head history up to and
    // including the season being viewed, plus THAT season's schedule, so the rail's year
    // links must keep you on it.
    href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/rivalries`,
    match: /^\/leagues\/([^/]+)\/rivalries\/?$/,
    idKind: 'league',
    requiresStatus: null,
    isAction: false,
    inRail: true,
  },
]

/** True when this sport has player pages. The only gate for linking a player's
 *  name: derived from the `players` row's `sports`, so no caller compares a sport
 *  string. */
export function playerPagesFor(sport: Sport): boolean {
  return LEAGUE_DESTINATIONS.find((d) => d.key === 'players')?.sports.includes(sport) ?? false
}

/** True when this sport has the player comparison (spec 049). Same shape as playerPagesFor: derived from the
 *  `compare` row's `sports`, so no caller compares a sport string. */
export function comparePagesFor(sport: Sport): boolean {
  return LEAGUE_DESTINATIONS.find((d) => d.key === 'compare')?.sports.includes(sport) ?? false
}

/** The player id in a player-page pathname, or null on any other path. */
export function playerIdFromPath(pathname: string): string | null {
  const m = pathname.match(/^\/leagues\/[^/]+\/players\/([^/]+)\/?$/)
  return m ? decodeURIComponent(m[1]) : null
}

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
  // Spec 046 FR-015: a mock would fork a snake-order simulation, which an auction doesn't have.
  // Spec 046 C9: for an auction `board`, `live` and `adp` all resolve to the same URL (the ADP page), so the
  // rail would show three rows to one page. Keep ONE: `board`, labelled "Auction board". It is the row other
  // code falls back to (switchTarget, the rail's board link), so it stays; `live` is the same page, and `adp`
  // is the same page under a second name. The rail still lights `board` on the ADP page via railKeyFor.
  const auction = auctionAdpRoute(ctx.season.draftType, ctx.season.sport, ctx.season.sleeperLeagueId) != null
  const dropped: DestinationKey[] = auction ? ['mock', 'live', 'adp'] : []
  return LEAGUE_DESTINATIONS.filter(
    (d) =>
      d.sports.includes(sport) &&
      (d.requiresStatus == null || d.requiresStatus.includes(status)) &&
      !dropped.includes(d.key),
  )
}

/**
 * Spec 046 C9: the row the rail should treat as "here". Path matching says `adp` on /leagues/:id/adp, but an
 * auction league offers no `adp` row (its `board` row IS that page), so without this nothing would light up and
 * the year links would fall back to league home instead of staying on the page.
 */
export function railKeyFor(current: DestinationKey | null, offered: LeagueDestination[]): DestinationKey | null {
  if (current === 'adp' && !offered.some((d) => d.key === 'adp') && offered.some((d) => d.key === 'board')) {
    return 'board'
  }
  return current
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
