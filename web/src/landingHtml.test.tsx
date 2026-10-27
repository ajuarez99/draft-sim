import { render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import source from '../index.html?raw'
import SignIn from './pages/SignIn'
import { groups } from './landingCopy'
import { staticBody, transform } from './landingHtml'

vi.mock('./api', () => ({ getSleeperUser: vi.fn() }))
vi.mock('./user', () => ({ setUser: vi.fn() }))

// Spec 050. The delivered HTML is what a bot reads, so it is tested as a string, from
// the real index.html source (the built dist/ is checked live, not here -- plan A8).
const out = transform(source)
const head = out.slice(0, out.indexOf('</head>'))

const norm = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim()

describe('landingHtml transform', () => {
  it('writes one title, the large card, an absolute 1200x630 image, the canonical, JSON-LD and boot.js', () => {
    expect(out.match(/<title>/g)).toHaveLength(1)
    expect(head).toContain('name="twitter:card" content="summary_large_image"')
    expect(head).toContain('property="og:image" content="https://www.ballknowers.co/og-image.png"')
    expect(head).toContain('property="og:image:width" content="1200"')
    expect(head).toContain('property="og:image:height" content="630"')
    expect(head).toContain('<link rel="canonical" href="https://www.ballknowers.co/" />')
    expect(head).toContain('application/ld+json')
    expect(head).toContain('<script src="/boot.js"></script>')
  })

  it('leaves no trace of the old framing in the head', () => {
    const lower = head.toLowerCase()
    expect(lower).not.toContain('monte carlo')
    expect(lower).not.toContain('simulat')
  })

  it('throws instead of silently shipping an unfilled template', () => {
    expect(() => transform('<html><head></head><body></body></html>')).toThrow()
    expect(() => transform('<html><body><div id="root"></div></body></html>')).toThrow()
  })

  it('JSON-LD parses and carries no rating or review (FR-017)', () => {
    const m = out.match(/<script type="application\/ld\+json">(.*?)<\/script>/s)
    expect(m).not.toBeNull()
    const data = JSON.parse(m![1])
    expect(data['@type']).toBe('WebApplication')
    expect(data).not.toHaveProperty('aggregateRating')
    expect(data).not.toHaveProperty('review')
    expect(m![1]).not.toMatch(/aggregateRating|"review"/)
  })

  it('the static markup carries no inline style attribute (CSP)', () => {
    expect(staticBody()).not.toContain('style=')
  })
})

describe('static body vs the React landing (FR-018)', () => {
  it('says the same thing: h1, subheadline, every h2, every feature name and line, in order', () => {
    const staticDoc = new DOMParser().parseFromString(staticBody(), 'text/html')
    const { container } = render(<SignIn />)
    const texts = (root: ParentNode, sel: string) =>
      [...root.querySelectorAll(sel)].map((el) => norm(el.textContent))

    for (const sel of ['h1', '.landing-sub', 'h2', 'h3', '.landing-tile-line', '.landing-tag', '.landing-sport', '.landing-platforms', '.signin-note', '.landing-footer']) {
      const fromStatic = texts(staticDoc, sel)
      expect(fromStatic.length, sel).toBeGreaterThan(0)
      expect(fromStatic, sel).toEqual(texts(container, sel))
    }
    expect(texts(staticDoc, 'h3')).toHaveLength(groups.flatMap((g) => g.features).length)
  })

  it('is wrapped in .app > .landing-prerender and its form is disabled', () => {
    const doc = new DOMParser().parseFromString(staticBody(), 'text/html')
    expect(doc.querySelector('.app > .landing-prerender > .content.landing')).not.toBeNull()
    expect(doc.querySelector('input')?.hasAttribute('disabled')).toBe(true)
    expect(doc.querySelector('button')?.hasAttribute('disabled')).toBe(true)
  })
})
