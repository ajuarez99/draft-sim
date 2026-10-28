import {
  SITE_ORIGIN, analyticsLine, description, featureSports, footerLine, groups, headingOf, headline, imageAlt,
  platformsNote, sports, subheadline, title,
} from './landingCopy'

// Spec 050: pure string renderers for the HTML a visitor who doesn't run scripts
// receives -- link-preview bots, search crawlers, a slow first paint. vite.config.ts
// calls transform() from a transformIndexHtml hook, so this runs in Node: no React and
// no DOM globals at module load. The words come from landingCopy.ts, the same module
// SignIn.tsx renders from, which is what keeps the two from drifting (FR-018).

const IMAGE_URL = `${SITE_ORIGIN}/og-image.png`
const IMAGE_WIDTH = 1200
const IMAGE_HEIGHT = 630
// Mirrors --bg in styles.css (oklch(14% 0.025 255)) as sRGB hex; a <meta> can't read a CSS variable.
const THEME_COLOR = '#040913'

export function esc(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** Title, description, canonical and the preview-card tags. index.html carries none of these any more. */
export function headTags(): string {
  const meta = (attr: 'name' | 'property', key: string, value: string | number) =>
    `    <meta ${attr}="${key}" content="${esc(String(value))}" />`
  return [
    `    <title>${esc(title)}</title>`,
    meta('name', 'description', description),
    `    <link rel="canonical" href="${SITE_ORIGIN}/" />`,
    meta('name', 'theme-color', THEME_COLOR),
    meta('property', 'og:title', title),
    meta('property', 'og:description', description),
    meta('property', 'og:url', `${SITE_ORIGIN}/`),
    meta('property', 'og:type', 'website'),
    meta('property', 'og:site_name', 'Ball Knowers'),
    meta('property', 'og:image', IMAGE_URL),
    meta('property', 'og:image:width', IMAGE_WIDTH),
    meta('property', 'og:image:height', IMAGE_HEIGHT),
    meta('property', 'og:image:type', 'image/png'),
    meta('property', 'og:image:alt', imageAlt),
    meta('name', 'twitter:card', 'summary_large_image'),
    meta('name', 'twitter:title', title),
    meta('name', 'twitter:description', description),
    meta('name', 'twitter:image', IMAGE_URL),
    meta('name', 'twitter:image:alt', imageAlt),
  ].join('\n')
}

/**
 * One WebApplication block. Deliberately no rating or review fields (FR-017): there is
 * nothing to back them. It's a data block, so the CSP's `script-src 'self'` doesn't run it.
 */
export function jsonLd(): string {
  const data = {
    '@context': 'https://schema.org',
    '@type': 'WebApplication',
    name: 'Ball Knowers',
    url: `${SITE_ORIGIN}/`,
    description,
    applicationCategory: 'SportsApplication',
    operatingSystem: 'Web',
    offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
  }
  // A literal "<" could close the script element early.
  const json = JSON.stringify(data).replace(/</g, '\\u003c')
  return `    <script type="application/ld+json">${json}</script>`
}

/**
 * The landing markup, same element tree and class names as SignIn.tsx. The form is
 * rendered disabled: it can't work before React mounts, but it takes the space the real
 * one will, so nothing jumps. No inline `style` (the CSP forbids it). The outer `.app`
 * matches App.tsx's wrapper so the layout is the same before and after mount.
 */
export function staticBody(): string {
  const sportPills = sports.map((s) => `<span class="landing-sport">${esc(s)}</span>`).join('')
  const groupHtml = groups
    .map((g) => {
      const tiles = g.features
        .map((f) => {
          const only = featureSports(f)
          const tag = only.length === 1 ? `<span class="landing-tag">${esc(only[0].toUpperCase())}</span>` : ''
          return (
            `<li class="landing-tile"><div class="landing-tile-head"><h3 class="landing-tile-name">${esc(f.name)}</h3>${tag}</div>` +
            `<p class="landing-tile-line">${esc(f.line)}</p></li>`
          )
        })
        .join('')
      return (
        `<section class="landing-group" data-group="${g.key}"><h2 class="landing-group-title cond">${esc(headingOf(g.key))}</h2>` +
        `<ul class="landing-tiles">${tiles}</ul></section>`
      )
    })
    .join('')
  return (
    `<div class="app"><div class="landing-prerender"><div class="content landing">` +
    `<header class="landing-hero">` +
    `<p class="landing-kicker cond">Ball Knowers</p>` +
    `<h1 class="landing-headline">${esc(headline)}</h1>` +
    `<p class="landing-sub">${esc(subheadline)}</p>` +
    `<p class="landing-sports">${sportPills}</p>` +
    `<form class="signin-form"><label class="signin-label">Your Sleeper username` +
    `<input class="signin-input" placeholder="Username or profile link" disabled /></label>` +
    `<button class="signin-continue" type="submit" disabled>Continue</button></form>` +
    `<p class="signin-note muted small">Enter your Sleeper username (or paste your Sleeper profile link) to see your own leagues and ` +
    `get your seat highlighted at the table. No password -- this app never asks Sleeper for anything private.</p>` +
    `<p class="signin-note muted small">${esc(analyticsLine)}</p>` +
    `<p class="landing-platforms muted small">${esc(platformsNote)}</p>` +
    `</header>${groupHtml}` +
    `<p class="landing-footer muted small">${esc(footerLine)}</p>` +
    `</div></div></div>`
  )
}

const HEAD_ANCHOR = '</head>'
const ROOT_ANCHOR = '<div id="root"></div>'

/** Fills index.html. Throws on a missing anchor so a changed template fails the build loudly. */
export function transform(html: string): string {
  if (!html.includes(HEAD_ANCHOR)) throw new Error('landingHtml: index.html has no </head>')
  if (!html.includes(ROOT_ANCHOR)) throw new Error('landingHtml: index.html has no empty <div id="root"></div>')
  const head = `${headTags()}\n${jsonLd()}\n    <script src="/boot.js"></script>\n  `
  return html
    .replace(HEAD_ANCHOR, () => `${head}${HEAD_ANCHOR}`)
    .replace(ROOT_ANCHOR, () => `<div id="root">${staticBody()}</div>`)
}
