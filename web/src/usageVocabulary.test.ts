import { afterEach, describe, expect, it, vi } from 'vitest'
import { EVENT_VOCABULARY, routeTemplate, routeVocabulary, routesFor } from './analytics'
import type { Site } from './site'

/*
 * Spec 076 (F2, F8). The server accepts only the strings in backend/src/main/resources/usage-vocabulary.json, which is
 * generated from analytics.ts's own exports. This test is the only thing that keeps the two copies in step, so it
 * FAILS (never skips) when the file is missing or differs.
 *
 * To regenerate after a route or event change, from web/:  USAGE_VOCAB_WRITE=1 npx vitest run src/usageVocabulary.test.ts
 * (the file is sorted and plain, so the diff shows exactly what changed). vitest runs with cwd = web/, and node:fs is
 * loaded dynamically and untyped because the app has no @types/node.
 */
const FILE = '../backend/src/main/resources/usage-vocabulary.json'
const SITES: Site[] = ['combined', 'leagues', 'timemachine']

type Fs = {
  existsSync: (p: string) => boolean
  readFileSync: (p: string, enc: string) => string
  writeFileSync: (p: string, data: string, enc: string) => void
}
async function fs(): Promise<Fs> {
  // @ts-expect-error node:fs has no types here
  return (await import('node:fs')) as Fs
}

// No @types/node in this app, so the environment is read untyped.
function env(): Record<string, string | undefined> | undefined {
  return (globalThis as unknown as { process?: { env: Record<string, string | undefined> } }).process?.env
}

function sortedEvents(): Record<string, Record<string, string[]>> {
  const out: Record<string, Record<string, string[]>> = {}
  for (const name of Object.keys(EVENT_VOCABULARY).sort()) {
    const props: Record<string, string[]> = {}
    const declared = EVENT_VOCABULARY[name as keyof typeof EVENT_VOCABULARY]
    for (const key of Object.keys(declared).sort()) props[key] = [...declared[key]].sort()
    out[name] = props
  }
  return out
}

function expected() {
  const sites: Record<string, string[]> = {}
  for (const site of SITES) sites[site] = routeVocabulary(site)
  return { sites, events: sortedEvents() }
}

describe('usage vocabulary', () => {
  afterEach(() => vi.unstubAllEnvs())

  it('the committed JSON is present and equals the analytics.ts exports', async () => {
    const f = await fs()
    const want = expected()
    if (env()?.USAGE_VOCAB_WRITE === '1') f.writeFileSync(FILE, JSON.stringify(want, null, 2) + '\n', 'utf8')
    expect(f.existsSync(FILE), `${FILE} is missing: run with USAGE_VOCAB_WRITE=1 to generate it`).toBe(true)
    const got = JSON.parse(f.readFileSync(FILE, 'utf8'))
    expect(got).toEqual(want)
    // Plain and deterministic: the file is exactly what the generator writes.
    expect(f.readFileSync(FILE, 'utf8')).toBe(JSON.stringify(want, null, 2) + '\n')
  })

  it('every site has routes, and the landing forms exist only where signed out means a landing page', () => {
    for (const site of SITES) expect(routeVocabulary(site).length).toBeGreaterThan(3)
    expect(routeVocabulary('timemachine').some((r) => r.startsWith('/(landing)'))).toBe(false)
    expect(routeVocabulary('leagues')).toEqual(expect.arrayContaining(['/(landing)', '/(landing)/leagues/:league/power', '/(landing)/(not-found)']))
    expect(routeVocabulary('combined')).toEqual(expect.arrayContaining(['/(landing)/history/:game', '/(not-found)']))
    expect(routeVocabulary('leagues')).toContain('/admin/usage')
  })

  it.each(SITES)('%s: every table entry, signed in and out, returns a string in the vocabulary', (site) => {
    vi.stubEnv('VITE_SITE', site === 'combined' ? '' : site)
    const vocab = new Set(routeVocabulary(site))
    const seen = new Set<string>()
    for (const entry of routesFor(site)) {
      // A concrete pathname for the template: each :param becomes a plain value, '*' (the catch-all) a path no table has.
      const pathname = entry.path === '*' ? '/zzz-no-such-page/123456789' : entry.path.replace(/:[A-Za-z]+/g, 'x1')
      for (const signedIn of [true, false]) {
        const { url } = routeTemplate(pathname, signedIn)
        expect(vocab.has(url), `${site} ${pathname} signedIn=${signedIn} -> ${url}`).toBe(true)
        seen.add(url)
      }
    }
    // The other direction: nothing in the vocabulary is unreachable.
    for (const v of vocab) expect(seen.has(v), `${site}: ${v} is never returned`).toBe(true)
  })
})
