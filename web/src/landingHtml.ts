import {
  analyticsLine, description, featureSports, footerLine, groups, headingOf, headline, imageAlt,
  platformsNote, signInNote, sports, subheadline, title,
} from './landingCopy'
import * as tm from './tmLandingCopy'
import { siteOrigin, type SiteConfig } from './site'

// Spec 050: pure string renderers for the HTML a visitor who doesn't run scripts
// receives -- link-preview bots, search crawlers, a slow first paint. vite.config.ts
// calls transform() from a transformIndexHtml hook, so this runs in Node: no React and
// no DOM globals at module load. The words come from landingCopy.ts, the same module
// SignIn.tsx renders from, which is what keeps the two from drifting (FR-018).
//
// Spec 073: which site is being built is an explicit SiteConfig argument (the origin used to
// be a module constant). `timemachine` writes the Time Machine front door's words from
// tmLandingCopy.ts; `combined` and `leagues` write the league landing, differing only in origin.

const IMAGE_WIDTH = 1200
const IMAGE_HEIGHT = 630
// Mirrors --bg in styles.css (oklch(15% 0.03 280)) as sRGB hex; a <meta> can't read a CSS variable.
const THEME_COLOR = '#090917'

export function esc(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** What a site's tags say: the Time Machine's own words, or the league landing's. */
function pageFor(cfg: SiteConfig) {
  const timeMachine = cfg.site === 'timemachine'
  return {
    title: timeMachine ? tm.title : title,
    description: timeMachine ? tm.description : description,
    imageAlt: timeMachine ? tm.imageAlt : imageAlt,
    imageFile: timeMachine ? 'og-image-tm.png' : 'og-image.png',
    name: timeMachine ? 'Ball Knowers Time Machine' : 'Ball Knowers',
  }
}

/** Title, description, canonical and the preview-card tags. index.html carries none of these any more. */
export function headTags(cfg: SiteConfig): string {
  const origin = siteOrigin(cfg)
  const p = pageFor(cfg)
  const imageUrl = `${origin}/${p.imageFile}`
  const meta = (attr: 'name' | 'property', key: string, value: string | number) =>
    `    <meta ${attr}="${key}" content="${esc(String(value))}" />`
  return [
    `    <title>${esc(p.title)}</title>`,
    meta('name', 'description', p.description),
    `    <link rel="canonical" href="${origin}/" />`,
    meta('name', 'theme-color', THEME_COLOR),
    meta('property', 'og:title', p.title),
    meta('property', 'og:description', p.description),
    meta('property', 'og:url', `${origin}/`),
    meta('property', 'og:type', 'website'),
    meta('property', 'og:site_name', 'Ball Knowers'),
    meta('property', 'og:image', imageUrl),
    meta('property', 'og:image:width', IMAGE_WIDTH),
    meta('property', 'og:image:height', IMAGE_HEIGHT),
    meta('property', 'og:image:type', 'image/png'),
    meta('property', 'og:image:alt', p.imageAlt),
    meta('name', 'twitter:card', 'summary_large_image'),
    meta('name', 'twitter:title', p.title),
    meta('name', 'twitter:description', p.description),
    meta('name', 'twitter:image', imageUrl),
    meta('name', 'twitter:image:alt', p.imageAlt),
  ].join('\n')
}

/**
 * One WebApplication block. Deliberately no rating or review fields (FR-017): there is
 * nothing to back them. It's a data block, so the CSP's `script-src 'self'` doesn't run it.
 */
export function jsonLd(cfg: SiteConfig): string {
  const p = pageFor(cfg)
  const data = {
    '@context': 'https://schema.org',
    '@type': 'WebApplication',
    name: p.name,
    url: `${siteOrigin(cfg)}/`,
    description: p.description,
    applicationCategory: cfg.site === 'timemachine' ? 'GameApplication' : 'SportsApplication',
    operatingSystem: 'Web',
    offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
  }
  // A literal "<" could close the script element early.
  const json = JSON.stringify(data).replace(/</g, '\\u003c')
  return `    <script type="application/ld+json">${json}</script>`
}

/**
 * The league landing markup, same element tree and class names as SignIn.tsx. The form is
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
    `<p class="signin-note muted small">${esc(signInNote)}</p>` +
    `<p class="signin-note muted small">${esc(analyticsLine)}</p>` +
    `<p class="landing-platforms muted small">${esc(platformsNote)}</p>` +
    `</header>${groupHtml}` +
    `<p class="landing-footer muted small">${esc(footerLine)}</p>` +
    `</div></div></div>`
  )
}

/** The mark drawn in pages/TimeMachineSignIn.tsx and the Time Machine home. Not text, so the parity test skips it. */
const TM_MARK =
  `<span class="tm-mark" aria-hidden="true"><svg viewBox="0 0 48 48" width="34" height="34" fill="none" stroke="currentColor" ` +
  `stroke-width="4" stroke-linecap="round" stroke-linejoin="round"><path d="M9 24a15 15 0 1 0 5-11.2"></path>` +
  `<path d="M8 7v7h7"></path><path d="M24 16v9l6 4"></path></svg></span>`

/** The Time Machine front door, same element tree and class names as pages/TimeMachineSignIn.tsx. */
export function staticTmBody(): string {
  const sportPills = tm.sports.map((s) => `<span class="landing-sport">${esc(s)}</span>`).join('')
  const steps = tm.steps
    .map((s) => `<li><strong>${esc(s.title)}</strong><span>${esc(s.body)}</span></li>`)
    .join('')
  return (
    `<div class="app"><div class="landing-prerender"><div class="content tm tm-page tm-door">` +
    `<header class="tm-hero">` +
    TM_MARK +
    `<p class="tm-over">Ball Knowers</p>` +
    `<h1 class="tm-title tm-title-sm">${esc(tm.headline)}</h1>` +
    `<p class="tm-tagline">${esc(tm.subheadline)}</p>` +
    `<p class="landing-sports">${sportPills}</p>` +
    `<form class="signin-form"><label class="signin-label">Your Sleeper username` +
    `<input class="signin-input" placeholder="Username or profile link" disabled /></label>` +
    `<button class="signin-continue" type="submit" disabled>Continue</button></form>` +
    `<p class="signin-note muted small">${esc(tm.signInNote)}</p>` +
    `<p class="signin-note muted small">${esc(tm.analyticsLine)}</p>` +
    `</header>` +
    `<section class="tm-section" aria-label="${esc(tm.howHeading)}"><h2>${esc(tm.howHeading)}</h2><ol class="tm-steps">${steps}</ol></section>` +
    `<p class="landing-footer muted small">${esc(tm.footerLine)}</p>` +
    `</div></div></div>`
  )
}

/**
 * The delivered head of the locked league site (2026-11-16): a bare title, a noindex, the theme colour and
 * nothing else. No description, canonical, preview-card tags or JSON-LD: a private site gives a crawler or
 * a link-preview bot nothing to describe it with. noindex is a request that well-behaved crawlers honour,
 * not protection (the site is a front-door lock, see leaguesLock.ts).
 */
export function lockedHeadTags(): string {
  return [
    `    <title>Ball Knowers</title>`,
    `    <meta name="robots" content="noindex, nofollow" />`,
    `    <meta name="theme-color" content="${THEME_COLOR}" />`,
  ].join('\n')
}

const HEAD_ANCHOR = '</head>'
const ROOT_ANCHOR = '<div id="root"></div>'

/** Fills index.html. Throws on a missing anchor so a changed template fails the build loudly. */
export function transform(html: string, cfg: SiteConfig): string {
  if (!html.includes(HEAD_ANCHOR)) throw new Error('landingHtml: index.html has no </head>')
  if (!html.includes(ROOT_ANCHOR)) throw new Error('landingHtml: index.html has no empty <div id="root"></div>')
  // The locked league site delivers an empty root: the lock screen is drawn by React, and the marketing
  // landing is no longer part of it (a crawler gets nothing to index). Every other site is unchanged.
  if (cfg.site === 'leagues') {
    const lockedHead = `${lockedHeadTags()}\n    <script src="/boot.js"></script>\n  `
    return html.replace(HEAD_ANCHOR, () => `${lockedHead}${HEAD_ANCHOR}`)
  }
  const head = `${headTags(cfg)}\n${jsonLd(cfg)}\n    <script src="/boot.js"></script>\n  `
  const body = cfg.site === 'timemachine' ? staticTmBody() : staticBody()
  return html
    .replace(HEAD_ANCHOR, () => `${head}${HEAD_ANCHOR}`)
    .replace(ROOT_ANCHOR, () => `<div id="root">${body}</div>`)
}
