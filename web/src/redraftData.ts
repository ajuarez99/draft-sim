import { useCallback, useEffect, useState } from 'react'
import {
  getPublicSeasonList,
  getPublicSeasonPage,
  type HistoricalSport,
  type SeasonList,
  type SeasonPage,
} from './api'
import { isNotFound } from './apiError'

// Spec 074 T035/T036: where a season page or a season list comes from in the browser. The static file a visitor landed
// on embeds its own data as `<script type="application/json" id="redraft-data">` (the same shape the public endpoint
// answers with), so the first paint needs no request. A client-side navigation to another season has no embedded copy
// for it (the one in the document belongs to the file that was loaded), so it asks the public endpoint, once.

export const EMBEDDED_ID = 'redraft-data'

function readEmbedded(): unknown | null {
  if (typeof document === 'undefined') return null
  const el = document.getElementById(EMBEDDED_ID)
  if (!el?.textContent) return null
  try {
    return JSON.parse(el.textContent)
  } catch {
    return null
  }
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** The embedded season page, only if it is THIS season's: after a client-side navigation the document's copy belongs to another page. */
export function embeddedSeasonPage(sport: HistoricalSport, slug: string): SeasonPage | null {
  const d = readEmbedded()
  return isObj(d) && d.sport === sport && d.slug === slug && Array.isArray(d.topOverall) ? (d as unknown as SeasonPage) : null
}

export function embeddedSeasonList(sport: HistoricalSport): SeasonList | null {
  const d = readEmbedded()
  return isObj(d) && d.sport === sport && Array.isArray(d.seasons) ? (d as unknown as SeasonList) : null
}

/** The home page embeds both lists (its sport tiles read their ranges and counts from them). */
export function embeddedHomeLists(): Record<HistoricalSport, SeasonList> | null {
  const d = readEmbedded()
  if (!isObj(d) || !isObj(d.NBA) || !isObj(d.NFL)) return null
  return Array.isArray(d.NBA.seasons) && Array.isArray(d.NFL.seasons) ? (d as unknown as Record<HistoricalSport, SeasonList>) : null
}

/**
 * The sports this build has pages for. The build writes `<meta name="redraft-sports" content="NBA">` only when a sport is
 * missing (REDRAFT_ALLOW_EMPTY_SPORT, CR-08); absent means both. The footer, the 404 and the shared result read it so they
 * never link to a sport index the build did not make.
 */
export function offeredSports(): HistoricalSport[] {
  const all: HistoricalSport[] = ['NBA', 'NFL']
  if (typeof document === 'undefined') return all
  const content = document.querySelector('meta[name="redraft-sports"]')?.getAttribute('content')
  if (!content) return all
  const named = content.split(',').map((x) => x.trim())
  const some = all.filter((s) => named.includes(s))
  return some.length > 0 ? some : all
}

const pageCache = new Map<string, SeasonPage>()
const listCache = new Map<HistoricalSport, SeasonList>()

/** Test-only. */
export function __resetRedraftDataForTests() {
  pageCache.clear()
  listCache.clear()
}

export type DataStatus = 'loading' | 'ready' | 'error' | 'missing'

export function useSeasonPage(sport: HistoricalSport, slug: string) {
  const key = `${sport}/${slug}`
  const initial = pageCache.get(key) ?? embeddedSeasonPage(sport, slug)
  const [page, setPage] = useState<SeasonPage | null>(initial)
  const [status, setStatus] = useState<DataStatus>(initial ? 'ready' : 'loading')
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (page) {
      pageCache.set(key, page)
      return
    }
    let current = true
    setStatus('loading')
    getPublicSeasonPage(sport, slug)
      .then((p) => {
        if (!current) return
        pageCache.set(key, p)
        setPage(p)
        setStatus('ready')
      })
      .catch((e) => {
        if (current) setStatus(isNotFound(e) ? 'missing' : 'error')
      })
    return () => {
      current = false
    }
    // `page` is the loaded state this effect is deciding about; `attempt` is the retry button.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, attempt])

  const retry = useCallback(() => setAttempt((n) => n + 1), [])
  return { page, status, retry }
}

export function useSeasonList(sport: HistoricalSport) {
  const initial = listCache.get(sport) ?? embeddedSeasonList(sport)
  const [list, setList] = useState<SeasonList | null>(initial)
  const [status, setStatus] = useState<DataStatus>(initial ? 'ready' : 'loading')
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (list) {
      listCache.set(sport, list)
      return
    }
    let current = true
    setStatus('loading')
    getPublicSeasonList(sport)
      .then((l) => {
        if (!current) return
        listCache.set(sport, l)
        setList(l)
        setStatus('ready')
      })
      .catch(() => current && setStatus('error'))
    return () => {
      current = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sport, attempt])

  const retry = useCallback(() => setAttempt((n) => n + 1), [])
  return { list, status, retry }
}

/**
 * Both sports' lists for the home page's tiles: the embedded pair first, else one request per sport. `status` is
 * `error` when a request failed (CR-09), so the page can say so and offer `retry` instead of quietly dropping the
 * tiles and the random-year button; `loading` while a request is out.
 */
export function useHomeLists() {
  const embedded = embeddedHomeLists()
  const [lists, setLists] = useState<Partial<Record<HistoricalSport, SeasonList>>>(() => ({
    ...(embedded ?? {}),
    ...Object.fromEntries(listCache),
  }))
  const missing = () => (['NBA', 'NFL'] as const).filter((s) => !lists[s])
  const [status, setStatus] = useState<DataStatus>(missing().length > 0 ? 'loading' : 'ready')
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let current = true
    const need = missing()
    for (const s of ['NBA', 'NFL'] as const) if (lists[s]) listCache.set(s, lists[s]!)
    if (need.length === 0) {
      setStatus('ready')
      return
    }
    setStatus('loading')
    Promise.all(
      need.map((s) =>
        getPublicSeasonList(s).then((l) => {
          if (!current) return
          listCache.set(s, l)
          setLists((prev) => ({ ...prev, [s]: l }))
        }),
      ),
    )
      .then(() => current && setStatus('ready'))
      .catch(() => current && setStatus('error'))
    return () => {
      current = false
    }
    // Once on mount, and again only when Try again is pressed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt])
  const retry = useCallback(() => setAttempt((n) => n + 1), [])
  return { lists, status, retry }
}
