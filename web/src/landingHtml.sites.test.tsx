import { render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import source from '../index.html?raw'
import TimeMachineSignIn from './pages/TimeMachineSignIn'
import { staticBody, staticTmBody, transform } from './landingHtml'
import { makeConfig } from './site'
import { LEAGUE_WORDS } from './leagueWords'
import * as tm from './tmLandingCopy'

vi.mock('./api', () => ({ getSleeperUser: vi.fn() }))
vi.mock('./user', () => ({ setUser: vi.fn() }))

// Spec 073: what each build mode delivers in index.html, read as text (no scripts run).
const TM = 'https://www.ballknowers.co'
const LG = 'https://leagues.ballknowers.co'
const norm = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim()
const headOf = (html: string) => html.slice(0, html.indexOf('</head>'))
const bodyText = (html: string) =>
  norm(new DOMParser().parseFromString(html, 'text/html').getElementById('root')?.textContent)

describe('the Time Machine build', () => {
  const out = transform(source, makeConfig({ VITE_SITE: 'timemachine' }))
  const head = headOf(out)

  it('writes Time Machine wording and the main address, with its own share image', () => {
    expect(head).toContain(`<title>${tm.title}</title>`)
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
    expect(data.name).toBe('Ball Knowers Time Machine')
    expect(m[1]).not.toMatch(/aggregateRating|"review"/)
  })

  it('the static body has no inline style, a disabled form and the .app > .landing-prerender wrapper', () => {
    expect(staticTmBody()).not.toContain('style=')
    const doc = new DOMParser().parseFromString(staticTmBody(), 'text/html')
    expect(doc.querySelector('.app > .landing-prerender > .content.tm')).not.toBeNull()
    expect(doc.querySelector('input')?.hasAttribute('disabled')).toBe(true)
  })

  it('the static body says the same thing as the React front door (landing parity)', () => {
    const staticDoc = new DOMParser().parseFromString(staticTmBody(), 'text/html')
    const { container } = render(<TimeMachineSignIn />)
    const texts = (root: ParentNode, sel: string) => [...root.querySelectorAll(sel)].map((el) => norm(el.textContent))
    for (const sel of ['h1', '.tm-over', '.tm-tagline', 'h2', '.tm-steps li', '.landing-sport', '.signin-note', '.landing-footer']) {
      const fromStatic = texts(staticDoc, sel)
      expect(fromStatic.length, sel).toBeGreaterThan(0)
      expect(fromStatic, sel).toEqual(texts(container, sel))
    }
  })

  it('carries its own analytics disclosure in both copies (spec 052 FR-012)', () => {
    expect(bodyText(out)).toContain(tm.analyticsLine)
    expect(norm(render(<TimeMachineSignIn />).container.textContent)).toContain(tm.analyticsLine)
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
