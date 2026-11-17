import { describe, expect, it } from 'vitest'
import type { RedraftSeasonData } from './api'
import fixtureJson from './__fixtures__/redraft-seasons.json'
import * as historicalCopy from './historicalCopy'
import * as redraftCopy from './redraftCopy'
import { pageParts } from './landingHtml'
import { appShellPage, indexablePages, notFoundPage, type RedraftPage } from './redraftPages'
import { siteCopy } from './siteCopy'
import { makeConfig } from './site'
import * as tmLandingCopy from './tmLandingCopy'

// Spec 074 T031 (FR-001..FR-004, FR-006a): the public game is called Redraft. The old name must not be readable
// anywhere on the Redraft site, and every indexable page names the game together with a year or "past".
//
// What this reads: every string a copy module exports (functions are called by redraftCopy.test.ts with sample
// arguments; here they are covered by the pages built from them), siteCopy's three Redraft-site strings, the
// historical how-to-play steps, and the head + body + embedded data of EVERY page the build writes from the fixture,
// the home page and the two private pages included.
//
// Not covered by this test, on purpose: code comments and internal names (file names, `.tm-*` classes, `tmHome`,
// the VITE_SITE value "timemachine"), which keep the old name by decision.

const OLD_NAME = /time[\s-]*machine/i
const data = fixtureJson as unknown as RedraftSeasonData
const cfg = makeConfig({ VITE_SITE: 'timemachine' })
const indexable = indexablePages(cfg, data)
const everyPage: RedraftPage[] = [...indexable, notFoundPage(cfg), appShellPage(cfg)]

function exportedStrings(mod: Record<string, unknown>): string[] {
  const out: string[] = []
  for (const v of Object.values(mod)) {
    if (typeof v === 'string') out.push(v)
    else if (Array.isArray(v)) {
      for (const x of v) {
        if (typeof x === 'string') out.push(x)
        else if (x && typeof x === 'object') out.push(...Object.values(x as Record<string, unknown>).filter((y): y is string => typeof y === 'string'))
      }
    } else if (v && typeof v === 'object') out.push(...Object.values(v as Record<string, unknown>).filter((y): y is string => typeof y === 'string'))
  }
  return out
}

const pageHtml = (p: RedraftPage) => {
  const parts = pageParts(p, cfg, data)
  return `${parts.head}${parts.body}${parts.after}`
}
const doc = (html: string) => new DOMParser().parseFromString(html, 'text/html')

describe('the old name', () => {
  it('reads the copy modules non-trivially (the guard is not reading nothing)', () => {
    expect(exportedStrings(redraftCopy as Record<string, unknown>).length).toBeGreaterThan(80)
    expect(exportedStrings(tmLandingCopy as Record<string, unknown>).length).toBeGreaterThan(8)
    expect(indexable.length).toBeGreaterThan(5)
  })

  it('is in no string a copy module exports', () => {
    const strings = [
      ...exportedStrings(redraftCopy as Record<string, unknown>),
      ...exportedStrings(tmLandingCopy as Record<string, unknown>),
      ...tmLandingCopy.allCopyStrings(),
      ...historicalCopy.howToPlaySteps(historicalCopy.NEUTRAL_SCORING).flatMap((s) => [s.title, s.body]),
      historicalCopy.NEUTRAL_SCORING,
      ...Object.values(siteCopy(cfg)).filter((s): s is string => typeof s === 'string'),
    ]
    expect(strings.filter((s) => OLD_NAME.test(s))).toEqual([])
  })

  it('is in the static HTML of every page the build writes, the home page and the private pages included', () => {
    for (const p of everyPage) expect(pageHtml(p), p.path).not.toMatch(OLD_NAME)
  })

  it('is not on the site-level JSON-LD or the share image text either', () => {
    for (const p of indexable) for (const block of p.jsonLd) expect(JSON.stringify(block), p.path).not.toMatch(OLD_NAME)
    expect(redraftCopy.imageAlt).not.toMatch(OLD_NAME)
  })
})

describe('every indexable page names the game and a year or "past"', () => {
  const YEAR_OR_PAST = /\b(19|20)\d{2}\b|past/i

  it('has Redraft and a year or "past" in its title, description and H1', () => {
    for (const p of indexable) {
      for (const [field, value] of [['title', p.title], ['description', p.description], ['h1', p.h1]] as const) {
        expect(value, `${p.path} ${field}`).toMatch(/redraft/i)
        expect(value, `${p.path} ${field}`).toMatch(YEAR_OR_PAST)
      }
    }
  })

  it('shows the same H1 in the static body as the page list says', () => {
    for (const p of indexable) {
      const h1 = doc(pageHtml(p)).querySelector('h1')
      expect(h1?.textContent, p.path).toBe(p.h1)
    }
  })

  // The breadcrumb items are short navigation names ("Home", "NBA seasons") and the site-level WebApplication
  // name is the brand "Ball Knowers Redraft" with no year: both are exempt by design. The noindex 404 page and
  // the app shell are exempt by name. The word is matched case-insensitively: the daily description uses it as a verb ("to redraft").
  it('exempts only the breadcrumb names, the brand name, and the two noindex pages', () => {
    expect(redraftCopy.brand).toBe('Ball Knowers Redraft')
    expect(everyPage.filter((p) => p.noindex).map((p) => p.kind).sort()).toEqual(['appShell', 'notFound'])
    for (const p of indexable) {
      const page = p.jsonLd.find((b) => (b as { '@type'?: string })['@type'] === 'WebPage') as { name: string } | undefined
      if (page) expect(page.name, p.path).toMatch(YEAR_OR_PAST)
    }
  })
})

describe('"hindsight" on a season page', () => {
  it('appears in no string a season page carries (FR-006a)', () => {
    const seasons = indexable.filter((p) => p.kind === 'season')
    expect(seasons.length).toBeGreaterThan(0)
    for (const p of seasons) expect(pageHtml(p), p.path).not.toMatch(/hindsight/i)
  })
})
