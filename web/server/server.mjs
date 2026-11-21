// Spec 079: the Redraft web server. It is what `serve dist --config ../serve.site.json` was (serve@14.2.6 is a thin CLI over
// serve-handler 6.1.7 and compression 1.8.1, the two libraries pinned here), plus ONE addition: on the Time Machine site,
// GET|HEAD /game/<digits> answers app-shell.html with that game's share-preview words written into the head, so a link
// unfurler (which reads the delivered HTML and runs no script) sees the result. Every other path goes to serve-handler
// with the same config file as before. The words come from the backend, which keeps the privacy rule next to `reduced(...)`.
import { createServer as createHttpServer } from 'node:http'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import compression from 'compression'
import handler from 'serve-handler'
import { gameIdFromPath, injectPreview, validatePreview } from './preview.mjs'

const here = dirname(fileURLToPath(import.meta.url))

// Cache lifetimes (plan PR-11): a success is stable (a COMPLETE game never changes), a 404 is NOT remembered (CR-01: /game/<id> is also the
// draft room, so a 404 seen mid-draft must not outlive the finish, when the player shares at once), and a
// failure (timeout, 5xx, network) is remembered briefly so a backend outage costs one wait per game per 30 s, not one per load.
const TTL_OK_MS = 10 * 60_000
const TTL_FAILURE_MS = 30_000
const MAX_ENTRIES = 500
const MAX_BODY_BYTES = 16 * 1024
const FETCH_TIMEOUT_MS = 1000

/**
 * @typedef {{ title: string, description: string, imageAlt: string }} PreviewWords
 * @typedef {{
 *   distDir?: string,
 *   serveConfigPath?: string,
 *   serverConfigPath?: string,
 *   log?: (line: string) => void,
 *   fetchTimeoutMs?: number,
 * }} ServerOptions
 */

/**
 * Builds (does not start) the HTTP server. Paths default to the layout the Dockerfile makes: /app/server/server.mjs,
 * /app/dist, /app/serve.site.json, /app/server.site.json; env DIST_DIR, SERVE_CONFIG, SERVER_CONFIG override.
 * @param {ServerOptions} [options]
 * @returns {import('node:http').Server}
 */
export function createServer(options = {}) {
  const distDir = resolve(options.distDir ?? process.env.DIST_DIR ?? resolve(here, '../dist'))
  const serveConfigPath = options.serveConfigPath ?? process.env.SERVE_CONFIG ?? resolve(here, '../serve.site.json')
  const serverConfigPath = options.serverConfigPath ?? process.env.SERVER_CONFIG ?? resolve(here, '../server.site.json')
  const log = options.log ?? ((line) => console.log(line))
  const timeoutMs = options.fetchTimeoutMs ?? FETCH_TIMEOUT_MS

  const serveConfig = JSON.parse(readFileSync(serveConfigPath, 'utf8'))
  const serverConfig = JSON.parse(readFileSync(serverConfigPath, 'utf8'))
  // PR-01/PR-03: serve-handler without `public` serves the working directory; without `etag` it sends none (the CLI sets it).
  const handlerConfig = { ...serveConfig, public: distDir, etag: true }

  // PR-04: only the Time Machine build has app-shell.html. Read once; any other site never intercepts.
  const shellPath = resolve(distDir, 'app-shell.html')
  const shell = serverConfig.site === 'timemachine' && existsSync(shellPath) ? readFileSync(shellPath, 'utf8') : null
  const apiBase = String(serverConfig.apiBase ?? '').replace(/\/+$/, '')
  const origin = String(serverConfig.origin ?? '').replace(/\/+$/, '')

  // FR-007: the security headers (source "**") ride on the intercepted page too, as serve-handler would add them.
  /** @type {Array<[string, string]>} */
  const globalHeaders = []
  for (const rule of serveConfig.headers ?? []) {
    if (rule.source !== '**') continue
    for (const h of rule.headers ?? []) globalHeaders.push([h.key, h.value])
  }

  /** @type {Map<string, { value: PreviewWords | null, expires: number }>} */
  const cache = new Map()
  /** @type {Map<string, Promise<PreviewWords | null>>} */
  const inflight = new Map()

  function remember(id, value, ttl) {
    cache.delete(id)
    cache.set(id, { value, expires: Date.now() + ttl })
    while (cache.size > MAX_ENTRIES) cache.delete(cache.keys().next().value)
  }

  async function readCapped(res) {
    const declared = Number(res.headers.get('content-length') ?? 0)
    if (declared > MAX_BODY_BYTES) throw new Error('preview body too large')
    const reader = res.body.getReader()
    const chunks = []
    let total = 0
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      total += value.length
      if (total > MAX_BODY_BYTES) {
        await reader.cancel()
        throw new Error('preview body too large')
      }
      chunks.push(value)
    }
    return Buffer.concat(chunks).toString('utf8')
  }

  async function loadWords(id) {
    try {
      const res = await fetch(`${apiBase}/api/history/public/games/${id}/preview`, {
        signal: AbortSignal.timeout(timeoutMs),
        headers: { Accept: 'application/json' },
      })
      if (res.status === 404) {
        await res.body?.cancel()
        cache.delete(id)
        return null
      }
      if (!res.ok) throw new Error(`backend answered ${res.status}`)
      const words = validatePreview(JSON.parse(await readCapped(res)))
      if (words === null) throw new Error('preview body has the wrong shape')
      remember(id, words, TTL_OK_MS)
      return words
    } catch (e) {
      log(`preview ${id} unavailable: ${e instanceof Error ? e.message : String(e)}`)
      remember(id, null, TTL_FAILURE_MS)
      return null
    }
  }

  /** @returns {Promise<PreviewWords | null>} */
  function wordsFor(id) {
    const hit = cache.get(id)
    if (hit && hit.expires > Date.now()) return Promise.resolve(hit.value)
    const pending = inflight.get(id)
    if (pending) return pending
    const p = loadWords(id).finally(() => inflight.delete(id))
    inflight.set(id, p)
    return p
  }

  function sendHtml(req, res, html) {
    const body = Buffer.from(html, 'utf8')
    res.statusCode = 200
    res.setHeader('Content-Type', 'text/html; charset=utf-8')
    res.setHeader('Cache-Control', 'no-cache')
    res.setHeader('Content-Length', body.length)
    for (const [k, v] of globalHeaders) res.setHeader(k, v)
    res.end(req.method === 'HEAD' ? undefined : body)
  }

  const compress = compression()

  return createHttpServer((req, res) => {
    const started = Date.now()
    res.on('finish', () => log(`${req.method} ${req.url} ${res.statusCode} ${Date.now() - started}ms`))
    compress(req, res, () => {
      const pathname = (req.url ?? '/').split('?')[0]
      const id = shell !== null && (req.method === 'GET' || req.method === 'HEAD') ? gameIdFromPath(pathname) : null
      if (id === null) {
        handler(req, res, handlerConfig).catch((e) => {
          log(`handler error: ${e instanceof Error ? e.message : String(e)}`)
          if (!res.headersSent) res.statusCode = 500
          res.end()
        })
        return
      }
      wordsFor(id).then((words) => {
        const html =
          words === null
            ? shell
            : injectPreview(shell, words, {
                gameUrl: `${origin}/game/${id}`,
                imageUrl: `${apiBase}/api/history/public/games/${id}/preview.png?v=1`,
              })
        sendHtml(req, res, html)
      }).catch((e) => {
        // CR-02: nothing above throws today; if it ever does, the page still answers instead of the process dying.
        log(`preview ${id} failed: ${e instanceof Error ? e.message : String(e)}`)
        if (!res.headersSent) sendHtml(req, res, shell)
        else res.end()
      })
    })
  })
}

// Start only when run as `node server/server.mjs`, not when a test imports createServer.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT || 3000)
  const server = createServer()
  server.listen(port, () => console.log(`web server listening on ${port}`))
  const stop = () => server.close(() => process.exit(0))
  process.on('SIGTERM', stop)
  process.on('SIGINT', stop)
}
