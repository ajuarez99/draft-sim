// @ts-expect-error node:fs has no types here: the app has no @types/node (see draftSlot.parity.test.ts)
import { readFileSync } from 'node:fs'
import type { HistoricalSport, RedraftSeasonData, SeasonList, SeasonPage } from '../src/api'

// Spec 074 T024: where the static season pages get their data. vite.config.ts calls loadSeasonData() from the
// Time Machine site's build only (never under vitest or `vite dev`, PR-20). It either reads the backend's
// public season endpoints, one request at a time, or reads a JSON fixture named by REDRAFT_SEASON_DATA. A
// build that cannot get the data FAILS, naming the URL and the reason: publishing pages from nothing, or
// from an older backend's shape, would be worse than not publishing (PR-04).
//
// A sport with no seasons fails the build by default (publishing half a site by accident is worse than failing). To build
// with only the other sport, set REDRAFT_ALLOW_EMPTY_SPORT=1 (a build-time environment variable, read in vite.config.ts and
// passed as `allowEmptySport`): the empty sport then gets no pages, no sitemap entries, no tile and no footer link, and
// the build logs a loud warning. Both sports empty always fails.
//
// Node only: no React, no browser global. Type-checked by tsc -b (tsconfig.json includes this file).

const SPORTS: HistoricalSport[] = ['NBA', 'NFL']
/** The `schema` the season-list wrapper must carry. A different number means the backend and this build disagree. */
export const EXPECTED_SCHEMA = 1
/** Per request: keep retrying a failed fetch for this long (a backend deploying beside this build), then fail. */
export const RETRY_BUDGET_MS = 3 * 60 * 1000
const RETRY_DELAY_MS = 5000

/**
 * A season's slug and label become a file name, a canonical URL and a sitemap entry, so they must look like a season:
 * "2016-17" (basketball) or "2007" (football). Anything else, a path, a quote or an ampersand, fails the build by name
 * (review-fixes B1).
 */
export const SEASON_LABEL_FORM = /^\d{4}(-\d{2})?$/

/** The build-time switch for a sport that has no seasons yet (CR-08): `REDRAFT_ALLOW_EMPTY_SPORT=1`. Anything else fails. */
export const ALLOW_EMPTY_ENV = 'REDRAFT_ALLOW_EMPTY_SPORT'

export class SeasonDataError extends Error {
  constructor(source: string, reason: string) {
    super(`Redraft season data: ${source}: ${reason}`)
    this.name = 'SeasonDataError'
  }
}

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)

function need(source: string, o: Obj, key: string, kind: 'string' | 'number' | 'array' | 'object' | 'boolean', where: string): void {
  const v = o[key]
  const ok =
    kind === 'array' ? Array.isArray(v) : kind === 'object' ? isObj(v) : typeof v === kind && (kind !== 'number' || Number.isFinite(v))
  if (!ok) throw new SeasonDataError(source, `${where}.${key} is ${v === undefined ? 'missing' : `not ${kind === 'array' ? 'an array' : `a ${kind}`}`}`)
}
function nullableString(source: string, o: Obj, key: string, where: string): void {
  if (o[key] !== null && typeof o[key] !== 'string')
    throw new SeasonDataError(source, `${where}.${key} is ${o[key] === undefined ? 'missing' : 'not a string or null'}`)
}

function checkPlayer(source: string, o: unknown, where: string, keys: { rank: string[]; nullableGoingIn?: boolean }): void {
  if (!isObj(o)) throw new SeasonDataError(source, `${where} is not an object`)
  need(source, o, 'playerId', 'number', where)
  need(source, o, 'name', 'string', where)
  need(source, o, 'positions', 'array', where)
  need(source, o, 'points', 'number', where)
  for (const k of keys.rank) need(source, o, k, 'number', where)
  if ('team' in o || keys.rank.length > 0) nullableString(source, o, 'team', where)
  if (keys.nullableGoingIn && o.goingInRank !== null && typeof o.goingInRank !== 'number')
    throw new SeasonDataError(source, `${where}.goingInRank is ${o.goingInRank === undefined ? 'missing' : 'not a number or null'}`)
}

/** Checks the season-list wrapper, then returns it typed. `schema` first, so an older backend is named as such. */
export function validateSeasonList(source: string, raw: unknown, sport: HistoricalSport, allowEmpty = false): SeasonList {
  if (!isObj(raw)) throw new SeasonDataError(source, 'the response is not a JSON object')
  if (raw.schema !== EXPECTED_SCHEMA)
    throw new SeasonDataError(source, `schema is ${raw.schema === undefined ? 'missing' : JSON.stringify(raw.schema)}, expected ${EXPECTED_SCHEMA} (an older or newer backend?)`)
  if (raw.sport !== sport) throw new SeasonDataError(source, `sport is ${JSON.stringify(raw.sport)}, expected ${sport}`)
  if (!Array.isArray(raw.seasons)) throw new SeasonDataError(source, 'seasons is missing or not an array')
  if (raw.seasons.length === 0 && !allowEmpty)
    throw new SeasonDataError(
      source,
      `the ${sport} season list is empty. To build anyway with no ${sport} pages, set ${ALLOW_EMPTY_ENV}=1 (the other sport must have seasons)`,
    )
  raw.seasons.forEach((s, i) => {
    if (!isObj(s)) throw new SeasonDataError(source, `seasons[${i}] is not an object`)
    need(source, s, 'seasonStart', 'number', `seasons[${i}]`)
    need(source, s, 'label', 'string', `seasons[${i}]`)
    need(source, s, 'slug', 'string', `seasons[${i}]`)
    for (const k of ['slug', 'label'] as const)
      if (!SEASON_LABEL_FORM.test(s[k] as string))
        throw new SeasonDataError(source, `seasons[${i}].${k} is ${JSON.stringify(s[k])}, expected a season like "2016-17" or "2007"`)
    if (s.sport !== sport) throw new SeasonDataError(source, `seasons[${i}].sport is ${JSON.stringify(s.sport)}, expected ${sport}`)
  })
  return raw as unknown as SeasonList
}

/** Checks every field the static pages read. Extra fields are ignored; a missing or mistyped one throws. */
export function validateSeasonPage(source: string, raw: unknown, sport: HistoricalSport, slug: string): SeasonPage {
  if (!isObj(raw)) throw new SeasonDataError(source, 'the response is not a JSON object')
  const o = raw
  for (const k of ['sport', 'label', 'slug', 'scoringNote', 'pointsNote', 'rankingLabel'] as const) need(source, o, k, 'string', 'page')
  for (const k of ['seasonStart', 'teams', 'rounds', 'boardPlayers', 'rankedPlayers', 'unrankedLeftOut', 'dreamRosterPoints'] as const)
    need(source, o, k, 'number', 'page')
  for (const k of ['rosterPositions', 'dataNotes', 'topOverall', 'busts', 'steals', 'dreamRoster'] as const) need(source, o, k, 'array', 'page')
  for (const k of ['excludedStats', 'statCoverage', 'topByPosition'] as const) need(source, o, k, 'object', 'page')
  for (const k of ['prevSlug', 'nextSlug', 'attribution'] as const) nullableString(source, o, k, 'page')
  if (o.sport !== sport) throw new SeasonDataError(source, `page.sport is ${JSON.stringify(o.sport)}, expected ${sport}`)
  for (const k of ['slug', 'label'] as const)
    if (!SEASON_LABEL_FORM.test(o[k] as string))
      throw new SeasonDataError(source, `page.${k} is ${JSON.stringify(o[k])}, expected a season like "2016-17" or "2007"`)
  if (o.slug !== slug) throw new SeasonDataError(source, `page.slug is ${JSON.stringify(o.slug)}, expected ${slug}`)
  if ((o.pointsNote as string).trim() === '') throw new SeasonDataError(source, 'page.pointsNote is empty (the page must say what points are)')
  const top = o.topOverall as unknown[]
  if (top.length === 0) throw new SeasonDataError(source, 'page.topOverall is empty')
  top.forEach((p, i) => checkPlayer(source, p, `topOverall[${i}]`, { rank: ['finishRank', 'weeksPlayed'], nullableGoingIn: true }))
  for (const k of ['busts', 'steals'] as const)
    (o[k] as unknown[]).forEach((p, i) => checkPlayer(source, p, `${k}[${i}]`, { rank: ['goingInRank', 'finishRank', 'weeksPlayed'] }))
  ;(o.dreamRoster as unknown[]).forEach((p, i) => checkPlayer(source, p, `dreamRoster[${i}]`, { rank: [] }))
  for (const [pos, rows] of Object.entries(o.topByPosition as Obj)) {
    if (!Array.isArray(rows)) throw new SeasonDataError(source, `page.topByPosition.${pos} is not an array`)
    rows.forEach((p, i) => checkPlayer(source, p, `topByPosition.${pos}[${i}]`, { rank: ['finishRank', 'weeksPlayed'], nullableGoingIn: true }))
  }
  return raw as unknown as SeasonPage
}

function validateAll(source: string, raw: unknown, allowEmpty: boolean): RedraftSeasonData {
  if (!isObj(raw)) throw new SeasonDataError(source, 'the file is not a JSON object')
  const out = {} as RedraftSeasonData
  for (const s of SPORTS) {
    const part = raw[s]
    if (!isObj(part)) throw new SeasonDataError(source, `${s} is missing`)
    const list = validateSeasonList(`${source}#${s}.list`, part.list, s, allowEmpty)
    if (!Array.isArray(part.pages)) throw new SeasonDataError(source, `${s}.pages is missing or not an array`)
    const pages = list.seasons.map((ref) => {
      const hit = (part.pages as unknown[]).find((p) => isObj(p) && p.slug === ref.slug)
      if (!hit) throw new SeasonDataError(source, `${s} lists ${ref.slug} but has no page for it`)
      return validateSeasonPage(`${source}#${s}/${ref.slug}`, hit, s, ref.slug)
    })
    out[s] = { list, pages }
  }
  return out
}

/** With the opt-in, one empty sport is a loud warning; both empty is still a failure (there would be nothing to publish). */
function checkEmpties(source: string, data: RedraftSeasonData, allowEmpty: boolean, log: (l: string) => void): void {
  const empty = SPORTS.filter((s) => data[s].list.seasons.length === 0)
  if (empty.length === 0) return
  if (!allowEmpty || empty.length === SPORTS.length)
    throw new SeasonDataError(source, `no seasons for ${empty.join(' and ')}, so there is nothing to publish for ${empty.length === SPORTS.length ? 'either sport' : empty.join(' and ')}`)
  const bar = '!'.repeat(78)
  log(`${bar}\n[redraft] WARNING: ${ALLOW_EMPTY_ENV}=1 and ${empty.join(' and ')} has NO seasons.\n[redraft] This build publishes NO ${empty.join(' and ')} pages: no index, no sitemap entries, no tile, no footer link.\n${bar}`)
}

export type LoadOptions = {
  /** VITE_API_BASE: the backend's origin, no trailing slash needed. */
  apiBase: string
  /** REDRAFT_SEASON_DATA: a JSON file shaped like RedraftSeasonData. When set, no request is made. */
  fixturePath?: string
  log?: (line: string) => void
  /** REDRAFT_ALLOW_EMPTY_SPORT=1: a sport with no seasons builds with no pages for it, and a loud warning (CR-08). */
  allowEmptySport?: boolean
  /** Injected by tests. */
  fetchImpl?: typeof fetch
  retryBudgetMs?: number
  retryDelayMs?: number
  sleep?: (ms: number) => Promise<void>
}

async function getJson(url: string, o: Required<Pick<LoadOptions, 'fetchImpl' | 'retryBudgetMs' | 'retryDelayMs' | 'sleep'>>, log: (l: string) => void): Promise<unknown> {
  const started = Date.now()
  let lastReason = ''
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await o.fetchImpl(url, { headers: { Accept: 'application/json' } })
      if (res.ok) return await res.json()
      lastReason = `HTTP ${res.status}`
    } catch (e) {
      lastReason = e instanceof Error ? e.message : String(e)
    }
    if (Date.now() - started + o.retryDelayMs > o.retryBudgetMs) throw new SeasonDataError(url, `${lastReason} (gave up after ${attempt} ${attempt === 1 ? 'try' : 'tries'} over ${Math.round((Date.now() - started) / 1000)}s)`)
    log(`[redraft] ${url}: ${lastReason}; retrying in ${Math.round(o.retryDelayMs / 1000)}s`)
    await o.sleep(o.retryDelayMs)
  }
}

/**
 * The data every static page is made from. Sequential on purpose: each season page is computed on first
 * request by the backend and cached, and a parallel fan-out of cold computations is what PR-19 warned about.
 */
export async function loadSeasonData(opts: LoadOptions): Promise<RedraftSeasonData> {
  const log = opts.log ?? (() => {})
  const allowEmpty = opts.allowEmptySport === true
  if (opts.fixturePath) {
    log(`[redraft] season data: fixture ${opts.fixturePath} (no backend request)`)
    let raw: unknown
    try {
      raw = JSON.parse(readFileSync(opts.fixturePath, 'utf8'))
    } catch (e) {
      throw new SeasonDataError(opts.fixturePath, `cannot read the fixture: ${e instanceof Error ? e.message : String(e)}`)
    }
    const fromFile = validateAll(opts.fixturePath, raw, allowEmpty)
    checkEmpties(opts.fixturePath, fromFile, allowEmpty, log)
    return fromFile
  }
  const base = opts.apiBase.trim().replace(/\/+$/, '')
  if (base === '')
    throw new SeasonDataError('VITE_API_BASE', 'is not set, and REDRAFT_SEASON_DATA is not given: the Redraft site build needs one of them to make its season pages')
  log(`[redraft] season data: backend ${base}`)
  const io = {
    fetchImpl: opts.fetchImpl ?? fetch,
    retryBudgetMs: opts.retryBudgetMs ?? RETRY_BUDGET_MS,
    retryDelayMs: opts.retryDelayMs ?? RETRY_DELAY_MS,
    sleep: opts.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms))),
  }
  const out = {} as RedraftSeasonData
  for (const s of SPORTS) {
    const listUrl = `${base}/api/history/public/seasons?sport=${s}`
    const list = validateSeasonList(listUrl, await getJson(listUrl, io, log), s, allowEmpty)
    const pages: SeasonPage[] = []
    for (const ref of list.seasons) {
      const url = `${base}/api/history/public/seasons/${s}/${encodeURIComponent(ref.slug)}`
      pages.push(validateSeasonPage(url, await getJson(url, io, log), s, ref.slug))
    }
    log(`[redraft] ${s}: ${pages.length} seasons`)
    out[s] = { list, pages }
  }
  checkEmpties(base, out, allowEmpty, log)
  return out
}
