import { describe, expect, it } from 'vitest'
import { pageHeadTags } from '../src/landingHtml'
import { appShellPage } from '../src/redraftPages'
import { makeConfig } from '../src/site'
import { escapeHtml, gameIdFromPath, injectPreview, validatePreview } from './preview.mjs'

// Spec 079 T010/T014: the pure half of the share-preview server, tested against the head the real build writes.
const cfg = makeConfig({ VITE_SITE: 'timemachine' })
const shell = `<!doctype html>\n<html lang="en">\n  <head>\n    <meta charset="utf-8" />\n${pageHeadTags(appShellPage(cfg), cfg)}\n  </head>\n  <body><div id="root"></div></body>\n</html>\n`
const words = { title: 'Redraft daily · Nov 21 · 🏆 Champion', description: "Draft score 87% · 2003-04 NBA · Play today's Redraft.", imageAlt: 'Champion, 13-1.' }
const urls = { gameUrl: 'https://www.ballknowers.co/game/42', imageUrl: 'https://api.ballknowers.co/api/history/public/games/42/preview.png?v=1' }
const content = (html, attr, key) => new RegExp(`<meta ${attr}="${key}" content="([^"]*)"`).exec(html)?.[1]
const count = (html, attr, key) => (html.match(new RegExp(`<meta ${attr}="${key}"`, 'g')) ?? []).length

describe('gameIdFromPath', () => {
  it('takes /game/<1-18 digits> and nothing else', () => {
    expect(gameIdFromPath('/game/42')).toBe('42')
    expect(gameIdFromPath('/game/123456789012345678')).toBe('123456789012345678')
    for (const p of ['/game/1234567890123456789', '/game/', '/game', '/game/abc', '/game/4a', '/game/42/', '/game/42/x', '/games/42', '/x/game/42', '/game/-1', '/game/4 2', '/game/0608', '/game/0', '/']) {
      expect(gameIdFromPath(p), p).toBeNull()
    }
  })
})

describe('escapeHtml', () => {
  it('escapes & < > " and the apostrophe', () => {
    expect(escapeHtml(`<script>alert("x")</script> & 'y'`)).toBe('&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; &#39;y&#39;')
  })
})

describe('validatePreview', () => {
  it('keeps the three strings and drops the card', () => {
    expect(validatePreview({ ...words, card: { champion: true } })).toEqual(words)
  })
  it('refuses a wrong shape, an empty string or one over 300 characters', () => {
    expect(validatePreview(null)).toBeNull()
    expect(validatePreview('x')).toBeNull()
    expect(validatePreview({ ...words, title: 5 })).toBeNull()
    expect(validatePreview({ ...words, description: '  ' })).toBeNull()
    expect(validatePreview({ ...words, imageAlt: 'a'.repeat(301) })).toBeNull()
    expect(validatePreview({ title: 'x', description: 'y' })).toBeNull()
  })
})

describe('injectPreview against the real app-shell head', () => {
  const out = injectPreview(shell, words, urls)

  it('the fixture really is the noindex app shell head', () => {
    expect(shell).toContain('<meta name="robots" content="noindex">')
    expect(count(shell, 'property', 'og:image')).toBe(1)
    expect(count(shell, 'property', 'og:url')).toBe(0)
  })

  it('replaces the title, description, og and twitter tags exactly once each', () => {
    expect(out).toContain(`<title>${escapeHtml(words.title)}</title>`)
    expect((out.match(/<title>/g) ?? []).length).toBe(1)
    expect(content(out, 'name', 'description')).toBe(escapeHtml(words.description))
    expect(content(out, 'property', 'og:title')).toBe(escapeHtml(words.title))
    expect(content(out, 'property', 'og:description')).toBe(escapeHtml(words.description))
    expect(content(out, 'property', 'og:image')).toBe(urls.imageUrl)
    expect(content(out, 'name', 'twitter:image')).toBe(urls.imageUrl)
    for (const [attr, key] of [['name', 'description'], ['property', 'og:title'], ['property', 'og:description'], ['property', 'og:image'], ['name', 'twitter:image']]) {
      expect(count(out, attr, key), key).toBe(1)
    }
  })

  it('adds og:url, the image size and type, both alts and the twitter title and description', () => {
    expect(content(out, 'property', 'og:url')).toBe(urls.gameUrl)
    expect(content(out, 'property', 'og:image:width')).toBe('1200')
    expect(content(out, 'property', 'og:image:height')).toBe('630')
    expect(content(out, 'property', 'og:image:type')).toBe('image/png')
    expect(content(out, 'property', 'og:image:alt')).toBe(words.imageAlt)
    expect(content(out, 'name', 'twitter:image:alt')).toBe(words.imageAlt)
    expect(content(out, 'name', 'twitter:title')).toBe(escapeHtml(words.title))
    expect(content(out, 'name', 'twitter:description')).toBe(escapeHtml(words.description))
  })

  it('keeps robots noindex, twitter:card and the rest of the shell', () => {
    expect(out).toContain('<meta name="robots" content="noindex">')
    expect(content(out, 'name', 'twitter:card')).toBe('summary_large_image')
    expect(content(out, 'name', 'theme-color')).toBe(content(shell, 'name', 'theme-color'))
    expect(out).toContain('<body><div id="root"></div></body>')
    expect(out.indexOf('</head>')).toBeGreaterThan(out.indexOf('og:url'))
  })

  it('escapes a hostile title and description, quotes and script tags included', () => {
    const evil = injectPreview(shell, { title: '<script>alert(1)</script> "x" & $& $1', description: `a" onload="b'`, imageAlt: '"><img src=x>' }, urls)
    expect(evil).not.toContain('<script>alert')
    expect(evil).not.toContain('<img src=x>')
    expect(evil).not.toContain('onload="b')
    expect(evil).toContain('&lt;script&gt;alert(1)&lt;/script&gt; &quot;x&quot; &amp; $&amp; $1')
    expect(count(evil, 'property', 'og:title')).toBe(1)
  })

  it('is idempotent in tag count when applied to its own output', () => {
    const twice = injectPreview(out, words, urls)
    expect(count(twice, 'property', 'og:url')).toBe(1)
    expect(count(twice, 'name', 'twitter:image:alt')).toBe(1)
  })
})
