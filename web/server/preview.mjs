// Spec 079: the pure parts of the share-preview server. No I/O here, so vitest can read every rule.

/** Longest string a preview field may carry. The backend writes ~100 chars; this is a guard, not a budget. */
export const MAX_FIELD = 300

/**
 * The game id of a `/game/<digits>` path, or null. Exactly one to eighteen digits and nothing after
 * them: a trailing slash is serve-handler's own redirect, not ours (PR-15). Pass a pathname (no query string).
 * @param {string} pathname
 * @returns {string | null}
 */
export function gameIdFromPath(pathname) {
  const m = /^\/game\/([1-9]\d{0,17})$/.exec(pathname)
  return m ? m[1] : null
}

/**
 * @param {string} s
 * @returns {string}
 */
export function escapeHtml(s) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * @typedef {{ title: string, description: string, imageAlt: string }} PreviewWords
 */

/**
 * The three strings the head needs, or null when the body is not that shape. Extra fields (the card) are dropped.
 * @param {unknown} body
 * @returns {PreviewWords | null}
 */
export function validatePreview(body) {
  if (body === null || typeof body !== 'object') return null
  const o = /** @type {Record<string, unknown>} */ (body)
  const out = {}
  for (const k of ['title', 'description', 'imageAlt']) {
    const v = o[k]
    if (typeof v !== 'string' || v.trim() === '' || v.length > MAX_FIELD) return null
    out[k] = v
  }
  return /** @type {PreviewWords} */ (out)
}

const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/**
 * Replaces the whole `<meta attr="key" ...>` tag, or adds one before `</head>` when there is none.
 * @param {string} html
 * @param {'name' | 'property'} attr
 * @param {string} key
 * @param {string} value
 */
function setMeta(html, attr, key, value) {
  const tag = `    <meta ${attr}="${key}" content="${escapeHtml(value)}" />`
  const re = new RegExp(`[ \\t]*<meta\\s[^>]*?\\b${attr}\\s*=\\s*["']${escapeRegExp(key)}["'][^>]*>`)
  if (re.test(html)) return html.replace(re, () => tag)
  return html.replace(/<\/head>/i, () => `${tag}\n  </head>`)
}

/**
 * Writes one game's words and image into the app shell's head. Replaces title, description, og:title,
 * og:description, og:image, twitter:image; adds og:url, og:image:width/height/type/alt, twitter:title,
 * twitter:description, twitter:image:alt. robots and twitter:card are left as the shell has them.
 * @param {string} shellHtml
 * @param {PreviewWords} words
 * @param {{ gameUrl: string, imageUrl: string }} urls
 * @returns {string}
 */
export function injectPreview(shellHtml, words, urls) {
  let html = shellHtml.replace(/<title>[\s\S]*?<\/title>/i, () => `<title>${escapeHtml(words.title)}</title>`)
  html = setMeta(html, 'name', 'description', words.description)
  html = setMeta(html, 'property', 'og:title', words.title)
  html = setMeta(html, 'property', 'og:description', words.description)
  html = setMeta(html, 'property', 'og:url', urls.gameUrl)
  html = setMeta(html, 'property', 'og:image', urls.imageUrl)
  html = setMeta(html, 'property', 'og:image:width', '1200')
  html = setMeta(html, 'property', 'og:image:height', '630')
  html = setMeta(html, 'property', 'og:image:type', 'image/png')
  html = setMeta(html, 'property', 'og:image:alt', words.imageAlt)
  html = setMeta(html, 'name', 'twitter:title', words.title)
  html = setMeta(html, 'name', 'twitter:description', words.description)
  html = setMeta(html, 'name', 'twitter:image', urls.imageUrl)
  html = setMeta(html, 'name', 'twitter:image:alt', words.imageAlt)
  return html
}
