import { useEffect } from 'react'
import { currentSite } from './siteEnv'

/**
 * What one Redraft page puts in the head once it has mounted. An indexable page passes its RedraftPage entry from
 * redraftPages.ts (the same entry its static file's head is written from by pageHeadTags), so the tags a crawler
 * reads and the ones a tab holds after a client-side move agree.
 *
 * `description` and `jsonLd` left undefined mean "leave what is there" (a sport index whose list is still loading
 * cannot name its range yet); `description: null` removes it.
 */
export type PageHead = {
  title: string
  description?: string | null
  /** Absolute. Ignored, and removed from the head, when `noindex`. */
  canonical?: string | null
  jsonLd?: object[]
  noindex?: boolean
}

function upsertMeta(attr: 'name' | 'property', key: string, value: string | null): void {
  const sel = `meta[${attr}="${key}"]`
  let el = document.head.querySelector<HTMLMetaElement>(sel)
  if (value == null) {
    el?.remove()
    return
  }
  if (!el) {
    el = document.createElement('meta')
    el.setAttribute(attr, key)
    document.head.appendChild(el)
  }
  el.setAttribute('content', value)
}

function setCanonical(href: string | null): void {
  let el = document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]')
  if (href == null) {
    el?.remove()
    return
  }
  if (!el) {
    el = document.createElement('link')
    el.setAttribute('rel', 'canonical')
    document.head.appendChild(el)
  }
  el.setAttribute('href', href)
}

/** Exported for the tests. Writes the per-page head tags pageHeadTags (landingHtml.ts) writes; the site-wide ones (image, theme colour) are the same on every page and are left alone. */
export function applyPageHead(h: PageHead): void {
  document.title = h.title
  upsertMeta('property', 'og:title', h.title)
  upsertMeta('name', 'twitter:title', h.noindex ? null : h.title)
  if (h.description !== undefined) {
    upsertMeta('name', 'description', h.description)
    upsertMeta('property', 'og:description', h.description)
    upsertMeta('name', 'twitter:description', h.noindex ? null : h.description)
  }
  const canonical = h.noindex ? null : (h.canonical ?? null)
  if (h.noindex || h.canonical !== undefined) {
    setCanonical(canonical)
    upsertMeta('property', 'og:url', canonical)
  }
  upsertMeta('name', 'robots', h.noindex ? 'noindex' : null)
  if (h.jsonLd !== undefined) {
    for (const el of document.head.querySelectorAll('script[type="application/ld+json"]')) el.remove()
    for (const block of h.jsonLd) {
      const el = document.createElement('script')
      el.type = 'application/ld+json'
      el.textContent = JSON.stringify(block)
      document.head.appendChild(el)
    }
  }
}

/**
 * Spec 074 PR-08: each Redraft page sets its own head from the same entry the static file's head is written from.
 * Before 2026-11-18 this set only `document.title`, so after a client-side move the canonical, og:url and description
 * still named the first page loaded (measured on prod: /nfl/2007 carried /nba/2020-21's canonical).
 *
 * Only on the Redraft site (CR-04): the pages that call this are also mounted by the combined build, whose App sets the
 * league titles itself; acting there would leave the Redraft head on every league page until a reload.
 */
export function usePageHead(h: PageHead): void {
  // Keyed by value, not identity: callers build a fresh object every render.
  const key = JSON.stringify(h)
  useEffect(() => {
    if (currentSite().site !== 'timemachine') return
    applyPageHead(JSON.parse(key) as PageHead)
  }, [key])
}
