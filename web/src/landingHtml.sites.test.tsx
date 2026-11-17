import { describe, expect, it, vi } from 'vitest'
import source from '../index.html?raw'
import { staticBody, transform } from './landingHtml'
import { makeConfig } from './site'
import { LEAGUE_WORDS } from './leagueWords'
import * as redraft from './redraftCopy'

vi.mock('./api', () => ({ getSleeperUser: vi.fn() }))
vi.mock('./user', () => ({ setUser: vi.fn() }))

// Spec 073: what each build mode delivers in index.html, read as text (no scripts run).
const TM = 'https://www.ballknowers.co'
const LG = 'https://leagues.ballknowers.co'
const norm = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim()
const headOf = (html: string) => html.slice(0, html.indexOf('</head>'))
const bodyText = (html: string) =>
  norm(new DOMParser().parseFromString(html, 'text/html').getElementById('root')?.textContent)

describe('the Redraft build (spec 074: index.html is the home page)', () => {
  // PINNED RULE REWRITTEN (T041): spec 073 pinned "the Time Machine build's index.html is a sign-in front door, and a
  // signed-out visitor gets it at every URL". Spec 074 delivers the real home page (daily cards, season tiles, a
  // sign-in panel at the end), written from the page list like every other page of the site.
  const out = transform(source, makeConfig({ VITE_SITE: 'timemachine' }))
  const head = headOf(out)

  it('writes the home title and description, the main address, and its own share image', () => {
    expect(head).toContain(`<title>${redraft.homeTitle}</title>`)
    expect(head).toContain(`name="description" content="${redraft.homeDescription}"`)
    expect(head).toContain(`<link rel="canonical" href="${TM}/" />`)
    expect(head).toContain(`property="og:url" content="${TM}/"`)
    expect(head).toContain(`property="og:image" content="${TM}/og-image-tm.png"`)
    expect(head).toContain(`name="twitter:image" content="${TM}/og-image-tm.png"`)
    expect(head).toContain('property="og:image:width" content="1200"')
    expect(head).toContain('<script src="/boot.js"></script>')
  })

  it('says nothing about leagues, mock drafts or managers, in the tags or the body', () => {
    const tags = head.slice(head.indexOf('<title>'))
    expect(tags).not.toMatch(LEAGUE_WORDS)
    expect(bodyText(out)).not.toMatch(LEAGUE_WORDS)
    expect(out).not.toContain(LG)
    expect(out).not.toContain('og-image.png')
  })

  it('JSON-LD parses, names the main address and carries no rating or review', () => {
    const m = out.match(/<script type="application\/ld\+json">(.*?)<\/script>/s)!
    const data = JSON.parse(m[1])
    expect(data.url).toBe(`${TM}/`)
    expect(data.name).toBe('Ball Knowers Redraft')
    expect(m[1]).not.toMatch(/aggregateRating|"review"/)
  })

  it('the static body is the home page: bar, the home content, a disabled sign-in form, no inline style', () => {
    const doc = new DOMParser().parseFromString(out, 'text/html')
    expect(out.slice(out.indexOf('<div id="root">'))).not.toContain('style=')
    expect(doc.querySelector('#root > .app-shell > header.topbar')).not.toBeNull()
    expect(doc.querySelector('#root .content.tm.tm-page h1')?.textContent).toBe(redraft.homeH1)
    expect(doc.querySelector('#sign-in input')?.hasAttribute('disabled')).toBe(true)
  })

  it('carries its analytics disclosure (spec 052 FR-012) and the random-id line (PR-27)', () => {
    expect(bodyText(out)).toContain(redraft.analyticsLine)
    expect(redraft.analyticsLine).toContain('We keep a random id in this browser so your games stay yours.')
  })
})

describe('the league build (locked, 2026-11-16)', () => {
  const out = transform(source, makeConfig({ VITE_SITE: 'leagues' }))
  const head = headOf(out)

  it('is titled just Ball Knowers and asks crawlers not to index or follow', () => {
    expect(head).toContain('<title>Ball Knowers</title>')
    expect(head).toContain('<meta name="robots" content="noindex, nofollow" />')
    expect(head).toContain('<script src="/boot.js"></script>')
  })

  it('carries no marketing tags: no description, canonical, og, twitter or JSON-LD', () => {
    expect(out).not.toMatch(/name="description"|rel="canonical"|property="og:|name="twitter:|ld\+json|og-image/)
    expect(out).not.toContain(LG)
    expect(out).not.toContain(TM)
    expect(head).not.toContain('Sleeper')
  })

  it('has an empty root: no pre-rendered landing for a crawler to read', () => {
    expect(out).toContain('<div id="root"></div>')
    expect(out).not.toContain(staticBody())
    expect(out).not.toContain('landing-prerender')
    expect(bodyText(out)).toBe('')
    expect(out).not.toMatch(/Fantasy Football|Sleeper|time machine/i)
  })
})

describe('the combined build', () => {
  it('names the main address and the league wording', () => {
    const out = transform(source, makeConfig({}))
    expect(headOf(out)).toContain(`<link rel="canonical" href="${TM}/" />`)
    expect(headOf(out)).toContain(`property="og:image" content="${TM}/og-image.png"`)
    expect(out).toContain(staticBody())
    expect(headOf(out)).not.toContain('noindex')
  })
})
