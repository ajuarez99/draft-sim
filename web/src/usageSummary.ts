import type { UsageEvent, UsageFunnelRow, UsageReport, UsageSite } from './api'
import { routesFor } from './analytics'
import type { Site } from './site'

/* Spec 076: the arithmetic behind the owner's usage page, kept apart from the page so it can be tested alone. */

export type FunnelTotals = {
  /** Games started in the range. The owner's signed-in games are left out when `ownerLeftOut`. */
  started: number
  finished: number
  /** started - finished: abandoned plus still drafting. */
  notFinished: number
  /** Gave up (pressed abandon). Owner left out when `ownerLeftOut`. */
  abandoned: number
  /** Left open. Owner left out when `ownerLeftOut`. */
  drafting: number
  guestStarted: number
  guestFinished: number
  unknownStarted: number
  /** Signed-in games, the owner's left out when `ownerLeftOut`. Never negative: guest, unknown and owner are disjoint. */
  signedInStarted: number
  /** Game of the day, the owner's left out when `ownerLeftOut`. */
  dailyStarted: number
  dailyFinished: number
  /** What was left out: the owner's signed-in games. All 0 when no owner is configured. */
  ownerStarted: number
  ownerFinished: number
  ownerAbandoned: number
  ownerDrafting: number
  ownerLeftOut: boolean
}

const sum = (rows: UsageFunnelRow[], f: (r: UsageFunnelRow) => number) => rows.reduce((a, r) => a + f(r), 0)

/**
 * Totals for one sport over the rows given. When the server knows who the owner is, EVERY number leaves out
 * the owner's signed-in games, so the page never mixes a total with and without them. The server counts the
 * owner only on `started_as_guest = false` rows, so guest, unknown and owner are disjoint and nothing here can go negative.
 */
export function funnelTotals(rows: UsageFunnelRow[], ownerConfigured: boolean): FunnelTotals {
  const own = (f: (r: UsageFunnelRow) => number) => (ownerConfigured ? sum(rows, f) : 0)
  const ownerStarted = own((r) => r.ownerStarted)
  const ownerFinished = own((r) => r.ownerFinished)
  const ownerAbandoned = own((r) => r.ownerAbandoned)
  const ownerDrafting = own((r) => r.ownerDrafting)
  const guestStarted = sum(rows, (r) => r.guestStarted)
  const unknownStarted = sum(rows, (r) => r.unknownStarted)
  const started = sum(rows, (r) => r.started) - ownerStarted
  const finished = sum(rows, (r) => r.finished) - ownerFinished
  return {
    started,
    finished,
    notFinished: started - finished,
    abandoned: sum(rows, (r) => r.abandoned) - ownerAbandoned,
    drafting: sum(rows, (r) => r.drafting) - ownerDrafting,
    guestStarted,
    guestFinished: sum(rows, (r) => r.guestFinished),
    unknownStarted,
    signedInStarted: started - guestStarted - unknownStarted,
    dailyStarted: sum(rows, (r) => r.dailyStarted) - own((r) => r.ownerDailyStarted),
    dailyFinished: sum(rows, (r) => r.dailyFinished) - own((r) => r.ownerDailyFinished),
    ownerStarted,
    ownerFinished,
    ownerAbandoned,
    ownerDrafting,
    ownerLeftOut: ownerConfigured,
  }
}

/** One day's started / finished / not finished, the owner's signed-in games left out when the server knows him. */
export function dayCounts(r: UsageFunnelRow, ownerConfigured: boolean) {
  const started = r.started - (ownerConfigured ? r.ownerStarted : 0)
  const finished = r.finished - (ownerConfigured ? r.ownerFinished : 0)
  return { started, finished, notFinished: started - finished }
}

/** finished / started as a whole percent, or null when nothing started (never a 0% for an empty cohort). */
export function finishRate(t: Pick<FunnelTotals, 'started' | 'finished'>): number | null {
  return t.started > 0 ? Math.round((t.finished / t.started) * 100) : null
}

/** The first counted day for one site, or null when that site has nothing counted yet. */
export function sinceFor(report: Pick<UsageReport, 'countingSince'>, site: UsageSite): string | null {
  return report.countingSince.find((s) => s.site === site)?.since ?? null
}

/** Browser-reported shares per sport (the `result-shared` events). A floor: a blocked or failed send is not here. */
export function sharesBySport(events: UsageEvent[]): Record<'nba' | 'nfl', number> {
  const out = { nba: 0, nfl: 0 }
  for (const e of events) {
    if (e.name !== 'result-shared') continue
    const sport = e.props.sport
    if (sport === 'nba' || sport === 'nfl') out[sport] += e.count
  }
  return out
}

/** Every date from `from` to `to` inclusive, YYYY-MM-DD, oldest first. Calendar arithmetic in UTC, so no zone or DST shift. */
export function daysBetween(from: string, to: string): string[] {
  const out: string[] = []
  const end = Date.parse(`${to}T00:00:00Z`)
  for (let t = Date.parse(`${from}T00:00:00Z`); t <= end && out.length < 400; t += 86_400_000) {
    out.push(new Date(t).toISOString().slice(0, 10))
  }
  return out
}

/** The label for a stored route template; the template itself when no label is left for it. */
export function routeLabel(site: string, route: string): string {
  if (site !== 'timemachine' && site !== 'leagues' && site !== 'combined') return route
  const table = routesFor(site as Site)
  const find = (url: string) => table.find((r) => r.url === url)?.title
  if (route === '/(landing)') return 'Landing'
  if (route.startsWith('/(landing)/')) {
    const from = find(route.slice('/(landing)'.length))
    return from ? `Landing (from ${from})` : route
  }
  return find(route) ?? route
}
