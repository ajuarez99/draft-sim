import {
  analyticsLine, description, featureSports, footerLine, groups, headingOf, headline, imageAlt,
  platformsNote, signInNote, sports, subheadline, title,
} from './landingCopy'
import { esc, jsonForScript } from './htmlEscape'
import { embeddedDataFor, staticBodyFor } from './seasonPageHtml'
import * as redraft from './redraftCopy'
import { EMPTY_REDRAFT_DATA, SPORTS, homePageEntry, offeredSportsOf, type RedraftPage } from './redraftPages'
import { redraftImageUrl, siteOrigin, type SiteConfig } from './site'
import type { RedraftSeasonData } from './api'

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

// esc() lives in htmlEscape.ts now (seasonPageHtml.ts needs it too); re-exported so existing imports keep working.
export { esc }

/**
 * What the league landing's tags say. (Spec 074: the Redraft site's index.html is its home page, written by pageHeadTags
 * from the page list like every other page of that site, so it has no entry here.)
 */
function pageFor(cfg: SiteConfig) {
  return {
    title,
    description,
    imageAlt,
    imageFile: 'og-image.png',
    name: 'Ball Knowers',
    origin: siteOrigin(cfg),
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
    applicationCategory: 'SportsApplication',
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

/**
 * Spec 074: the head and body of one Redraft page, made from the page list. `after` is markup that follows the
 * root element (a season page's embedded JSON), kept out of `#root` so React's mount does not replace it.
 */
export type PageParts = { head: string; body: string; after: string }

/** Head tags for one page of the page list. Indexable pages get the full set; 404.html and the app shell get noindex and no canonical. */
export function pageHeadTags(page: RedraftPage, cfg: SiteConfig): string {
  const imageUrl = redraftImageUrl(cfg)
  const meta = (attr: 'name' | 'property', key: string, value: string | number) =>
    `    <meta ${attr}="${key}" content="${esc(String(value))}" />`
  const lines = [`    <title>${esc(page.title)}</title>`]
  if (page.noindex) {
    // Static, not a header: serve matches `headers` against the rewritten file, so a header cannot do this (R4).
    lines.push(`    <meta name="robots" content="noindex">`)
    lines.push(meta('name', 'theme-color', THEME_COLOR))
    if (page.kind === 'appShell') {
      // Generic preview for a shared game link: a static host serves one file for every game (PR-23).
      lines.push(meta('name', 'description', page.description))
      lines.push(meta('property', 'og:title', page.title))
      lines.push(meta('property', 'og:description', page.description))
      lines.push(meta('property', 'og:type', 'website'))
      lines.push(meta('property', 'og:site_name', 'Ball Knowers'))
      lines.push(meta('property', 'og:image', imageUrl))
      lines.push(meta('name', 'twitter:card', 'summary_large_image'))
      lines.push(meta('name', 'twitter:image', imageUrl))
    }
  } else {
    lines.push(
      meta('name', 'description', page.description),
      `    <link rel="canonical" href="${esc(page.canonical)}" />`,
      meta('name', 'theme-color', THEME_COLOR),
      meta('property', 'og:title', page.title),
      meta('property', 'og:description', page.description),
      meta('property', 'og:url', page.canonical),
      meta('property', 'og:type', 'website'),
      meta('property', 'og:site_name', 'Ball Knowers'),
      meta('property', 'og:image', imageUrl),
      meta('property', 'og:image:width', IMAGE_WIDTH),
      meta('property', 'og:image:height', IMAGE_HEIGHT),
      meta('property', 'og:image:type', 'image/png'),
      meta('property', 'og:image:alt', redraft.imageAlt),
      meta('name', 'twitter:card', 'summary_large_image'),
      meta('name', 'twitter:title', page.title),
      meta('name', 'twitter:description', page.description),
      meta('name', 'twitter:image', imageUrl),
      meta('name', 'twitter:image:alt', redraft.imageAlt),
    )
  }
  for (const block of page.jsonLd) lines.push(`    <script type="application/ld+json">${jsonForScript(block)}</script>`)
  return lines.join('\n')
}

export function pageParts(page: RedraftPage, cfg: SiteConfig, data: RedraftSeasonData): PageParts {
  const embedded = embeddedDataFor(page, data)
  // CR-08: when a sport has no seasons the mounted footer, 404 and shared-result links must leave it out too, and
  // those pages do not embed the lists. Said in the head only when a sport is missing; absent means "both".
  const offered = offeredSportsOf(data)
  const sportsMeta =
    offered.length < SPORTS.length ? `\n    <meta name="redraft-sports" content="${esc(offered.join(','))}" />` : ''
  return {
    head: pageHeadTags(page, cfg) + sportsMeta,
    body: staticBodyFor(page, cfg, data),
    after: embedded == null ? '' : `<script type="application/json" id="redraft-data">${jsonForScript(embedded)}</script>`,
  }
}

const HEAD_ANCHOR = '</head>'
const ROOT_ANCHOR = '<div id="root"></div>'

/**
 * Fills the built template. Throws on a missing anchor so a changed template fails the build loudly, and on
 * a template that is already filled (no empty root), so filling twice cannot pass quietly.
 *
 * With no `parts` it writes index.html as before (the league landing or the locked league site; the Redraft site's home page). With `parts` (spec 074) it writes one page of the Redraft page list;
 * the leagues and combined builds never pass any.
 */
export function transform(html: string, cfg: SiteConfig, parts?: PageParts): string {
  if (!html.includes(HEAD_ANCHOR)) throw new Error('landingHtml: index.html has no </head>')
  if (!html.includes(ROOT_ANCHOR)) throw new Error('landingHtml: index.html has no empty <div id="root"></div>')
  if (parts) {
    const head = `${parts.head}
    <script src="/boot.js"></script>
  `
    return html
      .replace(HEAD_ANCHOR, () => `${head}${HEAD_ANCHOR}`)
      .replace(ROOT_ANCHOR, () => `<div id="root">${parts.body}</div>${parts.after}`)
  }
  // The locked league site delivers an empty root: the lock screen is drawn by React, and the marketing
  // landing is no longer part of it (a crawler gets nothing to index). Every other site is unchanged.
  if (cfg.site === 'leagues') {
    const lockedHead = `${lockedHeadTags()}\n    <script src="/boot.js"></script>\n  `
    return html.replace(HEAD_ANCHOR, () => `${lockedHead}${HEAD_ANCHOR}`)
  }
  // The Redraft site's index.html is its home page. With no season data in hand (a dev server, a test) the home page's
  // season tiles are simply absent; the build passes the real parts (vite.config.ts), tiles included.
  if (cfg.site === 'timemachine') return transform(html, cfg, pageParts(homePageEntry(cfg), cfg, EMPTY_REDRAFT_DATA))
  const head = `${headTags(cfg)}\n${jsonLd(cfg)}\n    <script src="/boot.js"></script>\n  `
  const body = staticBody()
  return html
    .replace(HEAD_ANCHOR, () => `${head}${HEAD_ANCHOR}`)
    .replace(ROOT_ANCHOR, () => `<div id="root">${body}</div>`)
}
